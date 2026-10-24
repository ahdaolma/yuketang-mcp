package cn.studybridge.mobile

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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
            field.performTextClearance().performClick().assertIsFocused()
            compose.waitForIdle()
            shell("input text '$expected'")
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
}
