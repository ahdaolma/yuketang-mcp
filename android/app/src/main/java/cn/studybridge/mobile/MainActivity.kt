package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StudyTheme { StudyApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudyApp(vm: StudyViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) vm.onResume()
            if (event == Lifecycle.Event.ON_STOP) vm.background() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val activity = LocalContext.current as? Activity
    DisposableEffect(ui.classUi.running, ui.classUi.paused, activity) {
        if (ui.classUi.running && !ui.classUi.paused) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var previousTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.tab) { if (ui.tab != 3) previousTab = ui.tab }
    var webRevision by remember { mutableIntStateOf(0) }
    var initialWebLoaded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val web = remember(context) { ChatGptWebSession(context, { webRevision++ }, { url ->
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }) }
    DisposableEffect(web) {
        vm.bindWeb(web)
        onDispose { vm.bindWeb(null); web.destroy() }
    }
    LaunchedEffect(ui.ready, ui.data.usesWeb) {
        if (ui.ready && ui.data.usesWeb && !initialWebLoaded) { initialWebLoaded = true; web.load(ui.data.webPending?.url ?: CHATGPT_WEB) }
        if (ui.data.usesWeb) web.view.onResume() else web.view.onPause()
    }
    LaunchedEffect(webRevision) { vm.webLocation(web.pageUrl) }
    BackHandler(enabled = ui.tab == 3) { vm.tab(previousTab) }
    BackHandler(enabled = ui.tab == 0 && ui.data.usesWeb && web.view.canGoBack()) {
        if (ui.locked) vm.notice("请先暂停上课模式或停止整理，再返回上一页。") else web.view.goBack()
    }
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
        ui.chatgptLoginActive -> "正在等待账号授权，请先完成或取消本次授权。"
        ui.busy -> "正在${ui.operation}，请稍候或点击停止。"
        ui.classUi.running && !ui.classUi.paused -> "上课模式正在运行，请先暂停再操作。"
        else -> "请先准备所需课件或完成配置。"
    }
    val controlFeedback = remember(vm, unavailable) { ControlFeedback(vm::notice, unavailable) }
    CompositionLocalProvider(LocalControlFeedback provides controlFeedback) {
    StudyFrame(ui, onTab = { target -> if (target == 3) previousTab = ui.tab; vm.tab(target) }, onBack = { if (ui.tab == 2) vm.noteReader.closeRequested = true else vm.tab(previousTab) }, onStop = vm::stop, snackbar = snackbar, noteOpen = ui.tab == 2 && ui.data.notes.any { it.id == vm.noteReader.id }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(ui.operation, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall) }
            if (!ui.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else Box(Modifier.fillMaxSize()) {
                if (ui.data.usesWeb) WebLearningScreen(ui, vm, web, ui.tab == 0, webRevision)
                if (ui.tab != 0 || !ui.data.usesWeb) Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (ui.tab) { 0 -> ChatScreen(ui, vm); 1 -> RainScreen(ui, vm); 2 -> NotesScreen(ui, vm); else -> ModelScreen(ui, vm) }
                }
            }
        }
    }
    }
}

