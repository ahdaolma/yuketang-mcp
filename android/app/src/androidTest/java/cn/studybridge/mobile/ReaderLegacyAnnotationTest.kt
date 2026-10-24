package cn.studybridge.mobile

import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
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
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = "tap"))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) { Box(Modifier.fillMaxSize()) {
                NoteReadingSelection(note, vm) { modifier -> NotebookReader(note, ui, vm, modifier, onBack = {}) }
            } }
        }
        try {
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
            }
        } finally { compose.runOnUiThread { store.clear() } }
    }
}
