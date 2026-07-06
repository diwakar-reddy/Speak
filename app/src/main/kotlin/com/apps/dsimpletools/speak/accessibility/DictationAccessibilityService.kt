package com.apps.dsimpletools.speak.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.apps.dsimpletools.speak.capture.CaptureController
import com.apps.dsimpletools.speak.insert.AccessibilityTextInserter
import com.apps.dsimpletools.speak.insert.InsertMode
import com.apps.dsimpletools.speak.insert.InsertResult
import com.apps.dsimpletools.speak.insert.TextInserter
import com.apps.dsimpletools.speak.overlay.BubbleController
import com.apps.dsimpletools.speak.overlay.BubblePositioner
import com.apps.dsimpletools.speak.overlay.BubbleVisibilityPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches focus/window events system-wide and shows/hides the floating mic
 * bubble next to whatever editable, non-password field currently has focus.
 * Also the sole owner of the "currently targeted" [AccessibilityNodeInfo]
 * that [CaptureController] and [AccessibilityTextInserter] act on.
 */
class DictationAccessibilityService : AccessibilityService() {

    private var bubbleController: BubbleController? = null
    private var captureController: CaptureController? = null
    private var textInserter: TextInserter? = null
    private var currentTarget: AccessibilityNodeInfo? = null
    private var isSetUp = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    private var serviceScope: CoroutineScope? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (isSetUp) {
            Log.i(TAG, "BUBBLE: onServiceConnected called again; already set up, no-op")
            return
        }

        val bubble = BubbleController(this)
        val inserter = AccessibilityTextInserter { currentTarget }
        val capture = CaptureController(this, bubble, inserter)
        bubble.onTap = { capture.onBubbleTapped() }

        bubbleController = bubble
        captureController = capture
        textInserter = inserter
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        instance = this
        isSetUp = true
        Log.i(TAG, "BUBBLE: service connected, overlay wiring ready")

