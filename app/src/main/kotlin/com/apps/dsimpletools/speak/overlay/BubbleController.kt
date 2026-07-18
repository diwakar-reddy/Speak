package com.apps.dsimpletools.speak.overlay

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Owns the single [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * window used for the floating bubble. Must be constructed with the
 * [AccessibilityService] itself (not the Application context) - that
 * window type is only grantable through an accessibility service context,
 * which is exactly why we don't need the SYSTEM_ALERT_WINDOW permission.
 */
class BubbleController(private val service: AccessibilityService) {

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val density = service.resources.displayMetrics.density
    val bubbleSizePx: Int = (BubbleView.SIZE_DP * density).roundToInt()
    private val touchSlop = ViewConfiguration.get(service).scaledTouchSlop

    private var bubbleView: BubbleView? = null
    private var params: WindowManager.LayoutParams? = null

    private var downRawX = 0f
    private var downRawY = 0f
    private var downParamX = 0
    private var downParamY = 0
    private var isDragging = false
    private var snapAnimator: ValueAnimator? = null

    /** Set by the service once it has a [com.apps.dsimpletools.speak.capture.CaptureController] to call. */
    var onTap: (() -> Unit)? = null

    /**
     * Invoked (with the snapped edge and the released y, in absolute screen px) once the user
     * finishes a committed drag (ACTION_UP). The service uses it to persist the preferred edge
     * and remember the released y for the rest of the current focus session. Not fired for a
     * cancelled drag, so an aborted gesture never persists anything.
     */
    var onDragReleased: ((edge: BubblePositioner.Edge, y: Int) -> Unit)? = null

    /**
     * Supplies the latest IME top edge in absolute screen px (or null when the keyboard is
     * hidden / its bounds are unavailable). Backed by a value the service caches on each
     * (window-driven) re-evaluation, so reading it during touch dispatch costs no Binder IPC
     * and still reflects a keyboard that appears mid-drag.
     */
    var imeTopProvider: () -> Int? = { null }

    fun show(x: Int, y: Int) {
        // An in-flight snap must not keep driving updateViewLayout over an explicit placement.
        snapAnimator?.cancel()
        val existing = bubbleView
        if (existing == null) {
            createBubble(x, y)
            return
        }
        params?.let {
            it.x = x
            it.y = y
            runCatching { windowManager.updateViewLayout(existing, it) }
        }
        if (existing.visibility != View.VISIBLE) {
            existing.visibility = View.VISIBLE
        }
        Log.i(TAG, "BUBBLE: show (existing bubble moved) x=$x y=$y")
    }

    fun hide() {
        // Stop any snap so it can't keep calling updateViewLayout on a hidden view.
        snapAnimator?.cancel()
        val existing = bubbleView ?: return
        if (existing.visibility != View.GONE) {
            existing.visibility = View.GONE
            Log.i(TAG, "BUBBLE: hide")
        }
    }

    /**
     * Make the existing bubble visible again without moving it (used mid-dictation
     * when the field bounds are no longer available but the session must keep the
     * bubble on screen). No-op if the bubble was never created.
     */
    fun ensureVisible() {
        val existing = bubbleView ?: return
        if (existing.visibility != View.VISIBLE) {
            existing.visibility = View.VISIBLE
            Log.i(TAG, "BUBBLE: ensureVisible (kept at last position)")
        }
    }

    /** Whether the overlay bubble currently exists (created, not necessarily visible). */
    fun hasBubble(): Boolean = bubbleView != null

    fun setBubbleState(state: BubbleState) {
        bubbleView?.setState(state)
    }

    fun screenSize(): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealSize(point)
            point
        }
    }

    /** Synchronously tears down the overlay window. Call from service teardown paths only. */
    fun destroy() {
        snapAnimator?.cancel()
        snapAnimator = null
        val existing = bubbleView ?: return
        runCatching { windowManager.removeViewImmediate(existing) }
        bubbleView = null
        params = null
        Log.i(TAG, "BUBBLE: overlay destroyed")
    }

    private fun createBubble(x: Int, y: Int) {
        val view = BubbleView(service)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
            // AccessibilityNodeInfo.getBoundsInScreen() reports true absolute display
            // coordinates (including under the status bar / camera cutout). Without
            // FLAG_LAYOUT_IN_SCREEN, this window's x/y are instead interpreted relative
            // to the content area *below* system bars, which silently shifted the
            // bubble down by the status bar/cutout height. layoutInDisplayCutoutMode
            // additionally lets it render into the cutout area itself if placement
            // calls for it, keeping both coordinate spaces in sync.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        view.setOnTouchListener(::handleTouch)
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> logPosition() }

        runCatching {
            windowManager.addView(view, lp)
        }.onSuccess {
            bubbleView = view
            params = lp
            Log.i(TAG, "BUBBLE: show (new bubble) x=$x y=$y")
        }.onFailure {
            Log.e(TAG, "BUBBLE: failed to add overlay view: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        val currentParams = params ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                downRawX = event.rawX
                downRawY = event.rawY
                downParamX = currentParams.x
                downParamY = currentParams.y
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!isDragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                    isDragging = true
                }
                if (isDragging) {
                    val screen = screenSize()
                    // imeTopProvider() reads the service's cached IME top (no IPC here) so a
                    // keyboard that appears mid-drag is honoured on the next move.
                    val clamped = BubblePositioner.clamp(
                        x = downParamX + dx.roundToInt(),
                        y = downParamY + dy.roundToInt(),
                        screenWidth = screen.x,
                        screenHeight = screen.y,
                        bubbleSizePx = bubbleSizePx,
                        imeTop = imeTopProvider()
                    )
                    currentParams.x = clamped.x
                    currentParams.y = clamped.y
                    runCatching { windowManager.updateViewLayout(view, currentParams) }
                    logPosition()
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    snapToEdge(view, currentParams, commit = true)
                } else {
                    view.performClick()
                    Log.i(TAG, "BUBBLE: tap detected")
                    onTap?.invoke()
                }
                isDragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                // Aborted gesture: settle the view to an edge but persist nothing.
                if (isDragging) snapToEdge(view, currentParams, commit = false)
                isDragging = false
                return true
            }
        }
        return false
    }

    /**
     * Snap the bubble horizontally to the nearest screen edge (briefly animated). When [commit]
     * (a real ACTION_UP release), also report the edge + released y via [onDragReleased] so the
     * service persists the preference and pins the session y; a cancelled drag ([commit] false)
     * settles visually but reports nothing. The reported values are known up front, so we report
     * immediately and let the animation be purely cosmetic.
     */
    private fun snapToEdge(view: View, p: WindowManager.LayoutParams, commit: Boolean) {
        val screen = screenSize()
        val edge = BubblePositioner.nearestEdge(p.x, screen.x, bubbleSizePx)
        val targetX = BubblePositioner.edgeX(edge, screen.x, bubbleSizePx)
        if (commit) {
            Log.i(TAG, "BUBBLE: snapped edge=${edge.name}")
            onDragReleased?.invoke(edge, p.y)
        }

        snapAnimator?.cancel()
        if (p.x == targetX) return
        snapAnimator = ValueAnimator.ofInt(p.x, targetX).apply {
            duration = SNAP_MS
            addUpdateListener {
                p.x = it.animatedValue as Int
                runCatching { windowManager.updateViewLayout(view, p) }
                logPosition()
            }
            start()
        }
    }

    private fun logPosition() {
        val view = bubbleView ?: return
        val p = params ?: return
        Log.d(TAG, "BUBBLE_POS: ${p.x},${p.y} ${view.width},${view.height}")
    }

    companion object {
        private const val TAG = "Speak.Bubble"
        private const val SNAP_MS = 150L
    }
}