@Composable fun Markdown(text: String) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val color = colors.onSurface.toArgb()
    val link = colors.primary.toArgb()
    val code = colors.surfaceContainerHigh.toArgb()
    val line = colors.outlineVariant.toArgb()
    val row = colors.surfaceContainer.toArgb()
    val background = colors.background.toArgb()
    val density = LocalDensity.current.density
    val bodySize = with(LocalDensity.current) { 16.sp.toPx() }
    val renderer = remember(context, color, link, code, line, row, background, density) {
        Markwon.builder(context)
            .usePlugin(TablePlugin.create { builder -> builder.tableBorderColor(line).tableBorderWidth(maxOf(1, (density * 0.5f).toInt())).tableCellPadding((8 * density).toInt()).tableHeaderRowBackgroundColor(code).tableOddRowBackgroundColor(row).tableEvenRowBackgroundColor(background) })
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder.linkColor(link).codeTextColor(color).codeBlockTextColor(color).codeBackgroundColor(code).codeBlockBackgroundColor(code).blockQuoteColor(link).headingBreakColor(line).thematicBreakColor(line).listItemColor(link).headingTextSizeMultipliers(floatArrayOf(1.4f, 1.25f, 1.12f, 1.05f, 1f, 1f))
                }
            }).build()
    }
    val formatted by produceState<CharSequence>("", text, renderer) { value = withContext(Dispatchers.Default) { renderer.toMarkdown(text) } }
    AndroidView(factory = { TextView(it).apply { textSize = 16f; setTextIsSelectable(true); setLineSpacing(0f, 1.3f) } }, modifier = Modifier.fillMaxWidth(), update = {
        if (it.currentTextColor != color) it.setTextColor(color)
        if (kotlin.math.abs(it.textSize - bodySize) > 0.1f) it.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, bodySize)
        if (it.tag !== formatted) { it.text = formatted; it.tag = formatted }
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

@Composable fun ChatScreen(ui: UiState, vm: StudyViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val chat = ui.data.chat()
    val latestMessages by rememberUpdatedState(chat.messages)
    val listState = rememberLazyListState()
    LaunchedEffect(chat.id) { if (chat.messages.isNotEmpty()) listState.scrollToItem(chat.messages.lastIndex) }
    LaunchedEffect(chat.id, ui.busy) {
        if (ui.busy) snapshotFlow { latestMessages.lastOrNull()?.text?.length }.distinctUntilChanged().collect {
            if (latestMessages.isNotEmpty() && !listState.isScrollInProgress && (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= latestMessages.lastIndex - 1) listState.scrollToItem(latestMessages.lastIndex)
            delay(160)
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box { TextButton(onClick = { history = true }) { Text("会话 ▾") }; DropdownMenu(expanded = history, onDismissRequest = { history = false }) {
                ui.data.conversations.forEach { c -> DropdownMenuItem(text = { Text(c.title) }, onClick = { vm.selectChat(c.id); history = false }, trailingIcon = { IconButton(onClick = { vm.deleteChat(c.id); history = false }, enabled = !ui.locked) { Icon(Icons.Default.DeleteOutline, "删除对话") } }) }
            } }
            TextButton(onClick = vm::newChat, enabled = !ui.locked) { Text("新对话") }
            Spacer(Modifier.weight(1f)); TextButton(onClick = vm::notes, enabled = !ui.locked && (chat.messages.isNotEmpty() || ui.pending != null), unavailableReason = if (chat.messages.isEmpty() && ui.pending == null) "先选择一页课件或进行对话，再生成笔记。" else null) { Text("生成笔记") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (chat.messages.isEmpty()) item { StudyEmpty(Icons.Outlined.Description, "从一页课件开始", "选择一页课件，提问或整理笔记。", Modifier.padding(top = 24.dp)) { Spacer(Modifier.height(4.dp)); OutlinedButton(onClick = { vm.tab(1) }) { Text("选择课程") } } }
            itemsIndexed(chat.messages, key = { index, _ -> "${chat.id}:$index" }, contentType = { _, m -> m.role }) { index, m -> Surface(color = if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(start = if (m.role == "user") 32.dp else 0.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (m.role == "user") "你" else ui.data.provider().name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); m.source?.let { Text(it, style = MaterialTheme.typography.labelSmall) }; m.image?.let { DataImage(it, m.source ?: "课件") }; if (ui.busy && m.role == "assistant" && index == chat.messages.lastIndex) Text(m.text.ifBlank { "正在思考…" }) else Markdown(m.text.ifBlank { "正在思考…" }) }
            } }
        }
        ui.pending?.let { a -> ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null); Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = vm::clearAttachment, enabled = !ui.locked) { Icon(Icons.Default.Close, "移除课件附件") } } } }
        if (ui.data.provider().chatgptAccountId.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { Text("使用 ChatGPT 套餐", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") } }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            OutlinedTextField(input, { if (it.length <= 20000) input = it }, Modifier.weight(1f), placeholder = { Text("问问这页课件…") }, shape = RoundedCornerShape(16.dp), maxLines = 4, enabled = !ui.locked)
            FilledIconButton(onClick = { if (vm.send(input.trim())) input = "" }, enabled = !ui.locked && input.isNotBlank(), unavailableReason = if (input.isBlank()) "先输入你的问题，再发送。" else null) { Icon(Icons.Default.ArrowUpward, "发送给所选模型") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun RainScreen(ui: UiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var manualId by rememberSaveable { mutableStateOf("") }
    var versions by remember { mutableStateOf(false) }
    var fromPage by rememberSaveable(ui.presentationId) { mutableStateOf("1") }
    var toPage by rememberSaveable(ui.presentationId, ui.slides.size) { mutableStateOf(minOf(ui.slides.maxOfOrNull { it.page } ?: 1, 50).toString()) }
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        item {
            StudyPanel {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Outlined.School, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(RAIN_NAMES[ui.data.region] ?: "雨课堂", style = MaterialTheme.typography.titleMedium); Text(loginStatusText(ui.data, ui.loginActive, ui.loginStage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    ui.qr?.let { bytes -> val bitmap by produceState<Bitmap?>(null, bytes) { value = withContext(Dispatchers.Default) { preview(bytes) } }; bitmap?.let { Image(it.asImageBitmap(), "微信登录二维码", Modifier.fillMaxWidth().height(210.dp), contentScale = ContentScale.Fit) }; Button(onClick = ::saveQr, modifier = Modifier.fillMaxWidth()) { Text("保存二维码") }; Text("微信扫一扫 → 相册 → 选择二维码并确认，再返回这里。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        if (ui.data.session.isNotBlank()) {
            item { ClassModePanel(ui, vm) }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (ui.course == null) "我的课程" else ui.course.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); if (ui.course != null) TextButton(onClick = vm::allCourses, enabled = !ui.locked) { Text("返回课程") } else TextButton(onClick = vm::loadCourses, enabled = !ui.locked && !ui.loginActive) { Text("刷新") } } }
            if (ui.courses.isEmpty() && ui.course == null) item { StudyEmpty(Icons.Outlined.MenuBook, "还没有读取课程", "点击右上方刷新，读取你已加入的课程。") }
            if (ui.course == null) itemsIndexed(ui.courses, key = { _, it -> it.id }) { index, c ->
                Card(onClick = { vm.loadActivities(c) }, enabled = !ui.locked, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Outlined.MenuBook, null, Modifier.padding(11.dp).size(22.dp), tint = MaterialTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(c.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis); if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            ui.course?.let { c ->
                if (ui.slides.isEmpty()) {
                    items(ui.presentations, key = { "${it.lessonId}:${it.id}" }) { p ->
                        Card(onClick = { vm.openPresentation(p) }, enabled = !ui.locked, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                            ListItem(headlineContent = { Text(p.title, style = MaterialTheme.typography.titleSmall) }, supportingContent = { Text(if (p.isLesson) "课堂记录 · 查看本节课件" else "课件 · 查看页面") }, leadingContent = { Icon(if (p.isLesson) Icons.Default.School else Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary) }, trailingContent = { Icon(Icons.Default.ChevronRight, null) })
                        }
                    }
                    item { Row { TextButton(onClick = { vm.loadActivities(c, ui.activityPage - 1) }, enabled = !ui.locked && ui.activityPage > 0) { Text("上一批") }; Text("第 ${ui.activityPage + 1} 批", Modifier.padding(12.dp), style = MaterialTheme.typography.labelMedium); TextButton(onClick = { vm.loadActivities(c, ui.activityPage + 1) }, enabled = !ui.locked && ui.activityPage < 1000) { Text("下一批") } } }
                }
            }
            if (ui.slides.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(ui.slideTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${ui.slides.size} 个已开放页面 · 选择范围生成一份讲解笔记", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(fromPage, { if (it.length <= 5 && it.all(Char::isDigit)) fromPage = it }, label = { Text("开始页") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !ui.locked)
                        OutlinedTextField(toPage, { if (it.length <= 5 && it.all(Char::isDigit)) toPage = it }, label = { Text("结束页") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f), enabled = !ui.locked)
                    }
                    Button(onClick = { vm.batchNotes(fromPage, toPage) }, enabled = !ui.locked && fromPage.isNotBlank() && toPage.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("连续整理并讲解") }
                    Text("每次最多 50 页，按当前学习方式逐页整理并保存。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(ui.slides, key = { it.page }) { slide -> StudyPanel { Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Description, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Column(Modifier.weight(1f)) { Text("第 ${slide.page} 页", style = MaterialTheme.typography.titleSmall); if (slide.text.isNotBlank()) Text(notePreview(slide.text), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; TextButton(onClick = { vm.attach(slide) }, enabled = !ui.locked) { Text("带入学习") } } } }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级入口" else "高级：按课件编号读取") }; if (advanced) { OutlinedTextField(manualId, { if (it.length <= 24 && it.all(Char::isDigit)) manualId = it }, label = { Text("课件编号 presentation_id") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.loadSlides(manualId) }, enabled = !ui.locked && manualId.isNotBlank()) { Text("读取课件") } } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ClassModePanel(ui: UiState, vm: StudyViewModel) {
    val classButtonColors = ButtonDefaults.filledTonalButtonColors()
    val run = ui.data.classRun
    val resumable = run != null && (!run.ended || run.pending.isNotEmpty())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.School, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary); Text("上课模式", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleSmall); Text(when { ui.classUi.running && !ui.classUi.paused -> if (run?.ended == true) "整理中" else if (ui.classUi.connected) "跟随中" else "连接中"; resumable -> "已暂停"; else -> "未开始" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(if (resumable) run!!.lesson.title else "老师翻到新一页，笔记就跟着补充。", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (resumable) {
                Text("已整理 ${run!!.completed.size} 页 · 待处理 ${run.pending.size} 页", style = MaterialTheme.typography.labelLarge)
                Text(ui.classUi.status.ifBlank { "本节记录已保留，点击继续恢复。" }, style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(colors = classButtonColors, onClick = { if (ui.classUi.running && !ui.classUi.paused) vm.pauseClass() else vm.resumeClass() }, enabled = !ui.busy && !ui.chatgptLoginActive) { Text(if (ui.classUi.running && !ui.classUi.paused) "暂停" else "继续") }
                    TextButton(onClick = { vm.tab(2) }) { Text("查看笔记") }
                    TextButton(onClick = vm::endClass, colors = ButtonDefaults.textButtonColors(), enabled = !ui.busy) { Text("结束") }
                }
            } else {
                FilledTonalButton(colors = classButtonColors, onClick = vm::discoverClasses, enabled = !ui.locked && ui.data.session.isNotBlank()) { Icon(Icons.Default.PlayArrow, null); Text(if (ui.data.session.isBlank()) "登录后开启上课模式" else "查找正在上的课") }
                ui.liveLessons.forEach { lesson -> FilledTonalButton(colors = classButtonColors, onClick = { vm.startClass(lesson) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth()) { Text("进入：${lesson.title}") } }
            }
            Text(if (ui.data.usesWeb) "网页跟课为实验功能 · 保持 App 前台" else "进入会登记入课 · 保持 App 前台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun NotesScreen(ui: UiState, vm: StudyViewModel) {
    val reader = vm.noteReader
    val note = ui.data.notes.find { it.id == reader.id }
    var leaving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun requestClose() { if (note != null && reader.editing && reader.draft != note.content) leaving = true else reader.close() }
    BackHandler(enabled = note != null) { requestClose() }
    LaunchedEffect(reader.closeRequested) { if (reader.closeRequested) { reader.closeRequested = false; requestClose() } }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val n = note
        val text = if (reader.editing) reader.draft else n?.content.orEmpty()
        if (uri != null && n != null) scope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write("$text\n\n---\n来源：${n.source}\n模型：${n.model}\n") } ?: error("无法写入文件。") } }.fold({ vm.notice("Markdown 笔记已导出。") }, { vm.notice("笔记导出失败。") }) }
    }
    if (note != null) {
        val writable = !ui.locked && ui.data.noteWritable(note.id)
        if (leaving) AlertDialog(onDismissRequest = { leaving = false }, title = { Text("保存这次修改？") }, text = { Text("切换底部标签会保留草稿。返回笔记列表前，可以保存或放弃这次修改。") }, confirmButton = { TextButton(onClick = { if (vm.editNote(note.id, reader.draft)) { reader.close(); leaving = false } }, enabled = writable) { Text("保存并返回") } }, dismissButton = { FlowRow { TextButton(onClick = { reader.close(); leaving = false }) { Text("放弃修改") }; TextButton(onClick = { leaving = false }) { Text("继续编辑") } } })
        if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("删除这份笔记？") }, text = { Text("“${note.title}”将从本机删除，此操作无法撤销。") }, confirmButton = { TextButton(onClick = { if (vm.deleteNote(note.id)) { reader.close(); deleting = false } }, enabled = writable, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("删除笔记") } }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("保留笔记") } })
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilledTonalButton(onClick = { if (reader.editing) { if (vm.editNote(note.id, reader.draft)) reader.editing = false } else { reader.draft = note.content; reader.editing = true } }, enabled = writable, unavailableReason = "请先结束整理并恢复待保存回答，再编辑笔记。") { Icon(if (reader.editing) Icons.Default.Check else Icons.Outlined.Edit, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text(if (reader.editing) "保存修改" else "编辑笔记") }
                OutlinedButton(onClick = { export.launch(note.title.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(50) + ".md") }) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("导出 Markdown") }
            }
                Box { IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreHoriz, "笔记选项", Modifier.size(22.dp)) }; DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) { DropdownMenuItem(text = { Text("删除笔记…") }, enabled = writable, onClick = { menu = false; deleting = true }) } }
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(note.title, style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { details = !details }) { Text("来源与模型"); Icon(if (details) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp)) }
            if (details) StudyPanel { Text("来源：${note.source}\n模型：${note.model}", Modifier.padding(14.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            if (reader.editing) OutlinedTextField(reader.draft, { reader.draft = it }, Modifier.fillMaxWidth().heightIn(min = 360.dp), label = { Text("Markdown 笔记") }, enabled = writable) else Markdown(note.content)
            Spacer(Modifier.height(24.dp))
            }
        }
    } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.data.notes.size} 份笔记", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = vm::notes, enabled = !ui.locked) { Text(if (ui.data.usesWeb) "保存网页回答" else "生成笔记") }
        } }
        if (ui.classUi.running || ui.data.classRun?.let { !it.ended || it.pending.isNotEmpty() } == true) item { ClassModePanel(ui, vm) }
        if (ui.noteDraft.isNotBlank()) item { StudyPanel { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("正在整理", style = MaterialTheme.typography.titleSmall); Text(ui.noteDraft.takeLast(1600), maxLines = 8, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
        if (ui.data.notes.isEmpty()) item { StudyEmpty(Icons.Outlined.AutoStories, "还没有笔记", "在学习页保存回答，随时回看、编辑或导出。") { Spacer(Modifier.height(4.dp)); OutlinedButton(onClick = { vm.tab(0) }) { Text("去学习") } } }
        items(ui.data.notes, key = { it.id }) { n -> Card(onClick = { reader.open(n); details = false }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(n.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(notePreview(n.content), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { Text(n.source, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(remember(n.createdAt) { java.text.SimpleDateFormat("MM月dd日", java.util.Locale.CHINA).format(java.util.Date(n.createdAt)) }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline) }
            }
        } }
    }
}

@Composable fun ChatGptAccountPanel(ui: UiState, vm: StudyViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("使用 ChatGPT 账号", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("登录并授权后，符合资格的请求使用你的 ChatGPT 套餐，无需填写 API Key。可用模型和额度由账号授权决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { vm.startChatGptLogin() }, enabled = !ui.locked, colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White), modifier = Modifier.fillMaxWidth()) { Text("Continue with ChatGPT") }
            if (ui.chatgptLoginActive) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = vm::cancelChatGptLogin) { Text("取消本次授权") } }
            if (ui.chatgptStage.isNotBlank()) Text(ui.chatgptStage, style = MaterialTheme.typography.bodySmall)
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
            TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }, enabled = !ui.chatgptLoginActive) { Text("管理 ChatGPT 用量") }
            Text("授权会打开本机系统浏览器，完成后点击返回雨课伴学。登录资料加密保存在本机。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun ModelScreen(ui: UiState, vm: StudyViewModel) {
    var adding by remember { mutableStateOf<Provider?>(null) }
    LaunchedEffect(ui.data.selectedProvider) { adding = null }
    val current = adding ?: ui.data.provider()
    val usingChatGpt = current.chatgptAccountId.isNotBlank()
    var name by remember(current.id) { mutableStateOf(current.name) }
    var base by remember(current.id) { mutableStateOf(current.baseUrl) }
    var key by remember(current.id) { mutableStateOf(current.key) }
    var model by remember(current.id, current.model) { mutableStateOf(current.model) }
    var protocol by remember(current.id) { mutableStateOf(current.protocol) }
    var images by remember(current.id) { mutableStateOf(current.images) }
    var showConfig by remember(current.id) { mutableStateOf(adding != null) }
    var advancedModels by remember { mutableStateOf(ui.chatgptLoginActive) }
    LaunchedEffect(ui.chatgptLoginActive) { if (ui.chatgptLoginActive) advancedModels = true }
    var webConsent by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(false) }
    var chooseModel by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var showPrompts by rememberSaveable { mutableStateOf(false) }
    var tutor by remember(ui.data.tutorPrompt) { mutableStateOf(ui.data.tutorPrompt) }
    var notes by remember(ui.data.notesPrompt) { mutableStateOf(ui.data.notesPrompt) }
    fun provider() = current.copy(name = name.trim(), baseUrl = if (usingChatGpt) CHATGPT_API else base.trim(), key = if (usingChatGpt) "" else key.trim(), model = model.trim(), images = images, protocol = if (usingChatGpt) "responses" else protocol)
    if (ui.chatgptPlanWelcome) AlertDialog(onDismissRequest = vm::acknowledgeChatGptPlan, title = { Text("正在使用你的 ChatGPT 套餐") }, text = { Text("符合资格的聊天和笔记请求会使用你的 ChatGPT 套餐。你可以在 ChatGPT 设置中管理本应用的用量和访问权限。账号授权并不代表某个模型请求已经验证成功。") }, confirmButton = { TextButton(onClick = vm::acknowledgeChatGptPlan) { Text("知道了") } }, dismissButton = { TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") } })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StudySectionLabel("学习方式")
        StudyPanel {
            SettingOption("ChatGPT 网页", "在官网页面登录，使用网页中的对话。", Icons.Outlined.Language, ui.data.usesWeb, !ui.locked) { vm.learningMode("web") }
            HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            SettingOption("模型接口", "连接自己的模型服务或账号授权。", Icons.Outlined.Tune, !ui.data.usesWeb, !ui.locked) { vm.learningMode("native") }
            if (ui.data.usesWeb) {
                HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                TextButton(onClick = { vm.tab(0) }, modifier = Modifier.align(Alignment.End).padding(end = 8.dp)) { Text("进入 ChatGPT 网页"); Icon(Icons.Default.ArrowForward, null, Modifier.padding(start = 6.dp).size(16.dp)) }
            }
        }
        if (ui.data.usesWeb) {
            StudySectionLabel("上课模式 · 实验功能")
            StudyPanel {
                SettingsToggle("网页跟课适配", if (ui.data.webClassConsent) "逐页保存讲解 · 需保持 App 前台" else "自动带入新页并保存回答", ui.data.webClassConsent, !ui.locked, { if (it) webConsent = true else vm.consentWebClass(false) })
            }
            if (webConsent) AlertDialog(onDismissRequest = { webConsent = false }, title = { Text("启用网页跟课适配") }, text = { Text("App 会在当前 ChatGPT 网页中带入课件、发送问题，并把对应回答保存为笔记，使用网页所选模式及额度。\n\n请先在学习页登录，并保持 App 前台。网页改版、登录验证或额度提示可能中断跟课；中断后先恢复待完成回答，避免重复发送。此适配仍需手机实测，不会自动改走模型接口。") }, confirmButton = { TextButton(onClick = { vm.consentWebClass(); webConsent = false }) { Text("启用适配") } }, dismissButton = { TextButton(onClick = { webConsent = false }) { Text("暂不开启") } })
        } else Text("接口模式的授权与用量规则由所选服务决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        StudySectionLabel("笔记")
        StudyPanel {
            SettingsToggle("添加课件时自动整理", if (ui.data.usesWeb && !ui.data.webClassConsent) if (ui.data.autoNotes) "开启跟课适配后生效，可随时关闭" else "网页模式需先开启跟课适配" else "选择课件后自动生成讲解笔记", ui.data.autoNotes, !ui.locked && (!ui.data.usesWeb || ui.data.webClassConsent || ui.data.autoNotes), vm::autoNotes, if (!ui.locked && ui.data.usesWeb && !ui.data.webClassConsent) "请先开启网页跟课适配，再使用自动整理。" else null)
        }
        if (!ui.data.usesWeb) {
            StudySectionLabel("模型与账号")
            StudyPanel { Card(onClick = { advancedModels = !advancedModels }, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) { Text("配置模型与账号", style = MaterialTheme.typography.titleSmall); Text(if (current.model.isBlank()) "连接账号，或添加自己的服务" else "${current.name} · ${current.model}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (advancedModels) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp)) }
            } }
        }
        if (!ui.data.usesWeb && advancedModels) {
        ChatGptAccountPanel(ui, vm)
        Text("模型配置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.data.providers, key = { it.id }) { p -> FilterChip(selected = adding == null && current.id == p.id, onClick = { adding = null; vm.selectProvider(p.id) }, label = { Text(p.name) }, enabled = !ui.locked) } }
        TextButton(onClick = { adding = Provider(name = "自定义模型"); showConfig = true }, enabled = !ui.locked) { Text("添加模型配置（API 接入）") }
        if (!showConfig) TextButton(onClick = { showConfig = true }, enabled = !ui.locked) { Text("手动配置模型接口") }
        if (showConfig) {
            OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.locked)
            if (usingChatGpt) {
                Text("使用 ChatGPT 套餐 · 无需 API Key", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") }
            } else {
                Text("可接入兼容 Chat Completions 或 Responses 的模型服务。API 凭据加密保存在本机。", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = protocol == "chat", onClick = { protocol = "chat" }, label = { Text("Chat Completions") }, enabled = !ui.locked); FilterChip(selected = protocol == "responses", onClick = { protocol = "responses" }, label = { Text("Responses") }, enabled = !ui.locked) }
                OutlinedTextField(base, { base = it }, label = { Text("API Base URL") }, placeholder = { Text("填写模型服务商的 HTTPS 地址") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !ui.locked)
                OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "切换密钥显示") } }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !ui.locked)
            }
            OutlinedTextField(model, { model = it }, label = { Text("模型") }, placeholder = { Text(if (usingChatGpt) "读取列表后选择模型" else "填写或从列表选择模型名称") }, singleLine = true, readOnly = usingChatGpt, modifier = Modifier.fillMaxWidth(), enabled = !ui.locked)
            if (ui.modelListFor == current.id && ui.modelList.isNotEmpty()) {
                OutlinedButton(onClick = { modelQuery = ""; chooseModel = true }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth()) { Text("选择模型 ▾") }
                if (chooseModel) ModalBottomSheet(onDismissRequest = { chooseModel = false }) {
                    OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text("搜索模型") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    val candidates = remember(ui.modelList, modelQuery, ui.modelNames) { ui.modelList.filter { it.contains(modelQuery, ignoreCase = true) || ui.modelNames[it]?.contains(modelQuery, ignoreCase = true) == true } }
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) { items(candidates, key = { it }) { id -> TextButton(onClick = { model = id; chooseModel = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(ui.modelNames[id] ?: id) } } } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("所选模型支持图片输入", Modifier.weight(1f)); Switch(images, { images = it }, enabled = !ui.locked) }
            Text("课件图片需要支持图片的模型。列表可见不等于已验证图片权限，实际以请求结果为准。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (vm.saveProvider(provider())) adding = null }, enabled = !ui.locked && model.isNotBlank()) { Text("保存并使用") }
                OutlinedButton(onClick = { vm.testProvider(provider()) }, enabled = !ui.locked && (if (usingChatGpt) ui.data.providerReady(current) else key.isNotBlank() && base.isNotBlank())) { Text("读取模型") }
            }
            if (adding != null) TextButton(onClick = { adding = null }) { Text("取消新增") } else TextButton(onClick = { vm.removeProvider(current.id) }, enabled = !ui.locked && ui.data.providers.size > 1) { Text("删除此配置") }
        }
        }
        StudySectionLabel("助手设置")
        StudyPanel { Card(onClick = { showPrompts = !showPrompts }, colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) { Icon(Icons.Outlined.EditNote, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant); Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text("学习助手提示词", style = MaterialTheme.typography.titleSmall); Text("调整讲解方式与笔记结构", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(if (showPrompts) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(20.dp)) }
        } }
        if (showPrompts) {
            OutlinedTextField(tutor, { tutor = it }, label = { Text("学习助教提示词") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            OutlinedTextField(notes, { notes = it }, label = { Text("笔记助手提示词") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("保存提示词") }; TextButton(onClick = { tutor = TUTOR_PROMPT; notes = NOTES_PROMPT; vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("恢复默认") } }
        }
    }
}
