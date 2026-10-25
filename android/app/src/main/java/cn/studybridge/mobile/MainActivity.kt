package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.widget.TextView
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import cn.studybridge.mobile.StudyModalBottomSheet as ModalBottomSheet
import cn.studybridge.mobile.StudyDropdownMenu as DropdownMenu
import cn.studybridge.mobile.FeedbackButton as Button
import cn.studybridge.mobile.FeedbackOutlinedButton as OutlinedButton
import cn.studybridge.mobile.FeedbackTextButton as TextButton
import cn.studybridge.mobile.FeedbackFilledTonalButton as FilledTonalButton
import cn.studybridge.mobile.FeedbackIconButton as IconButton
import cn.studybridge.mobile.FeedbackFilledIconButton as FilledIconButton
import cn.studybridge.mobile.FeedbackFilterChip as FilterChip
import cn.studybridge.mobile.FeedbackSwitch as Switch
import cn.studybridge.mobile.FeedbackDropdownMenuItem as DropdownMenuItem
import cn.studybridge.mobile.FeedbackNavigationBarItem as NavigationBarItem
import cn.studybridge.mobile.FeedbackCard as Card
import cn.studybridge.mobile.FeedbackOutlinedCard as OutlinedCard

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.core.MarkwonTheme
import androidx.compose.ui.platform.LocalDensity
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudyTheme { StudyApp((application as StudyApplication).studyViewModel) }
        }
    }
    override fun onResume() {
        super.onResume()
        ClassFollowService.refreshPermission(this)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudyApp(vm: StudyViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as? StudyApplication
    val followState = app?.classFollowStatus ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ClassFollowStatus()) }
    val follow by followState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.background() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activity = androidx.activity.compose.LocalActivity.current
    DisposableEffect(ui.classUi.running, ui.classUi.paused, activity) {
        if (ui.classUi.running && !ui.classUi.paused) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var previousTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.tab) { if (ui.tab != 3) previousTab = ui.tab }
    BackHandler(enabled = ui.tab == 3) { if (ui.settingsPage.isNotEmpty()) vm.settingsPage("") else vm.tab(previousTab) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.notice) {
        if (ui.notice.isNotBlank()) {
            snackbar.currentSnackbarData?.dismiss()
            val message = ui.notice
            snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
            if (vm.ui.value.notice == message) vm.notice("")
        }
    }
    val unavailable = when {
        ui.storageError.isNotBlank() -> "本机记录不可写，已保留原数据；请先排查解密问题。"
        !ui.ready -> "正在读取本机记录，请稍候。"
        ui.chatgptLoginActive -> "正在等待账号授权，请先完成或取消本次授权。"
        ui.busy -> "正在${ui.operation}，请稍候或点击停止。"
        ui.classUi.running && !ui.classUi.paused -> "上课模式正在运行，请先暂停再操作。"
        else -> "请先准备所需课件或完成配置。"
    }
    val controlFeedback = remember(vm, unavailable) { ControlFeedback(vm::notice, unavailable) }
    CompositionLocalProvider(LocalControlFeedback provides controlFeedback) {
    ClassManagerHost(ui, vm) {
    StudyFrame(ui, onTab = { target -> if (target == 3) previousTab = ui.tab; vm.tab(target) }, onBack = { if (ui.tab == 2) vm.noteReader.closeRequested = true else if (ui.tab == 3 && ui.settingsPage.isNotEmpty()) vm.settingsPage("") else vm.tab(previousTab) }, onStop = vm::stop, snackbar = snackbar, noteOpen = ui.tab == 2 && ui.data.notes.any { it.id == vm.noteReader.id }, onClassToggle = { if (ui.classUi.paused) vm.resumeClass() else vm.pauseClass() }, classServiceRunning = follow.serviceRunning) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (ui.storageError.isNotBlank()) Text(ui.storageError, Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            StudyOperationProgress(ui)
            if (!ui.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else StudyPageContent(ui, vm)
        }
    }
    LearningMaterialsHost(ui, vm)
    }
    }
}

@Composable fun StudyPageContent(ui: UiState, vm: StudyViewModel) {
    // Keep a single live page. Retaining the outgoing page while StudyFrame
    // already uses the destination's chrome causes overlap and stale controls.
    val opacity = remember(ui.tab) { Animatable(0f) }
    LaunchedEffect(opacity) { opacity.animateTo(1f, tween(160)) }
    Box(Modifier.fillMaxSize().clipToBounds().graphicsLayer { alpha = opacity.value }) {
        key(ui.tab) {
            when (ui.tab) { 0 -> ChatScreen(ui, vm); 1 -> RainScreen(ui, vm); 2 -> NotesScreen(ui, vm); else -> ModelScreen(ui, vm) }
        }
    }
}