        // Load the ASR models eagerly in the background so the first bubble tap
        // usually finds the engine ready; the service outlives individual dictations
        // and keeps the ~1 GB engine resident until it is disabled.
        capture.warmUpAsr()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                // Capture the freshly focused editable node as the candidate target,
                // then re-evaluate (debounced) so IME visibility is factored in.
                val node = event.source
                if (node != null && node.isEditable && !node.isPassword) {
                    currentTarget = node
                }
                scheduleReevaluate("focus")
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                // Some custom widgets / WebView inputs only surface an editable signal
                // via a click; route it through the same candidate + re-evaluate path.
                val node = event.source
                if (node != null && node.isEditable && !node.isPassword) {
                    currentTarget = node
                    scheduleReevaluate("click")
                }
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> scheduleReevaluate("window")
        }
    }

    /** Debounced re-evaluation of the bubble-visibility rule. */
    private fun scheduleReevaluate(reason: String) {
        cancelPendingReevaluate()
        val runnable = Runnable { reevaluateBubble(reason) }
        pendingRunnable = runnable
        mainHandler.postDelayed(runnable, FOCUS_DEBOUNCE_MS)
    }

    /** Called by [CaptureController] when a dictation session starts or ends. */
    fun onCaptureStateChanged() {
        // Undebounced: a session start must show the bubble at once, and a session end
        // must promptly hide it if the IME is already gone.
        mainHandler.post { reevaluateBubble("capture-state") }
    }

    /**
     * Central bubble show/hide decision. Resolves the currently focused editable node
     * (the event-driven target first, then a rootInActiveWindow/findFocus rescan so a
     * field that never emits a focus event — e.g. some Settings search boxes — is still
     * caught), whether the IME window is visible, and whether a dictation session is
     * active, then applies [BubbleVisibilityPolicy]. Every decision is logged.
     */
    private fun reevaluateBubble(reason: String) {
        val controller = bubbleController ?: return
        val sessionActive = captureController?.isSessionActive() == true

        // Resolve a focused, editable, non-password target: prefer the event-driven one
        // if it is still valid, else rescan the active window.
        var target = currentTarget
        val currentValid = target != null && target.refresh() &&
            target.isFocused && target.isEditable && !target.isPassword
        if (!currentValid) {
            target = findFocusedEditable()
            currentTarget = target
        }
        val editableFocused = target != null
        val imeVisible = isImeVisible()

        val shouldShow =
            BubbleVisibilityPolicy.shouldShow(editableFocused, imeVisible, sessionActive)
        Log.i(
            TAG,
            "BUBBLE: reevaluate ($reason) editableFocused=$editableFocused " +
                "imeVisible=$imeVisible session=$sessionActive -> ${if (shouldShow) "show" else "hide"}"
        )

        if (!shouldShow) {
            controller.hide()
            return
        }

        val node = target
        if (node != null) {
            // Reposition from the current field bounds (handles relayout / IME re-show).
            val fieldBounds = Rect()
            node.getBoundsInScreen(fieldBounds)
            val position = BubblePositioner.computePosition(
                fieldBoundsInScreen = fieldBounds,
                screenSize = controller.screenSize(),
                bubbleSizePx = controller.bubbleSizePx,
                marginPx = MARGIN_PX
            )
            Log.i(TAG, "BUBBLE: show fieldBounds=$fieldBounds pos=(${position.x},${position.y})")
            controller.show(position.x, position.y)
        } else {
            // Session active but no field bounds (field lost focus mid-session): keep the
            // bubble where it is rather than hiding it out from under the user.
            controller.ensureVisible()
        }
    }

    /**
     * Rescan for a focused, editable, non-password node even when no TYPE_VIEW_FOCUSED
     * event was delivered (the fix attempt for the Settings search box), and even when
     * the IME is up.
     *
     * When the soft keyboard is showing, the "active window" is often the IME itself, so
     * `findFocus`/`rootInActiveWindow` miss the app's focused field. We therefore also
     * scan every non-IME window's tree for the input focus.
     */
    private fun findFocusedEditable(): AccessibilityNodeInfo? {
        fun eligible(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? =
            if (n != null && n.isEditable && !n.isPassword) n else null

        eligible(findFocus(AccessibilityNodeInfo.FOCUS_INPUT))?.let { return it }
        eligible(rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))?.let { return it }

        val wins = runCatching { windows }.getOrNull() ?: return null
        for (window in wins) {
            if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val root = runCatching { window.root }.getOrNull() ?: continue
            eligible(root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT))?.let { return it }
        }
        return null
    }

    /** Whether a soft-keyboard (IME) window is currently on screen. */
    private fun isImeVisible(): Boolean {
        val wins = runCatching { windows }.getOrNull() ?: return false
        return wins.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
    }

    private fun cancelPendingReevaluate() {
        pendingRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingRunnable = null
    }

    /** Entry point used by the debug broadcast receiver; mirrors a real bubble tap exactly. */
    fun triggerDebugTap(): Boolean {
        val capture = captureController ?: return false
        Log.i(TAG, "BUBBLE: debug tap triggered")
        capture.onBubbleTapped()
        return true
    }

    /** Entry point used by the DEBUG_TRANSCRIBE_WAV receiver; runs the ASR pipeline on a file. */
    fun triggerDebugTranscribeWav(path: String): Boolean {
        val capture = captureController ?: return false
        Log.i(TAG, "BUBBLE: debug transcribe-wav triggered path=$path")
        capture.debugTranscribeWav(path)
        return true
    }

    /**
     * Entry point used by the DEBUG_INSERT_TEXT receiver: inserts a fixed string into the
     * currently focused field via the exact same [TextInserter] path a real dictation
     * uses (APPEND). Lets the insertion/prefix behaviour be verified deterministically
     * without a microphone.
     */
    fun triggerDebugInsert(text: String): Boolean {
        val inserter = textInserter ?: return false
        val scope = serviceScope ?: return false
        Log.i(TAG, "INSERT: debug insert triggered text=\"$text\"")
        scope.launch {
            when (val result = inserter.insert(text, InsertMode.APPEND)) {
                is InsertResult.Verified -> Log.i(TAG, "INSERT: debug insert verified")
                is InsertResult.Mismatch ->
                    Log.w(TAG, "INSERT: debug insert mismatch actual='${result.actual}'")
                is InsertResult.Failed ->
                    Log.e(TAG, "INSERT: debug insert failed reason=${result.reason}")
            }
        }
        return true
    }

    override fun onInterrupt() {
        Log.w(TAG, "BUBBLE: onInterrupt")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "BUBBLE: onUnbind -> tearing down overlay")
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.i(TAG, "BUBBLE: onDestroy -> tearing down overlay")
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        captureController?.forceStopAndReset("service teardown")
        bubbleController?.destroy()
        cancelPendingReevaluate()
        serviceScope?.cancel()
        serviceScope = null
        currentTarget = null
        bubbleController = null
        captureController = null
        textInserter = null
        isSetUp = false
        if (instance === this) instance = null
    }

    companion object {
        private const val TAG = "Speak.Accessibility"
        private const val FOCUS_DEBOUNCE_MS = 200L
        private const val MARGIN_PX = 24

        @Volatile
        private var instance: DictationAccessibilityService? = null

        /** @return true if a running service instance handled the tap, false if none is connected. */
        fun debugTap(): Boolean = instance?.triggerDebugTap() ?: false

        /** @return true if a running service instance handled the transcribe request. */
        fun debugTranscribeWav(path: String): Boolean =
            instance?.triggerDebugTranscribeWav(path) ?: false

        /** @return true if a running service instance handled the debug insert request. */
        fun debugInsert(text: String): Boolean =
            instance?.triggerDebugInsert(text) ?: false
    }
}
