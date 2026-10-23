package cn.studybridge.mobile

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The real Activity restores its composition; no test-side content reinstallation. */
class AppDraftRecreationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun fixture(saved: Saved, block: (StudyViewModel, android.app.Application) -> Unit) {
        val owner = compose.activity.application as StudyApplication
        val previous = owner.studyViewModel
        val app = noteTestApplication(owner)
        Vault(app).save(saved)
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store); owner.installClassFixture(vm) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.activityRule.scenario.recreate()
        try { block(vm, app) }
        finally {
            compose.runOnUiThread { owner.installClassFixture(previous); store.clear() }
        }
    }

    @Test fun newModelDraftSurvivesTabChangesAndRecreationUntilExplicitSave() = modelDraft(true)
    @Test fun existingModelDraftSurvivesTabChangesAndRecreationUntilExplicitSave() = modelDraft(false)

    private fun modelDraft(adding: Boolean) {
        val original = Provider("draft-provider", "原始配置", "https://original.example/v1", "original-key", "original-model", true)
        fixture(Saved(providers = listOf(original), selectedProvider = original.id)) { vm, app ->
            compose.runOnIdle { vm.tab(3); vm.settingsPage("models") }
            if (adding) compose.onNodeWithText("添加模型配置（API 接入）").performScrollTo().performClick()
            else compose.onNodeWithText("配置连接").performScrollTo().performClick()
            compose.onNodeWithText("配置名称").performScrollTo().performTextReplacement("待保存配置")
            compose.onNodeWithText("API Base URL").performScrollTo().performTextReplacement("https://draft.example/v1")
            compose.onNodeWithText("API Key").performScrollTo().performTextReplacement("draft-only-key")
            compose.onNodeWithText("手动填写模型 ID").performScrollTo().performClick()
            compose.onNodeWithText("模型 ID").performScrollTo().performTextReplacement("draft-model")
            compose.onNodeWithContentDescription("返回设置").performClick()
            compose.onNodeWithContentDescription("返回学习").performClick()
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("模型与账号").performClick()
            compose.activityRule.scenario.recreate()
            // All four inputs, including the masked credential, must remain.
            for (entry in listOf("配置名称" to "待保存配置", "API Base URL" to "https://draft.example/v1", "API Key" to "draft-only-key", "模型 ID" to "draft-model")) {
                compose.onNodeWithText(entry.first).performScrollTo()
                if (entry.first == "API Key") compose.onNodeWithContentDescription("切换密钥显示").performClick()
                compose.onNodeWithText(entry.first).assertChatDraftEquals(entry.second)
                if (entry.first == "API Key") compose.onNodeWithContentDescription("切换密钥显示").performClick()
            }
            assertEquals(original, vm.ui.value.data.provider())
            assertEquals(original, Vault(app).load().provider())
            advancedUiShot(compose.activity, "sample-model-draft-restored")
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(5000) { Vault(app).load().provider().model == "draft-model" }
            val saved = Vault(app).load().provider()
            assertEquals("待保存配置", saved.name); assertEquals("https://draft.example/v1", saved.baseUrl)
            assertEquals("draft-only-key", saved.key)
            assertEquals(if (adding) 2 else 1, vm.ui.value.data.providers.size)
        }
    }

    @Test fun openImageAndUnsavedHistorySurviveRotationWithoutOverwritingSavedMarks() {
        fixture(Saved()) { vm, app ->
            val images = SourceImages(app)
            val id = images.save(syntheticSlideImage())
            val original = ImageStroke(listOf(MarkPoint(.2f, .2f), MarkPoint(.3f, .2f)))
            images.markupStore.save(id, listOf(original))
            val note = Note(title = "旋转批注", content = sourceImageMarkdown(id, "旋转课件"), source = "手写", model = "手写")
            compose.runOnIdle { vm.updateNotebookState { it.copy(notes = listOf(note)) }; vm.noteReader.open(note); vm.tab(2) }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("旋转课件 缩略图").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("旋转课件 缩略图").performClick()
            canvasReady()
            compose.onNodeWithText("画笔").performClick()
            repeat(2) { index ->
                compose.onNodeWithTag("image-markup-canvas").performTouchInput { swipe(center + Offset(-30f, index * 18f), center + Offset(30f, index * 18f), 300) }
            }
            assertEquals(listOf(original), images.markupStore.load(id))
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            try {
                compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                canvasReady()
                compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "3 笔批注，缩放 1.0"))
                assertEquals(listOf(original), images.markupStore.load(id))
                compose.onNodeWithText("撤销").performScrollTo().performClick()
                compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "2 笔批注，缩放 1.0"))
                advancedUiShot(compose.activity, "sample-image-draft-rotated")
                compose.onNodeWithText("保存批注").performClick()
                compose.waitUntil(5000) { images.markupStore.load(id).size == 2 }
                assertEquals(original, images.markupStore.load(id).first())
                compose.onNodeWithText("关闭预览").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("旋转课件 缩略图").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("旋转课件 缩略图").performClick()
                canvasReady()
                compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "2 笔批注，缩放 1.0"))
            } finally { compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED } }
        }
    }

    private fun canvasReady() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("画笔").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.onNodeWithTag("image-markup-canvas").assertIsDisplayed()
    }
}
