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
import androidx.compose.material3.*
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import io.noties.markwon.Markwon
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
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(
                primary = Color(0xFF8DD6BE), onPrimary = Color(0xFF06382A), primaryContainer = Color(0xFF20473A), onPrimaryContainer = Color(0xFFBEEBD9),
                secondary = Color(0xFFB2C7BC), secondaryContainer = Color(0xFF293C33), onSecondaryContainer = Color(0xFFDBE7DF),
                background = Color(0xFF111713), surface = Color(0xFF111713), surfaceContainer = Color(0xFF1C241F), surfaceContainerHigh = Color(0xFF26302A), onSurface = Color(0xFFE2E9E2), onSurfaceVariant = Color(0xFFA9B9AE), outline = Color(0xFF596A5E), outlineVariant = Color(0xFF344238)
            ) else lightColorScheme(
                primary = Color(0xFF24634C), onPrimary = Color.White, primaryContainer = Color(0xFFE1EEE5), onPrimaryContainer = Color(0xFF224B38),
                secondary = Color(0xFF576A5D), secondaryContainer = Color(0xFFEDF2EC), onSecondaryContainer = Color(0xFF344D3D),
                background = Color(0xFFF8FAF7), surface = Color(0xFFF8FAF7), surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFF0F4EE), onSurface = Color(0xFF202B24), onSurfaceVariant = Color(0xFF6D7B70), outline = Color(0xFFA8B5A9), outlineVariant = Color(0xFFDFE7DF)
            )
            MaterialTheme(colorScheme = scheme) { StudyApp() }
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
    val labels = listOf("学习", "课程", "笔记")
    val icons = listOf(Icons.AutoMirrored.Filled.Chat, Icons.Default.School, Icons.Default.AutoStories)
    var previousTab by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(ui.tab) { if (ui.tab != 3) previousTab = ui.tab }
    var webRevision by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val web = remember(context) { ChatGptWebSession(context, { webRevision++ }, { url ->
        runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    }) }
    DisposableEffect(web) {
        vm.bindWeb(web)
        onDispose { vm.bindWeb(null); web.destroy() }
    }
    LaunchedEffect(ui.ready) { if (ui.ready && web.view.url == null) web.load(ui.data.webPending?.url ?: CHATGPT_WEB) }
    LaunchedEffect(webRevision) { vm.webLocation(web.pageUrl) }
    BackHandler(enabled = ui.tab == 3) { vm.tab(previousTab) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.notice) {
        if (ui.notice.isNotBlank()) {
            snackbar.currentSnackbarData?.dismiss()
            val message = ui.notice
            snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
            if (vm.ui.value.notice == message) vm.notice("")
        }
    }
    Scaffold(
        topBar = { if (!(ui.data.usesWeb && ui.tab == 0)) TopAppBar(
            title = { Text(if (ui.tab == 3) "设置" else labels[ui.tab], style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) },
            navigationIcon = { if (ui.tab == 3) IconButton(onClick = { vm.tab(previousTab) }) { Icon(Icons.Default.ArrowBack, "返回学习") } },
            actions = {
                if (ui.busy) TextButton(onClick = vm::stop) { Text("停止") }
                if (ui.tab != 3) IconButton(onClick = { previousTab = ui.tab; vm.tab(3) }) { Icon(Icons.Default.Settings, "设置") }
            }
        ) },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (ui.tab != 3) NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            labels.forEachIndexed { index, label -> NavigationBarItem(selected = ui.tab == index, onClick = { vm.tab(index) }, icon = { Icon(icons[index], label) }, label = { Text(label) }) }
        } }
    ) { padding ->
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

@Composable fun Markdown(text: String) {
    val context = LocalContext.current
    val renderer = remember(context) { Markwon.builder(context).usePlugin(TablePlugin.create(context)).build() }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val formatted by produceState<CharSequence>(text, text, renderer) { value = withContext(Dispatchers.Default) { renderer.toMarkdown(text) } }
    AndroidView(factory = { TextView(it).apply { textSize = 16f; setTextIsSelectable(true); setLineSpacing(4f, 1.1f) } }, modifier = Modifier.fillMaxWidth(), update = {
        if (it.currentTextColor != color) it.setTextColor(color)
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
            Spacer(Modifier.weight(1f)); TextButton(onClick = vm::notes, enabled = !ui.locked && (chat.messages.isNotEmpty() || ui.pending != null)) { Text("生成笔记") }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (chat.messages.isEmpty()) item { Column(Modifier.fillMaxWidth().padding(vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("从一页课件开始", style = MaterialTheme.typography.titleMedium)
                Text("选择课件，提问或整理笔记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { vm.tab(1) }) { Text("选择课程") }
            } }
            itemsIndexed(chat.messages, key = { index, _ -> "${chat.id}:$index" }, contentType = { _, m -> m.role }) { index, m -> Surface(color = if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(start = if (m.role == "user") 32.dp else 0.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (m.role == "user") "你" else ui.data.provider().name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); m.source?.let { Text(it, style = MaterialTheme.typography.labelSmall) }; m.image?.let { DataImage(it, m.source ?: "课件") }; if (ui.busy && m.role == "assistant" && index == chat.messages.lastIndex) Text(m.text.ifBlank { "正在思考…" }) else Markdown(m.text.ifBlank { "正在思考…" }) }
            } }
        }
        ui.pending?.let { a -> ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null); Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = vm::clearAttachment, enabled = !ui.locked) { Icon(Icons.Default.Close, "移除课件附件") } } } }
        if (ui.data.provider().chatgptAccountId.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { Text("使用 ChatGPT 套餐", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); TextButton(onClick = { vm.openLink(CHATGPT_USAGE) }) { Text("管理用量") } }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            OutlinedTextField(input, { if (it.length <= 20000) input = it }, Modifier.weight(1f), placeholder = { Text("问问这页课件…") }, shape = RoundedCornerShape(24.dp), maxLines = 4, enabled = !ui.locked)
            FilledIconButton(onClick = { if (vm.send(input.trim())) input = "" }, enabled = !ui.locked && input.isNotBlank()) { Icon(Icons.Default.ArrowUpward, "发送给所选模型") }
        }
    }
}

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
    var accountExpanded by remember { mutableStateOf(ui.data.session.isBlank()) }
    var advanced by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
        if (ui.data.session.isNotBlank()) item { ClassModePanel(ui, vm) }
        item {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(RAIN_NAMES[ui.data.region] ?: "雨课堂", style = MaterialTheme.typography.titleSmall); Text(loginStatusText(ui.data, ui.loginActive, ui.loginStage), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        TextButton(onClick = { accountExpanded = !accountExpanded }) { Text(if (accountExpanded) "收起" else "管理") }
                    }
                    if (accountExpanded) {
                        Text("登录信息加密保存在本机。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box { OutlinedButton(onClick = { versions = true }, enabled = !ui.loginActive && !ui.locked) { Text("切换版本 ▾") }; DropdownMenu(expanded = versions, onDismissRequest = { versions = false }) { RAIN_NAMES.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { vm.region(id); versions = false }) } } }
                        Row { Button(onClick = vm::startLogin, enabled = !ui.loginActive && !ui.locked) { Text(if (ui.data.session.isBlank()) "微信扫码登录" else "重新登录") }; if (ui.loginActive) TextButton(onClick = vm::cancelLogin) { Text("取消") }; if (ui.data.session.isNotBlank()) TextButton(onClick = vm::logout, enabled = !ui.locked) { Text("退出") } }
                        Row { TextButton(onClick = vm::diagnoseLogin, enabled = !ui.locked && !ui.loginActive) { Text("检查通道") }; if (ui.data.session.isNotBlank()) TextButton(onClick = vm::verifyLogin, enabled = !ui.locked) { Text("验证登录") } }
                        if (ui.loginSteps.isNotEmpty()) ui.loginSteps.takeLast(4).forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                    ui.qr?.let { bytes -> val bitmap by produceState<Bitmap?>(null, bytes) { value = withContext(Dispatchers.Default) { preview(bytes) } }; bitmap?.let { Image(it.asImageBitmap(), "微信登录二维码", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Fit) }; Button(onClick = ::saveQr) { Text("保存二维码") }; Text("微信扫一扫 → 相册 → 选择二维码并确认，再返回这里。", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        if (ui.data.session.isNotBlank()) {
            item { Row(verticalAlignment = Alignment.CenterVertically) { Text(if (ui.course == null) "我的课程" else ui.course.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); if (ui.course != null) TextButton(onClick = vm::allCourses, enabled = !ui.locked) { Text("返回课程") } else TextButton(onClick = vm::loadCourses, enabled = !ui.locked && !ui.loginActive) { Text("刷新") } } }
            if (ui.courses.isEmpty() && ui.course == null) item { Text("点击刷新，读取你已加入的课程。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (ui.course == null) itemsIndexed(ui.courses, key = { _, it -> it.id }) { index, c ->
                Card(onClick = { vm.loadActivities(c) }, enabled = !ui.locked, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) { Text("%02d".format(index + 1), Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(c.title, style = MaterialTheme.typography.titleSmall); if (c.detail.isNotBlank()) Text(c.detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
            items(ui.slides, key = { it.page }) { slide -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("第 ${slide.page} 页", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { vm.attach(slide) }, enabled = !ui.locked) { Text("添加到对话") } } } }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级入口" else "高级：按课件编号读取") }; if (advanced) { OutlinedTextField(manualId, { if (it.length <= 24 && it.all(Char::isDigit)) manualId = it }, label = { Text("课件编号 presentation_id") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.loadSlides(manualId) }, enabled = !ui.locked && manualId.isNotBlank()) { Text("读取课件") } } }
        }
    }
}

@Composable fun ClassModePanel(ui: UiState, vm: StudyViewModel) {
    val classButtonColors = ButtonDefaults.filledTonalButtonColors()
    val run = ui.data.classRun
    val resumable = run != null && (!run.ended || run.pending.isNotEmpty())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.School, null); Text("上课模式", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold); Text(if (ui.classUi.connected) "● 跟随中" else "逐页讲解", style = MaterialTheme.typography.labelMedium) }
            Text(if (resumable) run!!.lesson.title else "老师翻到新一页，笔记就跟着补充。", style = MaterialTheme.typography.bodyMedium)
            if (resumable) {
                Text("已整理 ${run!!.completed.size} 页 · 待处理 ${run.pending.size} 页", style = MaterialTheme.typography.labelLarge)
                Text(ui.classUi.status.ifBlank { "本节记录已保留，点击继续恢复。" }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(colors = classButtonColors, onClick = { if (ui.classUi.running && !ui.classUi.paused) vm.pauseClass() else vm.resumeClass() }, enabled = !ui.busy) { Text(if (ui.classUi.running && !ui.classUi.paused) "暂停" else "继续") }
                    FilledTonalButton(colors = classButtonColors, onClick = { vm.tab(2) }) { Text("查看笔记") }
                    TextButton(onClick = vm::endClass, colors = ButtonDefaults.textButtonColors(), enabled = !ui.busy) { Text("结束") }
                }
            } else {
                FilledTonalButton(colors = classButtonColors, onClick = vm::discoverClasses, enabled = !ui.locked && ui.data.session.isNotBlank()) { Icon(Icons.Default.PlayArrow, null); Text(if (ui.data.session.isBlank()) "登录后开启上课模式" else "查找正在上的课") }
                ui.liveLessons.forEach { lesson -> FilledTonalButton(colors = classButtonColors, onClick = { vm.startClass(lesson) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth()) { Text("进入：${lesson.title}") } }
            }
            Text(if (ui.data.usesWeb) "通过网页整理 · 保持 App 前台 · 网页适配需实测" else "进入会登记入课 · 保持 App 前台", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable fun NotesScreen(ui: UiState, vm: StudyViewModel) {
    var active by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val note = ui.data.notes.find { it.id == active }
    var draft by remember(note?.id) { mutableStateOf(note?.content ?: "") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val n = note
        if (uri != null && n != null) scope.launch { runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write("${if (editing) draft else n.content}\n\n---\n来源：${n.source}\n模型：${n.model}\n") } ?: error("无法写入文件。") } }.fold({ vm.notice("Markdown 笔记已导出。") }, { vm.notice("笔记导出失败。") }) }
    }
    if (note != null) Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { if (editing) vm.editNote(note.id, draft); active = null; editing = false }) { Text("← 全部笔记") }
        Text(note.title, style = MaterialTheme.typography.headlineSmall); Text("来源：${note.source}\n模型：${note.model}", style = MaterialTheme.typography.bodySmall)
        Row { TextButton(onClick = { if (editing) vm.editNote(note.id, draft); editing = !editing }, enabled = ui.data.classRun?.let { it.noteId != note.id || it.ended && it.pending.isEmpty() } != false) { Text(if (editing) "保存修改" else "编辑") }; TextButton(onClick = { export.launch(note.title.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(50) + ".md") }) { Text("导出 Markdown") }; TextButton(onClick = { vm.deleteNote(note.id); active = null }, enabled = ui.data.classRun?.let { it.noteId != note.id || it.ended && it.pending.isEmpty() } != false) { Text("删除") } }
        if (editing) OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 400.dp), label = { Text("Markdown 笔记") }) else Markdown(note.content)
    } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${ui.data.notes.size} 份笔记", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = vm::notes, enabled = !ui.locked) { Text(if (ui.data.usesWeb) "保存网页回答" else "生成笔记") }
        } }

        if (ui.classUi.running || ui.data.classRun?.let { !it.ended } == true) item { ClassModePanel(ui, vm) }
        if (ui.noteDraft.isNotBlank()) item { ElevatedCard { Column(Modifier.padding(16.dp)) { Text("正在整理，完成后可查看全文…"); Text(ui.noteDraft.takeLast(2400), maxLines = 18) } } }
        if (ui.data.notes.isEmpty()) item { Text("还没有笔记。学习页的回答可以保存到这里。", Modifier.padding(vertical = 24.dp)) }
        items(ui.data.notes, key = { it.id }) { n -> OutlinedCard(onClick = { active = n.id; editing = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(n.title, style = MaterialTheme.typography.titleMedium); Text(n.source, style = MaterialTheme.typography.bodySmall); Text(n.content.take(100).replace('\n', ' '), maxLines = 3, style = MaterialTheme.typography.bodyMedium) } } }
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

