package cn.studybridge.mobile

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class FeedbackLayoutTest {
    @Test fun visibleFeedbackKeepsActionsAtItsTopWithAndWithoutBottomNavigation() {
        val windowHeight = 800.dp
        for (bottomChrome in listOf(24.dp, 92.dp)) {
            val reservation = feedbackBottomReservation(56.dp, 0.dp, bottomChrome)
            val actionBottom = windowHeight - bottomChrome - reservation
            val feedbackTop = windowHeight - bottomChrome - 56.dp
            assertEquals(feedbackTop, actionBottom)
        }
    }

    @Test fun partiallyCoveringKeyboardReservesOnlyTheVisibleFeedbackAboveIt() {
        // The 50dp keyboard overlaps the bottom 26dp of feedback above a
        // 24dp system bar. The action must end at y=720, not y=694.
        val reservation = feedbackBottomReservation(56.dp, 50.dp, 24.dp)
        val actionBottom = 800.dp - 50.dp - reservation
        assertEquals(720.dp, actionBottom)
        // With a navigation bar, some IME space was already consumed by the
        // 92dp Scaffold padding, so the remaining overlap differs.
        val navigationReservation = feedbackBottomReservation(56.dp, 100.dp, 92.dp)
        assertEquals(652.dp, 800.dp - 100.dp - navigationReservation)
    }

    @Test fun keyboardHiddenFeedbackPreservesTheLastFiftyDpOfShortLandscapeContent() {
        val reservation = feedbackBottomReservation(56.dp, 210.dp, 24.dp)
        val contentHeight = 300.dp - 40.dp - 210.dp - reservation
        assertEquals(50.dp, contentHeight)
        assertEquals(0.dp, feedbackBottomReservation(56.dp, 210.dp, 0.dp))
    }

    @Test fun dismissalRestoresSpaceAtEveryKeyboardPosition() {
        for (ime in listOf(0.dp, 30.dp, 50.dp, 210.dp)) {
            assertEquals(0.dp, feedbackBottomReservation(0.dp, ime, 24.dp))
        }
    }
}
