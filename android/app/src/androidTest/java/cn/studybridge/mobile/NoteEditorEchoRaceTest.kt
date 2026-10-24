package cn.studybridge.mobile

import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Interleave real field edits with applyChanges, without delaying product code.
 * AndroidUiDispatcher queues launched effects in production; the standard test
 * dispatcher exposes that ordering instead of starting every effect eagerly.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class NoteEditorEchoRaceTest {
    private val effectDispatcher = StandardTestDispatcher()
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>(effectContext = effectDispatcher)

    private fun exercise(editor: Boolean) {
        val initial = "R8_SE"
        val expected = "R8_SECON"
        var source by mutableStateOf(initial)
        var control by mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
        var phase by mutableIntStateOf(0)
        val trace = mutableListOf<String>()
        var insert: ((AnnotatedString) -> Boolean)? = null
        fun record(event: String) {
            val line = "${trace.size}: editor=$editor $event"
            trace += line
            Log.i("EditorEchoRace", line)
        }
        fun insertCharacter(character: String) {
            record("insert $character before source=$source")
            assertTrue("The same focused field must accept $character", requireNotNull(insert)(AnnotatedString(character)))
            record("insert $character after source=$source")
        }
        val label = if (editor) "Markdown 笔记" else "Echo control"
        compose.setContent {
            val capturedSource = source
            val capturedPhase = phase
            StudyTheme(false) {
                if (editor) NoteEditor(source, {
                    record("onChange source=$source next=$it")
                    source = it
                }, true)
                else OutlinedTextField(control, {
                    record("onChange source=$source next=${it.text}")
                    control = it
                    source = it.text
                }, Modifier.fillMaxWidth(), label = { Text(label) })
            }
            SideEffect {
                record("apply capturedPhase=$capturedPhase capturedSource=$capturedSource liveSource=$source")
                when (capturedPhase) {
                    1 -> {
                        // NoteEditor has captured the C echo for its new
                        // LaunchedEffect. Edit O after apply, before that queued
                        // effect can consume the older captured String.
                        phase = 2
                        insertCharacter("O")
                    }
                    2 -> {
                        // The next composition has supplied its actual value
                        // back to CoreTextField's EditProcessor. N therefore
                        // reveals whether the old echo reset that real buffer.
                        phase = 3
                        insertCharacter("N")
                    }
                }
            }
        }
        val field = compose.onNodeWithText(label)
        field.performClick().assertIsFocused()
        compose.waitForIdle()
        val originalNode = field.fetchSemanticsNode()
        insert = requireNotNull(originalNode.config[SemanticsActions.InsertTextAtCursor].action)
        // In Compose Foundation 1.8.0 this action closes over the retained
        // CoreTextFieldSemanticsModifierNode, whose state/value are mutable.
        // With an input session it calls state.processor.apply via
        // TextFieldDelegate; it does not retain an earlier TextFieldValue.
        compose.mainClock.autoAdvance = false
        try {
            compose.runOnIdle {
                insertCharacter("C")
                assertEquals("C must be accepted before constructing the race", "R8_SEC", source)
                phase = 1
            }
            repeat(6) { frame ->
                compose.mainClock.advanceTimeByFrame()
                val node = field.fetchSemanticsNode()
                val editable = node.config[SemanticsProperties.EditableText].text
                compose.runOnIdle { record("frame=$frame phase=$phase source=$source editable=$editable node=${node.id}") }
                assertEquals("The diagnostic must retain its original field node", originalNode.id, node.id)
            }
            field.assertIsFocused()
            compose.runOnIdle {
                assertEquals("Both deterministic apply stages must execute; trace=${trace.joinToString(" | ")}", 3, phase)
                assertEquals("A stale parent echo must not discard a newer O; trace=${trace.joinToString(" | ")}", expected, source)
            }
            assertEquals(expected, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        } finally {
            compose.mainClock.autoAdvance = true
            val directory = File(compose.activity.getExternalFilesDir(null), "StudyUiReview").apply { mkdirs() }
            File(directory, "editor-echo-race-${if (editor) "note" else "control"}.txt").writeText(trace.joinToString("\n"))
            Log.i("EditorEchoRace", "FINAL editor=$editor expected=$expected source=$source phase=$phase")
        }
    }

    @Test fun noteEditorKeepsNewerInputWhenAnOlderParentEchoWasAlreadyScheduled() = exercise(true)
    @Test fun plainComposeControlKeepsTheSameInterleavedInput() = exercise(false)

    @Test fun genuineExternalReplacementStillSupportsUndoAndRedo() {
        var source by mutableStateOf("base")
        compose.setContent { StudyTheme(false) { NoteEditor(source, { source = it }, true) } }
        val field = compose.onNodeWithText("Markdown 笔记")
        field.performTextInput("_edit")
        compose.runOnIdle { assertEquals("base_edit", source) }
        compose.runOnIdle { source = "new external content" }
        compose.waitForIdle()
        assertEquals("new external content", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("撤销").performClick()
        compose.runOnIdle { assertEquals("base_edit", source) }
        compose.onNodeWithText("重做").performClick()
        compose.runOnIdle { assertEquals("new external content", source) }
        assertEquals(source, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }
}