@OptIn(ExperimentalMaterial3Api::class)
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("学习方式", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = ui.data.usesWeb, onClick = { vm.learningMode("web") }, label = { Text("ChatGPT 网页") }, enabled = !ui.locked)
            FilterChip(selected = !ui.data.usesWeb, onClick = { vm.learningMode("native") }, label = { Text("模型接口") }, enabled = !ui.locked)
        }
        Text(if (ui.data.usesWeb) "直接使用 ChatGPT 页面，在网页中登录并选择聊天模式。App 不发起模型接口请求，实际用量以网页提示为准。" else "由 App 调用模型接口。ChatGPT 套餐授权受第三方应用额度限制，与普通网页聊天体验不同。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ui.data.usesWeb) {
            OutlinedButton(onClick = { vm.tab(0) }, modifier = Modifier.fillMaxWidth()) { Text("进入 ChatGPT 网页") }
            ListItem(headlineContent = { Text("网页跟课适配") }, supportingContent = { Text("自动带入新页并保存回答 · 实验功能") }, trailingContent = { Switch(ui.data.webClassConsent, { if (it) webConsent = true else vm.consentWebClass(false) }, enabled = !ui.locked) })
            Text("网页改版、登录验证或额度提示可能中断跟课；中断后先恢复待完成回答，避免重复发送。普通网页聊天不需要开启此项。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (webConsent) AlertDialog(onDismissRequest = { webConsent = false }, title = { Text("启用网页跟课适配") }, text = { Text("开启上课模式或范围笔记后，App 会操作当前 ChatGPT 页面的附件、输入框和发送按钮，并读取对应回答保存笔记。使用你在网页选择的模式和网页自身额度。请先登录并打开可用对话；保持 App 前台。此适配未经你的手机与账号实测，网页变化时会暂停，不会改走模型接口。") }, confirmButton = { TextButton(onClick = { vm.consentWebClass(); webConsent = false }) { Text("启用适配") } }, dismissButton = { TextButton(onClick = { webConsent = false }) { Text("暂不开启") } })
        }
        HorizontalDivider()
        ListItem(headlineContent = { Text("添加课件时自动整理") }, trailingContent = { Switch(ui.data.autoNotes, vm::autoNotes, enabled = !ui.locked) })
        if (!ui.data.usesWeb) {
            TextButton(onClick = { advancedModels = !advancedModels }) { Text(if (advancedModels) "收起账号与模型" else "配置模型与账号") }
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
        HorizontalDivider()
        TextButton(onClick = { showPrompts = !showPrompts }) { Text(if (showPrompts) "收起内置角色 Prompt" else "查看 / 编辑内置角色 Prompt") }
        if (showPrompts) {
            OutlinedTextField(tutor, { tutor = it }, label = { Text("学习助教 Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            OutlinedTextField(notes, { notes = it }, label = { Text("笔记助手 Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            Row { Button(onClick = { vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("保存提示词") }; TextButton(onClick = { tutor = TUTOR_PROMPT; notes = NOTES_PROMPT; vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("恢复默认") } }
        }
    }
}
