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
    val labels = listOf("聊天", "雨课堂", "笔记", "模型")
    val icons = listOf(Icons.AutoMirrored.Filled.Chat, Icons.Default.School, Icons.Default.AutoStories, Icons.Default.Tune)
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("雨课伴学", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text("${labels[ui.tab]} · ${ui.data.provider().name}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }, actions = { if (ui.busy) TextButton(onClick = vm::stop) { Text("停止") } }) },
        bottomBar = { NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) { labels.forEachIndexed { index, label -> NavigationBarItem(selected = ui.tab == index, onClick = { vm.tab(index) }, icon = { Icon(icons[index], label) }, label = { Text(label) }) } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(ui.operation, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
            if (ui.notice.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(ui.notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = { vm.notice("") }) { Icon(Icons.Default.Close, "关闭提示") } }
            }
            if (!ui.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else when (ui.tab) { 0 -> ChatScreen(ui, vm); 1 -> RainScreen(ui, vm); 2 -> NotesScreen(ui, vm); else -> ModelScreen(ui, vm) }
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.data.providers, key = { it.id }) { p -> FilterChip(selected = p.id == ui.data.provider().id, onClick = { vm.selectProvider(p.id) }, enabled = !ui.locked, label = { Text(p.name) }) } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (chat.messages.isEmpty()) item { Column(Modifier.padding(vertical = 32.dp)) { Text("把每一页，学明白。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold); Spacer(Modifier.height(12.dp)); Text("连接雨课堂，随课件翻页积累讲解笔记。也可以选一页，聊透一个知识点。", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)); Row { TextButton(onClick = { vm.tab(1) }) { Text("开启上课模式 →") }; TextButton(onClick = { vm.tab(3) }) { Text("选择 AI") } } } }
            itemsIndexed(chat.messages, key = { index, _ -> "${chat.id}:$index" }, contentType = { _, m -> m.role }) { index, m -> Surface(color = if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else Color.Transparent, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(start = if (m.role == "user") 32.dp else 0.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (m.role == "user") "你" else ui.data.provider().name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); m.source?.let { Text(it, style = MaterialTheme.typography.labelSmall) }; m.image?.let { DataImage(it, m.source ?: "课件") }; if (ui.busy && m.role == "assistant" && index == chat.messages.lastIndex) Text(m.text.ifBlank { "正在思考…" }) else Markdown(m.text.ifBlank { "正在思考…" }) }
            } }
        }
        ui.pending?.let { a -> ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null); Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = vm::clearAttachment, enabled = !ui.locked) { Icon(Icons.Default.Close, "移除课件附件") } } } }
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
    var toPage by rememberSaveable(ui.presentationId, ui.slides.size) { mutableStateOf(minOf(ui.slides.size, 50).toString()) }
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
        item { ClassModePanel(ui, vm) }
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
                    Text("每次最多 50 页，逐页调用当前模型并保存。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(ui.slides, key = { it.page }) { slide -> Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(12.dp)) { Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Text("第 ${slide.page} 页", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); TextButton(onClick = { vm.attach(slide) }, enabled = !ui.locked) { Text("添加到对话") } } } }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "收起高级入口" else "高级：按课件编号读取") }; if (advanced) { OutlinedTextField(manualId, { if (it.length <= 24 && it.all(Char::isDigit)) manualId = it }, label = { Text("课件编号 presentation_id") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.loadSlides(manualId) }, enabled = !ui.locked && manualId.isNotBlank()) { Text("读取课件") } } }
        }
    }
}