@Composable fun Markdown(text: String, sourceOffset: Int? = null, annotationSource: NoteSelectionBlock? = sourceOffset?.let { NoteSelectionBlock(text, it) }, syntheticPrefixLength: Int = 0) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val color = colors.onSurface.toArgb()
    val link = colors.primary.toArgb()
    val code = colors.surfaceContainerHigh.toArgb()
    val line = colors.outlineVariant.toArgb()
    val row = colors.surfaceContainer.toArgb()
    val background = colors.background.toArgb()
    val density = LocalDensity.current.density
    val bodySize = with(LocalDensity.current) { LocalNoteFontSize.current.sp.toPx() }
    val renderer = remember(context, color, link, code, line, row, background, density) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create { builder -> builder.tableBorderColor(line).tableBorderWidth(maxOf(1, (density * 0.5f).toInt())).tableCellPadding((8 * density).toInt()).tableHeaderRowBackgroundColor(code).tableOddRowBackgroundColor(row).tableEvenRowBackgroundColor(background) })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(link).codeTextColor(color).codeBlockTextColor(color).codeBackgroundColor(code).codeBlockBackgroundColor(code).blockQuoteColor(link).blockQuoteWidth((2 * density).toInt()).headingBreakHeight(0).thematicBreakColor(line).listItemColor(link).headingTextSizeMultipliers(floatArrayOf(1.28f, 1.16f, 1.06f, 1f, 1f, 1f))
                }
            }).build()
    }
    val dark = colors.background.luminance() < 0.5f
    val personalMarks = LocalNoteAnnotations.current
    val annotationContent = LocalNoteAnnotationContent.current
    val stableMarkdown = LocalNoteStableMarkdown.current
    val syntax = LocalReaderMarkdownParser.current
    val contentWidth = LocalNoteContentWidth.current
    val readerWidth = contentWidth?.let { with(LocalDensity.current) { it.roundToPx() } }
    val toMarkdown = { raw: String ->
        val source = markdownForStudyMarks(raw)
        if (syntax == null) renderer.toMarkdown(source) else renderer.render(syntax.parse(source)).let {
            if (it.isEmpty() && source.isNotEmpty()) android.text.SpannableStringBuilder(source) else it
        }
    }
    val format = {
        synchronized(renderer) {
            val began = System.nanoTime()
            val rendered = toMarkdown(text)
            val renderedAt = System.nanoTime()
            val body = renderStudyMarks(rendered, density, dark)
            val markedAt = System.nanoTime()
            // TablePlugin owns mutable visitor state. Render prefix context in
            // the same worker sequence, never concurrently on the UI thread.
            val prefix = if (syntheticPrefixLength == 0) "" else toMarkdown(text.take(syntheticPrefixLength)).toString().trimEnd('\r', '\n')
            val annotationStart = if (body.toString().startsWith(prefix)) prefix.length else Int.MAX_VALUE
            val decorated = renderPersonalAnnotations(body, annotationSource?.source.orEmpty(), annotationSource?.sourceStart, personalMarks, annotationContent, annotationStart, text.take(syntheticPrefixLength)) { raw ->
                renderStudyMarks(toMarkdown(raw), density, dark).toString()
            }
            if (stableMarkdown && context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                android.util.Log.d("ReaderStage", "chars=${text.length} renderNs=${renderedAt - began} marksNs=${markedAt - renderedAt} annotationsNs=${System.nanoTime() - markedAt}")
            Triple(text, decorated, annotationStart)
        }
    }
    val formatted = if (stableMarkdown) {
        // Create final spans before the native view's first measurement. This
        // remains virtualized: only the visible bounded reader segments render.
        // Do not cache mutable TableRowSpan / TextViewSpan across native views.
        remember(text, renderer, density, dark, bodySize, contentWidth, syntheticPrefixLength, personalMarks, annotationContent, annotationSource) {
            val began = System.nanoTime()
            format().also {
                if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
                    android.util.Log.d("ReaderFormat", "chars=${text.length} ns=${System.nanoTime() - began}")
            }
        }
    } else {
        val result by produceState<Triple<String, android.text.Spanned, Int>?>(null, text, renderer, density, dark, syntheticPrefixLength, personalMarks, annotationContent, annotationSource) {
            val parsed = withContext(Dispatchers.Default) { format() }
            // AndroidView updates stay on Main even under a test frame clock.
            withContext(Dispatchers.Main.immediate) { value = parsed }
        }
        result
    }
    val selectionController = LocalNoteSelection.current
    val searchQuery = LocalNoteSearchQuery.current
    val searchTarget = LocalNoteSearchTarget.current
    val revealSearch = LocalNoteSearchReveal.current
    val renderReady = LocalNoteRenderReady.current
    val registerText = LocalNoteTextRegistered.current
    val onPageTap = LocalNotePageTap.current
    val annotationBlock = remember(formatted, text, annotationSource, syntheticPrefixLength) {
        annotationSource?.copy(annotationStart = formatted?.takeIf { it.first == text }?.third ?: Int.MAX_VALUE, renderPrefix = text.take(syntheticPrefixLength))
    }
    val decorated = remember(formatted, text, searchQuery) { formatted?.takeIf { it.first == text }?.second?.let { searchHighlight(it, searchQuery) as android.text.Spanned } }
    val locatedSearch by produceState<Pair<NoteSearchTarget, Int>?>(null, searchTarget, formatted, text, sourceOffset, renderer, density, dark) {
        value = null
        val target = searchTarget ?: return@produceState
        val start = sourceOffset ?: return@produceState
        val parsed = formatted?.takeIf { it.first == text }?.second ?: return@produceState
        if (target.offset !in start until start + text.length) return@produceState
        val found = withContext(Dispatchers.Default) { synchronized(renderer) {
            // Count visible prefix occurrences, not Markdown URLs or fence
            // language names which never became native body text.
            val prefix = renderStudyMarks(toMarkdown(noteSearchPrefixSource(text, target.offset - start)), density, dark)
            renderedNoteSearchOffset(parsed.toString(), prefix.toString(), target.query)
        } }
        withContext(Dispatchers.Main.immediate) { value = target to found }
    }
    AndroidView(factory = { NoteSelectableTextView(it).apply { layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT); textSize = 16f; setTextIsSelectable(true); setLineSpacing(0f, 1.3f) } }, modifier = Modifier.fillMaxWidth().then(if (contentWidth != null) Modifier.requiredWidth(contentWidth) else Modifier), update = {
        if (it.currentTextColor != color) it.setTextColor(color)
        if (kotlin.math.abs(it.textSize - bodySize) > 0.1f) it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodySize)
        it.controller = if (decorated != null) selectionController else null
        it.onPageTap = if (decorated != null) onPageTap else null
        it.block = if (decorated != null) annotationBlock else null
        if (selectionController != null) it.useApplicationMenu() else it.customSelectionActionModeCallback = null
        val parsed = decorated
        // Lazy lists must reserve text height while formatting runs. An empty
        // native view can otherwise let a gesture skip an unloaded source page.
        if (parsed == null && (it.tag == null || it.tag is String)) {
            if (it.tag != text) { it.text = text; it.tag = text }
            it.alpha = 0f
        }
        if (parsed != null) it.alpha = 1f
        if (parsed != null && it.tag !== parsed) {
            if (stableMarkdown && readerWidth != null) prepareReaderTables(parsed, it.paint,
                readerWidth - it.totalPaddingLeft - it.totalPaddingRight,
                context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            renderer.setParsedMarkdown(it, parsed); it.tag = parsed
        }
        val renderedView = it
        it.post {
            if (parsed != null && sourceOffset != null) registerText?.invoke(renderedView, sourceOffset)
            renderReady?.invoke(parsed != null)
        }
        val target = searchTarget
        if (target != null && sourceOffset != null && target.offset in sourceOffset until sourceOffset + text.length && parsed != null) {
            val key = "${target.revision}:${target.offset}:${target.query}"
            val found = locatedSearch?.takeIf { it.first == target }?.second ?: -1
            if (it.searchRevealKey != key && found >= 0) {
                it.searchRevealKey = key
                val view = it; val offset = found; view.post { revealSearch?.invoke(view, offset, target.revision) }
            }
        }
    })
}

