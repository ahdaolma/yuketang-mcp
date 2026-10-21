package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ImageMarkupTest {
    private val stroke = ImageStroke(listOf(MarkPoint(.2f, .3f), MarkPoint(.8f, .7f)))
    @Test fun fittedImageCoordinatesRoundTripThroughZoomAndPanAndRejectLetterbox() {
        val viewport = MarkupViewport(320f, 600f, 800f, 500f, 2.3f, 52f, -31f)
        val screen = viewport.screen(MarkPoint(.3f, .65f))
        val point = requireNotNull(viewport.point(screen.x, screen.y))
        assertEquals(.3f, point.x, .00001f); assertEquals(.65f, point.y, .00001f)
        assertNull(MarkupViewport(320f, 600f, 800f, 500f).point(100f, 10f))
    }
    @Test fun undoRedoAndNewStrokeRetainIndependentSnapshots() {
        val first = MarkupHistory().change(listOf(stroke))
        val second = first.change(first.strokes + stroke.copy(highlight = true))
        assertEquals(first.strokes, second.undo().strokes)
        assertEquals(second.strokes, second.undo().redo().strokes)
        assertTrue(second.undo().change(emptyList()).future.isEmpty())
        assertEquals(1, first.strokes.size)
    }
    @Test fun eraserHitsWholeSegmentsAndUsesImageAspectRatio() {
        assertTrue(eraseImageStrokes(listOf(stroke), MarkPoint(.5f, .5f), .625f, .02f).isEmpty())
        assertEquals(listOf(stroke), eraseImageStrokes(listOf(stroke), MarkPoint(.1f, .9f), .625f, .02f))
    }
    @Test fun jsonPreservesPenAndHighlightAndRejectsUnknownOrCorruptRecords() {
        val strokes = listOf(stroke, stroke.copy(width = .028f, highlight = true))
        assertEquals(strokes, imageMarkupFromJson(imageMarkupJson(strokes)))
        listOf("{}", "{\"version\":2,\"strokes\":[]}", "{\"version\":1,\"strokes\":[{\"width\":0.006,\"highlight\":false,\"points\":[[2,0]]}]}").forEach { assertTrue(runCatching { imageMarkupFromJson(it) }.isFailure) }
        assertTrue(runCatching { imageMarkupJson(listOf(stroke.copy(points = listOf(MarkPoint(Float.NaN, 0f))))) }.isFailure)
    }
}
