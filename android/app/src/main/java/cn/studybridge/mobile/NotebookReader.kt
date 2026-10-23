package cn.studybridge.mobile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

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
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val configuration = LocalViewConfiguration.current
    val chrome = LocalReadingChrome.current
    var fontSize by remember { mutableIntStateOf(ui.data.noteFontSize) }
    var bodyWidth by remember { mutableIntStateOf(0) }
    var restored by remember(note.id, mode, hash, fontSize, bodyWidth) { mutableStateOf(false) }
    var readyMode by remember(note.id) { mutableStateOf("") }
    var viewport by remember { mutableIntStateOf(1) }
    var bodyTop by remember { mutableFloatStateOf(0f) }
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
    var renderEpoch by remember(note.id) { mutableIntStateOf(0) }
    val renderReady = readyPages[selected] == renderEpoch
    val textViews = remember(note.id, hash) { ReaderTextRegistry() }
    var turning by remember { mutableStateOf(false) }
    var jumpGeneration by remember(note.id) { mutableIntStateOf(0) }
    var settledJump by remember(note.id) { mutableIntStateOf(0) }
    var jumpJob by remember(note.id) { mutableStateOf<Job?>(null) }
    val latestSelected by rememberUpdatedState(selected)
    val latestLabels by rememberUpdatedState(labels)
    val latestContinuous by rememberUpdatedState(continuous)
    val latestRestored by rememberUpdatedState(restored)
    val latestSettled by rememberUpdatedState(settledJump == jumpGeneration && !turning)
    fun rememberPosition() {
        if (latestRestored && latestSettled) {
            val anchor = textViews.capture(bodyTop, bodyTop + viewport)
            val page = anchor?.first ?: latestSelected
            vm.readingMemory.remember(NoteReadingPosition(note.id, page,
                if (latestContinuous) (if (list.firstVisibleItemIndex == 0) 0 else list.firstVisibleItemScrollOffset) else scroll.value,
                latestLabels[page], hash, anchor?.second))
        }
    }
    suspend fun placePage(page: Int, offset: Int = 0, end: Boolean = false, anchor: NoteTextAnchor? = null, title: Boolean = false) {
        if (continuous && title && anchor == null && offset == 0 && !end) {
            list.scrollToItem(0)
            return
        }
        if (continuous) list.scrollToItem(page + 1) else index = page
        // Every visual block reports readiness, including an image's terminal
        // error. Never use an arbitrary delay as a substitute for page layout.
        snapshotFlow { readyPages[page] == renderEpoch }.first { it }
        withFrameNanos { }; withFrameNanos { }
        val view = anchor?.let { textViews.find(page, it.sourceStart) }
        if (view != null && anchor != null) {
            val location = IntArray(2); view.getLocationInWindow(location)
            val line = view.layout.getLineForOffset(anchor.offset.coerceIn(0, view.text.length))
            val delta = location[1] + view.totalPaddingTop - view.scrollY + view.layout.getLineTop(line) - bodyTop - anchor.inset
            if (continuous) list.scrollBy(delta) else scroll.scrollTo((scroll.value + delta.toInt()).coerceIn(0, scroll.maxValue))
        } else if (continuous) {
            val size = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == page + 1 }?.size ?: 0
            val bottomSpace = if (page == segments.lastIndex) with(density) { 72.dp.roundToPx() } else 0
            val targetOffset = if (end) (size - viewport + bottomSpace).coerceAtLeast(0) else offset.coerceAtMost((size - 1).coerceAtLeast(0))
            list.scrollToItem(if (title) 0 else page + 1, targetOffset)
        } else scroll.scrollTo(if (end) scroll.maxValue else offset.coerceIn(0, scroll.maxValue))
    }
    LaunchedEffect(note.id, mode, hash, fontSize, bodyWidth) {
        jumpJob?.cancel(); jumpGeneration++; settledJump = jumpGeneration
        restored = false
        val position = if (readyMode.isEmpty()) initial else vm.readingMemory.position(note.id)
        index = restoredNoteIndex(position, hash, labels)
        renderEpoch++
        val sameContent = position?.contentHash == hash
        placePage(index, if (sameContent) position!!.scroll else 0, anchor = position?.textAnchor?.takeIf { sameContent },
            title = continuous && index == 0 && (position == null || position.scroll == 0))
        readyMode = mode; restored = true
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
        val target = segment.coerceIn(segments.indices)
        val generation = ++jumpGeneration
        jumpJob?.cancel()
        jumpJob = scope.launch {
            try {
            renderEpoch++
            placePage(target, offset, end, anchor, title)
            } finally { if (generation == jumpGeneration) settledJump = generation }
        }
    }
    fun turn(direction: Int) {
        if (turning || !restored || !renderReady || settledJump != jumpGeneration) return
        rememberPosition()
        turning = true
        val generation = ++jumpGeneration
        jumpJob?.cancel()
        jumpJob = scope.launch {
            try {
                if (direction > 0 && scroll.value < scroll.maxValue || direction < 0 && scroll.value > 0) scroll.animateScrollTo(nextReadingScreen(scroll.value, scroll.maxValue, viewport, direction))
                else {
                    val target = (selected + direction).coerceIn(segments.indices)
                    if (target != selected) {
                        renderEpoch++
                        placePage(target, end = direction < 0)
                    }
                }
            } finally { turning = false; if (generation == jumpGeneration) settledJump = generation }
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
        val generation = jumpGeneration
        scope.launch {
        // The segment's final placement owns the first jump. Exact hit reveal
        // follows it, so the segment restore cannot reset the hit back to top.
        snapshotFlow { settledJump == generation || jumpGeneration != generation }.first { it }
        withFrameNanos { }; withFrameNanos { }
        if (generation != jumpGeneration || revision != searchRevision || !view.isAttachedToWindow) return@launch
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
    CompositionLocalProvider(LocalNoteFontSize provides fontSize, LocalNoteSearchQuery provides query, LocalNoteSearchTarget provides target, LocalNoteSearchReveal provides reveal) {
        Column(modifier.fillMaxSize()) {
            if (query.isNotBlank()) Row(Modifier.fillMaxWidth().testTag("查找命中导航"), verticalAlignment = Alignment.CenterVertically) {
                val hitIndex = hits.indexOfFirst { it.offset == searchOffset }.coerceAtLeast(0)
                Text("$query · ${if (hits.isEmpty()) 0 else hitIndex + 1}/${hits.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FeedbackIconButton(onClick = { selectHit(hits[(hitIndex - 1).coerceAtLeast(0)], query) }, enabled = hits.isNotEmpty() && hitIndex > 0) { Icon(Icons.Default.ChevronLeft, "上一处") }
                FeedbackIconButton(onClick = { selectHit(hits[(hitIndex + 1).coerceAtMost(hits.lastIndex)], query) }, enabled = hits.isNotEmpty() && hitIndex < hits.lastIndex) { Icon(Icons.Default.ChevronRight, "下一处") }
                FeedbackIconButton(onClick = { query = ""; searchOffset = -1 }) { Icon(Icons.Default.Close, "结束查找") }
            }
            BoxWithConstraints(Modifier.weight(1f).widthIn(max = 840.dp).fillMaxWidth().align(Alignment.CenterHorizontally).onSizeChanged { viewport = it.height; bodyWidth = it.width }.onGloballyPositioned { bodyTop = it.positionInWindow().y }) {
                // Native text keeps the actual reader width during lazy item
                // premeasurement as well as its later placement on screen.
                val contentWidth = maxWidth
                val bodyModifier = Modifier.fillMaxSize().testTag("笔记正文").then(if (chrome != null) Modifier.nestedScroll(chrome.connection) else Modifier)
                val heading: @Composable () -> Unit = {
                    Text(note.title, Modifier.onSizeChanged { titleHeight = it.height }.testTag("笔记原始标题"), style = MaterialTheme.typography.titleLarge)
                    extraHeader()
                }
                val segment: @Composable (Int) -> Unit = { page ->
                    val part = segments[page]; val raw = part.display(note.content)
                    val shown = if (page == 0) noteTextForDisplay(raw, note.title) else raw
                    val epoch = renderEpoch
                    CompositionLocalProvider(LocalNoteRenderReady provides { ready -> if (epoch == renderEpoch) { if (ready) readyPages[page] = epoch else readyPages.remove(page) }; Unit },
                        LocalNoteTextRegistered provides { view, start -> textViews.register(page, start, view) }, LocalNoteContentWidth provides contentWidth) {
                        IllustratedMarkdown(shown, vm.sourceImages, part.start - part.prefix.length + raw.length - shown.length)
                    }
                }
                if (continuous) LazyColumn(bodyModifier, state = list, verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(bottom = 72.dp)) {
                    item(key = "title") { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { heading() } }
                    itemsIndexed(segments, key = { _, part -> part.start }) { page, _ -> Column(Modifier.fillMaxWidth().testTag("笔记段 $page"), verticalArrangement = Arrangement.spacedBy(14.dp)) { segment(page) } }
                } else Column(bodyModifier.noteScreenSwipe(mode, configuration.touchSlop, configuration.longPressTimeoutMillis, with(density) { 56.dp.toPx() }) { turn(it) }.verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (selected == 0) heading()
                    key(selected) { segment(selected) }
                    Spacer(Modifier.height(72.dp))
                }
                Surface(Modifier.align(Alignment.BottomEnd).padding(8.dp), shape = MaterialTheme.shapes.medium, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.primaryContainer) {
                    FeedbackIconButton(onClick = { tools = true }, modifier = Modifier.size(48.dp).testTag("笔记悬浮工具")) { Icon(Icons.Outlined.Tune, "悬浮阅读工具") }
                }
            }
        }
    }
}

private fun Modifier.noteScreenSwipe(mode: String, slop: Float, longPress: Long, distance: Float, onTurn: (Int) -> Unit): Modifier = composed {
    val currentOnTurn by rememberUpdatedState(onTurn)
    pointerInput(mode, slop, longPress, distance) {
    awaitEachGesture {
        val down = awaitFirstDown(false, PointerEventPass.Initial)
        var horizontal = false; var canceled = false; var dx = 0f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.find { it.id == down.id } ?: break
            if (!change.pressed && change.isConsumed) canceled = true
            val delta = change.position - down.position; dx = delta.x
            if (event.changes.count { it.pressed } > 1 || !horizontal && change.uptimeMillis - down.uptimeMillis >= longPress) canceled = true
            if (!horizontal && abs(delta.y) > slop && abs(delta.y) > abs(delta.x)) canceled = true
            if (!canceled && abs(dx) > slop && abs(dx) > abs(delta.y) * 1.4f) horizontal = true
            if (horizontal && !canceled) change.consume()
            if (!change.pressed) {
                if (!canceled && mode == "swipe" && horizontal && abs(dx) >= distance) currentOnTurn(if (dx < 0) 1 else -1)
                else if (!canceled && mode == "buttons" && delta.getDistance() < slop && change.uptimeMillis - down.uptimeMillis < longPress) {
                    if (down.position.x >= size.width * .75f) { change.consume(); currentOnTurn(1) }
                    else if (down.position.x <= size.width * .25f) { change.consume(); currentOnTurn(-1) }
                }
                break
            }
        } while (true)
    }
}
}