private fun preview(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
@Composable fun DataImage(data: String, label: String) {
    val bitmap by produceState<Bitmap?>(null, data) { value = withContext(Dispatchers.Default) { runCatching { preview(Base64.decode(data.substringAfter(','), Base64.DEFAULT)) }.getOrNull() } }
    bitmap?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ChatScreen(ui: UiState, vm: StudyViewModel) {
    val chat = ui.data.chat()
    var input by rememberSaveable(chat.id) { mutableStateOf(ui.chatDrafts[chat.id].orEmpty()) }
    var composerDetails by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var notebookActions by remember { mutableStateOf(false) }
    var navigation by remember { mutableStateOf(false) }
    val chrome = LocalReadingChrome.current
    val inlineHeader = LocalInlineReadingHeader.current
    val compactFraction = LocalReadingCompactFraction.current
    DisposableEffect(chrome) { onDispose { chrome?.locked = false } }
    val latestMessages by rememberUpdatedState(chat.messages)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var jump by remember { mutableStateOf(false) }
    if (jump) JumpDialog("跳转到消息", chat.messages.map { (if (it.role == "user") "你：" else "回答：") + notePreview(it.text).take(90) }, { jump = false }) { index -> jump = false; scope.launch { listState.scrollToItem(index) } }
    LaunchedEffect(chat.id) { if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.lastIndex) }
    LaunchedEffect(chat.id, ui.busy) {
        if (ui.busy) snapshotFlow { latestMessages.lastOrNull()?.text?.length }.distinctUntilChanged().collect {
            if (latestMessages.isNotEmpty() && !listState.isScrollInProgress && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= latestMessages.lastIndex - 1) listState.scrollToItem(latestMessages.lastIndex)
            delay(160)
        }
    }
    val landscapeInput = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val keyboardBeside = inlineHeader && (landscapeInput || imeVisible)
    ReadingChatLayout(keyboardBeside, Modifier.fillMaxSize().padding(horizontal = 14.dp), toolbar = {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("学习工具栏"), verticalAlignment = Alignment.CenterVertically) {
            Box { IconButton(onClick = { history = true }) { Icon(Icons.Outlined.Forum, "会话") }; if (history) ReadingActionSheet("会话", { history = false }) {
                ClassManagerMenuItem { history = false }
                ui.data.conversations.forEach { c -> DropdownMenuItem(text = { Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, enabled = !ui.locked, onClick = { vm.selectChat(c.id); history = false }, trailingIcon = { IconButton(onClick = { vm.deleteChat(c.id); history = false }, enabled = !ui.locked) { Icon(Icons.Default.DeleteOutline, "删除对话") } }) }
            } }
            MaterialsButton(ui, vm)
            IconButton(onClick = vm::newChat, enabled = !ui.locked) { Icon(Icons.Outlined.AddComment, "新对话") }
            Spacer(Modifier.weight(1f))
            if (ui.busy && inlineHeader) IconButton(onClick = vm::stop) { Icon(Icons.Outlined.StopCircle, "停止生成") }
            else Box {
                IconButton(onClick = { notebookActions = true }, enabled = !ui.locked && (chat.messages.isNotEmpty() || ui.pending != null), unavailableReason = if (chat.messages.isEmpty() && ui.pending == null) "先添加资料或进行对话。" else null) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.NoteAdd, "对话笔记", Modifier.size(20.dp)); Text("保存", style = MaterialTheme.typography.labelSmall) } }
                if (notebookActions) ReadingActionSheet("对话笔记", { notebookActions = false }) {
                    Text("选择保存原文，或让 AI 重新整理。笔记会以卡片返回当前对话。", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    DropdownMenuItem(text = { Text("保存对话原文") }, enabled = !ui.locked && chat.messages.any { it.kind != "note_result" && it.text.isNotBlank() }, onClick = { notebookActions = false; vm.saveChatAsNote() })
                    DropdownMenuItem(text = { Text("AI 整理成笔记") }, enabled = !ui.locked, onClick = { notebookActions = false; vm.notes() })
                }
            }
            if (chat.messages.isNotEmpty()) Box {
                IconButton(onClick = { navigation = true }) { Icon(Icons.Outlined.SwapVert, "消息跳转") }
                if (navigation) ReadingActionSheet("消息跳转", { navigation = false }) {
                    DropdownMenuItem(text = { Text("顶部") }, onClick = { navigation = false; scope.launch { listState.scrollToItem(0) } })
                    DropdownMenuItem(text = { Text("底部") }, onClick = { navigation = false; scope.launch { listState.scrollToItem(chat.messages.lastIndex); listState.scrollBy(1_000_000f) } })
                    DropdownMenuItem(text = { Text("跳转消息") }, onClick = { navigation = false; jump = true })
                    DropdownMenuItem(text = { Text("最新回答") }, enabled = chat.messages.any { it.role == "assistant" }, onClick = { navigation = false; val target = chat.messages.indexOfLast { it.role == "assistant" }; if (target >= 0) scope.launch { listState.scrollToItem(target) } })
                }
            }
            if (inlineHeader) IconButton(onClick = { vm.tab(3) }) { Icon(Icons.Outlined.Settings, "设置") }
        }
    }, body = {
        LazyColumn(Modifier.fillMaxSize().testTag("学习正文").then(if (chrome != null) Modifier.nestedScroll(chrome.connection) else Modifier), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 8.dp, bottom = if (compactFraction > 0.5f) 72.dp else 8.dp)) {
            if (chat.messages.isEmpty()) item { StudyEmpty(Icons.Outlined.Description, "从一页课件开始", "连接雨课堂或导入文件，带入课件提问，也可以直接输入问题。", Modifier.padding(top = 24.dp)) { Spacer(Modifier.height(4.dp)); Button(onClick = vm::openMaterials, enabled = !ui.locked) { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("选择学习资料") } } }
            itemsIndexed(chat.messages, key = { index, _ -> "${chat.id}:$index" }, contentType = { _, m -> m.role }) { index, m -> ChatMessageCard(m, "${chat.id}:$index", ui.data.provider().name, ui.busy && m.role == "assistant" && index == chat.messages.lastIndex, ui.data.notes, { id, edit -> vm.openChatNote(id, edit) }, !ui.locked) }
        }
    }, composer = {
        val shortWindow = LocalReadingShortWindow.current
        val compactComposer = shortWindow || (landscapeInput && imeVisible)
        Column {
        if (!inlineHeader) ui.pending?.let { a -> ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null); Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = vm::clearAttachment, enabled = !ui.locked) { Icon(Icons.Default.Close, "移除课件附件") } } } }
        if (!inlineHeader && ui.data.provider().chatgptAccountId.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { Text("使用 ChatGPT 套餐", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") } }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = if (shortWindow) 0.dp else 8.dp)) {
            OutlinedTextField(input, { if (it.length <= 20000) { input = it; vm.chatDraft(chat.id, it) } }, Modifier.weight(1f).onFocusChanged { chrome?.locked = it.isFocused }, placeholder = { Text(if (ui.pending != null) "想了解这份资料的什么？" else "输入问题，或先添加资料…", style = MaterialTheme.typography.bodyMedium) }, label = if (compactComposer) null else ({ Text("提问 · " + ui.data.provider().model.ifBlank { "尚未选择模型" } + if (ui.pending != null) " · 已附加资料" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) }), textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(14.dp), singleLine = landscapeInput || shortWindow, maxLines = if (inlineHeader) 2 else 4, enabled = !ui.locked, trailingIcon = ({
                Box {
                    IconButton(onClick = { composerDetails = true }) { Icon(if (ui.pending != null) Icons.Default.AttachFile else Icons.Outlined.MoreHoriz, "附件与用量") }
                    DropdownMenu(expanded = composerDetails, onDismissRequest = { composerDetails = false }, title = "附件与用量") {
                        DropdownMenuItem(text = { Text("当前模型：${ui.data.provider().model.ifBlank { "未选择" }}") }, onClick = { composerDetails = false })
                        DropdownMenuItem(text = { Text("切换模型") }, enabled = !ui.locked, onClick = { composerDetails = false; vm.tab(3); vm.settingsPage("models") })
                        ui.pending?.let { attachment ->
                            DropdownMenuItem(text = { Text(attachment.label) }, onClick = { composerDetails = false })
                            DropdownMenuItem(text = { Text("移除课件附件") }, enabled = !ui.locked, onClick = { composerDetails = false; vm.clearAttachment() })
                        }
                        if (ui.data.provider().chatgptAccountId.isNotBlank()) DropdownMenuItem(text = { Text("管理 ChatGPT 用量") }, onClick = { composerDetails = false; vm.openLink(CHATGPT_USAGE) })
                    }
                }
            }))
            FilledIconButton(onClick = { if (vm.send(input.trim())) input = "" }, enabled = !ui.locked && input.isNotBlank(), unavailableReason = if (input.isBlank()) "先输入你的问题，再发送。" else null) { Icon(Icons.Default.ArrowUpward, "发送给所选模型") }
        }
        }
    })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun RainScreen(ui: UiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    BackHandler(enabled = ui.course != null || ui.lesson != null || ui.presentationId.isNotBlank()) { vm.backRainMaterials() }
    var manualId by rememberSaveable { mutableStateOf("") }
    var versions by remember { mutableStateOf(false) }
    val exportQr = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val bytes = ui.qr
        if (uri != null && bytes != null) scope.launch { runCatching { withContext(Dispatchers.IO) { val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("二维码无法读取。"); context.contentResolver.openOutputStream(uri)?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("无法写入文件。") } }.fold({ vm.notice("二维码已导出。") }, { vm.notice("二维码保存失败。") }) }
    }
    fun saveQr() {
        val bytes = ui.qr ?: return
        if (Build.VERSION.SDK_INT < 29) { exportQr.launch("rain-login.png"); return }
        scope.launch { runCatching { withContext(Dispatchers.IO) {
            val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "rain-login-${System.currentTimeMillis()}.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyBridge"); put(MediaStore.Images.Media.IS_PENDING, 1) }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("保存失败。")
            try { resolver.openOutputStream(uri)?.use { val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("图片无法读取。"); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } ?: error("保存失败。"); resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) }
            catch (e: Exception) { resolver.delete(uri, null, null); throw e }
        } }.fold({ vm.notice("二维码已保存到相册，请用微信扫一扫尝试识别。") }, { vm.notice("二维码保存失败，可使用系统截图。") }) }
    }
    var accountExpanded by remember { mutableStateOf(false) }
    var connectionHelp by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    var focusedPage by rememberSaveable(ui.presentationId, ui.slideTitle) { mutableIntStateOf(0) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    val libraryColumns = courseLibraryColumns(maxWidth.value.coerceAtMost(1120f) - 40f, androidx.compose.ui.platform.LocalDensity.current.fontScale)
    Column(Modifier.widthIn(max = 1120.dp).fillMaxSize()) {
    SlidePageControls(ui.slides, focusedPage) { focusedPage = it }
    LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp).testTag("课程列表"), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item {
            StudyPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.School, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(RAIN_NAMES[ui.data.region] ?: "雨课堂", style = MaterialTheme.typography.titleMedium); Text(loginStatusText(ui.data, ui.loginActive, ui.loginStage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { vm.tab(3) }) { Icon(Icons.Outlined.Settings, "设置") }
                        if (ui.data.session.isNotBlank()) IconButton(onClick = { accountExpanded = !accountExpanded }) { Icon(if (accountExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "管理雨课堂账号", Modifier.size(22.dp)) }
                    }
                    if (ui.data.session.isBlank() || accountExpanded) {
                        if (ui.data.session.isBlank()) Text("用微信确认后，返回这里选择课程与课件。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box { TextButton(onClick = { versions = true }, enabled = !ui.loginActive && !ui.locked) { Text("切换雨课堂版本"); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp)) }; DropdownMenu(expanded = versions, onDismissRequest = { versions = false }) { RAIN_NAMES.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { vm.region(id); versions = false }) } } }
                        Button(onClick = vm::startLogin, enabled = !ui.loginActive && !ui.locked, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Icon(Icons.Default.QrCodeScanner, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (ui.data.session.isBlank()) "微信扫码登录" else "重新登录") }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ui.loginActive) TextButton(onClick = vm::cancelLogin) { Text("取消登录") }
                            if (ui.data.session.isNotBlank()) { TextButton(onClick = vm::verifyLogin, enabled = !ui.locked) { Text("验证登录") }; TextButton(onClick = vm::logout, enabled = !ui.locked) { Text("退出账号") } }
                            TextButton(onClick = { connectionHelp = !connectionHelp }) { Text(if (connectionHelp) "收起连接帮助" else "连接遇到问题") }
                        }
                        if (connectionHelp) { TextButton(onClick = vm::diagnoseLogin, enabled = !ui.locked && !ui.loginActive) { Text("检查通道") }; Text("登录信息加密保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (ui.loginActive || connectionHelp) ui.loginSteps.takeLast(3).forEach { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    ui.qr?.let { bytes -> val bitmap by produceState<Bitmap?>(null, bytes) { value = withContext(Dispatchers.Default) { preview(bytes) } }; bitmap?.let { Image(it.asImageBitmap(), "微信登录二维码", Modifier.fillMaxWidth().height(210.dp), contentScale = ContentScale.Fit) }; Button(onClick = ::saveQr, modifier = Modifier.fillMaxWidth()) { Text("保存二维码") }; Text("微信扫一扫 → 相册 → 选择二维码并确认，再返回这里。扫码期间会保持当前二维码与连接；超时后可重新获取。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (ui.data.session.isNotBlank() || ui.hasClassSession()) item { ClassModePanel(ui, vm) }
        if (ui.data.session.isNotBlank()) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (ui.course == null) "我的课程" else ui.course.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); if (ui.course != null || ui.presentationId.isNotBlank()) TextButton(onClick = vm::backRainMaterials) { Text(ui.rainBackLabel) } else TextButton(onClick = vm::loadCourses, enabled = !ui.locked && !ui.loginActive) { Text("刷新") } } }
            if (ui.courses.isEmpty() && ui.course == null) item { StudyEmpty(Icons.Outlined.MenuBook, "还没有读取课程", "点击右上方刷新，读取你已加入的课程。") }
            if (ui.presentationId.isBlank()) courseLibraryItems(ui, vm, libraryColumns)
            if (ui.presentationId.isNotBlank()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(ui.slideTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(ui.slideRecordedAt.ifBlank { "创建时间：未提供" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MaterialStudyControls(ui, vm)
                }
            }
            items(ui.slides.filter { focusedPage == 0 || it.page == focusedPage }, key = { it.page }) { slide -> StudyPanel { Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("第 ${slide.page} 页", style = MaterialTheme.typography.titleSmall)
                PagePreview(vm.pagePreviewKey(ui, slide), "${ui.slideTitle} · 第 ${slide.page} 页", vm.previews) { vm.previewPage(ui, slide) }
                if (slide.text.isNotBlank()) Text(notePreview(slide.text), maxLines = 2, style = MaterialTheme.typography.bodySmall)
                MaterialPageActions(slide, ui, vm)
            } } }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级入口" else "高级：按课件编号读取") }; if (advanced) { OutlinedTextField(manualId, { if (it.length <= 24 && it.all(Char::isDigit)) manualId = it }, label = { Text("课件编号 presentation_id") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.loadSlides(manualId) }, enabled = !ui.locked && manualId.isNotBlank()) { Text("读取课件") } } }
        }
    }
    }
}

}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun NotesScreen(ui: UiState, vm: StudyViewModel) {
    val reader = vm.noteReader
    val backLabel = if (ui.noteChatOrigin != null) "返回对话" else "返回笔记列表"
    val libraryState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val chrome = LocalReadingChrome.current
    val inlineHeader = LocalInlineReadingHeader.current
    DisposableEffect(chrome, reader.editing) { chrome?.locked = reader.editing; onDispose { chrome?.locked = false } }
    val note = ui.data.notes.find { it.id == reader.id }
    var leaving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deletingPages by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    var markLegend by remember { mutableStateOf(false) }
    var remarks by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var titleDraft by remember { mutableStateOf("") }
    var libraryPane by remember { mutableStateOf("") }
    var actionNoteId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialListAction by rememberSaveable { mutableStateOf("options") }
    NoteListActions(ui, vm, actionNoteId, initialListAction) { actionNoteId = null }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportPdf = rememberNotePdfExport(vm)
    fun requestClose() { if (note != null && reader.editing && reader.draft != note.content) leaving = true else vm.closeNoteReader() }
    BackHandler(enabled = note != null) { requestClose() }
    LaunchedEffect(reader.closeRequested) { if (reader.closeRequested) { reader.closeRequested = false; requestClose() } }
    var exportSnapshot by remember { mutableStateOf<Pair<Note, String>?>(null) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val snapshot = exportSnapshot; exportSnapshot = null
        if (uri != null && snapshot != null) vm.exportNote(snapshot.first, snapshot.second, uri)
    }
    if (note != null) {
        val writable = ui.noteEditable(note.id)
        val removable = !ui.locked && ui.data.noteWritable(note.id)
        if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("保存这次修改？") }, text = { Text("切换底部标签会保留草稿。返回前，可以保存或放弃这次修改。") }, confirmButton = { TextButton(onClick = { if (vm.editNote(note.id, reader.draft)) { vm.closeNoteReader(); leaving = false } }, enabled = writable) { Text("保存并返回") } }, dismissButton = { FlowRow { TextButton(onClick = { vm.discardNoteDraft(note.id); vm.closeNoteReader(); leaving = false }) { Text("放弃修改") }; TextButton(onClick = { leaving = false }) { Text("继续编辑") } } })
        if (deletingPages) NotePageDeletion(note, vm, removable && !reader.editing) { deletingPages = false }
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这份笔记？") }, text = { Text("“${note.title}”将移到回收站，可以恢复。") }, confirmButton = { TextButton(onClick = { if (vm.deleteNote(note.id)) { vm.closeNoteReader(); deleting = false } }, enabled = removable, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除笔记") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留笔记") } })
        if (renaming) AlertDialog(onDismissRequest = { renaming = false }, title = { Text("重命名笔记") }, text = {
            OutlinedTextField(titleDraft, { titleDraft = it.take(120) }, label = { Text("笔记标题") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(onClick = { if (vm.renameNote(note.id, titleDraft)) renaming = false }, enabled = writable && titleDraft.isNotBlank()) { Text("保存标题") } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } })
        val notePrimaryActions: @Composable (() -> Unit) -> Unit = { dismiss ->
            ReadingActionGroup("编辑与标记")
            if (!reader.editing) DropdownMenuItem(text = { Text("编辑笔记") }, enabled = writable, onClick = { dismiss(); reader.beginEditing(note) })
            DropdownMenuItem(text = { Text("我的标记与备注") }, onClick = { dismiss(); remarks = true })
        }
        val noteActions: @Composable (() -> Unit) -> Unit = { dismiss ->
            ReadingActionGroup("整理笔记")
            ClassManagerMenuItem { dismiss() }
            DropdownMenuItem(text = { Text("标签与复习") }, enabled = writable, onClick = { dismiss(); libraryPane = "details" })
            DropdownMenuItem(text = { Text("关联笔记") }, enabled = !reader.editing || reader.draft == note.content, onClick = { dismiss(); libraryPane = "links" })
            DropdownMenuItem(text = { Text("历史版本") }, onClick = { dismiss(); libraryPane = "history" })
            DropdownMenuItem(text = { Text(if (note.pinned) "取消置顶" else "置顶笔记") }, enabled = !ui.locked, onClick = { dismiss(); vm.toggleNotePin(note.id) })
            DropdownMenuItem(text = { Text("重命名笔记") }, enabled = writable, onClick = { dismiss(); titleDraft = note.title; renaming = true })
            DropdownMenuItem(text = { Text("移动到分类") }, enabled = !ui.locked, onClick = { dismiss(); initialListAction = "move"; actionNoteId = note.id })
            ReadingActionGroup("导出与讲解")
            DropdownMenuItem(text = { Text("导出 Markdown") }, enabled = !ui.locked, onClick = {
                dismiss()
                val text = if (reader.editing) reader.draft else note.content
                if (ui.data.downloadTreeUri.isNotBlank()) vm.exportNote(note, text) else { exportSnapshot = note to text; export.launch(downloadName(note.title, "md")) }
            })
            DropdownMenuItem(text = { Text("带入 AI 对话") }, enabled = !ui.locked && (!reader.editing || reader.draft == note.content), onClick = { dismiss(); vm.attachNote(note) })
            DropdownMenuItem(text = { Text("AI 讲解并保存新笔记") }, enabled = !ui.locked && (!reader.editing || reader.draft == note.content), onClick = { dismiss(); vm.explainNote(note) })
            DropdownMenuItem(text = { Text("导出 PDF") }, enabled = !ui.locked, onClick = { dismiss(); exportPdf(note.copy(content = if (reader.editing) reader.draft else note.content)) })
            ReadingActionGroup("标记与资料")
            DropdownMenuItem(text = { Text("标记说明") }, onClick = { dismiss(); markLegend = true })
            DropdownMenuItem(text = { Text("来源与模型") }, onClick = { dismiss(); details = true })
            HorizontalDivider()
            DropdownMenuItem(text = { Text("按页删除内容…") }, enabled = removable && !reader.editing, onClick = { dismiss(); deletingPages = true })
            DropdownMenuItem(text = { Text("删除整份笔记…") }, enabled = removable, onClick = { dismiss(); deleting = true })
        }
        val actions: @Composable () -> Unit = {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "笔记选项", Modifier.size(22.dp)) }
            if (menu) ReadingActionSheet("笔记选项", { menu = false }) { notePrimaryActions { menu = false }; noteActions { menu = false } }
        }
        if (remarks) NoteRemarksDialog(note, vm) { remarks = false }
        if (libraryPane == "details") NotebookNoteDetails(note, ui, vm) { libraryPane = "" }
        if (libraryPane == "history") NotebookHistory(note, ui, vm) { libraryPane = "" }
        if (libraryPane == "links") NotebookRelated(note, ui, { target -> libraryPane = ""; reader.open(target) }) { libraryPane = "" }
        if (markLegend) StudyMarkLegend { markLegend = false }
        if (details) AlertDialog(onDismissRequest = { details = false }, title = { Text("来源与模型") }, text = {
            Text("来源：${note.source}\n\n模型：${note.model}", Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
        }, confirmButton = { TextButton(onClick = { details = false }) { Text("关闭") } })
        Column(Modifier.fillMaxSize().testTag("笔记阅读页").padding(horizontal = 20.dp, vertical = 8.dp)) {
            val heading: @Composable () -> Unit = {
                Text(note.title, style = MaterialTheme.typography.titleLarge)
                OriginalNotePages(note, ui, vm)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            }
            if (reader.editing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (inlineHeader) IconButton(onClick = { requestClose() }) { Icon(Icons.Default.ArrowBack, backLabel) }
                    Text("编辑笔记", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { if (vm.editNote(note.id, reader.draft)) reader.editing = false }, enabled = writable) { Text("保存修改") }
                    actions()
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    heading()
                    Text(if (ui.noteDraftSaveError.isNotBlank()) ui.noteDraftSaveError else if (ui.noteDraftSaving) "正在自动保存草稿…" else if (ui.noteDraftSavedAt > 0) "草稿已自动保存" else "修改会自动保存为草稿", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    NoteImageEditor(reader.draft, { reader.draft = it }, vm.sourceImages, writable, vm::notice)
                    NoteEditor(reader.draft, { reader.draft = it }, writable)
                }
            } else NoteReadingSelection(note, vm, Modifier.weight(1f)) { readingModifier -> NotebookReader(note, ui, vm, readingModifier, extraHeader = { OriginalNotePages(note, ui, vm); HorizontalDivider() }, primaryTools = notePrimaryActions, extraTools = noteActions, backLabel = backLabel, onBack = { requestClose() }) }
        }
    } else libraryState.SaveableStateProvider("notebook-library") {
        NotebookLibraryScreen(ui, vm, onOpen = { reader.open(it); details = false }, onActions = { initialListAction = "options"; actionNoteId = it.id })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun PagedNoteContent(note: Note, modifier: Modifier = Modifier, independentScroll: Boolean = false, header: @Composable () -> Unit = {}, trailingActions: @Composable () -> Unit = {}, titleAlreadyShown: Boolean = false, onBack: () -> Unit = {}, onSettings: () -> Unit = {}, bookmark: NoteReadingPosition? = null, onReadingPosition: (NoteReadingPosition) -> Unit = {}, sourceImages: SourceImages? = null) {
    val chrome = LocalReadingChrome.current
    val inlineHeader = LocalInlineReadingHeader.current
    val compactFraction = LocalReadingCompactFraction.current
    var index by rememberSaveable(note.id) { mutableIntStateOf(0) }
    var jump by remember(note.id) { mutableStateOf(false) }
    var searching by remember(note.id) { mutableStateOf(false) }
    var navigation by remember(note.id) { mutableStateOf(false) }
    val scroll = rememberScrollState(); val scope = rememberCoroutineScope()
    val prepared by produceState<Pair<String, List<NoteReadingSegment>>?>(null, note.id, note.content) {
        value = withContext(Dispatchers.Default) { note.content to noteReadingSegments(note.content) }
    }
    val segments = prepared?.takeIf { it.first == note.content }?.second
    if (segments == null) { CircularProgressIndicator(Modifier.size(24.dp)); return }
    val selected = index.coerceIn(0, segments.lastIndex)
    val labels = remember(note.content, segments) { segments.mapIndexed { i, part ->
        val text = note.content.substring(part.start, minOf(part.end, part.start + 300))
        (readingHeadings(text).firstOrNull { it.second.contains(Regex("第\\s*\\d+\\s*页")) } ?: readingHeadings(text).firstOrNull())?.second ?: "第 ${i + 1} 段 · ${notePreview(text).take(50)}"
    } }
    val hash = remember(note.content) { noteContentHash(note.content) }
    val initialBookmark = remember(note.id) { bookmark }
    var restored by remember(note.id, note.content) { mutableStateOf(false) }
    var previousSelection by remember(note.id, note.content) { mutableIntStateOf(-1) }
    LaunchedEffect(note.id, note.content, labels) {
        if (independentScroll && !restored) {
            index = restoredNoteIndex(initialBookmark, hash, labels)
            if (initialBookmark != null && initialBookmark.contentHash == hash && initialBookmark.scroll > 0) {
                kotlinx.coroutines.withTimeoutOrNull(1800) { snapshotFlow { scroll.maxValue }.first { it >= initialBookmark.scroll } }
                scroll.scrollTo(initialBookmark.scroll.coerceAtMost(scroll.maxValue))
            }
            previousSelection = index; restored = true
        }
    }
    LaunchedEffect(selected, restored) {
        if (independentScroll && restored && previousSelection != selected) { scroll.scrollTo(0); previousSelection = selected }
    }
    val latestSelection by rememberUpdatedState(selected)
    val latestLabels by rememberUpdatedState(labels)
    val latestPositionCallback by rememberUpdatedState(onReadingPosition)
    LaunchedEffect(note.id, hash, restored) {
        if (independentScroll && restored) snapshotFlow { latestSelection to scroll.value }.debounce(180).collect { (page, offset) ->
            latestPositionCallback(NoteReadingPosition(note.id, page, offset, latestLabels[page], hash))
        }
    }
    DisposableEffect(note.id, hash, scroll, restored) {
        onDispose { if (independentScroll && restored) latestPositionCallback(NoteReadingPosition(note.id, latestSelection, scroll.value, latestLabels[latestSelection], hash)) }
    }
    val readingLifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(readingLifecycle, note.id, hash, restored) {
        // LifecycleRegistry requires registration and removal on Android's main
        // thread, including when a test frame resumes from a background snapshot.
        withContext(Dispatchers.Main.immediate) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP && independentScroll && restored) latestPositionCallback(NoteReadingPosition(note.id, latestSelection, scroll.value, latestLabels[latestSelection], hash))
            }
            readingLifecycle.addObserver(observer)
            try { awaitCancellation() } finally { readingLifecycle.removeObserver(observer) }
        }
    }
    if (searching) NoteSearchDialog(note, { searching = false }) { index = it; searching = false; scope.launch { scroll.scrollTo(0) } }
    if (jump) JumpDialog("笔记目录 / 阅读分段", labels, { jump = false }) { index = it; jump = false }
    ReadingChatLayout(false, modifier, toolbar = {
        val floatingTools = LocalFloatingReadingTools.current
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showArrows = !floatingTools && maxWidth >= (if (inlineHeader) 352.dp else 256.dp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (inlineHeader) IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回笔记列表") }
            if (segments.size > 1 && showArrows) IconButton(onClick = { index = selected - 1 }, enabled = selected > 0) { Icon(Icons.Default.ChevronLeft, "上一段") }
            TextButton(onClick = { jump = true }, modifier = Modifier.weight(1f).semantics {
                contentDescription = "目录跳转"
                stateDescription = "阅读分段 ${selected + 1} / ${segments.size} · 完整笔记已保留"
            }) { Text(if (segments.size > 1) "${selected + 1}/${segments.size} ▾" else "目录 ▾", maxLines = 1) }
            if (segments.size > 1 && showArrows) IconButton(onClick = { index = selected + 1 }, enabled = selected < segments.lastIndex) { Icon(Icons.Default.ChevronRight, "下一段") }
            if (independentScroll) Box {
                IconButton(onClick = { navigation = true }) { Icon(Icons.Default.SwapVert, "阅读跳转") }
                DropdownMenu(expanded = navigation, onDismissRequest = { navigation = false }, title = "阅读跳转") {
                    DropdownMenuItem(text = { Text("笔记内查找") }, onClick = { navigation = false; searching = true })
                    if (initialBookmark != null) DropdownMenuItem(text = { Text("回到上次阅读") }, onClick = {
                        navigation = false; index = restoredNoteIndex(initialBookmark, hash, labels)
                        scope.launch { kotlinx.coroutines.delay(40); scroll.scrollTo(if (initialBookmark.contentHash == hash) initialBookmark.scroll.coerceAtMost(scroll.maxValue) else 0) }
                    })
                    if (segments.size > 1) {
                        DropdownMenuItem(text = { Text("上一段") }, enabled = selected > 0, onClick = { navigation = false; index = selected - 1 })
                        DropdownMenuItem(text = { Text("下一段") }, enabled = selected < segments.lastIndex, onClick = { navigation = false; index = selected + 1 })
                    }
                    DropdownMenuItem(text = { Text("本段顶部") }, onClick = { navigation = false; scope.launch { scroll.scrollTo(0) } })
                    DropdownMenuItem(text = { Text("本段底部") }, onClick = { navigation = false; scope.launch { scroll.scrollTo(scroll.maxValue) } })
                }
            }
            trailingActions()
            if (inlineHeader) IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "设置") }
        }
        }
    }, body = {
        Column(if (independentScroll) Modifier.fillMaxSize().testTag("笔记正文").then(if (chrome != null) Modifier.nestedScroll(chrome.connection) else Modifier).verticalScroll(scroll) else Modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            header()
            key(note.id, selected) {
                val raw = segments[selected].display(note.content)
                val shown = if (titleAlreadyShown && selected == 0) noteTextForDisplay(raw, note.title) else raw
                IllustratedMarkdown(shown, sourceImages, segments[selected].start - segments[selected].prefix.length + raw.length - shown.length, segments[selected].sourceForDisplay(note.content, raw.length - shown.length))
            }
            Spacer(Modifier.height(if (compactFraction > 0.5f) 72.dp else 24.dp))
        }
    }, composer = {})
}

