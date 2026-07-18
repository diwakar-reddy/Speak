package com.apps.dsimpletools.speak.overlay

import com.apps.dsimpletools.speak.overlay.BubblePositioner.Edge
import com.apps.dsimpletools.speak.overlay.BubblePositioner.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of the framework-free bubble placement math: edge resolution,
 * on-screen / above-IME clamping, the rest position (above the field with a shrink-to-fit
 * gap, dropping below the field when there's no room above), and the composite [resolve]
 * decision (held-y vs rest, with the rotation guard).
 */
class BubblePositionerTest {

    private val bubble = 48
    private val screenW = 1080
    private val screenH = 2400

    // --- edgeX -------------------------------------------------------------

    @Test fun edgeX_left_isZero() {
        assertEquals(0, BubblePositioner.edgeX(Edge.LEFT, screenW, bubble))
    }

    @Test fun edgeX_right_isFlushToRightSide() {
        assertEquals(screenW - bubble, BubblePositioner.edgeX(Edge.RIGHT, screenW, bubble))
    }

    @Test fun edgeX_right_neverNegativeOnNarrowScreen() {
        assertEquals(0, BubblePositioner.edgeX(Edge.RIGHT, 30, bubble))
    }

    // --- nearestEdge -------------------------------------------------------

    @Test fun nearestEdge_leftHalf_isLeft() {
        assertEquals(Edge.LEFT, BubblePositioner.nearestEdge(0, screenW, bubble))
    }

    @Test fun nearestEdge_rightHalf_isRight() {
        assertEquals(Edge.RIGHT, BubblePositioner.nearestEdge(screenW - bubble, screenW, bubble))
    }

    @Test fun nearestEdge_justLeftOfCentre_isLeft() {
        // centre = x + 24; x=515 -> 539, 539*2=1078 < 1080 -> LEFT
        assertEquals(Edge.LEFT, BubblePositioner.nearestEdge(515, screenW, bubble))
    }

    @Test fun nearestEdge_exactlyCentred_tiesToRight() {
        // x=516 -> centre 540, 540*2=1080 not < 1080 -> RIGHT
        assertEquals(Edge.RIGHT, BubblePositioner.nearestEdge(516, screenW, bubble))
    }

    // --- clamp -------------------------------------------------------------

    @Test fun clamp_keepsBubbleOnScreen() {
        val p = BubblePositioner.clamp(2000, -10, screenW, screenH, bubble, imeTop = null)
        assertEquals(screenW - bubble, p.x)
        assertEquals(0, p.y)
    }

    @Test fun clamp_noIme_allowsFullHeight() {
        val p = BubblePositioner.clamp(100, 5000, screenW, screenH, bubble, imeTop = null)
        assertEquals(screenH - bubble, p.y)
    }

    @Test fun clamp_withIme_keepsBubbleAboveKeyboard() {
        val imeTop = 1600
        val p = BubblePositioner.clamp(100, 1700, screenW, screenH, bubble, imeTop)
        assertEquals(imeTop - bubble, p.y)
        assertEquals(imeTop, p.y + bubble) // bubble bottom sits exactly on the IME top
    }

    @Test fun clamp_withIme_leavesInBoundsYUntouched() {
        val p = BubblePositioner.clamp(100, 1000, screenW, screenH, bubble, imeTop = 1600)
        assertEquals(1000, p.y)
    }

    // --- restPosition ------------------------------------------------------

    private fun rest(
        fieldTop: Int, fieldBottom: Int, edge: Edge = Edge.RIGHT,
        gap: Int = 24, min: Int = 16, max: Int = 36, imeTop: Int? = null,
        w: Int = screenW, h: Int = screenH, size: Int = bubble
    ): Placement = BubblePositioner.restPosition(
        fieldTop = fieldTop, fieldBottom = fieldBottom, screenWidth = w, screenHeight = h,
        bubbleSizePx = size, edge = edge, gapPx = gap, minGapPx = min, maxGapPx = max, imeTop = imeTop
    )

    @Test fun restPosition_ampleRoom_sitsFullGapAboveField_atRightEdge() {
        val p = rest(fieldTop = 1000, fieldBottom = 1100)
        assertEquals(screenW - bubble, p.x)
        assertEquals(1000 - 24 - bubble, p.y) // 928
    }

    @Test fun restPosition_leftEdge_flushLeft() {
        val p = rest(fieldTop = 1000, fieldBottom = 1100, edge = Edge.LEFT)
        assertEquals(0, p.x)
        assertEquals(1000 - 24 - bubble, p.y)
    }

    @Test fun restPosition_capsGapAtMax() {
        val p = rest(fieldTop = 1000, fieldBottom = 1100, gap = 200) // clamped down to 36
        assertEquals(1000 - 36 - bubble, p.y) // 916
    }

    @Test fun restPosition_tightRoom_shrinksGapAndPinsToTop() {
        // roomAbove = 70-48 = 22 (in [16,24)): gap shrinks to 22, y pins to 0.
        val p = rest(fieldTop = 70, fieldBottom = 170)
        assertEquals(0, p.y)
    }

    @Test fun restPosition_noRoomAbove_dropsBelowField_realisticBrowserBar() {
        // Density-3 numbers: 144px bubble, address bar at fieldTop=100 (bar taller than the
        // room above it). Old logic clamped y to 0 and COVERED the bar; now it drops below.
        val bigBubble = 144
        val p = rest(
            fieldTop = 100, fieldBottom = 250, gap = 72, min = 48, max = 108,
            size = bigBubble
        )
        assertEquals(250 + 72, p.y) // 322, just below the bar
        assertTrue("bubble top must clear the field bottom", p.y >= 250)
    }

    // --- resolve -----------------------------------------------------------

    @Test fun resolve_heldY_sameScreen_keepsDraggedY() {
        val p = BubblePositioner.resolve(
            heldY = 1500, heldScreenWidth = screenW, heldScreenHeight = screenH,
            fieldTop = 1000, fieldBottom = 1100, screenWidth = screenW, screenHeight = screenH,
            bubbleSizePx = bubble, edge = Edge.RIGHT, density = 1f, imeTop = null
        )
        assertEquals(screenW - bubble, p.x)
        assertEquals(1500, p.y)
    }

    @Test fun resolve_heldY_afterRotation_discardsItAndRestsAboveField() {
        // Held y=1900 was captured in portrait (1080x2400). Now landscape (2400x1080): keeping
        // it would clamp to the bottom edge; instead we recompute the rest position.
        val p = BubblePositioner.resolve(
            heldY = 1900, heldScreenWidth = 1080, heldScreenHeight = 2400,
            fieldTop = 400, fieldBottom = 500, screenWidth = 2400, screenHeight = 1080,
            bubbleSizePx = bubble, edge = Edge.RIGHT, density = 1f, imeTop = null
        )
        assertEquals(2400 - bubble, p.x)
        assertEquals(400 - 24 - bubble, p.y) // 328 rest, not a clamped 1900
    }

    @Test fun resolve_noHeldY_usesRestPosition() {
        val p = BubblePositioner.resolve(
            heldY = null, heldScreenWidth = 0, heldScreenHeight = 0,
            fieldTop = 1000, fieldBottom = 1100, screenWidth = screenW, screenHeight = screenH,
            bubbleSizePx = bubble, edge = Edge.RIGHT, density = 1f, imeTop = null
        )
        assertEquals(1000 - 24 - bubble, p.y) // 928
    }

    @Test fun resolve_heldY_clampedAboveIme() {
        val p = BubblePositioner.resolve(
            heldY = 2000, heldScreenWidth = screenW, heldScreenHeight = screenH,
            fieldTop = 1000, fieldBottom = 1100, screenWidth = screenW, screenHeight = screenH,
            bubbleSizePx = bubble, edge = Edge.RIGHT, density = 1f, imeTop = 1500
        )
        assertEquals(1500 - bubble, p.y)
    }
}
