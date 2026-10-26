package cn.studybridge.mobile

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real NotesScreen contracts; Android execution is cloud-only. */
class ReaderModeContractTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val note = Note(title = "阅读模式隔离", content = "## 第 1 页\n\nMODE_FIRST。\n\n" +
        (1..38).joinToString("\n\n") { "原页段落$it：" + "长页必须逐屏阅读，不能混入其他模式的手势。".repeat(3) } +
        "\n\nMODE_END。\n\n## 第 2 页\n\nMODE_SECOND。", source = "两页合成资料", model = "fixture")

    private fun texts(): List<NoteSelectableTextView> {
        val found = mutableListOf<NoteSelectableTextView>()
        fun visit(view: View) { if (view is NoteSelectableTextView) found += view; if (view is ViewGroup) for (i in 0 until view.childCount) visit(view.getChildAt(i)) }
        visit(compose.activity.window.decorView)
        return found.filter { it.isAttachedToWindow && it.alpha >= .99f }
    }
    private fun firstY(): Float {
        var value = Float.NaN
        compose.runOnIdle { val view = texts().first { it.text.contains("MODE_FIRST") }; val xy = IntArray(2); view.getLocationInWindow(xy); value = xy[1].toFloat() }
        return value
    }
    private fun settle() {
        compose.mainClock.advanceTimeBy(1200)
        compose.waitForIdle()
        compose.waitUntil(8000) { compose.mainClock.advanceTimeByFrame(); compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
    }
    private fun reader(mode: String, style: String = "curl", block: (StudyViewModel) -> Unit) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(notes = listOf(note), noteReadMode = mode, notePageAnimation = style))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) { StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) { NotesScreen(ui, vm) }
            } }
        }
        try {
            compose.waitUntil(8000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(note.title))
            compose.onNodeWithText(note.title).performClick()
            compose.waitUntil(8000) { var ready = false; compose.runOnIdle { ready = texts().any { it.layout != null && it.text.contains("MODE_FIRST") } }; ready && vm.readingMemory.position(note.id) != null }
            settle(); block(vm)
        } finally { compose.mainClock.autoAdvance = true; compose.runOnUiThread { store.clear() } }
    }
    @Test fun tapModeDoesNotTurnOnHorizontalDragButAnActualTapStillAdvances() = reader("buttons") {
        val before = firstY()
        compose.onNodeWithTag("笔记正文").performTouchInput { swipe(Offset(width * .85f, height * .55f), Offset(width * .15f, height * .55f), 280) }
        settle()
        assertEquals("Horizontal dragging must not turn a tap-only screen", before, firstY(), 1f)
        compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .85f, height * .55f)) }
        compose.waitUntil(8000) { firstY() < before - 50 }
    }
    private fun noVerticalDrag(mode: String) = reader(mode) {
        val before = firstY()
        compose.onNodeWithTag("笔记正文").performTouchInput { swipe(Offset(width * .55f, height * .78f), Offset(width * .55f, height * .25f), 280) }
        settle()
        assertEquals("$mode must not scroll vertically on a long source page", before, firstY(), 1f)
        compose.onNodeWithTag("笔记正文").performTouchInput {
            if (mode == "buttons") click(Offset(width * .85f, height * .55f))
            else swipe(Offset(width * .85f, height * .55f), Offset(width * .15f, height * .55f), 280)
        }
        compose.waitUntil(8000) { firstY() < before - 50 }
    }
    @Test fun horizontalModeRejectsVerticalDraggingButStillAdvancesByScreen() = noVerticalDrag("swipe")
    @Test fun tapModeRejectsVerticalDraggingButStillAdvancesByScreen() = noVerticalDrag("buttons")
    @Test fun aDragReturningToItsOriginNeverBecomesATap() = reader("buttons") {
        val before = firstY()
        compose.onNodeWithTag("笔记正文").performTouchInput {
            val start = Offset(width * .8f, height * .02f)
            down(start); moveTo(start.copy(x = start.x - 50f), 80); moveTo(start, 80); up()
        }
        settle(); assertEquals("Canceled drag must not turn on release", before, firstY(), 1f)
    }
    @Test fun continuousModeStillAllowsNaturalVerticalScrolling() = reader("continuous") {
        val before = firstY()
        compose.onNodeWithTag("笔记正文").performTouchInput { swipeUp(durationMillis = 280) }
        settle(); assertTrue("Continuous scrolling remains available", firstY() < before - 50)
    }
    private fun entirePaper(style: String) = reader("swipe", style) {
        val outer = compose.onNodeWithTag("笔记阅读页").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
        advancedUiShot(compose.activity, "reader-mode-$style-before")
        assertEquals("Paper must include the left gutter", outer.left.value, body.left.value, 1f)
        assertEquals("Paper must include the right gutter", outer.right.value, body.right.value, 1f)
        compose.runOnIdle {
            val view = texts().first { it.text.contains("MODE_FIRST") }
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("Tablet text must remain limited to840dp", view.width <= 840 * density + 2)
        }
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("笔记正文").performTouchInput { down(Offset(width * .85f, height * .6f)); moveTo(Offset(width * .5f, height * .6f), 220) }
            compose.waitUntil(12000) { compose.mainClock.advanceTimeByFrame(); compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            val canvas = compose.onNodeWithTag("笔记翻页画面").getUnclippedBoundsInRoot()
            assertEquals(outer.left.value, canvas.left.value, 1f); assertEquals(outer.right.value, canvas.right.value, 1f)
            advancedUiShot(compose.activity, "reader-mode-$style-entire-paper")
            compose.onNodeWithTag("笔记正文").performTouchInput { up() }
        } finally { compose.mainClock.autoAdvance = true }
        settle()
    }
    @Test fun phoneCurlAnimatesBothGutters() = entirePaper("curl")
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { input -> input.readText() } }
    @Test fun tabletCoverAnimatesBothGuttersWithoutStretchingText() {
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        try {
            shell("wm size 2560x1600"); shell("wm density 320")
            compose.waitUntil(15000) { val metrics = compose.activity.resources.displayMetrics; kotlin.math.abs(metrics.widthPixels - 2560) < 16 && kotlin.math.abs(metrics.density - 2f) < .01f && compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            entirePaper("cover")
        } finally { shell("wm size reset"); shell("wm density reset") }
    }
}
