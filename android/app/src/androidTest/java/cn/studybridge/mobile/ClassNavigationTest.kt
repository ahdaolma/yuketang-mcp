package cn.studybridge.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClassNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun restoredPausedClassUsesSharedManagerWithoutCoveringCoursesOrNotes() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note("retained-class", "遗传学 · 课堂笔记", "已经保存的课堂内容。", "课堂", "测试")
        val run = ClassRun(LiveLesson("42", "遗传学", "7"), noteId = note.id, providerId = "fixture", completed = listOf("12:1", "12:2"))
        Vault(app).save(Saved(session = "fixture-session-123", notes = listOf(note), classRun = run))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.runOnUiThread { vm.tab(1) }
        compose.setContent { StudyTheme(false) { StudyApp(vm) } }
        try {
            compose.onNodeWithText("我的课程").assertIsDisplayed()
            compose.onNodeWithTag("上课控制面板").assertDoesNotExist()
            compose.onNodeWithContentDescription("继续跟课").assertIsDisplayed()
            compose.onNodeWithContentDescription("上课管理").assertIsDisplayed()
            compose.onRoot().captureToImage()
            advancedUiShot(compose.activity, "sample-courses-with-paused-class")
            compose.onNodeWithContentDescription("笔记").performClick()
            compose.onNodeWithText(note.title).assertIsDisplayed()
            compose.onNodeWithTag("上课控制面板").assertDoesNotExist()
            compose.onNodeWithContentDescription("继续跟课").assertIsDisplayed()
            compose.onRoot().captureToImage()
            advancedUiShot(compose.activity, "sample-notes-with-paused-class")
            compose.onNodeWithContentDescription("上课管理").performClick()
            compose.onNodeWithTag("上课控制面板").assertIsDisplayed()
            compose.onNodeWithText("继续", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithText("已整理 2 页 · 待处理 0 页").assertIsDisplayed()
            compose.onNodeWithTag("上课控制面板").captureToImage()
            advancedUiShot(compose.activity, "sample-class-management")
            compose.onNodeWithText("查看笔记", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("上课控制面板").assertDoesNotExist()
            compose.onNodeWithText(note.title).performClick()
            compose.onNodeWithContentDescription("笔记选项").performClick()
            compose.onNodeWithText("上课管理", useUnmergedTree = true).performClick()
            compose.onNodeWithText("结束", useUnmergedTree = true).performClick()
            compose.waitUntil(5000) { vm.ui.value.data.classRun?.ended == true }
            compose.onNodeWithTag("上课悬浮圆钮").assertDoesNotExist()
            compose.onNodeWithTag("上课控制面板").assertDoesNotExist()
            compose.runOnIdle { assertEquals(note, vm.ui.value.data.notes.single()) }
            compose.runOnUiThread { vm.tab(0) }
            compose.onNodeWithContentDescription("会话").performClick()
            compose.onNodeWithText("上课管理", useUnmergedTree = true).performClick()
            compose.onNodeWithText("查找正在上的课", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭上课管理").performClick()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun fastTabReversalsKeepOnlyCurrentPageEvenDuringAnimation() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.setContent { StudyTheme(false) { StudyApp(vm) } }
        compose.onNodeWithText("从一页课件开始").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        try {
            for (tab in listOf(1, 2, 0, 3, 1, 0, 2)) {
                compose.runOnUiThread { vm.tab(tab) }
                compose.mainClock.advanceTimeBy(32)
                compose.waitForIdle()
                if (tab == 0) compose.onNodeWithText("从一页课件开始").assertExists()
                else compose.onNodeWithText("从一页课件开始").assertDoesNotExist()
                if (tab == 1) compose.onNodeWithText("微信扫码登录").assertExists()
                else compose.onNodeWithText("微信扫码登录").assertDoesNotExist()
                if (tab == 2) compose.onNodeWithText("搜索笔记").assertExists()
                else compose.onNodeWithText("搜索笔记").assertDoesNotExist()
                if (tab == 3) compose.onNodeWithText("文件与下载").assertExists()
                else compose.onNodeWithText("文件与下载").assertDoesNotExist()
            }
            compose.mainClock.advanceTimeBy(48)
            compose.onRoot().captureToImage()
            advancedUiShot(compose.activity, "sample-page-switch-mid")
            compose.mainClock.advanceTimeBy(240)
            compose.waitForIdle()
            compose.onNodeWithText("搜索笔记").assertIsDisplayed()
            compose.onRoot().captureToImage()
            advancedUiShot(compose.activity, "sample-page-switch-complete")
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnIdle { store.clear() }
        }
    }
}
