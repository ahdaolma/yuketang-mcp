package cn.studybridge.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun beginnersSeeFourCategoriesAndFocusModePersistsWithoutWebControls() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.runOnIdle { vm.tab(3) }
        compose.setContent { StudyTheme(false) { StudyApp(vm) } }
        for (label in listOf("模型与账号", "笔记生成", "学习偏好", "文件与下载")) compose.onNodeWithText(label).assertIsDisplayed()
        compose.onNodeWithText("API Key").assertDoesNotExist(); compose.onNodeWithText("ChatGPT 网页").assertDoesNotExist()
        advancedUiShot(compose.activity, "sample-settings-overview")
        compose.onNodeWithText("笔记生成").performClick()
        compose.onNodeWithContentDescription("超重点模式").performClick()
        compose.waitUntil(5000) { vm.ui.value.data.superFocusNotes && Vault(app).load().superFocusNotes }
        compose.onNodeWithContentDescription("超重点模式").assertIsOn()
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        advancedUiShot(compose.activity, "sample-settings-super-focus")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("模型与账号").performClick()
        compose.onNodeWithText("配置连接").assertIsDisplayed().performClick()
        compose.onNodeWithText("API Base URL").assertIsDisplayed(); compose.onNodeWithText("API Key").assertIsDisplayed()
        compose.onNodeWithText("网页跟课适配").assertDoesNotExist()
        advancedUiShot(compose.activity, "sample-settings-model-setup")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("学习偏好").performClick()
        compose.onNodeWithText("我的学习情况").assertIsDisplayed(); compose.onNodeWithText("学习助教提示词").assertDoesNotExist()
        compose.runOnIdle { assertEquals("learning", vm.ui.value.settingsPage) }
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("关于雨课伴学").performScrollTo().performClick()
        compose.onNodeWithText("项目声明").performScrollTo().assertIsDisplayed()
        @Suppress("DEPRECATION")
        val version = compose.activity.packageManager.getPackageInfo(compose.activity.packageName, 0).versionName
        compose.onNodeWithText("版本 $version").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PROJECT_URL).assertIsDisplayed()
        compose.onNodeWithTag("项目地址").assertHasClickAction()
        advancedUiShot(compose.activity, "sample-settings-about")
        compose.onNodeWithText("开源与许可").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PROJECT_SOURCE_STATEMENT).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("使用风险与责任").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(PROJECT_RISK_STATEMENT).performScrollTo().assertIsDisplayed()
        advancedUiShot(compose.activity, "sample-settings-risk-statement")
        compose.onNodeWithContentDescription("返回设置").performClick()
        compose.onNodeWithText("模型与账号").assertIsDisplayed()
        compose.runOnIdle { assertEquals("", vm.ui.value.settingsPage); store.clear() }
    }
}
