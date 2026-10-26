package cn.studybridge.mobile

import android.text.SpannableStringBuilder
import android.text.TextPaint
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelStore
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.noties.markwon.ext.tables.TableRowSpan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ReaderMarkdownLeaseTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun continuousTableTextKeepsContrastAcrossLightAndDarkThemes() = tablePalette("continuous")
    @Test fun pagedTableTextKeepsContrastAcrossLightAndDarkThemes() = tablePalette("buttons")

    private fun tablePalette(mode: String) {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note(id = "table-palette", title = "表格对比度", source = "fixture", model = "fixture", content = "## 第 1 页\n\n| 概念 | 解释 |\n| --- | --- |\n| 表格正文 | 深色与浅色均可阅读 |\n\nTABLE_END")
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = mode))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        var dark by mutableStateOf(true)
        lateinit var palette: StudyMarkdownPalette
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(dark) {
                val p = studyMarkdownPalette(); SideEffect { palette = p }
                StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { NotesScreen(ui, vm) }
                }
            }
        }
        fun descendants(view: android.view.View): List<android.view.View> = listOf(view) + if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
        fun tables() = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>().filter { it.isAttachedToWindow && it.alpha >= .99f && it.layout != null && it.text.contains("TABLE_END") }
        var expectedHeights: List<Int>? = null
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithText(note.title).performClick()
            for (theme in listOf(true, false, true)) {
                compose.runOnIdle { dark = theme }
                compose.waitUntil(8000) {
                    var ready = false
                    compose.runOnIdle { ready = tables().any { view -> view.currentTextColor == palette.color && (view.text as? android.text.Spanned)?.getSpans(0, view.text.length, TableRowSpan::class.java)?.size == 2 } }
                    ready
                }
                compose.runOnIdle {
                    val view = tables().single()
                    val text = view.text as android.text.Spanned
                    val rows = text.getSpans(0, text.length, TableRowSpan::class.java)
                    val heights = rows.map { row ->
                        val layout = requireNotNull(row.findLayoutForHorizontalOffset(0))
                        android.util.Log.i("TablePalette", "mode=$mode dark=$theme native=${view.currentTextColor} cell=${layout.paint.color} size=${layout.paint.textSize}")
                        assertEquals("Table cells must use the actual native text color in $mode dark=$theme", view.currentTextColor, layout.paint.color)
                        for (background in listOf(palette.code, palette.row, palette.background)) assertTrue("Readable contrast against every table row", androidx.core.graphics.ColorUtils.calculateContrast(layout.paint.color, background) >= 4.5)
                        layout.height
                    }
                    expectedHeights?.let { assertEquals("Theme changes must not alter table geometry", it, heights) }
                    expectedHeights = heights
                }
                advancedUiShot(compose.activity, "reader-table-palette-$mode-$theme")
            }
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun preparedTablesBelongToOneViewAndKeepIndependentFontAndWidth() {
        lateinit var palette: StudyMarkdownPalette
        compose.setContent { StudyTheme(false) { val p = studyMarkdownPalette(); SideEffect { palette = p } } }
        compose.waitForIdle()
        val cache = ReaderMarkdownParser()
        configureReaderMarkdown(cache, compose.activity, palette)
        val source = "| 概念 | 解释 |\n| --- | --- |\n| 重点 | 同时存在的两个阅读帧不共享布局 |"
        runBlocking { withContext(Dispatchers.Default) { cache.warm(source) } }
        val first = requireNotNull(cache.takePrepared(source, palette))
        assertTrue("A staged value must never retain a native TextView", first.getSpans(0, first.length, Any::class.java).none { it.javaClass.simpleName == "TextViewSpan" })
        assertNull("A prepared value cannot be handed to a second native view", cache.takePrepared(source, palette))
        runBlocking { withContext(Dispatchers.Default) { cache.warm(source) } }
        val second = requireNotNull(cache.takePrepared(source, palette))
        val firstRows = first.getSpans(0, first.length, TableRowSpan::class.java)
        val secondRows = second.getSpans(0, second.length, TableRowSpan::class.java)
        assertEquals(2, firstRows.size)
        assertTrue(firstRows.none { a -> secondRows.any { b -> a === b } })
        compose.runOnIdle {
            val a = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 42f }
            val b = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 63f }
            prepareReaderTables(first, a, 900, false)
            prepareReaderTables(second, b, 600, false)
            assertEquals(450, firstRows.first().cellWidth())
            assertEquals(300, secondRows.first().cellWidth())
            assertEquals(42f, requireNotNull(firstRows.first().findLayoutForHorizontalOffset(0)).paint.textSize, .1f)
            assertEquals(63f, requireNotNull(secondRows.first().findLayoutForHorizontalOffset(0)).paint.textSize, .1f)
        }
    }
    @Test fun aLateWorkerCannotPublishThePreviousTheme() {
        val cache = ReaderMarkdownParser()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        cache.configureRendering("old") { { source, _ ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            SpannableStringBuilder("old:$source")
        } }
        try {
            val old = executor.submit { cache.warm("正文") }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            cache.configureRendering("new") { { source, _ -> SpannableStringBuilder("new:$source") } }
            release.countDown(); old.get(5, TimeUnit.SECONDS)
            assertNull(cache.takePrepared("正文", "new"))
            cache.warm("正文")
            assertEquals("new:正文", requireNotNull(cache.takePrepared("正文", "new")).toString())
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
