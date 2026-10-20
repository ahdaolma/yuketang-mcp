package cn.studybridge.mobile

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LongNoteReadingTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }
    private fun waitForNativeText(marker: String) {
        compose.waitUntil(5000) {
            var found = false
            compose.runOnIdle { found = textViews(compose.activity.window.decorView).any { it.text.contains(marker) } }
            found
        }
    }
    @Test fun changingReadingSegmentAndReplacingLongContentDoesNotShowStaleOffsets() {
        val content = "# 开头标记\n\n" + "这是前段的学习内容。\n\n".repeat(1800) + "# 最终段标记\n\n全部内容应保留。"
        val note = mutableStateOf(Note("long-note", "长笔记", content, "合成资料", "fixture"))
        val count = noteReadingSegments(content).size
        compose.setContent { StudyTheme(false) { Column(Modifier.verticalScroll(rememberScrollState())) { PagedNoteContent(note.value) } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("阅读分段 1 / $count · 完整笔记已保留").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("阅读分段 1 / $count · 完整笔记已保留").assertIsDisplayed()
        waitForNativeText("开头标记")
        repeat(count - 1) { compose.onNodeWithText("下一段").performClick() }
        waitForNativeText("最终段标记")
        compose.onNodeWithText("下一段").assertIsNotEnabled()
        assertEquals(content, note.value.content)
        compose.runOnIdle { note.value = note.value.copy(content = "短笔记仍完整") }
        waitForNativeText("短笔记仍完整")
        compose.onNodeWithText("下一段").assertDoesNotExist()
    }
}
