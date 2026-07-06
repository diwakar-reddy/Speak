package com.apps.dsimpletools.speak.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.apps.dsimpletools.speak.capture.CaptureController
import com.apps.dsimpletools.speak.insert.AccessibilityTextInserter
import com.apps.dsimpletools.speak.overlay.BubbleController
import com.apps.dsimpletools.speak.overlay.BubblePositioner

/**
 * Watches focus/window events system-wide and shows/hides the floating mic
 * bubble next to whatever editable, non-password field currently has focus.
 * Also the sole owner of the "currently targeted" [AccessibilityNodeInfo]
 * that [CaptureController] and [AccessibilityTextInserter] act on.
 */
class DictationAccessibilityService : AccessibilityService() {

    private var bubbleController: BubbleController? = null
    private var captureController: CaptureController? = null
    private var currentTarget: AccessibilityNodeInfo? = null
    private var isSetUp = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingFocusRunnable: Runnable? = null

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
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> handleFocusEvent(event)
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleClickEvent(event)
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowChange(event)
        }
    }

    private fun handleFocusEvent(event: AccessibilityEvent) {
        val node = event.source
        cancelPendingFocusCheck()

        if (node == null) {
            Log.d(TAG, "BUBBLE: focus event with null source -> hide")
            hideAndClearTarget("null source")
            return
        }
        if (!node.isEditable || node.isPassword) {
            Log.d(
                TAG,
                "BUBBLE: focused node not eligible editable=${node.isEditable} " +
                    "password=${node.isPassword} -> hide"
            )
            hideAndClearTarget("not eligible")
            return
        }

        val runnable = Runnable { evaluateAndShow(node) }
        pendingFocusRunnable = runnable
        mainHandler.postDelayed(runnable, FOCUS_DEBOUNCE_MS)
    }

    private fun handleClickEvent(event: AccessibilityEvent) {
        // Some views only surface an editable focus signal via a click event
        // (custom widgets, some WebView inputs) - route through the same path.
        val node = event.source ?: return
        if (node.isEditable && !node.isPassword) {
            handleFocusEvent(event)
        }
    }

    private fun evaluateAndShow(node: AccessibilityNodeInfo) {
        val refreshed = node.refresh()
        if (!refreshed || !node.isFocused || !node.isEditable || node.isPassword) {
            Log.d(
                TAG,
                "BUBBLE: debounce recheck failed refreshed=$refreshed " +
                    "focused=${node.isFocused} -> skip show"
            )
            return
        }

        val controller = bubbleController ?: return
        currentTarget = node

        val fieldBounds = Rect()
        node.getBoundsInScreen(fieldBounds)
        val screenSize = controller.screenSize()
        val position = BubblePositioner.computePosition(
            fieldBoundsInScreen = fieldBounds,
            screenSize = screenSize,
            bubbleSizePx = controller.bubbleSizePx,
            marginPx = MARGIN_PX
        )
        Log.i(TAG, "BUBBLE: show fieldBounds=$fieldBounds pos=(${position.x},${position.y})")
        controller.show(position.x, position.y)
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val target = currentTarget ?: return
        val stillValid = target.refresh() && target.isFocused
        if (!stillValid) {
            Log.i(TAG, "BUBBLE: window/state changed and target no longer focused -> hide")
            hideAndClearTarget("window change")
        }
    }

    private fun hideAndClearTarget(reason: String) {
        bubbleController?.hide()
        currentTarget = null
        Log.i(TAG, "BUBBLE: hide ($reason)")
    }

    private fun cancelPendingFocusCheck() {
        pendingFocusRunnable?.let { mainHandler.removeCallbacks(it) }
        pendingFocusRunnable = null
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
        cancelPendingFocusCheck()
        currentTarget = null
        bubbleController = null
        captureController = null
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
    }
}
