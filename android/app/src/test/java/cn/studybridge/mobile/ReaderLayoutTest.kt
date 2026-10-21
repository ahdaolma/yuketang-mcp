package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ReaderLayoutTest {
    @Test fun sourcePageCounterDoesNotCountLongPageSplitsAsNewSourcePages() {
        val content = "## 第一讲 · 第 5 页\n\n" + "同一来源页的长正文。".repeat(2500) + "\n\n## 第一讲 · 第 6 页\n\n最后一页。"
        val segments = noteReadingSegments(content)
        val labels = readerPageLabels(content, segments)
        assertTrue("Long source pages need more than one rendering segment", segments.size > 2)
        assertTrue(labels.dropLast(1).all { it == "第 5 页 · 1/2" })
        assertEquals("第 6 页 · 2/2", labels.last())
    }

    private fun classroomUi(paused: Boolean = false): UiState = UiState(
        data = Saved(session = "fixture-session", classRun = ClassRun(LiveLesson("42", "遗传学", "7"), providerId = "fixture")),
        classUi = ClassUi(running = true, paused = paused)
    )

    @Test fun retainedLessonNeverCreatesCircleWithoutCurrentService() {
        val active = classroomUi()
        assertFalse(showClassControl(active, serviceRunning = false))
        assertFalse(showClassControl(active.copy(classUi = ClassUi()), serviceRunning = false))
        assertFalse(showClassControl(classroomUi(paused = true), serviceRunning = false))
    }

    @Test fun liveServiceKeepsCircleWhenPausedSoOneTapCanContinue() {
        assertTrue(showClassControl(classroomUi(), serviceRunning = true))
        assertTrue(showClassControl(classroomUi(paused = true), serviceRunning = true))
        assertFalse(showClassControl(classroomUi().copy(classUi = ClassUi(running = false)), serviceRunning = true))
    }

    @Test fun circleNeedsSessionAndUnfinishedLessonOrPendingWork() {
        val ui = classroomUi()
        assertFalse(showClassControl(ui.copy(data = ui.data.copy(session = "  ")), serviceRunning = true))
        assertFalse(showClassControl(ui.copy(data = ui.data.copy(classRun = null)), serviceRunning = true))
        val ended = ui.data.classRun!!.copy(ended = true)
        assertFalse(showClassControl(ui.copy(data = ui.data.copy(classRun = ended)), serviceRunning = true))
        val pending = ended.copy(pending = listOf(LivePage("12", 1)))
        assertTrue(showClassControl(ui.copy(data = ui.data.copy(classRun = pending)), serviceRunning = true))
        assertFalse(showClassControl(ui.copy(data = ui.data.copy(classRun = pending)), serviceRunning = false))
    }

    @Test fun screenAdvanceLeavesOverlapAndVisitsExactEndBeforeSegmentChange() {
        assertEquals(88, nextReadingScreen(0, 250, 100, 1))
        assertEquals(176, nextReadingScreen(88, 250, 100, 1))
        assertEquals(250, nextReadingScreen(176, 250, 100, 1))
        assertEquals(250, nextReadingScreen(250, 250, 100, 1))
        assertEquals(162, nextReadingScreen(250, 250, 100, -1))
        assertEquals(0, nextReadingScreen(42, 250, 100, -1))
    }

    @Test fun everyScrollRangeCanReachBothEndsWithoutSkippingUnreadContent() {
        for (maximum in 0..160) for (viewport in 1..40) {
            var offset = 0
            var advances = 0
            while (offset < maximum) {
                val next = nextReadingScreen(offset, maximum, viewport, 1)
                assertTrue("forward max=$maximum viewport=$viewport offset=$offset", next > offset && next <= maximum)
                assertTrue("a screen advance must not leave a gap", next - offset <= viewport)
                offset = next
                assertTrue("must terminate", ++advances <= maximum)
            }
            assertEquals(maximum, offset)
            var retreats = 0
            while (offset > 0) {
                val next = nextReadingScreen(offset, maximum, viewport, -1)
                assertTrue("backward max=$maximum viewport=$viewport offset=$offset", next >= 0 && next < offset)
                assertTrue("a screen retreat must not leave a gap", offset - next <= viewport)
                offset = next
                assertTrue("must terminate", ++retreats <= maximum)
            }
            assertEquals(0, offset)
        }
    }

    @Test fun screenAdvanceClampsInvalidAndIntegerBoundaryInputs() {
        assertEquals(0, nextReadingScreen(100, -1, 100, 1))
        assertEquals(0, nextReadingScreen(Int.MIN_VALUE, Int.MAX_VALUE, 100, -1))
        assertEquals(Int.MAX_VALUE, nextReadingScreen(Int.MAX_VALUE - 1, Int.MAX_VALUE, Int.MAX_VALUE, 1))
        assertEquals(0, nextReadingScreen(1, Int.MAX_VALUE, Int.MAX_VALUE, -1))
        assertEquals(11, nextReadingScreen(10, 100, 0, 1))
        assertEquals(9, nextReadingScreen(10, 100, Int.MIN_VALUE, -1))
        assertEquals(10, nextReadingScreen(10, 100, 100, 0))
        assertEquals(100, nextReadingScreen(Int.MAX_VALUE, 100, 100, 0))
        assertEquals(0, nextReadingScreen(Int.MIN_VALUE, 100, 100, 0))
    }

    @Test fun legacySavedPreferencesDefaultToContinuousReadingAtSixteen() {
        val old = Saved().toJson().apply { remove("noteReadMode"); remove("noteFontSize") }
        val restored = savedFromJson(old)
        assertEquals("continuous", restored.noteReadMode)
        assertEquals(16, restored.noteFontSize)
    }

    @Test fun eachReadingModeAndSupportedFontSizeSurvivesSerialization() {
        for (mode in listOf("continuous", "buttons", "swipe")) for (size in 12..28) {
            val original = Saved(noteReadMode = mode, noteFontSize = size)
            val restored = savedFromJson(original.toJson())
            assertEquals(mode, restored.noteReadMode)
            assertEquals(size, restored.noteFontSize)
        }
    }

    @Test fun unsupportedSavedPreferencesFallBackOrClamp() {
        val invalid = Saved().toJson().apply { put("noteReadMode", "unknown"); put("noteFontSize", Int.MIN_VALUE) }
        assertEquals("continuous", savedFromJson(invalid).noteReadMode)
        assertEquals(12, savedFromJson(invalid).noteFontSize)
        invalid.put("noteFontSize", Int.MAX_VALUE)
        assertEquals(28, savedFromJson(invalid).noteFontSize)
    }
}
