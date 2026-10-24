package cn.studybridge.mobile

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real Android hardware-key injection, rather than a single semantics replacement. */
class NoteEditorHardwareInputTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun exercise(editor: Boolean) {
        var source by mutableStateOf("")
        val trace = mutableListOf<String>()
        compose.setContent {
            StudyTheme(false) {
                val change: (String) -> Unit = { source = it; trace += it; Log.d("EditorInput", "value=$it") }
                if (editor) NoteEditor(source, change, true)
                else OutlinedTextField(source, change, Modifier.fillMaxWidth().testTag("control"))
            }
        }
        val field = if (editor) compose.onNodeWithText("Markdown 笔记") else compose.onNodeWithTag("control")
        val expected = "R8_SECOND_FIRST_ABCDEFGHIJKLMNOPQRSTUVWXYZ_0123456789_SECOND_END"
        repeat(12) { round ->
            field.performTextClearance()
            field.performClick().assertIsFocused()
            compose.waitForIdle()
            // UiAutomation starts a process directly; it does not strip shell quotes.
            shell("input text $expected")
            compose.waitForIdle()
            try {
                compose.runOnIdle { assertEquals("editor=$editor round=$round trace=${trace.takeLast(8)}", expected, source) }
                assertEquals(expected, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            } finally {
                Log.d("EditorInput", "end editor=$editor round=$round expected=$expected actual=$source")
                if (source != expected) advancedUiShot(compose.activity, "editor-hardware-failure-$editor")
            }
        }
    }

    @Test fun noteEditorRetainsEveryHardwareInjectedCharacter() = exercise(true)
    @Test fun plainComposeFieldRetainsEveryHardwareInjectedCharacter() = exercise(false)

    @Test fun realNotebookEditorKeepsMultilineHardwareInputThroughAutosaveAndManualSave() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved())
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) {
                StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
                }
            }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithContentDescription("新建笔记").performClick()
            val field = compose.onNodeWithText("Markdown 笔记")
            val lines = listOf("## R8_FIRST", "", "release_first_body", "", "## R8_SECOND", "", "release_second_body")
            val expected = lines.joinToString("\n")
            repeat(5) { round ->
                field.performTextClearance()
                field.performClick().assertIsFocused()
                lines.forEachIndexed { index, line ->
                    if (index > 0) shell("input keyevent KEYCODE_ENTER")
                    if (line.isNotEmpty()) shell("input text ${line.replace(" ", "%s")}")
                }
                compose.waitForIdle()
                compose.runOnIdle { assertEquals("Production editor input round=$round", expected, vm.noteReader.draft) }
                assertEquals(expected, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            }
            shell("input keyevent KEYCODE_BACK")
            compose.onNodeWithText("保存修改").performClick()
            compose.runOnIdle { assertEquals(expected, vm.ui.value.data.notes.single().content) }
        } finally { compose.runOnUiThread { store.clear() } }
    }
}
