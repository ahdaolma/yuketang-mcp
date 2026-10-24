package cn.studybridge.mobile

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderMotionCompletionTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun exercise(style: String) {
        val note = Note(title = "完整完成动画", content = "## 第 1 页\n\n首页原生正文，点击后应逐步露出相邻纸页。\n\n" +
            "第一份课堂重点。\n\n## 第 2 页\n\n第二页正文，返回时必须仍有连续动画。\n\n第二份课堂重点。", source = "合成两页", model = "fixture")
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = "buttons", notePageAnimation = style))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        var progress: ReaderProgress? = null
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) {
                CompositionLocalProvider(LocalReaderProgress provides { progress = it }) {
                    NotebookReader(note, ui, vm, Modifier.fillMaxSize(), onBack = {})
                }
            }
        }
        try {
            compose.waitUntil(8000) { vm.readingMemory.position(note.id)?.index == 0 }
            for (direction in listOf(1, -1)) {
                compose.waitForIdle()
                val before = compose.onNodeWithTag("笔记正文").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)!!
                var sawIntermediate = false
                var inspectedPixels = false
                compose.mainClock.autoAdvance = false
                try {
                    compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * (if (direction > 0) .9f else .1f), height * .8f)) }
                    compose.waitUntil(12000) {
                        compose.mainClock.advanceTimeByFrame()
                        val nodes = compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes()
                        val percent = nodes.firstOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription)
                            ?.let { Regex("(\\d+)%").find(it)?.groupValues?.get(1)?.toIntOrNull() }
                        if (percent != null && percent in 1..99) sawIntermediate = true
                        if (!inspectedPixels && percent != null && percent in 25..65) {
                            val middle = compose.onNodeWithTag("笔记正文").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)!!
                            try {
                                assertEquals(before.width, middle.width); assertEquals(before.height, middle.height)
                                var changed = 0
                                // Both captures include identical floating
                                // tools, so only actual paper motion differs.
                                for (y in 0 until middle.height step 4) for (x in 0 until middle.width step 4)
                                    if (before.getPixel(x, y) != middle.getPixel(x, y)) changed++
                                assertTrue("Completion must animate actual paper pixels rather than just its counter", changed > 50)
                                advancedUiShot(compose.activity, "reader-completion-$style-$direction")
                                inspectedPixels = true
                            } finally { middle.recycle() }
                        }
                        progress?.text == if (direction > 0) "第 2 / 2 页" else "第 1 / 2 页"
                    }
                } finally { compose.mainClock.autoAdvance = true; before.recycle() }
                assertTrue("A tap must show intermediate completion frames in $style/$direction", sawIntermediate)
                assertTrue("Inspect real middle-frame pixels in $style/$direction", inspectedPixels)
                compose.onNodeWithTag("笔记翻页画面").assertDoesNotExist()
            }
        } finally { compose.mainClock.autoAdvance = true; compose.runOnIdle { store.clear() } }
    }

    @Test fun curlCompletionRendersRealIntermediatePaperInBothDirections() = exercise("curl")
    @Test fun coverCompletionRendersRealIntermediatePaperInBothDirections() = exercise("cover")
}
