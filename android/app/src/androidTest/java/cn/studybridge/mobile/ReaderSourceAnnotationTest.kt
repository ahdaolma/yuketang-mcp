package cn.studybridge.mobile

import android.text.Selection
import android.text.Spannable
import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderSourceAnnotationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun continuationBodyNativeSelectionAttachesAndReappliesWhileSyntheticHeaderCannotBeMarked() {
        val text = "|主题|说明|\n|---|---|\n|长行|" + "原始解释".repeat(100) + "|\n|结尾|最后一行|\n\n原始后续正文，需要保留重点和个人备注。"
        val part = noteReadingSegments(text, 256).last()
        assertTrue(part.prefix.isNotEmpty())
        val shown = part.display(text)
        val controller = NoteSelectionController()
        val marks = mutableStateOf(emptyList<NoteAnnotation>())
        lateinit var view: NoteSelectableTextView
        compose.setContent {
            StudyTheme(false) { Surface {
                CompositionLocalProvider(LocalNoteSelection provides controller, LocalNoteAnnotations provides marks.value) {
                    Column(Modifier.fillMaxWidth()) {
                        IllustratedMarkdown(shown, sourceOffset = part.start - part.prefix.length, readingSource = part.sourceForDisplay(text))
                    }
                }
            } }
        }
        compose.waitUntil(5000) {
            var ready = false
            compose.runOnIdle {
                val candidate = descendants(compose.activity.window.decorView).filterIsInstance<NoteSelectableTextView>().firstOrNull { it.alpha >= .99f && it.layout != null && it.block != null }
                if (candidate != null) { view = candidate; ready = true }
            }
            ready
        }
        lateinit var mark: NoteAnnotation
        lateinit var remark: NoteAnnotation
        compose.runOnIdle {
            assertEquals(text.substring(part.start, part.end), view.block!!.source)
            assertEquals(part.start, view.block!!.sourceStart)
            val native = view.text as Spannable
            // Select visible plain text after the continued table in the SAME
            // Markdown view. Table cells themselves use Markwon row spans.
            val first = native.toString().indexOf("原始后续正文")
            assertTrue(first >= 0)
            Selection.setSelection(native, first, first + "原始后续正文".length)
            view.reportSelection()
            mark = controller.selection!!.annotation("highlight")
            assertTrue("Continuation body annotation must survive the existing save attachment check", mark.isAttached(text))
            assertEquals("原始后续正文", mark.quote)
            assertEquals(part.start, mark.sourceStart)
            assertEquals(text.substring(part.start, part.end).length, mark.sourceLength)
            val remarkStart = native.toString().indexOf("个人备注")
            Selection.setSelection(native, remarkStart, remarkStart + "个人备注".length)
            view.reportSelection()
            remark = controller.selection!!.annotation("remark", "人工核对的备注")
            assertTrue(remark.isAttached(text))
            marks.value = listOf(mark, remark)
        }
        compose.waitUntil(5000) {
            var reapplied = false
            compose.runOnIdle { reapplied = (view.text as Spanned).getSpans(0, view.text.length, NoteAnnotationSpan::class.java).map { it.annotation.id }.toSet().containsAll(listOf(mark.id, remark.id)) }
            reapplied
        }
        compose.runOnIdle {
            val native = view.text as Spannable
            val header = native.getSpans(0, native.length, TableRowSpan::class.java).minBy { native.getSpanStart(it) }
            Selection.setSelection(native, native.getSpanStart(header), native.getSpanEnd(header))
            view.reportSelection()
            assertNotNull("Synthetic context remains selectable for copying", controller.selection)
            assertFalse("A repeated header must not anchor a new mark to unrelated body source", controller.selection!!.annotation("highlight").isAttached(text))
            assertTrue("Existing body annotations remain attached", marks.value.all { it.isAttached(text) })
        }
    }
}