@Composable fun ClassModePanel(ui: UiState, vm: StudyViewModel) {
    val run = ui.data.classRun
    val resumable = run != null && (!run.ended || run.pending.isNotEmpty())
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.School, null); Text("上课模式", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Text(if (ui.classUi.connected) "● 跟随中" else "逐页讲解", style = MaterialTheme.typography.labelMedium) }
            Text(if (resumable) run!!.lesson.title else "老师翻到新一页，笔记就跟着补充。", style = MaterialTheme.typography.bodyMedium)
            if (resumable) {
                Text("已整理 ${run!!.completed.size} 页 · 待处理 ${run.pending.size} 页", style = MaterialTheme.typography.labelLarge)
                Text(ui.classUi.status.ifBlank { "本节记录已保留，点击继续恢复。" }, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { if (ui.classUi.running && !ui.classUi.paused) vm.pauseClass() else vm.resumeClass() }, enabled = !ui.busy) { Text(if (ui.classUi.running && !ui.classUi.paused) "暂停" else "继续") }
                    FilledTonalButton(onClick = { vm.tab(2) }) { Text("查看笔记") }
                    TextButton(onClick = vm::endClass, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onPrimary), enabled = !ui.busy) { Text("结束") }
                }
            } else {
                FilledTonalButton(onClick = vm::discoverClasses, enabled = !ui.locked && ui.data.session.isNotBlank()) { Icon(Icons.Default.PlayArrow, null); Text("查找正在上的课") }
                ui.liveLessons.forEach { lesson -> FilledTonalButton(onClick = { vm.startClass(lesson) }, enabled = !ui.locked, modifier = Modifier.fillMaxWidth()) { Text("进入：${lesson.title}") } }
            }
            Text("进入会在雨课堂登记入课，并将展示页发送给所选模型。请保持 App 前台；切到后台会暂停，返回后可继续。", style = MaterialTheme.typography.labelSmall)
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
        item { Text("我的学习笔记", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold); Row(verticalAlignment = Alignment.CenterVertically) { Text("添加课件后自动整理", Modifier.weight(1f)); Switch(ui.data.autoNotes, vm::autoNotes, enabled = !ui.locked) }; Text("课件笔记按来源逐页讲解。上课模式会持续补充同一份笔记，范围整理可一次处理多页。", style = MaterialTheme.typography.bodySmall); Button(onClick = vm::notes, enabled = !ui.locked) { Text("从当前课件或对话生成") } }
        if (ui.classUi.running || ui.data.classRun?.let { !it.ended } == true) item { ClassModePanel(ui, vm) }
        if (ui.noteDraft.isNotBlank()) item { ElevatedCard { Column(Modifier.padding(16.dp)) { Text("正在整理，完成后可查看全文…"); Text(ui.noteDraft.takeLast(2400), maxLines = 18) } } }
        if (ui.data.notes.isEmpty()) item { Text("还没有笔记。可以开启自动整理，再去雨课堂添加一页课件。", Modifier.padding(vertical = 24.dp)) }
        items(ui.data.notes, key = { it.id }) { n -> OutlinedCard(onClick = { active = n.id; editing = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(n.title, style = MaterialTheme.typography.titleMedium); Text(n.source, style = MaterialTheme.typography.bodySmall); Text(n.content.take(100).replace('\n', ' '), maxLines = 3, style = MaterialTheme.typography.bodyMedium) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ModelScreen(ui: UiState, vm: StudyViewModel) {
    var adding by remember { mutableStateOf<Provider?>(null) }
    val current = adding ?: ui.data.provider()
    var name by remember(current.id) { mutableStateOf(current.name) }
    var base by remember(current.id) { mutableStateOf(current.baseUrl) }
    var key by remember(current.id) { mutableStateOf(current.key) }
    var model by remember(current.id) { mutableStateOf(current.model) }
    var protocol by remember(current.id) { mutableStateOf(current.protocol) }
    var images by remember(current.id) { mutableStateOf(current.images) }
    var visible by remember { mutableStateOf(false) }
    var chooseModel by remember { mutableStateOf(false) }
    var modelQuery by remember { mutableStateOf("") }
    var showPrompts by rememberSaveable { mutableStateOf(false) }
    var tutor by remember(ui.data.tutorPrompt) { mutableStateOf(ui.data.tutorPrompt) }
    var notes by remember(ui.data.notesPrompt) { mutableStateOf(ui.data.notesPrompt) }
    fun provider() = current.copy(name = name.trim(), baseUrl = base.trim(), key = key.trim(), model = model.trim(), images = images, protocol = protocol)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("用你选择的 AI", style = MaterialTheme.typography.headlineSmall)
        Text("支持 OpenAI Responses 与兼容 Chat Completions 的服务。密钥加密保存在手机，请求直接发送给你选择的服务商。", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.data.providers, key = { it.id }) { p -> FilterChip(selected = adding == null && current.id == p.id, onClick = { adding = null; vm.selectProvider(p.id) }, label = { Text(p.name) }, enabled = !ui.locked) } }
        Row { TextButton(onClick = { adding = Provider(name = "OpenAI GPT", baseUrl = "https://api.openai.com/v1", model = "", images = true, protocol = "responses") }, enabled = !ui.locked) { Text("新增 OpenAI") }; TextButton(onClick = { adding = Provider() }, enabled = !ui.locked) { Text("新增 DeepSeek") }; TextButton(onClick = { adding = Provider(name = "自定义模型", baseUrl = "https://api.example.com/v1", model = "", images = false) }, enabled = !ui.locked) { Text("新增自定义") } }
        OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.locked)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(selected = protocol == "chat", onClick = { protocol = "chat" }, label = { Text("Chat Completions") }, enabled = !ui.locked); FilterChip(selected = protocol == "responses", onClick = { protocol = "responses" }, label = { Text("Responses") }, enabled = !ui.locked) }
        if (protocol == "responses") Text("这里使用 OpenAI API Key；本版未提供 ChatGPT 账号授权登录。填写密钥后读取模型列表，按账号权限选择支持图片的模型。", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(base, { base = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !ui.locked)
        OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "切换密钥显示") } }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !ui.locked)
        OutlinedTextField(model, { model = it }, label = { Text("模型名称（以服务商提供的名称为准）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.locked)
        if (ui.modelListFor == current.id && ui.modelList.isNotEmpty()) {
            OutlinedButton(onClick = { modelQuery = ""; chooseModel = true }, enabled = !ui.locked) { Text("从已读取的模型列表选择 ▾") }
            if (chooseModel) ModalBottomSheet(onDismissRequest = { chooseModel = false }) {
                OutlinedTextField(modelQuery, { modelQuery = it }, label = { Text("搜索模型名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                val candidates = remember(ui.modelList, modelQuery) { ui.modelList.filter { it.contains(modelQuery, ignoreCase = true) } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(candidates, key = { it }) { id -> TextButton(onClick = { model = id; chooseModel = false }, modifier = Modifier.fillMaxWidth()) { Text(id) } }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("此模型支持图片输入", Modifier.weight(1f)); Switch(images, { images = it }, enabled = !ui.locked) }
        Text("图片课件需要模型支持图片。纯文字模型可用于文字聊天和对话笔记；不会自动假定它能看图。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { if (vm.saveProvider(provider())) adding = null }, enabled = !ui.locked) { Text("保存并使用") }; OutlinedButton(onClick = { vm.testProvider(provider()) }, enabled = !ui.locked && key.isNotBlank()) { Text("读取模型") } }
        if (adding != null) TextButton(onClick = { adding = null }) { Text("取消新增") } else TextButton(onClick = { vm.removeProvider(current.id) }, enabled = !ui.locked && ui.data.providers.size > 1) { Text("删除此配置") }
        HorizontalDivider()
        TextButton(onClick = { showPrompts = !showPrompts }) { Text(if (showPrompts) "收起内置角色 Prompt" else "查看 / 编辑内置角色 Prompt") }
        Text("学习助教和笔记助手的提示词会在每次请求中作为 system 消息发送，不依赖服务商知道本软件的定位。", style = MaterialTheme.typography.bodySmall)
        if (showPrompts) {
            OutlinedTextField(tutor, { tutor = it }, label = { Text("学习助教 Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            OutlinedTextField(notes, { notes = it }, label = { Text("笔记助手 Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.locked)
            Row { Button(onClick = { vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("保存提示词") }; TextButton(onClick = { tutor = TUTOR_PROMPT; notes = NOTES_PROMPT; vm.savePrompts(tutor, notes) }, enabled = !ui.locked) { Text("恢复默认") } }
        }
    }
}
