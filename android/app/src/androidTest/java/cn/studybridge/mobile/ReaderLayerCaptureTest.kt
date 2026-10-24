package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class ReaderLayerCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun fullViewportLayerContainsNativeTextHighlightAndComposeImage() {
        val image = Bitmap.createBitmap(60, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(18, 68, 216)) }
        var captured: ImageBitmap? = null
        var capture: (() -> Unit)? = null
        compose.setContent {
            val layer = rememberGraphicsLayer()
            val scope = rememberCoroutineScope()
            SideEffect { capture = { scope.launch { captured = layer.toImageBitmap() } } }
            Column(Modifier.size(300.dp, 400.dp).testTag("完整阅读快照").drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }.background(androidx.compose.ui.graphics.Color.White)) {
                Text("真实笔记标题", Modifier.padding(8.dp))
                AndroidView(factory = { context -> NoteSelectableTextView(context).apply {
                    textSize = 24f; setTextColor(Color.BLACK); setBackgroundColor(Color.WHITE)
                    text = SpannableString("原生正文高亮\n全宽排版与文字不能消失。\n下一行仍然清楚。").apply {
                        setSpan(BackgroundColorSpan(Color.YELLOW), 0, 6, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                } }, modifier = Modifier.fillMaxWidth().testTag("快照原生文字"))
                Image(image.asImageBitmap(), "真实原图", Modifier.fillMaxWidth().height(100.dp))
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { requireNotNull(capture).invoke() }
        compose.waitUntil(10000) { captured != null }
        val result = requireNotNull(captured).asAndroidBitmap()
        val frame = compose.onNodeWithTag("完整阅读快照").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithTag("快照原生文字").fetchSemanticsNode().boundsInRoot
        assertEquals(frame.width.roundToInt(), result.width)
        assertEquals(frame.height.roundToInt(), result.height)
        var blue = 0; var yellow = 0; var ink = 0
        for (y in 0 until result.height) for (x in 0 until result.width) {
            val color = result.getPixel(x, y)
            if (Color.blue(color) > 180 && Color.red(color) < 50 && Color.green(color) in 40..100) blue++
            if (y >= text.top - frame.top && y < text.bottom - frame.top) {
                if (Color.red(color) > 220 && Color.green(color) > 220 && Color.blue(color) < 40) yellow++
                if (Color.red(color) < 60 && Color.green(color) < 60 && Color.blue(color) < 60) ink++
            }
        }
        assertTrue("Compose source image must appear in the captured paper", blue > 1000)
        assertTrue("Native annotation background must appear in the captured paper", yellow > 100)
        assertTrue("Actual native glyphs must appear rather than a blank placeholder", ink > 100)
        advancedUiShot(compose.activity, "reader-layer-capture-source")
    }
}