@Composable fun ChatGptAccountPanel(ui: UiState, vm: StudyViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("使用 ChatGPT 账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("登录并授权后，符合资格的请求使用你的 ChatGPT 套餐，无需填写 API Key。可用模型和额度由账号授权决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.startChatGptLogin() }, enabled = !ui.locked, colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Continue with ChatGPT") }
            if (ui.chatgptLoginActive) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancelChatGptLogin) { Text("取消本次授权") } }
            if (ui.chatgptStage.isNotBlank()) Text(ui.chatgptStage, style = MaterialTheme.typography.bodySmall)
            if (ui.data.chatgptAccounts.isNotEmpty() || ui.data.chatgptDraftClients.isNotEmpty()) Column(Modifier.fillMaxWidth().heightIn(max = expandedListHeight(240.dp)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ui.data.chatgptAccounts.forEach { account ->
                HorizontalDivider()
                Text(account.label, style = MaterialTheme.typography.titleSmall)
                Text(when { !account.connected -> "已退出，账号注册信息已保留"; account.planGranted -> "已连接 · 已授权使用套餐"; else -> "身份已验证 · 尚未授权套餐" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    if (account.connected && account.planGranted) TextButton(onClick = { vm.useChatGptAccount(account.id) }, enabled = !ui.locked) { Text(if (ui.data.provider().chatgptAccountId == account.id) "读取此账号模型" else "使用此账号") }
                    TextButton(onClick = { vm.startChatGptLogin(accountId = account.id) }, enabled = !ui.locked) { Text("重新登录") }
                    if (account.connected) TextButton(onClick = { vm.signOutChatGpt(account.id) }, enabled = !ui.locked) { Text("退出") }
                }
                if (account.connected && !account.planGranted) OutlinedButton(onClick = { vm.startChatGptLogin(accountId = account.id, forceConsent = true) }, enabled = !ui.locked) { Text("授权使用套餐") }
            }
            ui.data.chatgptDraftClients.forEach { id -> TextButton(onClick = { vm.startChatGptLogin(draftClientId = id) }, enabled = !ui.locked) { Text("继续未完成的授权 · ${id.takeLast(6)}") } }
            }
            TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }, enabled = !ui.chatgptLoginActive) { Text("管理 ChatGPT 用量") }
            Text("授权会打开本机系统浏览器，完成后点击返回雨课伴学。登录资料加密保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ModelScreen(ui: UiState, vm: StudyViewModel) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val editor = vm.modelEditor
    var adding by editor.adding
    LaunchedEffect(ui.data.selectedProvider) { editor.observeSelection(ui.data.selectedProvider) }
    val current = adding ?: ui.data.provider()
    val usingChatGpt = current.chatgptAccountId.isNotBlank()
    val draft = editor.draft(current)
    var name by draft.name
    var base by draft.base
    var key by draft.key
    var model by draft.model
    var protocol by draft.protocol
    var images by draft.images
    var showConfig by draft.showConfig
    var accountOptions by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var chooseModel by remember(current.id) { mutableStateOf(false) }
    var manualModel by draft.manualModel
    var requestedCatalog by remember { mutableStateOf("") }
    var modelQuery by remember { mutableStateOf("") }
    var showLearner by rememberSaveable { mutableStateOf(false) }
    var learner by rememberSaveable(ui.data.learnerProfile) { mutableStateOf(ui.data.learnerProfile) }
    var showPrompts by rememberSaveable { mutableStateOf(false) }
    var tutor by remember(ui.data.tutorPrompt) { mutableStateOf(ui.data.tutorPrompt) }
    var notes by remember(ui.data.notesPrompt) { mutableStateOf(ui.data.notesPrompt) }
    fun provider() = current.copy(name = name.trim(), baseUrl = if (usingChatGpt) CHATGPT_API else base.trim(), key = if (usingChatGpt) "" else key.trim(), model = model.trim(), images = images, protocol = if (usingChatGpt) "responses" else protocol)
    val catalogKey = modelCatalogKey(provider())
    val catalogMatches = ui.modelListFor == current.id && ui.modelListRequest == catalogKey
    val hasCatalog = catalogMatches && ui.modelList.isNotEmpty()
    val modelsActive = ui.tab == 3 && ui.settingsPage == "models"
    DisposableEffect(modelsActive, catalogKey) {
        chooseModel = false; requestedCatalog = ""
        onDispose { chooseModel = false; requestedCatalog = "" }
    }
    LaunchedEffect(ui.busy, ui.modelListRequest, requestedCatalog, modelsActive) {
        if (modelsActive && requestedCatalog.isNotEmpty() && requestedCatalog == catalogKey && !ui.busy) {
            if (hasCatalog) { manualModel = false; modelQuery = ""; chooseModel = true }
            requestedCatalog = ""
        }
    }
    if (ui.chatgptPlanWelcome) AlertDialog(onDismissRequest = vm::acknowledgeChatGptPlan, title = { Text("正在使用你的 ChatGPT 套餐") }, text = { Text("符合资格的聊天和笔记请求会使用你的 ChatGPT 套餐。你可以在 ChatGPT 设置中管理本应用的用量和访问权限。账号授权并不代表某个模型请求已经验证成功。") }, confirmButton = { TextButton(onClick = vm::acknowledgeChatGptPlan) { Text("知道了") } }, dismissButton = { TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") } })
    if (ui.settingsPage.isEmpty()) { SettingsOverview(ui, vm); return }
    if (ui.settingsPage == "about") { AboutSettings { vm.openLink(PROJECT_URL) }; return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (ui.settingsPage) {
            "models" -> {
                Text("先连接服务，再读取并选择模型。课件图片需要支持图片的模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { accountOptions = !accountOptions }) { Text("ChatGPT 账号授权（可选）") }
                if (accountOptions || ui.chatgptLoginActive || usingChatGpt) ChatGptAccountPanel(ui, vm)
        Text("模型配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.data.providers, key = { it.id }) { p -> FilterChip(selected = adding == null && current.id == p.id, onClick = { adding = null; vm.selectProvider(p.id) }, label = { Text(p.name) }, enabled = !ui.locked) } }
        TextButton(onClick = editor::beginNew, enabled = !ui.locked) { Text("添加模型配置（API 接入）") }
        Text("当前服务：${current.name}", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = { showConfig = !showConfig }, enabled = !ui.locked) { Text(if (showConfig) "收起连接详情" else "配置连接") }
        if (showConfig) {
            OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.locked)
            if (usingChatGpt) {
                Text("使用 ChatGPT 套餐 · 无需 API Key", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") }
            } else {
                Text("可接入兼容 Chat Completions 或 Responses 的模型服务。API 凭据加密保存在本机。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = protocol == "chat", onClick = { if (protocol != "chat") { protocol = "chat"; model = "" } }, label = { Text("Chat Completions") }, enabled = !ui.locked); FilterChip(selected = protocol == "responses", onClick = { if (protocol != "responses") { protocol = "responses"; model = "" } }, label = { Text("Responses") }, enabled = !ui.locked) }
                OutlinedTextField(base, { if (base != it) { base = it; model = "" } }, label = { Text("API Base URL") }, placeholder = { Text("填写模型服务商的 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !ui.locked)
                OutlinedTextField(key, { if (key != it) { key = it; model = "" } }, label = { Text("API Key") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "切换密钥显示") } }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !ui.locked)
            }
        }
            OutlinedButton(onClick = { requestedCatalog = catalogKey; vm.testProvider(provider()) }, enabled = !ui.locked && (if (usingChatGpt) ui.data.providerReady(current) else key.isNotBlank() && base.isNotBlank()), unavailableReason = "请先填写 API 地址和密钥，或完成账号授权。", modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (hasCatalog) "刷新模型列表" else "读取可用模型") }
            if (requestedCatalog.isNotEmpty()) TextButton(onClick = { requestedCatalog = ""; chooseModel = false }) { Text("取消自动打开模型列表") }
            if (manualModel && !usingChatGpt) {
                OutlinedTextField(model, { model = it }, label = { Text("模型 ID") }, placeholder = { Text("按服务商接口文档填写") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.locked)
            } else OutlinedButton(onClick = { modelQuery = ""; chooseModel = true }, enabled = !ui.locked && hasCatalog, unavailableReason = "请先读取可用模型，再从列表选择。", modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                    Text(if (model.isBlank()) "选择模型" else ui.modelNames[model]?.takeIf { catalogMatches } ?: model)
                    if (model.isNotBlank() && catalogMatches && ui.modelNames[model]?.let { it != model } == true) Text(model, style = MaterialTheme.typography.labelSmall)
                }
                Icon(Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
            if (!usingChatGpt) TextButton(onClick = { manualModel = !manualModel }, enabled = !ui.locked) { Text(if (manualModel) "返回列表选择" else "手动填写模型 ID") }
            if (chooseModel && hasCatalog) ModalBottomSheet(onDismissRequest = { chooseModel = false; requestedCatalog = "" }) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("选择可用模型", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { chooseModel = false; requestedCatalog = "" }) { Icon(Icons.Default.Close, "关闭模型列表") }
                }
                OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text("搜索模型") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                val candidates = remember(ui.modelList, modelQuery, ui.modelNames) { ui.modelList.filter { it.contains(modelQuery, ignoreCase = true) || ui.modelNames[it]?.contains(modelQuery, ignoreCase = true) == true } }
                if (candidates.isEmpty()) Text("没有匹配的模型", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = expandedListHeight(420.dp)).testTag("模型选择列表"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                    items(candidates, key = { it }) { id ->
                        StudyChoiceRow(onClick = { model = id; chooseModel = false; focus.clearFocus(); keyboard?.hide() }, selected = model == id) {
                            Column { Text(ui.modelNames[id] ?: id, maxLines = 2, overflow = TextOverflow.Ellipsis); if (ui.modelNames[id]?.let { it != id } == true) Text(id, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("所选模型支持图片输入", Modifier.weight(1f)); Switch(images, { images = it }, enabled = !ui.locked) }
            Text("课件图片需要支持图片的模型。列表可见不等于已验证图片权限，实际以请求结果为准。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (vm.saveProvider(provider())) { editor.saved(current.id); adding = null } }, enabled = !ui.locked && model.isNotBlank()) { Text("保存并使用") }
            }
            if (adding != null) TextButton(onClick = editor::cancelNew) { Text("取消新增") } else if (showConfig) {
                TextButton(onClick = { editor.discard(current.id); showConfig = false }) { Text("放弃未保存修改") }
                TextButton(onClick = { vm.removeProvider(current.id); if (vm.ui.value.data.providers.none { it.id == current.id }) editor.discard(current.id) }, enabled = !ui.locked && ui.data.providers.size > 1) { Text("删除此配置") }
            }

            }
            "notes" -> {
                StudyPanel { SettingsToggle("超重点模式", "只保留核心结论、必要公式、条件和易错点；下一页生成起生效。", ui.data.superFocusNotes, ui.ready && ui.storageError.isBlank(), vm::superFocusNotes) }
                StudyPanel { SettingsToggle("添加课件时自动整理", "选择课件后自动生成讲解并保存笔记。", ui.data.autoNotes, !ui.locked, vm::autoNotes) }
                Text("原有笔记保留原样；以上设置只影响后续生成。", style = MaterialTheme.typography.bodySmall)
            }
            "files" -> { DownloadDirectorySettings(ui, vm); NotebookStorage(ui, vm) }
            "learning" -> {
                        StudyPanel { Card(onClick = { showLearner = !showLearner }, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.School, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("我的学习情况", style = MaterialTheme.typography.titleSmall)
                    Text(if (ui.data.learnerProfile.isBlank()) "告诉 AI 你的基础、难点与目标" else ui.data.learnerProfile.replace("\n", " "), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (showLearner) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp))
            }
        } }
        if (showLearner) {
            OutlinedTextField(learner, { learner = it.take(LEARNER_PROFILE_LIMIT) }, label = { Text("当前水平与学习需求") }, placeholder = { Text("例如：大一，生物基础较弱。遗传学术语容易混淆，希望先讲基础，再拆解机制，最后用例题检验理解。") }, supportingText = { Text("${learner.length} / $LEARNER_PROFILE_LIMIT · 可随学习进展调整") }, modifier = Modifier.fillMaxWidth(), minLines = 4, maxLines = 8, enabled = !ui.locked)
            Text("加密保存在本机。应用内讲解和笔记会自动参考，生成时随学习请求发送给所选模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.saveLearnerProfile(learner) }, enabled = !ui.locked && learner.trim() != ui.data.learnerProfile, unavailableReason = "修改学习情况后即可保存。") { Text("保存学习情况") }
                TextButton(onClick = { learner = ""; vm.saveLearnerProfile("") }, enabled = !ui.locked && (learner.isNotBlank() || ui.data.learnerProfile.isNotBlank())) { Text("清除") }
            }
        }
        StudyPanel { Card(onClick = { showPrompts = !showPrompts }, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Icon(Icons.Outlined.EditNote, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("学习助手提示词", style = MaterialTheme.typography.titleSmall); Text("调整讲解方式与笔记结构", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (showPrompts) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp)) }
        } }
        if (showPrompts) {
            OutlinedTextField(tutor, { tutor = it }, label = { Text("学习助教提示词") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            OutlinedTextField(notes, { notes = it }, label = { Text("笔记助手提示词") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("保存提示词") }; TextButton(onClick = { tutor = TUTOR_PROMPT; notes = NOTES_PROMPT; vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("恢复默认") } }
        }
        ClassFollowSettings(ui, vm)

            }
        }
    }
}
