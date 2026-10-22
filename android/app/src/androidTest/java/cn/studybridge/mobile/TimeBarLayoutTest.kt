package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TimeBarLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun clockSharesCutoutRowRatherThanAddingAnotherTimeRowBelowIt() {
        val cutout = mutableStateOf(0.dp)
        compose.setContent { StudyTheme(false) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    StudyTimeBar(clockCutoutHeight = cutout.value)
                    Text("课堂笔记", Modifier.fillMaxWidth().testTag("时间栏下方正文"))
                }
            }
        } }
        for ((height, expected) in listOf(0.dp to 36.dp, 18.dp to 36.dp, 40.dp to 48.dp)) {
            compose.runOnIdle { cutout.value = height }
            compose.onNodeWithTag("应用时间栏").assertHeightIsEqualTo(expected)
            val bar = compose.onNodeWithTag("应用时间栏").getUnclippedBoundsInRoot()
            val clock = compose.onNode(hasContentDescription("当前时间 ", substring = true)).getUnclippedBoundsInRoot()
            val body = compose.onNodeWithTag("时间栏下方正文").getUnclippedBoundsInRoot()
            assertEquals("content starts immediately after the shared row", bar.bottom.value, body.top.value, 1f)
            assertEquals("clock is centered inside the cutout row", (bar.top.value + bar.bottom.value) / 2f, (clock.top.value + clock.bottom.value) / 2f, 1f)
        }
        compose.onRoot().captureToImage()
        advancedUiShot(compose.activity, "sample-reader-time-cutout")
    }

    @Test fun retainedRunOnlyGetsCircleWhileCurrentServiceRunsAndPausedCircleResumesOnTap() {
        val saved = Saved(session = "fixture-session", classRun = ClassRun(LiveLesson("42", "遗传学", "7"), providerId = "fixture"))
        val ui = mutableStateOf(UiState(data = saved, tab = 1, ready = true))
        val serviceRunning = mutableStateOf(false)
        var taps = 0
        compose.setContent { StudyTheme(false) {
            StudyFrame(ui.value, onTab = {}, onBack = {}, onStop = {}, classServiceRunning = serviceRunning.value,
                onClassToggle = { taps++; ui.value = ui.value.copy(classUi = ui.value.classUi.copy(paused = !ui.value.classUi.paused)) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) { Text("恢复的课堂记录") }
            }
        } }
        compose.onNodeWithTag("上课悬浮圆钮").assertDoesNotExist()
        compose.runOnIdle {
            ui.value = ui.value.copy(classUi = ClassUi(running = true, paused = false))
            serviceRunning.value = true
        }
        compose.onNodeWithContentDescription("暂停跟课").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, taps); assertTrue(ui.value.classUi.paused) }
        compose.onNodeWithContentDescription("继续跟课").assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle { assertEquals(2, taps); assertFalse(ui.value.classUi.paused) }
        compose.onNodeWithContentDescription("暂停跟课").assertIsDisplayed()
        compose.runOnIdle { serviceRunning.value = false }
        compose.onNodeWithTag("上课悬浮圆钮").assertDoesNotExist()
    }
}
