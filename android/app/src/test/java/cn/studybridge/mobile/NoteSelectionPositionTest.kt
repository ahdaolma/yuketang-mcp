package cn.studybridge.mobile

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.*
import org.junit.Test

class NoteSelectionPositionTest {
    private fun position(selection: IntRect, safe: IntRect, size: IntSize = IntSize(220, 48)) =
        NoteSelectionPositionProvider(NoteSelectionGeometry(selection, safe), 8)
            .calculatePosition(IntRect.Zero, IntSize(1200, 900), LayoutDirection.Ltr, size)

    @Test fun menuFollowsTheSelectedLineRatherThanTheBottomOfTheReader() {
        val safe = IntRect(0, 24, 400, 800)
        val upper = position(IntRect(130, 180, 190, 204), safe)
        val lower = position(IntRect(130, 480, 190, 504), safe)
        assertEquals(124, upper.y)
        assertEquals(424, lower.y)
        assertEquals(300, lower.y - upper.y)
    }

    @Test fun selectionNearTopPlacesMenuBelowAndHorizontalEdgesStayVisible() {
        val safe = IntRect(30, 40, 350, 620)
        val left = position(IntRect(31, 42, 48, 70), safe)
        assertEquals(38, left.x)
        assertEquals(78, left.y)
        val right = position(IntRect(330, 580, 350, 610), safe)
        assertEquals(122, right.x)
        assertEquals(524, right.y)
    }

    @Test fun translatedWindowCutoutAndKeyboardBoundsAreRespected() {
        // A split/floating window has a non-zero origin; the IME ends its
        // visible area at y=470 and a side cutout ends at x=90.
        val safe = IntRect(90, 50, 630, 470)
        val result = position(IntRect(510, 420, 590, 454), safe, IntSize(280, 64))
        assertTrue(result.x >= safe.left + 8)
        assertTrue(result.x + 280 <= safe.right - 8)
        assertEquals(348, result.y)
        assertTrue(result.y + 64 <= safe.bottom - 8)
    }

    @Test fun largeMultiLineSelectionUsesAvailableEdgeWithoutLeavingTheWindow() {
        val safe = IntRect(0, 0, 320, 180)
        val result = position(IntRect(20, 18, 300, 170), safe, IntSize(300, 64))
        assertEquals(10, result.x)
        assertEquals(8, result.y)
    }
}
