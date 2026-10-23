package cn.studybridge.mobile

import android.content.ClipboardManager
import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.text.Selection
import android.text.Spannable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real native long-press and Compose popup measurements; no live account. */
class NoteSelectionAnchoringTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val note = Note(title = "选区菜单定位", content = "DNA复制需要模板，方向需要分清。".repeat(12), source = "合成正文", model = "fixture")
    private val topSpace = mutableStateOf(140)
    private lateinit var vm: StudyViewModel
    private lateinit var textView: NoteSelectableTextView

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun prepare(store: ViewModelStore, fontScale: Float = 1f) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note)))
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                StudyTheme(false) { Surface(Modifier.fillMaxSize()) {
                    NoteReadingSelection(ui.data.notes.single(), vm, Modifier.fillMaxSize()) { body ->
                        Column(body.testTag("选区测试正文").verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                            Spacer(Modifier.height(topSpace.value.dp))
                            Markdown(note.content, sourceOffset = 0)
                            Spacer(Modifier.height(900.dp))
                        }
                    }
                } }
            }
        }
        compose.waitUntil(5000) {
            var ready = false
            compose.runOnIdle {
                val view = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>().firstOrNull { it.alpha >= .99f && it.layout != null && it.text.isNotEmpty() }
                if (view != null) { textView = view; ready = true }
            }
            ready
        }
    }

    private fun longPress() {
        var x = 0f; var y = 0f
        compose.runOnIdle {
            val location = IntArray(2); textView.getLocationOnScreen(location)
            x = location[0] + textView.totalPaddingLeft + textView.layout.getPrimaryHorizontal(1) + 1
            y = location[1] + textView.totalPaddingTop + textView.layout.getLineBottom(0) / 2f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(700)
        instrumentation.sendPointerSync(MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("正文选词工具条").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun toolbarOnScreen(): RectF {
        val node = compose.onNodeWithTag("正文选词工具条").fetchSemanticsNode()
        var result = RectF()
        compose.runOnIdle {
            val root = (node.root as ViewRootForTest).view
            val screen = IntArray(2); root.getLocationOnScreen(screen)
            val window = IntArray(2); root.getLocationInWindow(window)
            result = RectF(node.boundsInWindow.left, node.boundsInWindow.top, node.boundsInWindow.right, node.boundsInWindow.bottom)
            result.offset((screen[0] - window[0]).toFloat(), (screen[1] - window[1]).toFloat())
        }
        return result
    }

    private fun assertAnchored(below: Boolean = false): RectF {
        var menu = RectF()
        compose.waitUntil(5000) {
            menu = toolbarOnScreen()
            var follows = false
            compose.runOnIdle {
                val geometry = textView.controller?.selection?.geometry
                if (geometry != null) {
                    val screen = IntArray(2); textView.getLocationOnScreen(screen)
                    val window = IntArray(2); textView.getLocationInWindow(window)
                    val dx = screen[0] - window[0]; val dy = screen[1] - window[1]
                    val selected = geometry.boundsInWindow
                    val safe = geometry.safeWindowBounds
                    val gap = 8 * textView.resources.displayMetrics.density
                    val distance = if (below) menu.top - (selected.bottom + dy) else selected.top + dy - menu.bottom
                    follows = kotlin.math.abs(distance - gap) < 3 && menu.left >= safe.left + dx && menu.right <= safe.right + dx + 1 && menu.top >= safe.top + dy && menu.bottom <= safe.bottom + dy + 1
                }
            }
            follows
        }
        return menu
    }

    @Test fun nativeLongPressMenuFollowsMovingTextAndClosingClearsNativeSelection() {
        val store = ViewModelStore()
        try {
            prepare(store)
            longPress()
            val initial = assertAnchored()
            compose.runOnIdle { topSpace.value = 260 }
            val moved = assertAnchored()
            assertTrue("Moving selected text must also move its menu", moved.top > initial.top + 60 * compose.activity.resources.displayMetrics.density)
            compose.onNodeWithTag("选区测试正文").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 80 * compose.activity.resources.displayMetrics.density) }
            val scrolled = assertAnchored()
            assertTrue("Parent scrolling must move the menu with the selected line", scrolled.top < moved.top - 40 * compose.activity.resources.displayMetrics.density)
            compose.runOnIdle { Selection.setSelection(textView.text as Spannable, 0, 8) }
            compose.waitUntil(5000) { var quote = ""; compose.runOnIdle { quote = textView.controller?.selection?.quote.orEmpty() }; quote == note.content.take(8) }
            assertAnchored()
            advancedUiShot(compose.activity, "sample-selection-menu-near-text")
            compose.onNodeWithText("关闭").performScrollTo().performClick()
            compose.onNodeWithTag("正文选词工具条").assertDoesNotExist()
            compose.runOnIdle {
                assertNull(textView.controller?.selection)
                assertTrue("Dismissal must also remove the native selected range", textView.selectionStart == textView.selectionEnd)
                textView.invalidate()
            }
            compose.waitForIdle()
            compose.onNodeWithTag("正文选词工具条").assertDoesNotExist()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun topEdgeLargeFontSelectionOpensBelowAndCopyAndMarksStillWork() {
        val store = ViewModelStore()
        try {
            topSpace.value = 8
            prepare(store, fontScale = 2f)
            longPress()
            assertAnchored(below = true)
            advancedUiShot(compose.activity, "sample-selection-menu-top-large-font")
            compose.runOnIdle { Selection.setSelection(textView.text as Spannable, 0, 8) }
            compose.waitUntil(5000) { var end = 0; compose.runOnIdle { end = textView.controller?.selection?.end ?: 0 }; end == 8 }
            compose.onNodeWithText("复制").performScrollTo().performClick()
            compose.runOnIdle {
                assertEquals(note.content.take(8), (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text.toString())
            }
            longPress()
            compose.onNodeWithText("重点高亮").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single().annotations.any { it.kind == "highlight" } }
            longPress()
            compose.onNodeWithText("下划线").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single().annotations.any { it.kind == "underline" } }
            longPress()
            compose.onNodeWithText("取消标记").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single().annotations.isEmpty() }
            assertEquals(note.content, vm.ui.value.data.notes.single().content)
        } finally { compose.runOnIdle { store.clear() } }
    }
}
