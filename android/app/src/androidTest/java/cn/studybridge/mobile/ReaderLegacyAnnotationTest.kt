package cn.studybridge.mobile

import android.text.Spanned
import android.text.Spannable
import android.text.Selection
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
import androidx.lifecycle.ViewModelStore
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderLegacyAnnotationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun unchanged102AnchorsRemainVisibleWithOriginalIdsAfterImageAtomicSegmentation() {
        val content = "旧标记" + "x".repeat(11967) + "![原图](study-image:" + "a".repeat(64) + ")尾文"
        val marks = listOf("highlight", "underline", "remark").map { kind ->
            NoteAnnotation(id = "legacy-$kind", sourceStart = 0, sourceLength = 12000,
                sourceHash = "6e8a739b3a871be43398bf063d12c553830051bde43a9de0231adeedcf20ee58",
                start = 0, end = 3, quote = "旧标记", kind = kind, comment = if (kind == "remark") "旧备注仍可编辑" else "")
        }
        assertEquals(12055, content.length)
        assertTrue(marks.all { it.isAttached(content) })
        assertEquals(11970, noteReadingSegments(content).first().end)
        val note = Note(title = "旧版个人标记兼容", content = content, source = "fixture", model = "fixture", annotations = marks)
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = "buttons"))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) { Box(Modifier.fillMaxSize()) {
                val savedNote = ui.data.notes.find { it.id == note.id } ?: note
                NoteReadingSelection(savedNote, vm) { modifier -> NotebookReader(savedNote, ui, vm, modifier, onBack = {}) }
            } }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.waitUntil(10000) {
                var ready = false
                compose.runOnIdle {
                    ready = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>()
                        .any { it.alpha >= .99f && it.block?.sourceStart == 0 && it.text.startsWith("旧标记") }
                }
                ready
            }
            compose.runOnIdle {
                val view = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>()
                    .first { it.alpha >= .99f && it.block?.sourceStart == 0 && it.text.startsWith("旧标记") }
                val rendered = view.text as Spanned
                val spans = rendered.getSpans(0, 3, NoteAnnotationSpan::class.java)
                assertEquals("All unchanged 1.0.2 annotation kinds must render at their original quote", marks.map { it.id }.toSet(), spans.map { it.annotation.id }.toSet())
                spans.forEach { assertEquals(0, rendered.getSpanStart(it)); assertEquals(3, rendered.getSpanEnd(it)); assertEquals(marks.first { mark -> mark.id == it.annotation.id }, it.annotation) }
                assertEquals("Rendering must not migrate or replace saved anchors", marks, note.annotations)
                assertEquals("Selection removal must address legacy IDs through projected spans", marks.map { it.id }.toSet(), noteAnnotationIdsInSelection(rendered, 0, 3))
                val x = view.totalPaddingLeft + view.layout.getPrimaryHorizontal(1)
                val y = view.totalPaddingTop + view.layout.getLineBottom(0) / 2f
                val time = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
                val up = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_UP, x, y, 0)
                try { view.onTouchEvent(down); view.onTouchEvent(up) } finally { down.recycle(); up.recycle() }
            }
            compose.onNodeWithText("编辑备注").assertIsDisplayed()
            compose.onNodeWithText("旧备注仍可编辑").performTextReplacement("旧版备注编辑后保留锚点")
            compose.onNodeWithText("保存备注").performClick()
            compose.runOnIdle {
                val saved = vm.ui.value.data.notes.first { it.id == note.id }.annotations
                assertEquals(marks.map { it.id }.toSet(), saved.map { it.id }.toSet())
                val edited = saved.first { it.id == "legacy-remark" }
                assertEquals(marks.first { it.kind == "remark" }.copy(comment = "旧版备注编辑后保留锚点"), edited)
            }
            compose.onNodeWithText("关闭").performClick()
            compose.runOnIdle {
                val view = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>()
                    .first { it.alpha >= .99f && it.block?.sourceStart == 0 && it.text.startsWith("旧标记") }
                Selection.setSelection(view.text as Spannable, 0, 3)
                view.reportSelection()
            }
            compose.onNodeWithText("取消标记").performClick()
            compose.runOnIdle { assertTrue("Cancel selection must remove all projected legacy IDs", vm.ui.value.data.notes.first { it.id == note.id }.annotations.isEmpty()) }
        } finally { compose.runOnUiThread { store.clear() } }
    }

    @Test fun realMarkdownRendererProjectsBothShiftedEdgesWithoutChoosingFirstDuplicate() {
        val renderer = Markwon.builder(compose.activity).usePlugin(TablePlugin.create { }).build()
        fun parse(raw: String) = renderStudyMarks(renderer.toMarkdown(markdownForStudyMarks(raw)), 1f, false)
        val content = "**重复重点** ".repeat(50)
        val oldStart = 10 * 9
        val oldEnd = 40 * 9
        // All blocks begin/end at whole inline tokens. Their repeated quotes
        // are identical; raw endpoints, not a quote search, determine the rank.
        val old = content.substring(oldStart, oldEnd)
        val oldRendered = parse(old)
        val offset = oldRendered.toString().indexOf("重复重点", 30)
        val mark = NoteAnnotation(id = "legacy-middle-remark", sourceStart = oldStart, sourceLength = old.length,
            sourceHash = imageDigest(old.toByteArray()), start = offset, end = offset + 4, quote = "重复重点", kind = "remark", comment = "旧中段备注")
        val currentStart = 8 * 9
        val currentEnd = 38 * 9
        val current = content.substring(currentStart, currentEnd)
        val result = renderPersonalAnnotations(parse(current), current, currentStart, listOf(mark), content, render = { parse(it).toString() })
        val span = result.getSpans(0, result.length, NoteAnnotationSpan::class.java).single()
        val expected = 2 * "重复重点 ".length + offset
        assertEquals(expected, result.getSpanStart(span))
        assertEquals(expected + 4, result.getSpanEnd(span))
        assertSame(mark, span.annotation)
        val changed = content.replaceRange(oldStart + 3, oldStart + 4, "改")
        val changedSource = changed.substring(currentStart, currentEnd)
        val rejected = renderPersonalAnnotations(parse(changedSource), changedSource, currentStart, listOf(mark), changed, render = { parse(it).toString() })
        assertTrue(rejected.getSpans(0, rejected.length, NoteAnnotationSpan::class.java).isEmpty())
    }
}
