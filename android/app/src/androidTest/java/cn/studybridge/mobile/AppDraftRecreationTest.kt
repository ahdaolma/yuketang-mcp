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
        catch (error: Throwable) {
            runCatching { advancedUiShot(compose.activity, "draft-fixture-failure-${newId().take(8)}") }
            runCatching { android.util.Log.e("DraftFailure", compose.onRoot(useUnmergedTree = true).printToString()) }
            throw error
        }
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
            compose.waitUntil(5000) { compose.onAllNodesWithText("模型 ID").fetchSemanticsNodes().size == 1 }
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
            assertViewerStatusBarHidden()
            compose.onNodeWithText("画笔").performClick()
            repeat(2) { index ->
                compose.onNodeWithTag("image-markup-canvas").performTouchInput { swipe(center + Offset(-30f, index * 18f), center + Offset(30f, index * 18f), 300) }
            }
            assertEquals(listOf(original), images.markupStore.load(id))
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            try {
                compose.waitUntil(5000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
                canvasReady()
                assertViewerStatusBarHidden()
                compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "3 笔批注，缩放 1.0"))
                assertEquals(listOf(original), images.markupStore.load(id))
                compose.onNodeWithText("撤销").performScrollTo().performClick()
                compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "2 笔批注，缩放 1.0"))
                settledImageShot()
                assertViewerStatusBarHidden()
                compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "2 笔批注，缩放 1.0"))
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

    private fun settledImageShot() {
        var previous: android.graphics.Bitmap? = null
        var identicalFrames = 0
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            repeat(80) { sample ->
                compose.waitForIdle()
                val bitmap = requireNotNull(automation.takeScreenshot())
                if (sample == 0) saveUiReviewBitmap(compose.activity, "image-rotation-initial", bitmap)
                identicalFrames = if (bitmap.width > bitmap.height && previous?.sameAs(bitmap) == true) identicalFrames + 1 else 0
                android.util.Log.i("SettledImage", "sample=$sample size=${bitmap.width}x${bitmap.height} identical=$identicalFrames")
                previous?.recycle(); previous = bitmap
                if (identicalFrames >= 3) {
                    saveUiReviewBitmap(compose.activity, "sample-image-draft-rotated", bitmap)
                    return
                }
                android.os.SystemClock.sleep(80)
            }
            error("Landscape pixels did not settle after rotating the actual image window")
        } finally { previous?.recycle() }
    }

    private fun canvasReady() {
        compose.waitUntil(10000) { compose.onAllNodesWithText("画笔").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.onNodeWithTag("image-markup-canvas").assertIsDisplayed()
    }

    @Suppress("DEPRECATION")
    private fun assertViewerStatusBarHidden() {
        var visible: Boolean? = null
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .check { view, error ->
                if (error != null) throw error
                val insets = androidx.core.view.ViewCompat.getRootWindowInsets(view)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                val flags = (view.layoutParams as? android.view.WindowManager.LayoutParams)?.flags ?: 0
                val legacyHidden = (view.systemUiVisibility and android.view.View.SYSTEM_UI_FLAG_FULLSCREEN) != 0 ||
                    (flags and android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
                // On API26 a floating Dialog's frame excludes the status-bar
                // area; compat insets can report zero even while the bar shows.
                visible = if (android.os.Build.VERSION.SDK_INT < 30) !legacyHidden else insets
                android.util.Log.i("ViewerChrome", "sdk=${android.os.Build.VERSION.SDK_INT} insets=$insets flags=$flags ui=${view.systemUiVisibility} policyHidden=$legacyHidden")
            }
        assertEquals("The actual image-editor window must keep the system status bar hidden, including after rotation", false, visible)
    }
}
