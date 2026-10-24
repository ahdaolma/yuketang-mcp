package cn.studybridge.mobile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animate
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private class ReadingFrame(val page: Int, val token: Int) {
    val scroll = ScrollState(0)
    val text = ReaderTextRegistry()
    var positioned by mutableStateOf(false)
}

private class ReadingDrag(val generation: Int, val direction: Int) {
    val release = CompletableDeferred<Boolean>()
}

@OptIn(kotlinx.coroutines.FlowPreview::class, ExperimentalFoundationApi::class)
@Composable fun NotebookReader(note: Note, ui: UiState, vm: StudyViewModel, modifier: Modifier = Modifier, extraHeader: @Composable () -> Unit = {}, primaryTools: @Composable (() -> Unit) -> Unit = {}, extraTools: @Composable (() -> Unit) -> Unit = {}, backLabel: String = "返回笔记列表", onBack: () -> Unit) {
    val prepared by produceState<Pair<String, List<NoteReadingSegment>>?>(null, note.content) {
        val result = withContext(Dispatchers.Default) { note.content to noteReadingSegments(note.content) }
        withContext(Dispatchers.Main.immediate) { value = result }
    }
    val segments = prepared?.takeIf { it.first == note.content }?.second ?: run { CircularProgressIndicator(Modifier.size(24.dp)); return }
    val labels = remember(note.content, segments) { segments.mapIndexed { i, part -> readingHeadings(note.content.substring(part.start, minOf(part.end, part.start + 300))).firstOrNull()?.second ?: "第 ${i + 1} 段" } }
    val pageLabels = remember(note.content, segments) { readerPageLabels(note.content, segments) }
    val hash = remember(note.content) { noteContentHash(note.content) }
    val initial = remember(note.id) { vm.readingMemory.position(note.id) }
    val mode = validNoteReadingMode(ui.data.noteReadMode)
    val continuous = mode == "continuous"
    var index by rememberSaveable(note.id) { mutableIntStateOf(restoredNoteIndex(initial, hash, labels)) }
    // Offscreen native TextViews must not be premeasured at zero width: their
    // cached text layouts can outlive the later full-width Compose placement.
    // Keep virtualization, but create native page content only when measured
    // for the viewport rather than through speculative prefetch.
    val prefetch = remember { object : LazyListPrefetchStrategy {
        override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = Unit
        override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = Unit
        override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) = Unit
    } }
    val list = rememberLazyListState(prefetchStrategy = prefetch)
    var frameToken by remember(note.id) { mutableIntStateOf(0) }
    var activeFrame by remember(note.id, hash) { mutableStateOf(ReadingFrame(index, ++frameToken)) }
    var incomingFrame by remember(note.id, hash) { mutableStateOf<ReadingFrame?>(null) }
    val scroll = activeFrame.scroll
    val scope = rememberCoroutineScope()
    val readerContext = LocalContext.current
    val debugMotion = readerContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    fun traceMotion(message: String) { if (debugMotion) android.util.Log.d("ReaderMotion", message) }
    val density = LocalDensity.current
    val configuration = LocalViewConfiguration.current
    val chrome = LocalReadingChrome.current
    var fontSize by remember { mutableIntStateOf(ui.data.noteFontSize) }
    var bodyWidth by remember { mutableIntStateOf(0) }
    var restored by remember(note.id, mode, hash, fontSize, bodyWidth) { mutableStateOf(false) }
    var readyMode by remember(note.id) { mutableStateOf("") }
    var viewport by remember { mutableIntStateOf(1) }
    var bodyTop by remember { mutableFloatStateOf(0f) }
    var bodyLeft by remember { mutableFloatStateOf(0f) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var transitionDirection by remember { mutableIntStateOf(0) }
    var dragSession by remember { mutableStateOf<ReadingDrag?>(null) }
    val selectionController = LocalNoteSelection.current
    var titleHeight by remember { mutableIntStateOf(1) }
    var tools by remember { mutableStateOf(false) }
    var directory by remember { mutableStateOf(false) }
    var preferences by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember(note.id) { mutableStateOf("") }
    var searchOffset by remember(note.id) { mutableIntStateOf(-1) }
    var searchRevision by remember(note.id) { mutableIntStateOf(0) }
    val hits by produceState(emptyList<NoteSearchHit>(), query, note.content) {
        val result = withContext(Dispatchers.Default) { searchNoteContent(note.content, query, segments) }
        withContext(Dispatchers.Main.immediate) { value = result }
    }
    val selected by remember(continuous, restored, segments) { derivedStateOf { if (continuous && restored) (list.firstVisibleItemIndex - 1).coerceIn(segments.indices) else index.coerceIn(segments.indices) } }
    val readyPages = remember(note.id, hash, mode) { mutableStateMapOf<Int, Int>() }
    val readyFrames = remember(note.id, hash, mode) { mutableStateMapOf<Int, Boolean>() }
    var renderEpoch by remember(note.id) { mutableIntStateOf(0) }
    val renderReady = if (continuous) readyPages[selected] == renderEpoch else readyFrames[activeFrame.token] == true
    val continuousText = remember(note.id, hash) { ReaderTextRegistry() }
    val textViews = if (continuous) continuousText else activeFrame.text
    val commands = remember(note.id) { ReaderCommandOwner() }
    val turning = commands.busy
    val jumpGeneration = commands.generation
    val settledJump = commands.settled
    val latestSelected by rememberUpdatedState(selected)
    val latestLabels by rememberUpdatedState(labels)
    val latestContinuous by rememberUpdatedState(continuous)
    val latestRestored by rememberUpdatedState(restored)
    val latestSettled by rememberUpdatedState(settledJump == jumpGeneration && !turning)
    val latestTextViews by rememberUpdatedState(textViews)
    val latestScroll by rememberUpdatedState(scroll)
    fun rememberPosition() {
        if (latestRestored && latestSettled) {
            val anchor = latestTextViews.capture(bodyTop, bodyTop + viewport)
            val page = anchor?.first ?: latestSelected
            vm.readingMemory.remember(NoteReadingPosition(note.id, page,
                if (latestContinuous) (if (list.firstVisibleItemIndex == 0) 0 else list.firstVisibleItemScrollOffset) else latestScroll.value,
                latestLabels[page], hash, anchor?.second))
        }
    }
    // Readiness, restoration and motion have one cancellable owner.
    fun discardIncoming() {
        incomingFrame = null
        readyFrames.keys.retainAll(setOf(activeFrame.token))
    }
    fun operate(block: suspend (Int) -> Unit) {
        traceMotion("replace gen=${commands.generation} mode=$mode page=${activeFrame.page}")
        discardIncoming(); dragOffset = 0f; transitionDirection = 0; dragSession = null
        commands.replace(scope, onSettled = { traceMotion("settle gen=${commands.generation} page=${activeFrame.page}"); discardIncoming(); dragOffset = 0f; dragSession = null }, block = block)
    }
    suspend fun preparePage(page: Int, offset: Int = 0, end: Boolean = false, anchor: NoteTextAnchor? = null, title: Boolean = false): ReadingFrame? {
        val destination = if (continuous) null else ReadingFrame(page, ++frameToken).also { incomingFrame = it }
        traceMotion("prepare gen=${commands.generation} page=$page frame=${destination?.token} offset=$offset end=$end width=$bodyWidth")
        if (continuous && title && anchor == null && offset == 0 && !end) { list.scrollToItem(0); return null }
        if (continuous) list.scrollToItem(page + 1)
        // Both frames are measured at the real viewport width. Incoming text has
        // its own registry and cannot become the saved outgoing reading anchor.
        snapshotFlow { if (destination == null) readyPages[page] == renderEpoch else readyFrames[destination.token] == true }.first { it }
        traceMotion("ready gen=${commands.generation} page=$page frame=${destination?.token}")
        withFrameNanos { }; withFrameNanos { }
        val registry = destination?.text ?: continuousText
        val destinationScroll = destination?.scroll
        val view = anchor?.let { registry.find(page, it.sourceStart) }
        if (view != null && anchor != null) {
            val location = IntArray(2); view.getLocationInWindow(location)
            val line = view.layout.getLineForOffset(anchor.offset.coerceIn(0, view.text.length))
            val delta = location[1] + view.totalPaddingTop - view.scrollY + view.layout.getLineTop(line) - bodyTop - anchor.inset
            if (destinationScroll == null) list.scrollBy(delta) else destinationScroll.scrollTo((destinationScroll.value + delta.toInt()).coerceIn(0, destinationScroll.maxValue))
        } else if (destinationScroll == null) {
            val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page + 1 }?.size ?: 0
            val bottomSpace = if (page == segments.lastIndex) with(density) { 72.dp.roundToPx() } else 0
            val targetOffset = if (end) (size - viewport + bottomSpace).coerceAtLeast(0) else offset.coerceAtMost((size - 1).coerceAtLeast(0))
            list.scrollToItem(if (title) 0 else page + 1, targetOffset)
        } else destinationScroll.scrollTo(if (end) destinationScroll.maxValue else offset.coerceIn(0, destinationScroll.maxValue))
        destination?.positioned = true
        traceMotion("positioned gen=${commands.generation} page=$page frame=${destination?.token} scroll=${destinationScroll?.value}/${destinationScroll?.maxValue}")
        return destination
    }
    suspend fun commitPage(destination: ReadingFrame?, direction: Int = 0) {
        if (destination != null) {
            if (direction != 0) {
                animate(dragOffset, -direction * bodyWidth.toFloat(), animationSpec = tween(240)) { value, _ -> dragOffset = value }
            }
            // Commit with no suspension after the final animation frame.
            activeFrame = destination; index = destination.page; incomingFrame = null; dragOffset = 0f
            traceMotion("commit gen=${commands.generation} page=${destination.page} frame=${destination.token}")
            readyFrames.keys.retainAll(setOf(destination.token))
        }
    }
    suspend fun placePage(page: Int, offset: Int = 0, end: Boolean = false, anchor: NoteTextAnchor? = null, title: Boolean = false,
        direction: Int = 0) {
        transitionDirection = direction
        commitPage(preparePage(page, offset, end, anchor, title), direction)
    }
    LaunchedEffect(note.id, mode, hash, fontSize, bodyWidth) {
        if (bodyWidth <= 0 || viewport <= 1) return@LaunchedEffect
        val position = if (readyMode.isEmpty()) initial else vm.readingMemory.position(note.id)
        restored = false
        operate {
            renderEpoch++
            val page = restoredNoteIndex(position, hash, labels)
            val sameContent = position?.contentHash == hash
            placePage(page, if (sameContent) position!!.scroll else 0, anchor = position?.textAnchor?.takeIf { sameContent },
                title = continuous && page == 0 && (position == null || position.scroll == 0))
            if (continuous) index = page
            readyMode = mode; restored = true
        }
    }
    LaunchedEffect(selected, restored, hash, mode, settledJump, turning) {
        if (restored) snapshotFlow { if (continuous) list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset else selected to scroll.value }.debounce(180).collect { rememberPosition() }
    }
    DisposableEffect(note.id, hash, mode) { onDispose { rememberPosition() } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, note.id, hash) { withContext(Dispatchers.Main.immediate) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) rememberPosition() }
        lifecycle.addObserver(observer); try { awaitCancellation() } finally { lifecycle.removeObserver(observer) }
    } }
    LaunchedEffect(ui.data.noteFontSize) { fontSize = ui.data.noteFontSize }
    fun goTo(segment: Int, offset: Int = 0, end: Boolean = false, anchor: NoteTextAnchor? = null, title: Boolean = false) {
        rememberPosition()
        operate {
            renderEpoch++
            placePage(segment.coerceIn(segments.indices), offset, end, anchor, title)
            readyMode = mode; restored = true
        }
    }
    fun canTurn() = !continuous && !commands.busy && restored && renderReady && commands.settled == commands.generation
    fun turn(direction: Int) {
        if (!canTurn()) return
        rememberPosition()
        val current = activeFrame
        val nextOffset = nextReadingScreen(current.scroll.value, current.scroll.maxValue, viewport, direction)
        val nextPage = if (nextOffset != current.scroll.value) selected else (selected + direction).coerceIn(segments.indices)
        operate {
            if (nextPage == selected && nextOffset == current.scroll.value) {
                // A tap at the document boundary has no destination.
            } else {
                placePage(nextPage, offset = if (nextPage == selected) nextOffset else 0,
                    end = nextPage != selected && direction < 0, direction = direction)
            }
        }
    }
    fun beginDrag(displacement: Float): Int? {
        traceMotion("begin dx=$displacement mode=$mode gen=${commands.generation}/${commands.settled} busy=${commands.busy} restored=$restored ready=$renderReady selection=${selectionController?.selection != null}")
        if (!canTurn() || selectionController?.selection != null) return null
        rememberPosition()
        val direction = if (displacement < 0) 1 else -1
        val current = activeFrame
        val nextOffset = nextReadingScreen(current.scroll.value, current.scroll.maxValue, viewport, direction)
        val nextPage = if (nextOffset != current.scroll.value) selected else (selected + direction).coerceIn(segments.indices)
        val hasDestination = nextOffset != current.scroll.value || nextPage != selected
        var generation: Int? = null
        operate { command ->
            generation = command
            traceMotion("drag-owner gen=$command target=$nextPage offset=$nextOffset hasTarget=$hasDestination")
            val session = ReadingDrag(command, direction)
            dragSession = session; transitionDirection = direction
            dragOffset = displacement.coerceIn(-bodyWidth.toFloat(), bodyWidth.toFloat())
            coroutineScope {
                // Start once at horizontal recognition, including the real-width
                // layout wait; moves only update translation of these two frames.
                val prepared = if (hasDestination) async(start = CoroutineStart.UNDISPATCHED) {
                    preparePage(nextPage, offset = if (nextPage == current.page) nextOffset else 0,
                        end = nextPage != current.page && direction < 0)
                } else null
                if (session.release.await() && prepared != null) commitPage(prepared.await(), direction)
                else {
                    prepared?.cancelAndJoin()
                    animate(dragOffset, 0f, animationSpec = tween(180)) { value, _ -> dragOffset = value }
                }
            }
        }
        return generation
    }
    fun moveDrag(generation: Int, displacement: Float) {
        val session = dragSession?.takeIf { it.generation == generation && commands.generation == generation } ?: return
        dragOffset = if (session.direction > 0) displacement.coerceIn(-bodyWidth.toFloat(), 0f) else displacement.coerceIn(0f, bodyWidth.toFloat())
    }
    fun releaseDrag(generation: Int, displacement: Float, commit: Boolean) {
        traceMotion("release gen=$generation current=${commands.generation} session=${dragSession?.generation} dx=$displacement commit=$commit")
        val session = dragSession?.takeIf { it.generation == generation && commands.generation == generation } ?: return
        moveDrag(generation, displacement)
        session.release.complete(commit && displacement * session.direction < 0)
    }
    fun cancelDrag(generation: Int) {
        traceMotion("cancel gen=$generation current=${commands.generation} session=${dragSession?.generation}")
        if (dragSession?.generation != generation) return
        commands.cancel(generation) {
            discardIncoming(); dragOffset = 0f; dragSession = null
        }
    }
    fun selectHit(hit: NoteSearchHit, word: String) { query = word; searchOffset = hit.offset; searchRevision++; searching = false; goTo(hit.segment) }
    var revealedSearchRevision by remember(note.id) { mutableIntStateOf(0) }
    val target = if (searchOffset >= 0 && query.isNotBlank() && revealedSearchRevision != searchRevision) NoteSearchTarget(searchOffset, query, searchRevision) else null
    val reportProgress = LocalReaderProgress.current
    val sticky by remember { derivedStateOf { list.firstVisibleItemIndex > 0 || list.firstVisibleItemScrollOffset > titleHeight } }
    val progress = ReaderProgress(pageLabels[selected], "阅读分段 ${selected + 1} / ${segments.size} · 完整笔记已保留", note.title.takeIf { continuous }, continuous && restored && sticky)
    SideEffect { reportProgress(progress) }
    DisposableEffect(reportProgress) { onDispose { reportProgress(null) } }
    val revealInset = with(density) { 24.dp.toPx() }
    val reveal: (android.widget.TextView, Int, Int) -> Unit = { view, offset, revision ->
        val generation = commands.generation
        scope.launch {
        // The segment's final placement owns the first jump. Exact hit reveal
        // follows it, so the segment restore cannot reset the hit back to top.
        snapshotFlow { commands.settled == generation || commands.generation != generation }.first { it }
        withFrameNanos { }; withFrameNanos { }
        if (generation != commands.generation || revision != searchRevision || !view.isAttachedToWindow) return@launch
        view.layout?.let { layout ->
            val position = IntArray(2); view.getLocationInWindow(position)
            val delta = position[1] + layout.getLineTop(layout.getLineForOffset(offset.coerceAtMost(view.text.length))) - bodyTop - revealInset
            if (continuous) list.scrollBy(delta) else scroll.scrollTo((scroll.value + delta.toInt()).coerceIn(0, scroll.maxValue))
            revealedSearchRevision = revision
        }
    } }
    if (searching) NoteSearchDialog(note, { searching = false }, onHit = ::selectHit) { goTo(it); searching = false }
    if (directory) JumpDialog("笔记目录 / 阅读分段", labels, { directory = false }) { goTo(it); directory = false }
    if (tools) ReadingActionSheet("阅读工具", { tools = false }) {
        ReadingActionGroup("阅读")
        FeedbackDropdownMenuItem(text = { Text(backLabel) }, leadingIcon = { Icon(Icons.Default.ArrowBack, null) }, modifier = Modifier.semantics { contentDescription = backLabel }, onClick = { tools = false; onBack() })
        FeedbackDropdownMenuItem(text = { Text("笔记内查找") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.semantics { contentDescription = "笔记内查找" }, onClick = { tools = false; searching = true })
        FeedbackDropdownMenuItem(text = { Text("目录 · ${selected + 1}/${segments.size}") }, leadingIcon = { Icon(Icons.Outlined.MenuBook, null) }, modifier = Modifier.semantics { contentDescription = "目录跳转"; stateDescription = "阅读分段 ${selected + 1} / ${segments.size} · 完整笔记已保留" }, onClick = { tools = false; directory = true })
        FeedbackDropdownMenuItem(text = { Text("阅读方式与字号") }, leadingIcon = { Icon(Icons.Outlined.TextFields, null) }, modifier = Modifier.semantics { contentDescription = "阅读设置" }, onClick = { tools = false; preferences = true })
        primaryTools { tools = false }
        ReadingActionGroup("阅读位置")
        if (initial != null) FeedbackDropdownMenuItem(text = { Text("回到上次阅读") }, onClick = { tools = false; goTo(restoredNoteIndex(initial, hash, labels), if (initial.contentHash == hash) initial.scroll else 0, anchor = initial.textAnchor?.takeIf { initial.contentHash == hash }) })
        if (segments.size > 1) {
            FeedbackDropdownMenuItem(text = { Text("上一段") }, enabled = selected > 0, onClick = { tools = false; goTo(selected - 1) })
            FeedbackDropdownMenuItem(text = { Text("下一段") }, enabled = selected < segments.lastIndex, onClick = { tools = false; goTo(selected + 1) })
        }
        FeedbackDropdownMenuItem(text = { Text("本段顶部") }, onClick = { tools = false; goTo(selected) })
        FeedbackDropdownMenuItem(text = { Text("本段底部") }, onClick = { tools = false; goTo(selected, end = true) })
        FeedbackDropdownMenuItem(text = { Text("整本开头") }, onClick = { tools = false; goTo(0, title = continuous) })
        FeedbackDropdownMenuItem(text = { Text("整本末尾") }, onClick = { tools = false; goTo(segments.lastIndex, end = true) })
        extraTools { tools = false }
        ReadingActionGroup("应用")
        FeedbackDropdownMenuItem(text = { Text("设置") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) }, modifier = Modifier.semantics { contentDescription = "设置" }, onClick = { tools = false; vm.tab(3) })
    }
    if (preferences) ReadingActionSheet("阅读方式与字号", { preferences = false }) {
        NOTE_READING_MODES.forEach { (id, title) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(mode == id, role = Role.RadioButton, onClick = { rememberPosition(); vm.updateNotebookState { it.copy(noteReadMode = id) } }), verticalAlignment = Alignment.CenterVertically) { RadioButton(mode == id, null); Text(title) }
        }
        Text("点击翻页：点正文右侧前进一屏，左侧返回一屏。横滑：向左前进，向右返回。先看完当前页每一屏，再进入下一页。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FeedbackTextButton(onClick = { rememberPosition(); fontSize = (fontSize - 1).coerceAtLeast(12); vm.updateNotebookState { it.copy(noteFontSize = fontSize) } }, enabled = fontSize > 12) { Text("减小字号") }
            Text("$fontSize sp", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            FeedbackTextButton(onClick = { rememberPosition(); fontSize = (fontSize + 1).coerceAtMost(28); vm.updateNotebookState { it.copy(noteFontSize = fontSize) } }, enabled = fontSize < 28) { Text("增大字号") }
        }
        Text("字号预览：理解概念、联系课件、整理重点。", Modifier.padding(12.dp), fontSize = fontSize.sp)
        FeedbackTextButton(onClick = { rememberPosition(); fontSize = 16; vm.updateNotebookState { it.copy(noteFontSize = 16) } }) { Text("恢复默认字号") }
    }
    val pageTap: ((Float) -> Unit)? = if (mode == "buttons") { x -> if (selectionController?.selection == null) turn(if (x - bodyLeft >= bodyWidth / 2f) 1 else -1) } else null
    CompositionLocalProvider(LocalNoteFontSize provides fontSize, LocalNoteSearchQuery provides query, LocalNoteSearchTarget provides target, LocalNoteSearchReveal provides reveal, LocalNotePageTap provides pageTap) {
        Column(modifier.fillMaxSize()) {
            if (query.isNotBlank()) Row(Modifier.fillMaxWidth().testTag("查找命中导航"), verticalAlignment = Alignment.CenterVertically) {
                val hitIndex = noteSearchHitIndex(hits, searchOffset).coerceAtLeast(0)
                Text("$query · ${if (hits.isEmpty()) 0 else hitIndex + 1}/${hits.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FeedbackIconButton(onClick = { selectHit(hits[(hitIndex - 1).coerceAtLeast(0)], query) }, enabled = hits.isNotEmpty() && hitIndex > 0) { Icon(Icons.Default.ChevronLeft, "上一处") }
                FeedbackIconButton(onClick = { selectHit(hits[(hitIndex + 1).coerceAtMost(hits.lastIndex)], query) }, enabled = hits.isNotEmpty() && hitIndex < hits.lastIndex) { Icon(Icons.Default.ChevronRight, "下一处") }
                FeedbackIconButton(onClick = { query = ""; searchOffset = -1 }) { Icon(Icons.Default.Close, "结束查找") }
            }
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally).onSizeChanged { viewport = it.height; bodyWidth = it.width }.onGloballyPositioned { bodyTop = it.positionInWindow().y; bodyLeft = it.positionInWindow().x }) {
                // Native text keeps the actual reader width during lazy item
                // premeasurement as well as its later placement on screen.
                val contentWidth = maxWidth
                val bodyModifier = Modifier.fillMaxSize().testTag("笔记正文").then(if (chrome != null) Modifier.nestedScroll(chrome.connection) else Modifier)
                val heading: @Composable () -> Unit = {
                    Text(note.title, Modifier.onSizeChanged { titleHeight = it.height }.testTag("笔记原始标题"), style = MaterialTheme.typography.titleLarge)
                    extraHeader()
                }
                val segment: @Composable (Int, ReadingFrame?) -> Unit = { page, frame ->
                    val part = segments[page]; val raw = part.display(note.content)
                    val shown = if (page == 0) noteTextForDisplay(raw, note.title) else raw
                    val epoch = renderEpoch
                    CompositionLocalProvider(LocalNoteRenderReady provides { ready ->
                        if (frame != null) { if (frame === activeFrame || frame === incomingFrame) readyFrames[frame.token] = ready }
                        else if (epoch == renderEpoch) { if (ready) readyPages[page] = epoch else readyPages.remove(page) }; Unit },
                        LocalNoteTextRegistered provides { view, start -> (frame?.text ?: continuousText).register(page, start, view) }, LocalNoteContentWidth provides contentWidth,
                        LocalNoteSearchTarget provides (if (frame == null || frame === activeFrame) target else null),
                        LocalNotePageTap provides (if (frame == null || frame === activeFrame) pageTap else null)) {
                        IllustratedMarkdown(shown, vm.sourceImages, part.start - part.prefix.length + raw.length - shown.length, part.sourceForDisplay(note.content, raw.length - shown.length))
                    }
                }
                if (continuous) LazyColumn(bodyModifier, state = list, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 72.dp)) {
                    item(key = "title") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { heading() } }
                    itemsIndexed(segments, key = { _, part -> part.start }) { page, _ -> Column(Modifier.fillMaxWidth().testTag("笔记段 $page"), verticalArrangement = Arrangement.spacedBy(14.dp)) { segment(page, null) } }
                } else Box(bodyModifier.clipToBounds().semantics {
                    verticalScrollAxisRange = ScrollAxisRange({ activeFrame.scroll.value.toFloat() }, { activeFrame.scroll.maxValue.toFloat() })
                }.noteScreenSwipe(mode, configuration.touchSlop, configuration.longPressTimeoutMillis, with(density) { 56.dp.toPx() },
                    enabled = { (canTurn() && selectionController?.selection == null).also {
                        traceMotion("gate allowed=$it mode=$mode gen=${commands.generation}/${commands.settled} busy=${commands.busy} restored=$restored ready=$renderReady selection=${selectionController?.selection != null}")
                    } }, trace = ::traceMotion,
                    onBegin = ::beginDrag, onDrag = ::moveDrag,
                    onRelease = ::releaseDrag, onCancel = ::cancelDrag, onTurn = { turn(it) })) {
                    // Stable keys preserve the incoming native view when it
                    // becomes active; neither viewport height nor text reflows.
                    listOfNotNull(activeFrame, incomingFrame).forEach { frame -> key(frame.token) {
                        val active = frame === activeFrame
                        Column(Modifier.fillMaxSize().graphicsLayer {
                            // Keep the outgoing page intact until the real
                            // destination layout and its reading offset exist.
                            val displacement = if (incomingFrame?.positioned == false) 0f else dragOffset
                            translationX = if (active) displacement else transitionDirection * bodyWidth.toFloat() + displacement
                            alpha = if (active || transitionDirection != 0 && frame.positioned) 1f else 0f
                        }.verticalScroll(frame.scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (frame.page == 0) heading()
                            segment(frame.page, frame)
                            Spacer(Modifier.height(72.dp))
                        }
                    } }
                }
                Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = MaterialTheme.shapes.medium, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.primaryContainer) {
                    FeedbackIconButton(onClick = { tools = true }, modifier = Modifier.size(48.dp).testTag("笔记悬浮工具")) { Icon(Icons.Outlined.Tune, "悬浮阅读工具") }
                }
            }
        }
    }
}

