package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream

fun syntheticSlideImage(): String {
    val bitmap = Bitmap.createBitmap(800, 500, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.drawColor(Color.rgb(239, 246, 251))
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 76, 105); textSize = 42f }
    canvas.drawText("DNA / RNA", 55f, 100f, paint)
    paint.textSize = 28f; canvas.drawText("Synthetic slide for preview testing", 55f, 160f, paint)
    paint.color = Color.rgb(212, 128, 153); canvas.drawCircle(225f, 310f, 80f, paint)
    paint.color = Color.rgb(103, 161, 178); canvas.drawRect(405f, 235f, 660f, 385f, paint)
    val out = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle()
    return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}

class PreviewNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun shot(name: String, dialog: Boolean = false) {
        val bitmap = (if (dialog) compose.onNode(isDialog()) else compose.onRoot()).captureToImage().asAndroidBitmap()
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
    @Test fun imageIsDurableOfflineAndExportContainsActualImageBytes() {
        val images = SourceImages(compose.activity); val image = syntheticSlideImage()
        val id = images.save(image)
        assertEquals(id, images.save(image))
        assertArrayEquals(Base64.decode(image.substringAfter(','), Base64.DEFAULT), SourceImages(compose.activity).bytes(id))
        val markdown = "# 笔记\n\n" + sourceImageMarkdown(id, "课件第 3 页") + "解释文字"
        val output = ByteArrayOutputStream(); images.writeExport(markdown, output)
        val exported = output.toString("UTF-8")
        assertTrue(exported.contains(image)); assertFalse(exported.contains("study-image:")); assertTrue(exported.endsWith("解释文字"))
        assertTrue(runCatching { images.bytes("../outside") }.isFailure)
        assertTrue(runCatching { images.save("data:image/png;base64,broken") }.isFailure)
    }
    @Test fun thumbnailCanRetryEnlargeAndThenLoadFromCacheWithoutSource() {
        val repository = MaterialPreviews(compose.activity)
        val calls = AtomicInteger(); val image = syntheticSlideImage(); val cacheKey = "test:" + newId()
        compose.setContent { StudyTheme(false) { Column { PagePreview(cacheKey, "测试课件第 3 页", repository) {
            if (calls.incrementAndGet() == 1) error("synthetic unavailable")
            Attachment(image, "测试课件", "")
        } } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("重试预览").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重试预览").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("测试课件第 3 页 缩略图").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("测试课件第 3 页 缩略图").assertIsDisplayed()
        shot("sample-slide-preview")
        compose.onNodeWithText("放大预览").assertDoesNotExist()
        compose.onNodeWithContentDescription("测试课件第 3 页 缩略图").performClick()
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithText("关闭预览").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("测试课件第 3 页").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            val trees = compose.onAllNodes(isRoot()).printToString(maxDepth = 12)
            val bitmap = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            bitmap?.let {
                val resolver = compose.activity.contentResolver
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "preview-enlarge-failure.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
                requireNotNull(resolver.openOutputStream(uri)).use { out -> check(it.compress(Bitmap.CompressFormat.PNG, 100, out)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            }
            throw AssertionError("Full preview did not become visible. Source calls=${calls.get()}; semantics=$trees", error)
        }
        compose.onNodeWithContentDescription("测试课件第 3 页").assertIsDisplayed()
        compose.onNodeWithText("重置缩放").performClick()
        compose.onNodeWithText("关闭预览").performClick()
        runBlocking { assertNotNull(repository.load(cacheKey) { error("must use cached thumbnail") }.bitmap) }
        assertEquals(3, calls.get())
    }
    @Test fun fullPreviewCanRetryAfterSourceFailure() {
        val calls = AtomicInteger(); val image = syntheticSlideImage()
        var open by mutableStateOf(true)
        compose.setContent { StudyTheme(false) { if (open) ImageViewer("原页重试测试", { open = false }) {
            if (calls.incrementAndGet() == 1) error("synthetic unavailable")
            Attachment(image, "原页重试测试", "")
        } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("重新读取").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("重新读取").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("原页重试测试").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("原页重试测试").assertIsDisplayed()
        shot("sample-slide-full-preview", dialog = true)
        compose.onNodeWithText("关闭预览").performClick()
        compose.onNodeWithContentDescription("原页重试测试").assertDoesNotExist()
        assertEquals(2, calls.get())
    }
    @Test fun noteDirectoryJumpsToPageWithInlineImageAndPersistentControls() {
        val images = SourceImages(compose.activity); val id = images.save(syntheticSlideImage())
        val content = "# 图文笔记\n\n## 第 1 页\n\n" + sourceImageMarkdown(id, "第 1 页原图") + "### 讲解\n\n" + "前页讲解。\n\n".repeat(300) + "## 第 8 页\n\n" + sourceImageMarkdown(id, "第 8 页原图") + "末页讲解"
        val note = Note(title = "图文笔记", content = content, source = "合成课件", model = "测试")
        compose.setContent { StudyTheme(false) { PagedNoteContent(note, Modifier.fillMaxSize(), independentScroll = true) } }
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("目录跳转").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("目录跳转").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("第 8 页")
        val destination = compose.onNodeWithText("2. 第 8 页")
        destination.performScrollTo()
        // The platform IME can still resize the sheet after Compose is idle.
        // Tap the physical row only once its window position has settled.
        var previous = destination.getUnclippedBoundsInRoot()
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(5000) {
            val bounds = destination.getUnclippedBoundsInRoot()
            val now = android.os.SystemClock.uptimeMillis()
            if (bounds != previous) { previous = bounds; stableSince = now }
            now - stableSince >= 200
        }
        destination.assertIsDisplayed().performClick()
        try {
            compose.waitUntil(5000) { compose.onAllNodesWithText("取消跳转").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("第 8 页原图 缩略图").fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            advancedUiShot(compose.activity, "preview-directory-failure")
            throw AssertionError("Directory jump failed: ${compose.onAllNodes(isRoot()).printToString(maxDepth = 14)}", error)
        }
        compose.onNodeWithContentDescription("第 8 页原图 缩略图").assertIsDisplayed()
        compose.onNodeWithContentDescription("阅读跳转").performClick()
        compose.onNodeWithText("本段底部").performClick()
        compose.onNodeWithContentDescription("目录跳转").assertIsDisplayed()
        compose.onNodeWithContentDescription("阅读跳转").performClick()
        compose.onNodeWithText("本段顶部").performClick()
        shot("sample-note-with-slide")
        assertEquals(content, note.content)
    }
}
