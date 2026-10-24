package cn.studybridge.mobile

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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

    @Test fun aHeldDragShowsItsRealNeighborAndCancelRestoresExactlyOnce() {
        val note = Note(title = "拖动相邻页", content = "## 第 1 页\n\n首页正文标记。\n\n## 第 2 页\n\n相邻页正文标记。", source = "两页合成笔记", model = "fixture")
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = "swipe"))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        val selection = NoteSelectionController()
        var progress: ReaderProgress? = null
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) {
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
            val bounds = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow
            compose.waitUntil(5000) { readViews { views -> views.any { view ->
                val visible = Rect()
                val location = IntArray(2); view.getLocationInWindow(location)
                view.text.contains("相邻页正文标记") && view.alpha >= .99f && view.getGlobalVisibleRect(visible) &&
                    location[0] > bounds.left && location[0] < bounds.right
            } } }
            assertTrue("Outgoing native text follows the held gesture", pageX("首页正文标记") < origin - 10)
            compose.runOnIdle {
                assertEquals("A preview does not commit its source page", "第 1 / 2 页", progress?.text)
                assertEquals("A preview does not save the incoming source page", 0, vm.readingMemory.position(note.id)?.index)
            }
            compose.onNodeWithTag("笔记正文").performTouchInput { cancel() }
            compose.waitForIdle()
            assertEquals("Cancellation returns to the original native position", origin, pageX("首页正文标记"))
            assertFalse("Canceled incoming content is discarded", readViews { views -> views.any { it.isAttachedToWindow && it.text.contains("相邻页正文标记") } })
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 180) }
            compose.waitUntil(5000) { readViews { views -> views.none { it.isAttachedToWindow && it.text.contains("首页正文标记") } } }
            assertEquals("Reader remains usable after cancellation", origin, pageX("相邻页正文标记"))
        } finally { compose.runOnIdle { store.clear() } }
    }
}
