package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderMotionPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun <T> readViews(block: (List<NoteSelectableTextView>) -> T): T {
        var result: T? = null
        compose.runOnIdle { result = block(descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>()) }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }
    private fun pageX(marker: String) = readViews { views ->
        val view = views.first { it.isAttachedToWindow && it.text.contains(marker) }
        val location = IntArray(2); view.getLocationInWindow(location); location[0]
    }

    private fun colorImage(color: Int): String {
        val bitmap = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle()
        return "data:image/png;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
    }
    private fun assertActualPaperColors(label: String) {
        val bitmap = compose.onNodeWithTag("笔记翻页画面").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)!!
        try {
            var blue = 0; var green = 0
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                if (Color.blue(color) > 180 && Color.red(color) < 50 && Color.green(color) in 40..100) blue++
                if (Color.green(color) > 130 && Color.red(color) < 60 && Color.blue(color) in 55..110) green++
            }
            android.util.Log.i("ReaderPaperPixels", "$label blue=$blue green=$green size=${bitmap.width}x${bitmap.height}")
            advancedUiShot(compose.activity, label)
            assertTrue("Current real source image must be drawn on the moving paper: blue=$blue green=$green", blue > 1000)
            assertTrue("Neighbor real source image must be revealed, not an empty placeholder: blue=$blue green=$green", green > 1000)
        } finally { bitmap.recycle() }
    }

    private fun exercisePaper(style: String, dark: Boolean) {
        val app = noteTestApplication(compose.activity.applicationContext)
        val images = SourceImages(app)
        val first = images.save(colorImage(Color.rgb(18, 68, 216)))
        val second = images.save(colorImage(Color.rgb(28, 156, 86)))
        val note = Note(title = "拖动相邻页", content = "## 第 1 页\n\n首页正文标记。\n\n" + sourceImageMarkdown(first, "首页原图") +
            "\n\n## 第 2 页\n\n相邻页正文标记。\n\n" + sourceImageMarkdown(second, "邻页原图"), source = "两页合成笔记", model = "fixture")
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = "swipe", notePageAnimation = style))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        val selection = NoteSelectionController()
        var progress: ReaderProgress? = null
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(dark) {
                CompositionLocalProvider(LocalNoteSelection provides selection, LocalReaderProgress provides { progress = it }) {
                    NotebookReader(note, ui, vm, Modifier.fillMaxSize(), onBack = {})
                }
            }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready && vm.ui.value.data.noteReadMode == "swipe" }
            compose.waitUntil(5000) { readViews { views -> views.any { it.text.contains("首页正文标记") && it.alpha >= .99f && it.layout != null } } }
            compose.waitUntil(5000) { vm.readingMemory.position(note.id)?.index == 0 }
            compose.waitForIdle()
            val origin = pageX("首页正文标记")
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(width * .8f, height * .5f))
                moveTo(Offset(width * .2f, height * .5f), 160)
            }
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            assertActualPaperColors("reader-motion-$style-forward-${if (dark) "dark" else "light"}")
            assertEquals("Native text remains full-width behind the canvas rather than reflowing into a strip", origin, pageX("首页正文标记"))
            compose.runOnIdle {
                assertEquals("A preview does not commit its source page", "第 1 / 2 页", progress?.text)
                assertEquals("A preview does not save the incoming source page", 0, vm.readingMemory.position(note.id)?.index)
            }
            compose.onNodeWithTag("笔记正文").performTouchInput { cancel() }
            compose.waitForIdle()
            assertEquals("Cancellation returns to the original native position", origin, pageX("首页正文标记"))
            compose.onNodeWithTag("笔记翻页画面").assertDoesNotExist()
            assertFalse("Canceled incoming content is discarded", readViews { views -> views.any { it.isAttachedToWindow && it.text.contains("相邻页正文标记") } })
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 180) }
            compose.waitUntil(5000) { readViews { views -> views.none { it.isAttachedToWindow && it.text.contains("首页正文标记") } } }
            assertEquals("Reader remains usable after cancellation", origin, pageX("相邻页正文标记"))
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(width * .2f, height * .3f)); moveTo(Offset(width * .8f, height * .3f), 160)
            }
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            assertActualPaperColors("reader-motion-$style-backward-${if (dark) "dark" else "light"}")
            compose.onNodeWithTag("笔记正文").performTouchInput { up() }
            compose.waitUntil(5000) { readViews { views -> views.none { it.isAttachedToWindow && it.text.contains("相邻页正文标记") } } }
            repeat(8) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); assertEquals("Backward completion cannot snap back to page 2", origin, pageX("首页正文标记")) }
            compose.onNodeWithContentDescription("首页原图 缩略图").performClick()
            compose.onNodeWithText("关闭预览").performClick()
            compose.onNodeWithTag("笔记翻页画面").assertDoesNotExist()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun curlLightShowsRealNeighborAndCancelThenBackwardRemainUsable() = exercisePaper("curl", false)
    @Test fun curlDarkShowsRealNeighborAndCancelThenBackwardRemainUsable() = exercisePaper("curl", true)
    @Test fun coverLightShowsRealNeighborAndCancelThenBackwardRemainUsable() = exercisePaper("cover", false)
    @Test fun coverDarkShowsRealNeighborAndCancelThenBackwardRemainUsable() = exercisePaper("cover", true)
}
