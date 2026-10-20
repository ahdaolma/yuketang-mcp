package cn.studybridge.mobile

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LearnerSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun editingSavingAndClearingProfileWritesEncryptedRecordWithoutLosingNotes() {
        lateinit var vm: StudyViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        compose.waitUntil(5000) { vm.ui.value.ready }
        val original = vm.ui.value.data
        compose.setContent { val ui by vm.ui.collectAsState(); StudyTheme(false) { ModelScreen(ui, vm) } }
        compose.onNodeWithText("学习偏好").performClick()
        compose.onNodeWithText("我的学习情况").performScrollTo().performClick()
        val field = compose.onNodeWithText("当前水平与学习需求")
        field.performScrollTo().performTextReplacement("基础较弱，希望先讲术语，再分步骤解释。")
        compose.onNodeWithText("保存学习情况").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.data.learnerProfile == "基础较弱，希望先讲术语，再分步骤解释。" }
        val loaded = Vault(compose.activity).load()
        assertEquals(vm.ui.value.data.learnerProfile, loaded.learnerProfile)
        assertEquals(original.notes, loaded.notes); assertEquals(original.providers, loaded.providers); assertEquals(original.session, loaded.session)
        compose.onNodeWithText("清除").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.data.learnerProfile.isEmpty() }
        assertEquals("", Vault(compose.activity).load().learnerProfile)
        compose.runOnIdle { vm.saveLearnerProfile(original.learnerProfile) }
        compose.waitUntil(5000) { !vm.ui.value.busy }
    }
}
