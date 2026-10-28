package cn.studybridge.mobile

import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderNativeTapTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private fun nativeText(text: CharSequence = "可选择的正文与普通点击。", run: (NoteSelectableTextView, NoteSelectionController, () -> Int) -> Unit) {
        val controller = NoteSelectionController()
        var taps = 0
        lateinit var view: NoteSelectableTextView
        compose.setContent {
            CompositionLocalProvider(LocalNoteSelection provides controller) {
                AndroidView(factory = { context -> NoteSelectableTextView(context).also {
                    view = it
                    it.text = text; it.textSize = 22f; it.setTextIsSelectable(true)
                    it.controller = controller; it.block = NoteSelectionBlock(text.toString(), 0)
                    it.onPageTap = { taps++ }
                } }, modifier = Modifier.fillMaxWidth().testTag("原生阅读文本"))
            }
        }
        compose.waitForIdle()
        run(view, controller) { taps }
    }

    @Test fun anOrdinaryNativeTextTapForwardsExactlyOnePageAction() = nativeText { _, _, taps ->
        compose.onNodeWithTag("原生阅读文本").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps()) }
    }

    @Test fun windowReturnClearsOnlyTheReadOnlyCaretAndPreservesAnActiveSelection() = nativeText { view, controller, _ ->
        compose.runOnIdle {
            assertTrue(view.requestFocus())
            Selection.setSelection(view.text as Spannable, 2)
            view.onWindowFocusChanged(false)
            view.onWindowFocusChanged(true)
            assertFalse("A read-only caret must not re-anchor its parent on window return", view.isFocused)
            assertEquals(-1, view.selectionStart)
            assertTrue(view.requestFocus())
            Selection.setSelection(view.text as Spannable, 1, 5)
            view.reportSelection()
            assertNotNull(controller.selection)
            view.onWindowFocusChanged(false)
            view.onWindowFocusChanged(true)
            assertTrue("An actual selection must keep its handles and application actions", view.isFocused)
            assertEquals(1, view.selectionStart); assertEquals(5, view.selectionEnd)
            assertNotNull(controller.selection)
        }
    }

    @Test fun aSlowTapBelowTheSystemLongPressTimeoutStillForwards() = nativeText { _, _, taps ->
        compose.onNodeWithTag("原生阅读文本").performTouchInput {
            down(center)
            advanceEventTime(300)
            up()
        }
        compose.runOnIdle { assertEquals(1, taps()) }
    }

    @Test fun movingAwayAndReturningToTheDownPointIsNotATap() = nativeText { view, _, taps ->
        val slop = android.view.ViewConfiguration.get(view.context).scaledTouchSlop
        compose.onNodeWithTag("原生阅读文本").performTouchInput {
            down(center)
            moveTo(center.copy(y = center.y + slop * 2f), 80)
            moveTo(center, 80)
            up()
        }
        compose.runOnIdle { assertEquals(0, taps()) }
    }

    @Test fun canceledNativeTouchDoesNotForwardButTheNextTapWorks() = nativeText { _, _, taps ->
        compose.onNodeWithTag("原生阅读文本").performTouchInput { down(center); cancel() }
        compose.runOnIdle { assertEquals(0, taps()) }
        compose.onNodeWithTag("原生阅读文本").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(1, taps()) }
    }

    @Test fun aTapThatStartsWithASelectionBelongsToTheNativeSelection() = nativeText { view, controller, taps ->
        compose.runOnIdle {
            Selection.setSelection(view.text as Spannable, 0, 4)
            view.reportSelection()
            assertNotNull(controller.selection)
        }
        compose.onNodeWithTag("原生阅读文本").performTouchInput { click(center) }
        compose.runOnIdle { assertEquals(0, taps()) }
    }

    @Test fun clickableSpansDoNotForwardAPageAction() {
        var opened = 0
        val value = SpannableString("链接拥有这次点击").apply {
            setSpan(object : ClickableSpan() { override fun onClick(widget: View) { opened++ } }, 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        nativeText(value) { view, _, taps ->
            compose.onNodeWithTag("原生阅读文本").performTouchInput { click(centerLeft.copy(x = 20f)) }
            compose.runOnIdle { assertEquals(0, taps()); assertEquals(1, opened) }
            var end = androidx.compose.ui.geometry.Offset.Zero
            compose.runOnIdle {
                val layout = requireNotNull(view.layout)
                val line = layout.getLineForOffset(view.text.length)
                end = androidx.compose.ui.geometry.Offset(layout.getPrimaryHorizontal(view.text.length) - 1f + view.totalPaddingLeft,
                    (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f + view.totalPaddingTop)
            }
            compose.onNodeWithTag("原生阅读文本").performTouchInput { click(end) }
            compose.runOnIdle { assertEquals(0, taps()); assertEquals(2, opened) }
        }
    }
}
