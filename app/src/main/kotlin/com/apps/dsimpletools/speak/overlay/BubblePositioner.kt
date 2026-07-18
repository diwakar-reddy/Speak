package com.apps.dsimpletools.speak.overlay

import kotlin.math.roundToInt

/**
 * Pure, framework-free placement math for the floating bubble, kept separate from
 * [BubbleController] so it can be reasoned about and unit tested without a
 * WindowManager (all inputs/outputs are plain pixels — no android.graphics types).
 *
 * Rest behaviour: the bubble floats ABOVE the focused field, flush to the persisted
 * preferred screen edge (LEFT/RIGHT), with a ~[GAP_DP] gap between the bubble bottom
 * and the field top; when there's no room above (field hugging the screen top) it drops
 * BELOW the field instead of covering it. On drag-release it snaps to the nearest edge.
 * Every placement is clamped to stay fully on screen and, when the IME is up, above the
 * IME top edge (a null imeTop means "no constraint / bounds unavailable").
 */
object BubblePositioner {

    enum class Edge { LEFT, RIGHT }

    data class Placement(val x: Int, val y: Int)

    /** Rest gap between the bubble and the field edge: target 24dp, clamped to 16..36dp. */
    const val GAP_DP = 24
    const val MIN_GAP_DP = 16
    const val MAX_GAP_DP = 36

    /** Left edge is x=0; right edge is flush against the screen's right side. */
    fun edgeX(edge: Edge, screenWidth: Int, bubbleSizePx: Int): Int =
        if (edge == Edge.LEFT) 0 else (screenWidth - bubbleSizePx).coerceAtLeast(0)

    /** Nearest screen edge to the bubble's current x, by its horizontal centre. */
    fun nearestEdge(x: Int, screenWidth: Int, bubbleSizePx: Int): Edge {
        val center = x + bubbleSizePx / 2
        return if (center * 2 < screenWidth) Edge.LEFT else Edge.RIGHT
    }

    /**
     * Clamp an (x, y) so the whole bubble stays on screen and, when the IME is visible,
     * so the bubble bottom sits at or above the IME top ([imeTop]; pass null to disable
     * that constraint / when bounds are unavailable).
     */
    fun clamp(
        x: Int,
        y: Int,
        screenWidth: Int,
        screenHeight: Int,
        bubbleSizePx: Int,
        imeTop: Int?
    ): Placement {
        val maxX = (screenWidth - bubbleSizePx).coerceAtLeast(0)
        val bottomLimit = if (imeTop != null) minOf(imeTop, screenHeight) else screenHeight
        val maxY = (bottomLimit - bubbleSizePx).coerceAtLeast(0)
        return Placement(x.coerceIn(0, maxX), y.coerceIn(0, maxY))
    }

    /**
     * Default rest position: flush to [edge] horizontally, floating above the field with a
     * [gapPx] gap that shrinks toward [minGapPx] when space is tight. If there isn't room for
     * even the min gap above the field, the bubble drops BELOW the field ([fieldBottom] +
     * gap) rather than covering it. Result is [clamp]ed on-screen / above the IME.
     */
    fun restPosition(
        fieldTop: Int,
        fieldBottom: Int,
        screenWidth: Int,
        screenHeight: Int,
        bubbleSizePx: Int,
        edge: Edge,
        gapPx: Int,
        minGapPx: Int,
        maxGapPx: Int,
        imeTop: Int?
    ): Placement {
        val desiredGap = gapPx.coerceIn(minGapPx, maxGapPx)
        val x = edgeX(edge, screenWidth, bubbleSizePx)
        val roomAbove = fieldTop - bubbleSizePx
        val y = if (roomAbove >= minGapPx) {
            // Above the field, shrinking the gap toward min when room is tight.
            fieldTop - minOf(desiredGap, roomAbove) - bubbleSizePx
        } else {
            // No room above (field at the screen top): drop below it instead of covering it.
            fieldBottom + desiredGap
        }
        return clamp(x, y, screenWidth, screenHeight, bubbleSizePx, imeTop)
    }

    /**
     * The single placement decision the service delegates to: keep the user's dragged y
     * ([heldY]) for the current focus session, otherwise compute the rest position above/below
     * the field. The held y is honoured only while the screen size is unchanged from when it
     * was captured ([heldScreenWidth]/[heldScreenHeight]) so a rotation doesn't strand it at a
     * now-off-screen absolute y. Gaps are derived from the dp constants via [density].
     */
    fun resolve(
        heldY: Int?,
        heldScreenWidth: Int,
        heldScreenHeight: Int,
        fieldTop: Int,
        fieldBottom: Int,
        screenWidth: Int,
        screenHeight: Int,
        bubbleSizePx: Int,
        edge: Edge,
        density: Float,
        imeTop: Int?
    ): Placement {
        val screenUnchanged = heldScreenWidth == screenWidth && heldScreenHeight == screenHeight
        if (heldY != null && screenUnchanged) {
            return clamp(
                edgeX(edge, screenWidth, bubbleSizePx), heldY,
                screenWidth, screenHeight, bubbleSizePx, imeTop
            )
        }
        return restPosition(
            fieldTop = fieldTop,
            fieldBottom = fieldBottom,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            bubbleSizePx = bubbleSizePx,
            edge = edge,
            gapPx = (GAP_DP * density).roundToInt(),
            minGapPx = (MIN_GAP_DP * density).roundToInt(),
            maxGapPx = (MAX_GAP_DP * density).roundToInt(),
            imeTop = imeTop
        )
    }
}
