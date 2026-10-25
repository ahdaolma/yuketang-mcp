package cn.studybridge.mobile

import android.text.SpannableStringBuilder
import android.text.TextPaint
import androidx.compose.runtime.SideEffect
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
