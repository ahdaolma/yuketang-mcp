package cn.studybridge.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ControlFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun physicalTapRunsOnceAndDisabledTapExplainsWithoutRunningAction() {
        val clicks = AtomicInteger()
        val explanation = AtomicReference("")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalControlFeedback provides ControlFeedback({ explanation.set(it) }, "请先准备资料")) {
                    Column {
                        FeedbackButton(onClick = { clicks.incrementAndGet() }) { Text("可用按钮") }
                        FeedbackButton(onClick = { clicks.addAndGet(100) }, enabled = false, unavailableReason = "请先选择课件") { Text("需要课件") }
                    }
                }
            }
        }
        compose.onNodeWithText("可用按钮").performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, clicks.get()) }
        compose.onNodeWithText("需要课件").performTouchInput { click() }
        compose.runOnIdle { assertEquals("请先选择课件", explanation.get()); assertEquals(1, clicks.get()) }
    }
}
