package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ModelCatalogUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun choosesFromActualCatalogAndCannotReuseItAfterEditingTheDestination() {
        val p = Provider(id = "catalog-fixture", name = "测试服务", baseUrl = "https://provider.example/v1", key = "fixture-key")
        val state = mutableStateOf(UiState(ready = true, tab = 3, data = Saved(learningMode = "native", providers = listOf(p), selectedProvider = p.id),
            modelListFor = p.id, modelListRequest = modelCatalogKey(p), modelList = listOf("text-model", "vision-model"), modelNames = mapOf("text-model" to "文字讲解", "vision-model" to "视觉讲解")))
        lateinit var vm: StudyViewModel
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.setContent {
            StudyTheme(false) {
                StudyFrame(state.value, onTab = { state.value = state.value.copy(tab = it) }, onBack = {}, onStop = {}) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) { ModelScreen(state.value, vm) }
                }
            }
        }
        compose.onNodeWithText("配置模型与账号").performScrollTo().performClick()
        compose.onNodeWithText("配置连接").performScrollTo().performClick()
        compose.onNodeWithText("模型 ID").assertDoesNotExist()
        compose.onNodeWithText("选择模型").performScrollTo().performClick()
        compose.onNodeWithText("视觉讲解").assertIsDisplayed()
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val resolver = compose.activity.contentResolver
            val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "sample-model-picker.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1) }
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
            requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        } finally { bitmap.recycle() }
        compose.onNodeWithText("搜索模型").performTextInput("vision")
        compose.onNodeWithText("文字讲解").assertDoesNotExist()
        compose.onNodeWithText("视觉讲解").performClick()
        compose.onNodeWithText("保存并使用").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("vision-model", vm.ui.value.data.provider().model) }
        compose.onNodeWithText("API Base URL").performScrollTo().performTextReplacement("https://other.example/v1")
        compose.onNodeWithText("选择模型").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("保存并使用").performScrollTo().assertIsNotEnabled()
    }
}
