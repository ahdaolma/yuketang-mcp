package cn.studybridge.mobile

import android.text.Spanned
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.noties.markwon.Markwon
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReadingMarksAndClassControlTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun highlighterUnderlineSlashesAndWavesRenderWithoutVisibleDelimitersInBothThemes() {
        val content = "### 复制的主线\n\n==DNA 的复制是半保留复制==，原有两条链分别作为模板。\n\n++理解方向与互补配对如何共同决定复制过程++，再串联关键酶的作用。\n\n%%复制研究的发现史作为背景了解%%。\n\n!!不要把两条亲代链同时保留在同一个子代分子中!!。\n\n`==代码中的等号保持原样==`"
        val dark = mutableStateOf(false)
        compose.setContent { StudyTheme(dark.value) { androidx.compose.material3.Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().padding(24.dp)) { StudyTimeBar(); Markdown(content) } } } }
        fun textViews(view: View): List<TextView> = when (view) {
            is TextView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
            else -> emptyList()
        }
        compose.waitUntil(5000) {
            var found = false
            compose.runOnIdle { found = textViews(compose.activity.window.decorView).any { (it.text as? Spanned)?.getSpans(0, it.text.length, StudyMarkerSpan::class.java)?.size == 4 } }
            found
        }
        compose.runOnIdle {
            val text = textViews(compose.activity.window.decorView).map { it.text }.first { it.contains("复制的主线") }
            assertTrue(text.contains("==代码中的等号保持原样=="))
            assertFalse(text.contains("==DNA")); assertFalse(text.contains("++理解")); assertFalse(text.contains("%%复制")); assertFalse(text.contains("!!不要"))
        }
        advancedUiShot(compose.activity, "sample-note-marks-light")
        var previousText: CharSequence? = null
        compose.runOnIdle { previousText = textViews(compose.activity.window.decorView).first { it.text.contains("复制的主线") }.text; dark.value = true }
        compose.waitUntil(5000) { var changed = false; compose.runOnIdle { changed = textViews(compose.activity.window.decorView).any { it.text.contains("复制的主线") && it.text !== previousText } }; changed }
        compose.waitForIdle(); advancedUiShot(compose.activity, "sample-note-marks-dark")
        val parsed = Markwon.create(compose.activity).toMarkdown("==**原有加粗**==")
        val marked = renderStudyMarks(parsed, 1f, false)
        assertEquals("原有加粗", marked.toString())
        assertTrue(marked.getSpans(0, marked.length, Any::class.java).size >= 2)
    }
    @Test fun classCircleDragsWithoutPausingLongPressDoesNothingAndShortTapPauses() {
        var taps = 0
        val paused = mutableStateOf(false)
        compose.setContent { StudyTheme(false) { androidx.compose.material3.Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(24.dp)) { StudyTimeBar(); Markdown("# DNA 的复制\n\n老师正在讲解第 3 页，笔记随课件继续整理。\n\n==先理解模板与互补配对==，再连接方向与关键酶的作用。") }
            ClassFloatingControl(paused.value, "synthetic-class", { taps++; paused.value = !paused.value })
        } } } }
        val before = compose.onNodeWithTag("上课悬浮圆钮").getUnclippedBoundsInRoot()
        compose.onNodeWithTag("上课悬浮圆钮").performTouchInput { longClick(durationMillis = 900) }
        assertEquals(0, taps)
        compose.onNodeWithTag("上课悬浮圆钮").performTouchInput { swipe(center, center + androidx.compose.ui.geometry.Offset(-180f, 100f), durationMillis = 900) }
        assertEquals(0, taps)
        val moved = compose.onNodeWithTag("上课悬浮圆钮").getUnclippedBoundsInRoot()
        assertTrue(moved.left < before.left - 10.dp)
        compose.onNodeWithTag("上课悬浮圆钮").performTouchInput { click() }
        assertEquals(1, taps)
        compose.onNodeWithContentDescription("继续跟课").assertIsDisplayed()
        advancedUiShot(compose.activity, "sample-class-floating-control")
        compose.onNodeWithTag("上课悬浮圆钮").performTouchInput { longClick(durationMillis = 900) }
        assertEquals(1, taps)
        compose.onNodeWithTag("上课悬浮圆钮").performTouchInput { click() }
        assertEquals(2, taps)
    }
}
