package cn.studybridge.mobile

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalTestApi::class)
class ControlFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun interactionsRemainAvailableWithoutRequestingHapticFeedback() {
        val requests = AtomicInteger()
        val clicks = AtomicInteger()
        val actions = AtomicInteger()
        val checked = mutableStateOf(false)
        val platformFeedback = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { requests.incrementAndGet() }
        }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides platformFeedback) {
                StudyTheme {
                    val feedback = LocalHapticFeedback.current
                    Column(Modifier.systemBarsPadding()) {
                        FeedbackButton(onClick = { clicks.incrementAndGet(); feedback.performHapticFeedback(HapticFeedbackType.LongPress) }) { Text("触感关闭后的按钮") }
                        FeedbackSwitch(checked.value, { checked.value = it }, Modifier.testTag("触感关闭后的开关"))
                        NoteListCard(Note(id = "silent", title = "长按笔记", content = "正文", source = "测试", model = "测试"), { clicks.incrementAndGet() }, { actions.incrementAndGet() }) { Text("仍可打开操作") }
                    }
                }
            }
        }
        compose.onNodeWithText("触感关闭后的按钮").performTouchInput { click() }
        compose.onNodeWithTag("触感关闭后的开关").performTouchInput { click() }
        compose.onNodeWithTag("笔记卡片 silent").performTouchInput { longClick(durationMillis = 900) }
        compose.runOnIdle {
            assertEquals(1, clicks.get())
            assertTrue(checked.value)
            assertEquals(1, actions.get())
            assertEquals("Built-in and application haptics must not reach the platform provider", 0, requests.get())
            val text = NoteSelectableTextView(compose.activity)
            assertFalse(text.isHapticFeedbackEnabled)
            assertFalse(text.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS))
            val overlay = ClassOverlayView(compose.activity, { clicks.incrementAndGet() }, { _, _ -> }, {})
            assertFalse(overlay.isHapticFeedbackEnabled)
            overlay.performClick()
            assertEquals(2, clicks.get())
        }
    }

    @Test fun physicalTapRunsOnceAndDisabledTapExplainsWithoutRunningAction() {
        val clicks = AtomicInteger()
        val explanation = AtomicReference("")
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalControlFeedback provides ControlFeedback({ explanation.set(it) }, "请先准备资料")) {
                    Column(Modifier.systemBarsPadding()) {
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

    @Test fun disabledLongPressCancelledTouchAndScrollingDoNotExplainOrRun() {
        val clicks = AtomicInteger()
        val explanations = AtomicInteger()
        lateinit var scroll: ScrollState
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                CompositionLocalProvider(LocalControlFeedback provides ControlFeedback({ explanations.incrementAndGet() }, "暂不可用")) {
                    Column(Modifier.systemBarsPadding().height(260.dp).verticalScroll(scroll)) {
                        FeedbackButton(onClick = { clicks.incrementAndGet() }, enabled = false, modifier = Modifier.fillMaxWidth().height(160.dp)) { Text("不可用入口") }
                        repeat(30) { Text("可滚动正文 $it") }
                    }
                }
            }
        }
        compose.onNodeWithText("不可用入口").performTouchInput { longClick(durationMillis = 900) }
        compose.onNodeWithText("不可用入口").performTouchInput { down(center); cancel() }
        compose.onNodeWithText("不可用入口").performTouchInput { swipeUp(durationMillis = 400) }
        compose.runOnIdle {
            assertEquals(0, clicks.get())
            assertEquals(0, explanations.get())
            assertTrue("A disabled control must not block its parent's scrolling", scroll.value > 0)
        }
    }

    @Test fun feedbackUsesLatestProviderAndRapidLegitimateTapsRemainAvailable() {
        val first = AtomicInteger()
        val latest = AtomicInteger()
        val revision = mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                val explain: (String) -> Unit = if (revision.value) ({ latest.incrementAndGet() }) else ({ first.incrementAndGet() })
                CompositionLocalProvider(LocalControlFeedback provides ControlFeedback(explain, "相同的不可用原因")) {
                    Column(Modifier.systemBarsPadding()) {
                        FeedbackButton(onClick = {}, enabled = false) { Text("原因") }
                        FeedbackButton(onClick = { latest.incrementAndGet() }) { Text("继续编辑") }
                    }
                }
            }
        }
        compose.runOnIdle { revision.value = true }
        compose.onNodeWithText("原因").performTouchInput { click() }
        compose.onNodeWithText("继续编辑").performTouchInput { repeat(3) { click(); advanceEventTime(50) } }
        compose.runOnIdle { assertEquals(0, first.get()); assertEquals(4, latest.get()) }
    }

    @Test fun pressingNestedActionDoesNotShrinkOrActivateItsParentCard() {
        val parentClicks = AtomicInteger()
        val childClicks = AtomicInteger()
        compose.setContent {
            MaterialTheme {
                Column(Modifier.systemBarsPadding()) {
                    FeedbackCard(onClick = { parentClicks.incrementAndGet() }, modifier = Modifier.fillMaxWidth().testTag("父卡")) {
                        Text("课程内容")
                        FeedbackButton(onClick = { childClicks.incrementAndGet() }) { Text("子操作") }
                    }
                }
            }
        }
        val before = compose.onNodeWithTag("父卡").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithText("子操作").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(180)
            val held = compose.onNodeWithTag("父卡").getUnclippedBoundsInRoot()
            assertEquals("Only the actual pressed control may scale", (before.right - before.left).value, (held.right - held.left).value, 0.1f)
            compose.onNodeWithText("子操作").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        compose.runOnIdle { assertEquals(0, parentClicks.get()); assertEquals(1, childClicks.get()) }
    }

    @Test fun nativeScrollingCancelsActionAndKeyboardActivationRunsOnce() {
        val clicks = AtomicInteger()
        lateinit var scroll: ScrollState
        lateinit var inputMode: InputModeManager
        compose.setContent {
            MaterialTheme {
                inputMode = LocalInputModeManager.current
                scroll = rememberScrollState()
                Column(Modifier.systemBarsPadding().height(260.dp).verticalScroll(scroll)) {
                    FeedbackButton(onClick = { clicks.incrementAndGet() }, modifier = Modifier.fillMaxWidth().height(160.dp)) { Text("滚动中的入口") }
                    repeat(30) { Text("正文 $it") }
                }
            }
        }
        compose.onNodeWithText("滚动中的入口").performTouchInput { swipeUp(durationMillis = 400) }
        compose.runOnIdle { assertEquals(0, clicks.get()); assertTrue(scroll.value > 0) }
        compose.runOnIdle { assertTrue(inputMode.requestInputMode(InputMode.Keyboard)) }
        compose.onNodeWithText("滚动中的入口").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("滚动中的入口").assertIsFocused()
        compose.onNodeWithText("滚动中的入口").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        compose.runOnIdle { assertEquals(1, clicks.get()) }
    }
}
