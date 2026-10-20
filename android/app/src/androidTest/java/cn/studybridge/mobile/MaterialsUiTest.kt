package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.provider.MediaStore
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MaterialsUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun fullSourceTitleAndActualTimeRemainVisibleAndDownloadSettingsAreReachable() {
        val title = "医学遗传学 Medical Genetics — Chapter 03 Molecular Basis of Inherited Disease 完整原始课件名称"
        val state = mutableStateOf(UiState(ready = true, materialsOpen = true, materialPane = "rain", course = Course("1", "医学遗传学"), presentations = listOf(Presentation("12", title, recordedAt = "2026-10-20 08:00"))))
        lateinit var vm: StudyViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        kotlinx.coroutines.runBlocking { vm.previews.load(vm.deckPreviewKey(state.value, state.value.presentations.single())) { Attachment(syntheticSlideImage(), title, "") } }
        val beforeDialog = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        compose.setContent { StudyTheme(false) { if (state.value.materialsOpen) LearningMaterialsHost(state.value, vm) else ModelScreen(state.value, vm) } }
        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText("课件 · 2026-10-20 08:00").assertIsDisplayed()
        compose.waitForIdle()
        compose.waitUntil(5000) { focusedAppStatusBarsHidden() }
        val bitmap = dialogScreenshot(compose, beforeDialog)
        try { val resolver = compose.activity.contentResolver; val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "sample-materials.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1) })); requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) } finally { bitmap.recycle() }
        compose.runOnIdle { state.value = state.value.copy(materialsOpen = false, tab = 3, data = state.value.data.copy(downloadDirectoryName = "学习资料文件夹", downloadTreeUri = "content://fixture/tree/notes")) }
        compose.onNodeWithText("文件与下载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("学习资料文件夹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("选择文件夹").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("每次询问").performScrollTo().assertIsDisplayed()
    }
}
