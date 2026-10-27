package cn.studybridge.mobile

import android.app.Application
import android.graphics.Rect
import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReaderExperienceTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    private val markers = (1..3).flatMap { page -> (1..6).map { row -> "来源${page}段落${row}标记" } + "来源${page}页尾标记" }
    private fun fullNote() = Note(
        title = "完整阅读体验",
        content = (1..3).joinToString("\n\n") { page ->
            "## 第 $page 页\n\n" + (1..6).joinToString("\n\n") { row ->
                "来源${page}段落${row}标记。" + "先理解模板方向，再联系配对关系与具体步骤。".repeat(8)
            } + "\n\n来源${page}页尾标记。"
        }, source = "三个合成来源页", model = "fixture"
    )

    private fun withReader(note: Note, setup: (Application) -> Unit = {}, widthFraction: State<Float>? = null, heightFraction: State<Float>? = null, block: (Application, StudyViewModel) -> Unit) {
        val app = noteTestApplication(compose.activity.applicationContext)
        setup(app)
        Vault(app).save(Saved(notes = listOf(note)))
        val store = ViewModelStore()
        lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            StudyTheme(false) {
                StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxHeight(heightFraction?.value ?: 1f).fillMaxWidth(widthFraction?.value ?: 1f).padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
                }
            }
        }
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(note.title))
            compose.onNodeWithText(note.title).performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { nativeSnapshot { views -> views.any { it.alpha >= .99f && it.layout != null && it.text.isNotBlank() } } }
            block(app, vm)
        } finally { compose.runOnIdle { store.clear() } }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private val readingViewport = Rect()
    private fun <T> nativeSnapshot(read: (List<NoteSelectableTextView>) -> T): T {
        val bounds = compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().firstOrNull()?.boundsInWindow
        val stickyBottom = compose.onAllNodesWithTag("笔记吸顶标题").fetchSemanticsNodes().firstOrNull()?.boundsInWindow?.bottom
        var result: T? = null
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            if (bounds != null) {
                val screen = IntArray(2); decor.getLocationOnScreen(screen)
                val window = IntArray(2); decor.getLocationInWindow(window)
                readingViewport.set((bounds.left + screen[0] - window[0]).toInt(), (maxOf(bounds.top, stickyBottom ?: bounds.top) + screen[1] - window[1]).toInt(), (bounds.right + screen[0] - window[0]).toInt(), (bounds.bottom + screen[1] - window[1]).toInt())
            }
            result = read(descendants(decor).filterIsInstance<NoteSelectableTextView>())
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    // A whole paragraph can exist in a TextView while its lines are below the
    // viewport. Count a marker only after its actual native line was visible.
    private fun lineVisible(view: NoteSelectableTextView, offset: Int): Boolean {
        if (!view.isShown || !view.isAttachedToWindow || view.alpha < .99f) return false
        val layout = view.layout ?: return false
        if (layout.width != view.width - view.compoundPaddingLeft - view.compoundPaddingRight) return false
        val visible = Rect()
        if (!view.getGlobalVisibleRect(visible) || !visible.intersect(readingViewport)) return false
        val location = IntArray(2); view.getLocationOnScreen(location)
        val line = layout.getLineForOffset(offset.coerceIn(0, view.text.length))
        val top = location[1] + view.totalPaddingTop - view.scrollY + layout.getLineTop(line)
        val bottom = location[1] + view.totalPaddingTop - view.scrollY + layout.getLineBottom(line)
        return top >= visible.top && bottom <= visible.bottom
    }

    private fun visibleMarkers(wanted: List<String>) = nativeSnapshot { views ->
        wanted.filter { marker -> views.any { view ->
            val offset = view.text.toString().indexOf(marker)
            offset >= 0 && lineVisible(view, offset)
        } }.toSet()
    }

    private fun assertReaderChrome() {
        compose.onAllNodesWithContentDescription("悬浮阅读工具").assertCountEquals(1)
        compose.onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("应用顶部栏").assertDoesNotExist()
        compose.onNodeWithTag("应用底部导航").assertDoesNotExist()
        compose.onNodeWithTag("按屏翻页栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("当前阅读页码").assertIsDisplayed()
        val clock = compose.onNodeWithTag("应用时间行").getUnclippedBoundsInRoot()
        val page = compose.onNodeWithContentDescription("当前阅读页码").getUnclippedBoundsInRoot()
        assertTrue("Page number belongs in the time row", page.top >= clock.top && page.bottom <= clock.bottom)
    }

    private fun readingOffset(): Float = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
    private fun readingMaximum(): Float = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue()
    private fun setMode(vm: StudyViewModel, mode: String, label: String) {
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == mode }
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
    }
    private fun jump(label: String) {
        compose.openNoteTool(label)
    }

    @Test fun tapsInsideBothReadingHalvesAdvanceAndReturnInsteadOfFallingIntoADeadZone() = withReader(fullNote()) { _, vm ->
        setMode(vm, "buttons", "点击翻页")
        jump("整本开头")
        for ((right, left) in listOf(.60f to .40f, .74f to .26f, .92f to .08f)) {
            val before = readingOffset()
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * right, height * .5f)) }
            try { compose.waitUntil(3000) { readingOffset() > before + 10f } }
            catch (e: Throwable) { advancedUiShot(compose.activity, "reader-audit-dead-zone-$right"); throw AssertionError("Right half x=$right did not advance", e) }
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * left, height * .5f)) }
            compose.waitUntil(3000) { kotlin.math.abs(readingOffset() - before) < 2f }
        }
    }

    @Test fun tappingAnOriginalImageOpensPreviewWithoutAlsoTurningTheReader() {
        val image = syntheticSlideImage()
        val id = imageDigest(android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT))
        val note = Note(title = "图片点击归属", content = "## 第 1 页\n\n" + sourceImageMarkdown(id, "可点击原图") +
            "图片之后的说明。".repeat(350) + "\n\n## 第 2 页\n\n下一原页。", source = "合成图片", model = "fixture")
        withReader(note, { SourceImages(it).save(image) }) { _, vm ->
            setMode(vm, "buttons", "点击翻页")
            jump("整本开头")
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("可点击原图 缩略图").fetchSemanticsNodes().isNotEmpty() }
            val before = readingOffset()
            compose.onNodeWithContentDescription("可点击原图 缩略图").performTouchInput { click(Offset(width * .92f, height * .5f)) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("关闭预览").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭预览").performClick()
            compose.waitForIdle()
            assertEquals("An image tap belongs to preview and must not advance the text behind it", before, readingOffset(), 2f)
        }
    }

    @Test fun anExistingTextSelectionDoesNotAlsoTriggerAnEdgePageTurn() = withReader(fullNote()) { _, vm ->
        setMode(vm, "buttons", "点击翻页")
        jump("整本开头")
        nativeSnapshot { views ->
            val view = views.first { it.text.contains(markers.first()) }
            android.text.Selection.setSelection(view.text as android.text.Spannable, 0, 6)
            view.reportSelection()
        }
        compose.onNodeWithTag("正文选词工具条").assertIsDisplayed()
        val before = readingOffset()
        compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .92f, height * .65f)) }
        compose.waitForIdle()
        assertEquals("Dismiss/select text interaction must not turn the page underneath", before, readingOffset(), 2f)
    }

    private fun inspectEveryNativeLine(animation: String) {
        val note = fullNote()
        withReader(note) { _, vm ->
            compose.runOnIdle { vm.updateNotebookState { it.copy(noteFontSize = 28, notePageAnimation = animation) } }
            for ((mode, label) in NOTE_READING_MODES) {
                setMode(vm, mode, label)
                jump("整本开头")
                val expected = mutableSetOf<Pair<Int, Int>>()
                val seen = mutableSetOf<Pair<Int, Int>>()
                var lastLineSeen = false
                for (attempt in 0 until 100) {
                    nativeSnapshot { views -> views.forEach { view ->
                        val source = view.block?.sourceStart
                        val layout = view.layout
                        if (source != null && layout != null && view.alpha >= .99f && layout.width == view.width - view.compoundPaddingLeft - view.compoundPaddingRight) {
                            for (line in 0 until layout.lineCount) {
                                val offset = layout.getLineStart(line)
                                if (view.text.subSequence(offset, layout.getLineEnd(line)).isNotBlank()) {
                                    val key = source to offset
                                    expected += key
                                    if (lineVisible(view, offset)) seen += key
                                }
                            }
                        }
                    } }
                    lastLineSeen = lastLineSeen || markers.last() in visibleMarkers(listOf(markers.last()))
                    if (lastLineSeen && seen.containsAll(expected)) break
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        when (mode) {
                            "buttons" -> click(Offset(width * .92f, height * .5f))
                            "swipe" -> swipeLeft(durationMillis = 220)
                            else -> { down(Offset(center.x, height * .80f)); moveTo(Offset(center.x, height * .25f), 600); advanceEventTime(250); up() }
                        }
                    }
                    compose.waitForIdle()
                }
                assertTrue("Must actually inspect substantial native line layouts in $mode", expected.size > 100)
                assertTrue("Must reach final original page in $mode", lastLineSeen)
                assertEquals("No rendered text line can fall between screens in $mode; missing=${expected - seen}", expected, seen)
            }
        }
    }

    @Test fun everyRenderedNativeLineRemainsReachableAtLargeFontInEachMode() = inspectEveryNativeLine("curl")
    @Test fun coverKeepsEveryRenderedNativeLineReachableAtLargeFontInEachMode() = inspectEveryNativeLine("cover")

    @Test fun settledPagedScreensNeverStartInsideANativeTextLine() {
        // A short viewport reproduces landscape/split-screen geometry without
        // letting activity recreation replace this test's injected model.
        val height = mutableStateOf(.57f)
        withReader(fullNote(), heightFraction = height) { _, vm ->
            compose.runOnIdle { vm.updateNotebookState { it.copy(noteFontSize = 27) } }
            for (animation in listOf("cover", "curl")) for (mode in listOf("buttons", "swipe")) {
                compose.runOnIdle { vm.updateNotebookState { it.copy(notePageAnimation = animation) } }
                setMode(vm, mode, NOTE_READING_MODES.getValue(mode))
                jump("整本开头")
                repeat(5) { screen ->
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        if (mode == "buttons") click(Offset(width * .92f, height * .5f))
                        else swipeLeft(durationMillis = 220)
                    }
                    compose.waitForIdle()
                    advancedUiShot(compose.activity, "reader-edge-$animation-$mode-$screen")
                    nativeSnapshot { views -> views.filter { it.isShown && it.alpha >= .99f }.forEach { view ->
                        val layout = view.layout ?: return@forEach
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        val origin = location[1] + view.totalPaddingTop - view.scrollY
                        val edge = readingViewport.top
                        for (line in 0 until layout.lineCount) {
                            val top = origin + layout.getLineTop(line)
                            val bottom = origin + layout.getLineBottom(line)
                            if (layout.getLineLeft(line) < layout.getLineRight(line))
                                assertFalse("Settled $animation/$mode screen $screen cuts native line $line: $top < $edge < $bottom", top + 1 < edge && edge < bottom - 1)
                        }
                    } }
                }
            }
        }
    }

    @Test fun twoShortPagesSettleAtBothEndsWithoutChangingLayoutAfterTheGesture() {
        val note = Note(title = "两页短笔记", content = (1..2).joinToString("\n\n") { page ->
            "## 第 $page 页\n\n" + (1..7).joinToString("\n\n") { "第${page}页短段落${it}。" }
        }, source = "合成两页", model = "fixture")
        withReader(note) { _, vm ->
            for ((mode, label) in NOTE_READING_MODES) {
                setMode(vm, mode, label)
                jump("整本开头")
                repeat(6) {
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        when (mode) {
                            "buttons" -> click(Offset(width * .92f, height * .5f))
                            "swipe" -> swipeLeft(durationMillis = 180)
                            else -> swipeUp(durationMillis = 180)
                        }
                    }
                }
                compose.waitForIdle()
                val before = compose.onNodeWithContentDescription("当前阅读页码").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
                val offset = readingOffset()
                repeat(8) { compose.mainClock.advanceTimeBy(120); compose.waitForIdle(); assertEquals("Two-page reader must settle in $mode", offset, readingOffset(), 1f) }
                assertEquals(before, compose.onNodeWithContentDescription("当前阅读页码").fetchSemanticsNode().config[SemanticsProperties.StateDescription])
                advancedUiShot(compose.activity, "reader-audit-two-short-$mode")
            }
        }
    }

    @Test fun horizontalDragFoldsTheActualPaperBeforeReleaseAndAShortDragReturnsInPlace() = withReader(fullNote()) { _, vm ->
        setMode(vm, "swipe", "横向滑屏")
        jump("整本开头")
        fun pageX(): Int = nativeSnapshot { views ->
            val view = views.first { it.text.contains(markers.first()) && it.isAttachedToWindow }
            val location = IntArray(2); view.getLocationInWindow(location); location[0]
        }
        val beforeX = pageX()
        val beforeOffset = readingOffset()
        val beforePaper = compose.onNodeWithTag("笔记正文").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)!!
        compose.onNodeWithTag("笔记正文").performTouchInput {
            down(Offset(width * .5f, height * .5f))
            moveTo(Offset(width * .42f, height * .5f), 160)
        }
        compose.waitForIdle()
        try {
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            val held = compose.onNodeWithTag("笔记正文").captureToImage().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)!!
            try {
                assertEquals(beforePaper.width, held.width); assertEquals(beforePaper.height, held.height)
                var changed = 0
                for (y in 0 until held.height step 4) for (x in 0 until held.width step 4)
                    if (held.getPixel(x, y) != beforePaper.getPixel(x, y)) changed++
                assertTrue("A held gesture must visibly fold the actual full-width paper before release", changed > 50)
                assertEquals("Native text must not reflow or move independently under the folded snapshot", beforeX, pageX())
            } finally { held.recycle() }
            advancedUiShot(compose.activity, "reader-audit-held-short-swipe")
        } finally { beforePaper.recycle(); compose.onNodeWithTag("笔记正文").performTouchInput { up() } }
        compose.waitForIdle()
        assertEquals("Short drag must return to its exact origin", beforeX, pageX())
        assertEquals("Short drag must not commit another reading screen", beforeOffset, readingOffset(), 2f)
    }

    @Test fun aNewModeAndFontSupersedeAHeldPreviewWithoutLeavingTheReaderStuck() = withReader(fullNote()) { _, vm ->
        setMode(vm, "swipe", "横向滑屏")
        jump("整本开头")
        compose.onNodeWithTag("笔记正文").performTouchInput {
            down(Offset(width * .8f, height * .5f))
            moveTo(Offset(width * .35f, height * .5f), 160)
        }
        try { compose.runOnIdle { vm.updateNotebookState { it.copy(noteReadMode = "buttons", noteFontSize = 22) } } }
        finally { compose.onNodeWithTag("笔记正文").performTouchInput { up() } }
        compose.waitUntil(8000) { markers.first() in visibleMarkers(listOf(markers.first())) }
        compose.waitForIdle()
        val before = readingOffset()
        compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .6f, height * .5f)) }
        compose.waitUntil(5000) { readingOffset() > before + 10f }
        assertEquals("buttons", vm.ui.value.data.noteReadMode)
        assertReaderChrome()
    }

    @Test fun switchingEitherAnimationDuringItsHeldPaperCancelsTheOldCommandAndPersistsTheChoice() = withReader(fullNote()) { app, vm ->
        setMode(vm, "swipe", "横向滑屏")
        for ((style, title) in NOTE_PAGE_ANIMATIONS) {
            jump("整本开头")
            compose.openNoteTool("阅读设置")
            compose.onNodeWithText(title).performScrollTo().performClick()
            compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
            compose.waitForIdle()
            val before = readingOffset()
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(width * .8f, height * .6f)); moveTo(Offset(width * .35f, height * .6f), 160)
            }
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            val other = NOTE_PAGE_ANIMATIONS.keys.first { it != style }
            try { compose.runOnIdle { vm.updateNotebookState { it.copy(notePageAnimation = other) } } }
            finally { compose.onNodeWithTag("笔记正文").performTouchInput { up() } }
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isEmpty() }
            compose.waitForIdle()
            repeat(6) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); assertEquals("Old animation cannot later commit its screen", before, readingOffset(), 2f) }
            compose.waitUntil(5000) { Vault(app).load().notePageAnimation == other }
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 220) }
            compose.waitUntil(5000) { readingOffset() > before + 10f }
            assertReaderChrome()
        }
    }

    @Test fun shrinkingOnlyViewportHeightDuringCoverPreviewCancelsTheOldScreenGeometry() {
        val viewportHeightFraction = mutableStateOf(1f)
        withReader(fullNote(), heightFraction = viewportHeightFraction) { _, vm ->
            setMode(vm, "swipe", "横向滑屏")
            compose.runOnIdle { vm.updateNotebookState { it.copy(notePageAnimation = "cover") } }
            jump("整本开头")
            val originalHeight = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow.height
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(width * .8f, height * .5f)); moveTo(Offset(width * .35f, height * .5f), 160)
            }
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            try { compose.runOnIdle { viewportHeightFraction.value = .65f } }
            finally { compose.onNodeWithTag("笔记正文").performTouchInput { up() } }
            compose.waitUntil(8000) { compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow.height < originalHeight * .75f }
            compose.waitForIdle()
            compose.onNodeWithTag("笔记翻页画面").assertDoesNotExist()
            val before = readingOffset()
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 220) }
            compose.waitUntil(5000) { readingOffset() > before + 10f }
        }
    }

    @Test fun backgroundingAHeldPaperAfterChangingModesCancelsItAndStillAllowsTheNextGesture() = withReader(fullNote()) { _, vm ->
        // Start continuous, then change mode: lifecycle callbacks must observe
        // the current mode rather than the one captured on initial composition.
        setMode(vm, "swipe", "横向滑屏")
        for (style in NOTE_PAGE_ANIMATIONS.keys) {
            jump("整本开头")
            compose.runOnIdle { vm.updateNotebookState { it.copy(notePageAnimation = style) } }
            compose.waitForIdle()
            val before = readingOffset()
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(width * .8f, height * .6f)); moveTo(Offset(width * .35f, height * .6f), 160)
            }
            compose.waitUntil(8000) { compose.onAllNodesWithTag("笔记翻页画面").fetchSemanticsNodes().isNotEmpty() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("笔记正文").performTouchInput { cancel() }
            compose.waitForIdle()
            compose.onNodeWithTag("笔记翻页画面").assertDoesNotExist()
            repeat(6) { compose.mainClock.advanceTimeBy(100); compose.waitForIdle(); assertEquals("Stopped paper cannot later commit", before, readingOffset(), 2f) }
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 220) }
            compose.waitUntil(5000) { readingOffset() > before + 10f }
        }
    }

    @Test fun resizingTheViewportDuringAPreviewCancelsItsOldGeometryAndCanTurnAgain() {
        val viewportWidthFraction = mutableStateOf(1f)
        withReader(fullNote(), widthFraction = viewportWidthFraction) { _, vm ->
            setMode(vm, "swipe", "横向滑屏")
            jump("整本开头")
            val originalWidth = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow.width
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(width * .8f, height * .5f))
                moveTo(Offset(width * .35f, height * .5f), 160)
            }
            try { compose.runOnIdle { viewportWidthFraction.value = .72f } }
            finally { compose.onNodeWithTag("笔记正文").performTouchInput { up() } }
            compose.waitUntil(8000) { compose.onNodeWithTag("笔记正文").fetchSemanticsNode().boundsInWindow.width < originalWidth * .8f }
            compose.waitForIdle()
            val before = readingOffset()
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 220) }
            compose.waitUntil(5000) { readingOffset() > before + 10f }
            assertReaderChrome()
        }
    }

    @Test fun aTwoPageContinuousDragDoesNotReverseItsOwnScrollOrOscillateWhileIdle() {
        val note = Note(title = "两页临界高度", content = (1..2).joinToString("\n\n") { page ->
            "## 第 $page 页\n\n" + (1..7).joinToString("\n\n") { "第${page}页临界段落${it}。" }
        }, source = "两页近一屏高度", model = "fixture")
        withReader(note) { _, vm ->
            setMode(vm, "continuous", "连续滚动")
            repeat(4) { round ->
                jump("整本开头")
                var previous = readingOffset()
                compose.onNodeWithTag("笔记正文").performTouchInput { down(Offset(center.x, height * .85f)) }
                try {
                    repeat(12) { step ->
                        compose.onNodeWithTag("笔记正文").performTouchInput { moveTo(Offset(center.x, height * (.81f - step * .04f)), 40) }
                        compose.waitForIdle()
                        val offset = readingOffset()
                        val native = nativeSnapshot { views -> views.filter { it.isAttachedToWindow }.map { "${it.block?.sourceStart}:${it.width}x${it.height}/${it.layout?.height}" } }
                        android.util.Log.i("ReaderAuditFrames", "round=$round step=$step offset=$offset views=$native")
                        assertTrue("One-way finger movement must not reverse list position: $previous -> $offset", offset + .01f >= previous)
                        previous = offset
                    }
                } finally { compose.onNodeWithTag("笔记正文").performTouchInput { advanceEventTime(250); up() } }
                compose.waitForIdle()
                val settled = readingOffset()
                repeat(10) {
                    compose.mainClock.advanceTimeBy(100)
                    compose.waitForIdle()
                    assertEquals("Two-page continuous position must converge after release", settled, readingOffset(), .01f)
                }
            }
            advancedUiShot(compose.activity, "reader-audit-two-page-continuous-settled")
        }
    }

    @Test fun mixedImageAndShortLongPagesRemainReadableAcrossEveryModeAndBackwards() {
        val image = syntheticSlideImage()
        val id = imageDigest(android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT))
        val longPage = (1..8).joinToString("\n\n") { "图文段${it}标记。" + "图示与解释应按顺序完整阅读。".repeat(12) }
        val note = Note(title = "混合图文翻页", source = "合成图文", model = "fixture", content =
            "## 第 1 页\n\n短首页标记。\n\n## 第 2 页\n\n" + sourceImageMarkdown(id, "翻页原图") + longPage + "\n\n图文页尾标记。\n\n## 第 3 页\n\n短末页标记。")
        val wanted = listOf("短首页标记", "短末页标记", "图文页尾标记") + (1..8).map { "图文段${it}标记" }
        withReader(note, { SourceImages(it).save(image) }) { _, vm ->
            for ((mode, label) in NOTE_READING_MODES) {
                setMode(vm, mode, label)
                jump("整本开头")
                val seen = mutableSetOf<String>()
                var sawImage = false
                for (attempt in 0 until 45) {
                    seen += visibleMarkers(wanted)
                    sawImage = sawImage || compose.onAllNodesWithContentDescription("翻页原图 缩略图").fetchSemanticsNodes().any { it.boundsInRoot.height > 0f }
                    if (seen.size == wanted.size && sawImage) break
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        when (mode) {
                            "buttons" -> click(Offset(width * .92f, height * .5f))
                            "swipe" -> swipeLeft(durationMillis = 220)
                            else -> { down(Offset(center.x, height * .8f)); moveTo(Offset(center.x, height * .25f), 600); advanceEventTime(250); up() }
                        }
                    }
                }
                assertEquals("All mixed-page text must be reachable in $mode", wanted.toSet(), seen)
                assertTrue("The source image must be visible in $mode", sawImage)
                if (mode != "continuous") {
                    jump("本段顶部")
                    compose.onNodeWithTag("笔记正文").performTouchInput {
                        if (mode == "buttons") click(Offset(width * .08f, height * .5f)) else swipeRight(durationMillis = 220)
                    }
                    compose.waitUntil(8000) { "图文页尾标记" in visibleMarkers(listOf("图文页尾标记")) }
                }
            }
        }
    }

    @Test fun switchingAllReadingModesKeepsTheCurrentParagraphVisible() = withReader(fullNote()) { _, vm ->
        val marker = "来源2段落3标记"
        compose.openNoteTool("笔记内查找")
        compose.onNodeWithText("查找完整正文").performTextInput(marker)
        compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("第 2 段").performClick()
        compose.waitUntil(5000) { marker in visibleMarkers(listOf(marker)) }
        compose.onNodeWithContentDescription("结束查找").performClick()
        compose.waitUntil(5000) { marker in visibleMarkers(listOf(marker)) }
        for ((mode, label) in listOf("buttons" to "点击翻页", "swipe" to "横向滑屏", "continuous" to "连续滚动", "buttons" to "点击翻页")) {
            setMode(vm, mode, label)
            try { compose.waitUntil(8000) { marker in visibleMarkers(listOf(marker)) } }
            catch (failure: Throwable) {
                advancedUiShot(compose.activity, "reader-audit-switch-$mode")
                throw AssertionError("Switching to $mode lost the current paragraph; memory=${vm.readingMemory.position(vm.noteReader.id!!)}", failure)
            }
            compose.openNoteTool("阅读设置")
            compose.onNodeWithText("增大字号").performScrollTo().performClick()
            compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
            compose.waitUntil(8000) { marker in visibleMarkers(listOf(marker)) }
        }
        assertReaderChrome()
    }

    @Test fun backwardPageCrossingStartsAtThePreviousPagesFinalScreen() = withReader(fullNote()) { _, vm ->
        for ((mode, label) in listOf("buttons" to "点击翻页", "swipe" to "横向滑屏")) {
            setMode(vm, mode, label)
            jump("整本末尾"); jump("本段顶部")
            compose.waitUntil(5000) { "来源3段落1标记" in visibleMarkers(listOf("来源3段落1标记")) }
            compose.onNodeWithTag("笔记正文").performTouchInput {
                if (mode == "buttons") click(Offset(width * .08f, height * .5f)) else swipeRight(durationMillis = 220)
            }
            try { compose.waitUntil(8000) { "来源2页尾标记" in visibleMarkers(listOf("来源2页尾标记")) } }
            catch (failure: Throwable) {
                advancedUiShot(compose.activity, "reader-audit-backward-$mode")
                throw AssertionError("Backward crossing in $mode must expose the previous page's last line", failure)
            }
        }
        assertReaderChrome()
    }

    @Test fun returningFromTheLastPartialScreenRestoresTheScreenJustRead() = withReader(fullNote()) { _, vm ->
        setMode(vm, "buttons", "点击翻页")
        var previous = 0f
        var reachedEnd = false
        for (attempt in 0 until 40) {
            if (readingOffset() >= readingMaximum() - 1f) { reachedEnd = true; break }
            previous = readingOffset()
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .92f, height * .5f)) }
        }
        assertTrue("The current source page must reach its end", reachedEnd)
        compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .08f, height * .5f)) }
        assertEquals("Forward then backward must return to the same screen", previous, readingOffset(), 2f)
    }

    private fun assertSingleVerticalTools() {
        compose.ensureNoteTools()
        compose.onAllNodesWithTag("阅读操作面板").assertCountEquals(1)
        val first = compose.onNodeWithContentDescription("返回笔记列表").getUnclippedBoundsInRoot()
        val second = compose.onNodeWithContentDescription("目录跳转").getUnclippedBoundsInRoot()
        assertTrue("Reading actions must be vertical", second.top >= first.bottom)
        listOf("返回笔记列表", "目录跳转", "笔记内查找", "阅读设置", "设置").forEach {
            compose.onNodeWithContentDescription(it).performScrollTo().assertIsDisplayed().assertIsEnabled()
        }
        compose.onNodeWithContentDescription("关闭阅读工具").performClick()
        assertReaderChrome()
    }

    @Test fun continuousGesturesShowEverySourceParagraphThroughTheLastLine() = withReader(fullNote()) { _, vm ->
        assertReaderChrome()
        val seen = mutableSetOf<String>()
        for (attempt in 0 until 45) {
            seen += visibleMarkers(markers)
            if (attempt % 5 == 0) {
                val nativeLayouts = nativeSnapshot { views -> views.filter { it.isShown }.map { "view=${it.width}x${it.height}, textLayout=${it.layout?.width}x${it.layout?.height}, alpha=${it.alpha}" } }
                android.util.Log.i("ReaderRegression", "continuous step=$attempt seen=${seen.size} axis=" + compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() + " layouts=$nativeLayouts")
            }
            if (seen.size == markers.size) break
            compose.onNodeWithTag("笔记正文").performTouchInput {
                down(Offset(center.x, height * .8f))
                moveTo(Offset(center.x, height * .25f), 600)
                advanceEventTime(250)
                up()
            }
            compose.waitForIdle()
        }
        advancedUiShot(compose.activity, "sample-reader-continuous-end")
        assertEquals("Scrolling must expose every source marker", markers.toSet(), seen)
        compose.waitUntil(5000) { vm.readingMemory.position(vm.noteReader.id!!)?.index == 2 }
        compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 3 / 3 · 完整笔记已保留"))
        assertReaderChrome()
        assertSingleVerticalTools()
    }

    @Test fun rightEdgeTapsShowEachScreenOfEveryLongSourcePageBeforeAdvancing() = withReader(fullNote()) { app, vm ->
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("点击翻页").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "buttons" }
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.waitUntil(5000) { visibleMarkers(listOf(markers.first())).isNotEmpty() }
        val seen = mutableSetOf<String>()
        for (attempt in 0 until 35) {
            seen += visibleMarkers(markers)
            if (seen.size == markers.size) break
            // The right reading zone is the only forward action used here.
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .92f, height * .5f)) }
            compose.waitForIdle()
        }
        assertEquals("A source transition must never skip its remaining screens", markers.toSet(), seen)
        compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 3 / 3 · 完整笔记已保留"))
        compose.waitUntil(5000) { Vault(app).load().noteReadMode == "buttons" }
        assertReaderChrome()
        advancedUiShot(compose.activity, "sample-reader-click-pages")
        assertSingleVerticalTools()
    }

    @Test fun fontControlsResizeNativeBodyAndPersistTheChosenSize() = withReader(fullNote()) { app, vm ->
        val baseline = nativeSnapshot { views -> views.first { it.text.contains(markers.first()) }.textSize }
        compose.openNoteTool("阅读设置")
        repeat(3) { compose.onNodeWithText("增大字号").performScrollTo().performClick() }
        compose.onNodeWithText("19 sp").assertIsDisplayed()
        compose.waitUntil(5000) { vm.ui.value.data.noteFontSize == 19 && Vault(app).load().noteFontSize == 19 }
        advancedUiShot(compose.activity, "sample-reader-font-settings")
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.waitUntil(5000) { nativeSnapshot { views -> views.any { it.text.contains(markers.first()) && it.textSize > baseline } } }
        val enlarged = nativeSnapshot { views -> views.first { it.text.contains(markers.first()) }.textSize }
        assertEquals("Native text must use the selected sp size", baseline * 19f / 16f, enlarged, 1f)
        compose.openNoteTool("返回笔记列表")
        compose.onNodeWithTag("笔记列表").performScrollToNode(hasText("完整阅读体验"))
        compose.onNodeWithText("完整阅读体验").performClick()
        compose.waitUntil(5000) { nativeSnapshot { views -> views.any { it.text.contains(markers.first()) && kotlin.math.abs(it.textSize - enlarged) < 1f } } }
        assertEquals(19, Vault(app).load().noteFontSize)
        assertReaderChrome()
        assertSingleVerticalTools()
    }

    @Test fun longStickyTitleUsesEllipsisAndNeverMovesTheReadingViewport() {
        val title = "遗传学课堂笔记与染色体结构的详细复习整理".repeat(4)
        withReader(fullNote().copy(title = title)) { _, vm ->
            val before = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            compose.onNodeWithTag("笔记正文").performScrollToIndex(1)
            compose.onNodeWithTag("笔记吸顶标题").assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(hasText(title) and hasTestTag("笔记吸顶标题"))
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(1, layouts.single().lineCount)
            assertTrue("The overflowing title must visibly end with ellipsis", layouts.single().isLineEllipsized(0))
            val bar = compose.onNodeWithTag("应用时间栏").getUnclippedBoundsInRoot()
            val sticky = compose.onNodeWithTag("笔记吸顶标题").getUnclippedBoundsInRoot()
            val clockRow = compose.onNodeWithTag("应用时间行").getUnclippedBoundsInRoot()
            val page = compose.onNodeWithContentDescription("当前阅读页码").getUnclippedBoundsInRoot()
            assertTrue("Sticky title must be below the camera and clock row", sticky.top >= clockRow.bottom && sticky.bottom <= bar.bottom)
            assertTrue("Page counter stays beside the clock", page.top >= clockRow.top && page.bottom <= clockRow.bottom)
            val after = compose.onNodeWithTag("笔记正文").getUnclippedBoundsInRoot()
            assertEquals("Title entrance must not push the body down", before.top.value, after.top.value, .1f)
            assertEquals(before.bottom.value, after.bottom.value, .1f)
            advancedUiShot(compose.activity, "sample-reader-sticky-title")
            compose.onNodeWithTag("笔记正文").performScrollToIndex(0)
            compose.onNodeWithTag("笔记吸顶标题").assertDoesNotExist()
            compose.onNodeWithTag("笔记原始标题").assertTextEquals(title)
            compose.openNoteTool("阅读设置")
            compose.onNodeWithText("点击翻页").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "buttons" }
            compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
            compose.onNodeWithTag("笔记正文").performTouchInput { click(Offset(width * .92f, height * .5f)) }
            compose.onNodeWithTag("笔记吸顶标题").assertDoesNotExist()
        }
    }

    @Test fun horizontalGesturesAlsoVisitEveryScreenBeforeChangingSourcePage() = withReader(fullNote()) { _, vm ->
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("横向滑屏").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "swipe" }
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        compose.waitUntil(5000) { visibleMarkers(listOf(markers.first())).isNotEmpty() }
        val seen = mutableSetOf<String>()
        for (attempt in 0 until 35) {
            seen += visibleMarkers(markers)
            if (seen.size == markers.size) break
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeLeft(durationMillis = 180) }
            compose.waitForIdle()
        }
        assertEquals("Horizontal turns must not skip source content", markers.toSet(), seen)
        compose.onNodeWithTag("笔记吸顶标题").assertDoesNotExist()
        assertReaderChrome()
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("横向滑屏").assertIsSelected()
        advancedUiShot(compose.activity, "sample-reader-swipe-pages")
    }

    @Test fun endCommandsExposeTheFinalLineInContinuousAndPagedModes() = withReader(fullNote()) { _, vm ->
        fun jumpEnd() {
            compose.openNoteTool("整本末尾")
            compose.waitUntil(5000) { visibleMarkers(listOf(markers.last())).isNotEmpty() }
        }
        jumpEnd()
        compose.openNoteTool("本段顶部")
        compose.waitUntil(5000) { visibleMarkers(listOf("来源3段落1标记")).isNotEmpty() }
        compose.openNoteTool("本段底部")
        compose.waitUntil(5000) { visibleMarkers(listOf(markers.last())).isNotEmpty() }
        compose.openNoteTool("阅读设置")
        compose.onNodeWithText("点击翻页").performScrollTo().performClick()
        compose.waitUntil(5000) { vm.ui.value.data.noteReadMode == "buttons" }
        compose.onNodeWithContentDescription("关闭阅读方式与字号").performClick()
        jumpEnd()
        assertReaderChrome()
    }

    @Test fun searchHighlightsNativeTextAndPreviousNextRevealTheActualHitLines() {
        val query = "模板坐标"
        val hits = listOf("定位甲", "定位乙", "定位丙")
        val filler = "先看定义，再理解配对、方向和机制。\n\n".repeat(18)
        val note = Note(title = "命中定位", content = hits.mapIndexed { index, marker -> "## 第 ${index + 1} 页\n\n" + filler + "$marker：$query。\n\n" + filler }.joinToString("\n\n"), source = "合成查找页", model = "fixture")
        withReader(note) { _, _ ->
            compose.openNoteTool("笔记内查找")
            compose.onNodeWithText("查找完整正文").performTextInput(query)
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 3 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("找到 3 处").assertIsDisplayed()
            compose.onNodeWithText("第 1 段").performClick()
            fun assertHit(index: Int) {
                compose.waitUntil(5000) { compose.onAllNodesWithText("$query · ${index + 1}/3").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("$query · ${index + 1}/3").assertIsDisplayed()
                try { compose.waitUntil(5000) { nativeSnapshot { views -> views.any { view ->
                    val text = view.text as? Spanned
                    val markerOffset = view.text.toString().indexOf(hits[index])
                    val wordOffset = if (markerOffset < 0) -1 else view.text.toString().indexOf(query, markerOffset)
                    text != null && wordOffset >= 0 && lineVisible(view, wordOffset) &&
                        text.getSpans(wordOffset, wordOffset + query.length, BackgroundColorSpan::class.java).any { span ->
                            text.getSpanStart(span) == wordOffset && text.getSpanEnd(span) == wordOffset + query.length
                        }
                } } } } catch (failure: Throwable) {
                    advancedUiShot(compose.activity, "reader-search-failure-$index")
                    android.util.Log.e("ReaderRegression", "search hit=$index " + nativeSnapshot { views -> views.map { "${it.width}x${it.height} layout=${it.layout?.width}x${it.layout?.height} text=${it.text.toString().take(24)}" } })
                    throw failure
                }
                compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 ${index + 1} / 3 · 完整笔记已保留"))
            }
            assertHit(0)
            compose.onNodeWithContentDescription("上一处").assertIsNotEnabled()
            compose.onNodeWithContentDescription("下一处").performClick(); assertHit(1)
            compose.onNodeWithContentDescription("下一处").performClick(); assertHit(2)
            compose.onNodeWithContentDescription("下一处").assertIsNotEnabled()
            compose.onNodeWithContentDescription("上一处").performClick(); assertHit(1)
            advancedUiShot(compose.activity, "sample-reader-search-highlight")
            compose.onNodeWithContentDescription("结束查找").performClick()
            compose.onNodeWithTag("查找命中导航").assertDoesNotExist()
            compose.waitUntil(5000) { nativeSnapshot { views -> views.all { view ->
                (view.text as? Spanned)?.getSpans(0, view.text.length, BackgroundColorSpan::class.java).orEmpty().isEmpty()
            } } }
            assertReaderChrome()
            assertSingleVerticalTools()
        }
    }

    @Test fun hiddenLinkAddressesDoNotCountOrPreventRevealingTheLaterVisibleWord() {
        val query = "定位词"
        val note = Note(title = "Markdown命中映射", content = "## 第 1 页\n\n[定位词](https://example.test/定位词)\n\n" +
            "正文填充，需要逐行定位。\n\n".repeat(60) + "最终命中：定位词。", source = "合成链接", model = "fixture")
        withReader(note) { _, _ ->
            compose.openNoteTool("笔记内查找")
            compose.onNodeWithText("查找完整正文").performTextInput(query)
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 2 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("查找结果列表").performScrollToIndex(1)
            compose.onAllNodesWithText("第 1 段").onLast().performClick()
            compose.waitUntil(5000) { "最终命中" in visibleMarkers(listOf("最终命中")) }
            compose.onNodeWithText("$query · 2/2").assertIsDisplayed()
            compose.onNodeWithContentDescription("下一处").assertIsNotEnabled()
        }
    }

    @Test fun aReferenceDefinitionAfterTheTargetStillRevealsTheOnlyVisibleOccurrence() {
        val note = Note(title = "后置引用命中", content = "## 第 1 页\n\n[外链][needle]\n\n" +
            "正文填充，需要逐行定位。\n\n".repeat(60) + "最终命中 needle\n\n[needle]: https://example.test", source = "合成引用", model = "fixture")
        withReader(note) { _, _ ->
            compose.openNoteTool("笔记内查找")
            compose.onNodeWithText("查找完整正文").performTextInput("needle")
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("第 1 段").performClick()
            compose.waitUntil(5000) { "最终命中" in visibleMarkers(listOf("最终命中")) }
            compose.onNodeWithText("needle · 1/1").assertIsDisplayed()
        }
    }
}
