package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderPaperVisualTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val blue = AndroidColor.rgb(18, 68, 216)
    private val green = AndroidColor.rgb(28, 156, 86)
    private fun paper(color: Int, label: String) = Bitmap.createBitmap(600, 800, Bitmap.Config.ARGB_8888).apply {
        eraseColor(color)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = AndroidColor.WHITE; textSize = 40f }
        val canvas = Canvas(this)
        canvas.drawText(label, 45f, 120f, ink)
        ink.color = AndroidColor.YELLOW; canvas.drawRect(35f, 35f, 100f, 70f, ink)
    }
    private fun pixels() = compose.onNodeWithTag("笔记翻页画面").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)!!
    private fun colorAt(bitmap: Bitmap, x: Float, y: Float) = bitmap.getPixel((bitmap.width * x).toInt(), (bitmap.height * y).toInt())

    @Test fun bothPaperStylesRenderActualSourcePixelsInBothDirectionsAndThemes() {
        val first = paper(blue, "FIRST ACTUAL PAGE").asImageBitmap()
        val second = paper(green, "SECOND ACTUAL PAGE").asImageBitmap()
        var animation by mutableStateOf("cover")
        var direction by mutableIntStateOf(1)
        var dark by mutableStateOf(false)
        var progress by mutableFloatStateOf(.35f)
        compose.setContent {
            StudyTheme(dark) {
                ReaderPaperCanvas(ReaderPaperImages(if (direction > 0) first else second, if (direction > 0) second else first),
                    animation, { progress }, direction, true, { 400f }, MaterialTheme.colorScheme.background, Modifier.size(300.dp, 400.dp))
            }
        }
        for (night in listOf(false, true)) for (style in NOTE_PAGE_ANIMATIONS.keys) for (turn in listOf(1, -1)) {
            compose.runOnIdle { dark = night; animation = style; direction = turn; progress = .35f }
            compose.waitForIdle()
            val result = pixels()
            try {
                assertEquals("Upper-left source remains the first real paper for $style/$turn", blue, colorAt(result, .04f, .04f))
                assertEquals("Lower-right reveals the second real paper for $style/$turn", green, colorAt(result, .96f, .96f))
                if (style == "cover") assertEquals("Cover direction changes which real page owns the center", if (turn > 0) blue else green, colorAt(result, .5f, .5f))
                var sourceBlue = 0; var sourceGreen = 0; var annotationYellow = 0
                for (y in 0 until result.height) for (x in 0 until result.width) {
                    when (result.getPixel(x, y)) { blue -> sourceBlue++; green -> sourceGreen++; AndroidColor.YELLOW -> annotationYellow++ }
                }
                assertTrue("First source bitmap is not a blank substitute", sourceBlue > result.width * result.height / 50)
                assertTrue("Second source bitmap is not a blank substitute", sourceGreen > result.width * result.height / 50)
                assertTrue("Actual source annotation remains part of the folded/covered page", annotationYellow > 100)
                advancedUiShot(compose.activity, "reader-paper-$style-${if (turn > 0) "forward" else "backward"}-${if (night) "dark" else "light"}")
            } finally { result.recycle() }
        }
        for (style in NOTE_PAGE_ANIMATIONS.keys) for (turn in listOf(1, -1)) for (end in listOf(0f, 1f)) {
            compose.runOnIdle { animation = style; direction = turn; progress = end }
            val result = pixels()
            try {
                val expected = if ((turn > 0) == (end == 0f)) blue else green
                assertEquals("No blank paper at either endpoint: $style/$turn/$end", expected, colorAt(result, .8f, .8f))
            } finally { result.recycle() }
        }
    }
}
