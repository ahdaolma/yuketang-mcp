package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ImageMarkupWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun pictureFills320dpWidthAndRetainsItsActualAspectRatio() {
        val app = noteTestApplication(compose.activity)
        val image = syntheticSlideImage()
        compose.setContent { StudyTheme(false) { Box(Modifier.width(320.dp)) { PagePreview(remember { "aspect-${newId()}" }, "完整课件", remember { MaterialPreviews(app) }) { Attachment(image, "完整课件", "") } } } }
        compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("完整课件 缩略图").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("完整课件 缩略图").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(200.dp).assertHasClickAction()
        compose.onNodeWithText("放大预览").assertDoesNotExist()
        advancedUiShot(compose.activity, "sample-note-image-full-width")
    }

    @Test fun pointerMarksSaveReopenExportAndLeaveOriginalBytesUnchanged() {
        val app = noteTestApplication(compose.activity); val images = SourceImages(app)
        val original = syntheticSlideImage(); val id = images.save(original)
        val originalBytes = images.bytes(id)
        var open by mutableStateOf(true)
        compose.setContent { StudyTheme(false) { if (open) ImageViewer("可画的课件", { open = false }, images.markupStore) { Attachment(images.data(id), "可画的课件", "") } } }
        waitForCanvas()
        compose.onNodeWithText("画笔").performClick()
        compose.onNodeWithTag("image-markup-canvas").performTouchInput { swipe(center - Offset(45f, 0f), center + Offset(45f, 15f), 500) }
        compose.onNodeWithText("保存批注").assertIsEnabled()
        compose.onNodeWithText("撤销").performScrollTo().performClick()
        compose.onNodeWithText("保存批注").assertIsNotEnabled()
        compose.onNodeWithText("重做").performScrollTo().performClick()
        // Pinching while a pen is selected must cancel its tentative stroke.
        compose.onNodeWithTag("image-markup-canvas").performTouchInput { pinch(center - Offset(30f, 0f), center + Offset(30f, 0f), center - Offset(70f, 0f), center + Offset(70f, 0f), 500) }
        compose.onNodeWithText("保存批注").performClick()
        compose.waitUntil(5000) { images.markupStore.load(id).isNotEmpty() }
        assertEquals(1, images.markupStore.load(id).size)
        val beforeZoomStroke = images.markupStore.load(id).single()
        // Drawing after zoom uses the inverse transform, not the viewport bounds.
        compose.onNodeWithTag("image-markup-canvas").performTouchInput { swipe(center, center + Offset(25f, 0f), 300) }
        compose.onNodeWithText("保存批注").performClick()
        compose.waitUntil(5000) { images.markupStore.load(id).size == 2 }
        val marks = ImageMarkupStore(app).load(id)
        assertEquals(beforeZoomStroke, marks.first())
        assertEquals(.5f, marks.last().points.first().x, .025f)
        assertEquals(.5f, marks.last().points.first().y, .025f)
        assertTrue(marks.last().points.last().x - marks.last().points.first().x < .09f)
        advancedUiShot(compose.activity, "sample-image-markup-saved")
        compose.onNodeWithText("关闭预览").performClick()
        compose.runOnIdle { open = true }
        waitForCanvas()
        compose.onNodeWithTag("image-markup-canvas").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "2 笔批注，缩放 1.0"))
        assertArrayEquals(originalBytes, SourceImages(app).bytes(id))
        assertEquals(original, SourceImages(app).data(id))
        assertNotEquals(original, SourceImages(app).exportData(id))
        assertTrue(images.export(sourceImageMarkdown(id, "课件")).contains("data:image/png;base64,"))
        val backup = requireNotNull(images.markupStore.exportRaw(id))
        ImageMarkupStore(app).importRaw(id, backup)
        assertEquals(marks, ImageMarkupStore(app).load(id))
        compose.onNodeWithText("橡皮").performScrollTo().performClick()
        compose.onNodeWithTag("image-markup-canvas").performTouchInput { click(center) }
        compose.onNodeWithText("保存批注").assertIsEnabled()
        compose.onNodeWithText("撤销").performScrollTo().performClick()
        compose.onNodeWithText("保存批注").assertIsNotEnabled()
    }

    @Test fun unsavedExitRequiresChoiceAndCorruptMarksAreNotOverwritten() {
        val app = noteTestApplication(compose.activity); val images = SourceImages(app)
        val id = images.save(syntheticSlideImage()); var open by mutableStateOf(true)
        compose.setContent { StudyTheme(false) { if (open) ImageViewer("离开保护", { open = false }, images.markupStore) { Attachment(images.data(id), "离开保护", "") } } }
        waitForCanvas(); compose.onNodeWithText("荧光笔").performClick()
        compose.onNodeWithTag("image-markup-canvas").performTouchInput { swipe(center, center + Offset(35f, 0f)) }
        compose.onNodeWithText("关闭预览").performClick()
        compose.onNodeWithText("保存图片批注？").assertIsDisplayed()
        compose.onNodeWithText("继续编辑").performClick()
        compose.onNodeWithText("关闭预览").performClick()
        compose.onNodeWithText("放弃更改").performClick()
        assertTrue(images.markupStore.load(id).isEmpty())
        compose.runOnIdle { open = true }; waitForCanvas()
        compose.onNodeWithText("画笔").performClick()
        compose.onNodeWithTag("image-markup-canvas").performTouchInput { swipe(center, center + Offset(35f, 0f)) }
        compose.onNodeWithText("关闭预览").performClick()
        compose.onNodeWithText("保存并关闭").performClick()
        compose.waitUntil(5000) { images.markupStore.load(id).isNotEmpty() }
        assertEquals(1, images.markupStore.load(id).size)
        val raw = requireNotNull(images.markupStore.exportRaw(id))
        assertTrue(runCatching { images.markupStore.importRaw(id, "broken".toByteArray()) }.isFailure)
        assertArrayEquals(raw, images.markupStore.exportRaw(id))
        // A malformed disk record must block editing rather than turn it into an
        // empty document that a later Save could overwrite.
        compose.waitUntil(5000) { compose.onAllNodesWithTag("image-markup-canvas").fetchSemanticsNodes().isEmpty() }
        java.io.File(app.noBackupFilesDir, "note-image-marks/$id.json").writeText("broken")
        compose.runOnIdle { open = true }
        compose.waitUntil(5000) { compose.onAllNodesWithText("重试批注").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("保存批注").assertIsNotEnabled()
        compose.onNodeWithText("画笔").assertIsNotEnabled()
    }

    private fun waitForCanvas() {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("image-markup-canvas").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("画笔").fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        compose.waitForIdle()
    }
}
