package com.apps.dsimpletools.speak.overlay

import android.graphics.Point
import android.graphics.Rect

/**
 * Pure placement logic, kept separate from [BubbleController] so it can be
 * reasoned about (and unit tested later) without a WindowManager involved.
 *
 * Preference order: to the right of the field, then to the left of it, then
 * above/below it as a last resort for fields that span the full screen
 * width — always clamped to stay fully on screen.
 */
object BubblePositioner {

    fun computePosition(
        fieldBoundsInScreen: Rect,
        screenSize: Point,
        bubbleSizePx: Int,
        marginPx: Int
    ): Point {
        var x = fieldBoundsInScreen.right + marginPx
        var y = fieldBoundsInScreen.top

        val fitsRight = x + bubbleSizePx <= screenSize.x
        if (!fitsRight) {
            val leftCandidate = fieldBoundsInScreen.left - bubbleSizePx - marginPx
            if (leftCandidate >= 0) {
                x = leftCandidate
            } else {
                // Field spans (nearly) the full width - float above it instead,
                // falling back to below if there's no room above either.
                x = (screenSize.x - bubbleSizePx).coerceAtLeast(0)
                y = fieldBoundsInScreen.top - bubbleSizePx - marginPx
                if (y < 0) {
                    y = fieldBoundsInScreen.bottom + marginPx
                }
            }
        }

        val maxX = (screenSize.x - bubbleSizePx).coerceAtLeast(0)
        val maxY = (screenSize.y - bubbleSizePx).coerceAtLeast(0)
        x = x.coerceIn(0, maxX)
        y = y.coerceIn(0, maxY)
        return Point(x, y)
    }
}
