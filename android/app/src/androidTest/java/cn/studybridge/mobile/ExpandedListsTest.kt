package cn.studybridge.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ExpandedListsTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun assertBounded(tag: String, ratio: Float, maximum: Float) {
        val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        val limit = minOf(maximum, compose.activity.resources.configuration.screenHeightDp * ratio)
        assertTrue("$tag must be bounded: ${bounds.bottom - bounds.top} > $limit", (bounds.bottom - bounds.top).value <= limit + 2f)
    }

    @Test fun longAnchoredMenusScrollInternallyAndTheLastActionWorks() {
        var open by mutableStateOf(true)
        var selected = 0
        compose.setContent { StudyTheme(false) {
            Box(Modifier.fillMaxSize()) {
                Text("菜单背景")
                StudyDropdownMenu(open, { open = false }) {
                    (1..60).forEach { number -> FeedbackDropdownMenuItem(text = { Text("操作 $number") }, onClick = { selected = number; open = false }) }
                }
            }
        } }
        assertBounded("展开菜单容器", .45f, 320f)
        compose.onNodeWithText("操作 60").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭选择操作").assertIsDisplayed()
        advancedUiShot(compose.activity, "sample-expanded-dropdown")
        compose.onNodeWithText("操作 60").performClick()
        compose.runOnIdle { assertEquals(60, selected) }
        compose.onNodeWithTag("展开菜单容器").assertDoesNotExist()
    }

    @Test fun longDirectoryKeepsItsSearchAndCloseVisibleWhileLastPageIsReachable() {
        var open by mutableStateOf(true)
        var selected = -1
        compose.setContent { StudyTheme(false) {
            Text("目录背景")
            if (open) JumpDialog("课件目录", (1..120).map { "课件第${it}页" }, { open = false }) { selected = it; open = false }
        } }
        assertBounded("跳转选择列表", .45f, 360f)
        compose.onNodeWithTag("跳转选择列表").performScrollToIndex(119)
        compose.onNodeWithText("120. 课件第120页").assertIsDisplayed()
        compose.onNodeWithText("输入序号或关键词").assertIsDisplayed()
        compose.onNodeWithText("取消跳转").assertIsDisplayed()
        advancedUiShot(compose.activity, "sample-expanded-directory")
        compose.onNodeWithText("120. 课件第120页").performClick()
        compose.runOnIdle { assertEquals(119, selected) }
    }

    @Test fun classroomChoicesAreBoundedEvenWhenManyLessonsAreAvailable() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        val ui = UiState(ready = true, data = Saved(session = "fixture-only"), liveLessons = (1..40).map { LiveLesson("$it", "课堂 $it", "1") })
        compose.setContent { StudyTheme(false) { Box(Modifier.fillMaxSize()) { ClassModePanel(ui, vm) } } }
        try {
            assertBounded("课堂选择列表", .45f, 240f)
            compose.onNodeWithText("进入：课堂 40").performScrollTo().assertIsDisplayed().assertIsEnabled()
            advancedUiShot(compose.activity, "sample-expanded-classes")
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun landscapeLargeTextSheetKeepsCloseFixedAndAllActionsReachable() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        try {
            compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            var open by mutableStateOf(true)
            var selected = 0
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) { StudyTheme(false) {
                    Text("面板背景")
                    if (open) ReadingActionSheet("全部操作", { open = false }) {
                        (1..40).forEach { number -> FeedbackDropdownMenuItem(text = { Text("列表操作 $number") }, onClick = { selected = number }) }
                    }
                } }
            }
            assertBounded("展开面板容器", .60f, 440f)
            compose.onNodeWithText("列表操作 40").performScrollTo().assertIsDisplayed().performClick()
            compose.runOnIdle { assertEquals(40, selected) }
            compose.onNodeWithContentDescription("关闭全部操作").assertIsDisplayed()
            advancedUiShot(compose.activity, "sample-expanded-landscape")
            compose.onNodeWithContentDescription("关闭全部操作").performClick()
            compose.onNodeWithTag("展开面板容器").assertDoesNotExist()
        } finally { compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT } }
    }
}