private fun Modifier.noteScreenSwipe(mode: String, slop: Float, longPress: Long, distance: Float,
    trace: (String) -> Unit,
    enabled: () -> Boolean, onBegin: (Float) -> Int?, onDrag: (Int, Float) -> Unit,
    onRelease: (Int, Float, Boolean) -> Unit, onCancel: (Int) -> Unit, onTurn: (Int) -> Unit): Modifier = composed {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentTrace by rememberUpdatedState(trace)
    val currentOnBegin by rememberUpdatedState(onBegin)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val currentOnTurn by rememberUpdatedState(onTurn)
    pointerInput(mode, slop, longPress, distance) {
        awaitEachGesture {
            val down = awaitFirstDown(false, PointerEventPass.Initial)
            var owner: Int? = null
            var canceled = !currentEnabled()
            currentTrace("DOWN mode=$mode enabled=${!canceled} consumed=${down.isConsumed} pos=${down.position} slop=$slop")
            var dx = 0f
            var moves = 0
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.find { it.id == down.id } ?: break
                    val delta = change.position - down.position
                    dx = delta.x
                    if (moves++ < 2 || !change.pressed) currentTrace("event mode=$mode pressed=${change.pressed} dx=$dx dy=${delta.y} consumed=${change.isConsumed} age=${change.uptimeMillis - down.uptimeMillis} owner=$owner canceled=$canceled")
                    if (event.changes.count { it.pressed } > 1 || owner == null && change.uptimeMillis - down.uptimeMillis >= longPress) canceled = true
                    if (owner == null && abs(delta.y) > slop && abs(delta.y) > abs(delta.x)) canceled = true
                    if (!canceled && owner == null && abs(dx) > slop && abs(dx) > abs(delta.y) * 1.4f) {
                        owner = currentOnBegin(dx)
                        currentTrace("recognized mode=$mode owner=$owner dx=$dx")
                        if (owner == null) canceled = true
                    }
                    val movingOwner = owner
                    if (movingOwner != null && !canceled) {
                        change.consume()
                        currentOnDrag(movingOwner, dx)
                    }
                    // Child clicks decide ownership before an unconsumed gap.
                    val final = awaitPointerEvent(PointerEventPass.Final).changes.firstOrNull { it.id == down.id }
                    if (!change.pressed) {
                        val releasedOwner = owner
                        owner = null // finally must not release/cancel it twice.
                        if (releasedOwner != null) currentOnRelease(releasedOwner, dx, !canceled && abs(dx) >= distance)
                        else if (!canceled && mode == "buttons" && final?.isConsumed == false && delta.getDistance() < slop) {
                            currentOnTurn(if (down.position.x >= size.width / 2f) 1 else -1)
                        }
                        break
                    }
                    if (canceled) {
                        val canceledOwner = owner
                        owner = null
                        canceledOwner?.let { currentOnCancel(it) }
                    }
                } while (true)
            } finally {
                currentTrace("pointer-finally mode=$mode owner=$owner dx=$dx canceled=$canceled")
                // ACTION_CANCEL, a missing pointer and pointerInput replacement
                // all reset the gesture's owner; a newer command ignores its ID.
                owner?.let { currentOnCancel(it) }
            }
        }
    }
}
