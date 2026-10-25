package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

class ReaderPreviewSizeTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun exercise(width: Int, height: Int, missingHint: Boolean = false) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(220, 233, 239))
        val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        bitmap.recycle()
        val image = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val app = noteTestApplication(compose.activity.applicationContext)
        val images = SourceImages(app); val id = images.save(image)
        assertEquals(width.toFloat() / height, requireNotNull(images.aspectRatio(id)), .001f)
        val gate = CompletableDeferred<Unit>()
        val attempts = AtomicInteger()
        val key = "stable-preview-${newId()}"
        compose.setContent {
            StudyTheme(false) {
                Column(Modifier.width(300.dp)) {
                    PagePreview(key, "稳定课件", remember { MaterialPreviews(app) }, sizeHint = { if (missingHint) null else images.aspectRatio(id) }) {
                        val attempt = attempts.incrementAndGet()
                        gate.await()
                        if (attempt == 1) error("合成加载失败")
                        Attachment(image, "稳定课件", "")
                    }
                    Text("AFTER_PREVIEW")
                }
            }
        }
        fun after() = compose.onNodeWithText("AFTER_PREVIEW").fetchSemanticsNode().boundsInWindow.top
        compose.onNodeWithText("AFTER_PREVIEW").assertExists()
        val initial = after()
        gate.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithText("重试预览").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("An error must not change the reserved image height", initial, after(), 1f)
        assertTrue("Even a very wide image must leave a usable retry target", compose.onNodeWithText("重试预览").fetchSemanticsNode().boundsInWindow.height >= compose.activity.resources.displayMetrics.density * 48 - 1)
        compose.onNodeWithText("重试预览").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("稳定课件 缩略图").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Pixel decode must not move the following content", initial, after(), 1f)
        compose.onNodeWithContentDescription("稳定课件 缩略图").performClick()
        compose.onNodeWithText("关闭预览").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("稳定课件 缩略图").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Returning from the original / annotation viewer must keep content placement", initial, after(), 1f)
        advancedUiShot(compose.activity, "reader-preview-$width-$height-$missingHint")
    }
    @Test fun landscapePreviewKeepsSizeThroughLoadingFailureRetryAndViewer() = exercise(800, 500)
    @Test fun portraitPreviewKeepsSizeThroughLoadingFailureRetryAndViewer() = exercise(400, 700)
    @Test fun veryWidePreviewRetainsUsableRetryAndStableSize() = exercise(1200, 60)
    @Test fun missingMetadataKeepsStableFallbackUntilRetry() = exercise(800, 500, true)
}
