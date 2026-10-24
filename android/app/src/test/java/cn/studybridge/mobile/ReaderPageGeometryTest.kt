package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ReaderPageGeometryTest {
    private fun area(points: List<PaperPoint>): Float {
        if (points.size < 3) return 0f
        return abs(points.indices.sumOf { i ->
            val next = points[(i + 1) % points.size]
            (points[i].x * next.y - next.x * points[i].y).toDouble()
        }.toFloat()) / 2f
    }
    @Test fun completedAndUnstartedPaperHaveNoResidualFold() {
        val first = readerPaperFold(600f, 900f, 0f, true, 400f)
        val last = readerPaperFold(600f, 900f, 1f, true, 400f)
        assertEquals(540000f, area(first.front), .1f)
        assertTrue(first.back.isEmpty())
        assertTrue(last.front.isEmpty()); assertTrue(last.back.isEmpty())
    }
    @Test fun everyCornerAndProgressPreserveFullUnfoldedSourceAreaAndFiniteCoordinates() {
        for (bottom in listOf(true, false)) for (step in 1..99) {
            val fold = readerPaperFold(600f, 900f, step / 100f, bottom, 350f)
            assertEquals(540000f, area(fold.front) + area(fold.folded), 1f)
            assertTrue(fold.back.all { it.x in -.1f..600.1f && it.y in -.1f..900.1f })
            assertEquals(2, fold.crease.size)
            fold.folded.forEach { point ->
                val roundTrip = fold.reflect(fold.reflect(point))
                assertEquals(point.x, roundTrip.x, .01f); assertEquals(point.y, roundTrip.y, .01f)
            }
        }
    }
    @Test fun topAndBottomFoldsAreMirroredWithoutMovingTheirSourceRank() {
        for (step in 1..9) {
            val upper = readerPaperFold(400f, 800f, step / 10f, false, 300f)
            val lower = readerPaperFold(400f, 800f, step / 10f, true, 500f)
            assertEquals(area(upper.front), area(lower.front), .2f)
            assertEquals(area(upper.back), area(lower.back), .2f)
        }
    }
    @Test fun oldSettingsDefaultToCurlAndBothChoicesRoundTrip() {
        val legacy = Saved().toJson().apply { remove("notePageAnimation") }
        assertEquals("curl", savedFromJson(legacy).notePageAnimation)
        NOTE_PAGE_ANIMATIONS.keys.forEach { style ->
            assertEquals(style, savedFromJson(Saved(notePageAnimation = style).toJson()).notePageAnimation)
        }
        assertEquals("curl", validNotePageAnimation("unknown"))
    }
}
