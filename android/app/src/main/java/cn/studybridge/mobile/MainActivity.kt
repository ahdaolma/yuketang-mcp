package cn.studybridge.mobile

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
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
import androidx.lifecycle.viewmodel.compose.viewModel
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF8DD5BE)) else lightColorScheme(primary = Color(0xFF1B6554), background = Color(0xFFF6F8F5), surface = Color(0xFFF6F8F5))
            MaterialTheme(colorScheme = scheme) { StudyApp() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudyApp(vm: StudyViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val labels = listOf("聊天", "雨课堂", "笔记", "模型")
    val icons = listOf(Icons.AutoMirrored.Filled.Chat, Icons.Default.School, Icons.Default.AutoStories, Icons.Default.Tune)
    Scaffold(
        topBar = { TopAppBar(title = { Column { Text("雨课伴学"); Text("${labels[ui.tab]} · ${ui.data.provider().name}", style = MaterialTheme.typography.labelMedium) } }, actions = { if (ui.busy) TextButton(onClick = vm::stop) { Text("停止") } }) },
        bottomBar = { NavigationBar { labels.forEachIndexed { index, label -> NavigationBarItem(selected = ui.tab == index, onClick = { vm.tab(index) }, icon = { Icon(icons[index], label) }, label = { Text(label) }) } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (ui.busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(ui.operation, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium) }
            if (ui.notice.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), shape = MaterialTheme.shapes.medium) {
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
    AndroidView(factory = { TextView(it).apply { textSize = 16f; setTextIsSelectable(true); setLineSpacing(4f, 1.1f) } }, modifier = Modifier.fillMaxWidth(), update = { it.setTextColor(color); renderer.setMarkdown(it, text) })
}

private fun preview(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
@Composable fun DataImage(data: String, label: String) {
    val bitmap = remember(data) { runCatching { preview(Base64.decode(data.substringAfter(','), Base64.DEFAULT)) }.getOrNull() }
    bitmap?.let { Image(it.asImageBitmap(), label, Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit) }
}

@Composable fun ChatScreen(ui: UiState, vm: StudyViewModel) {
    var input by rememberSaveable { mutableStateOf("") }
    var history by remember { mutableStateOf(false) }
    val chat = ui.data.chat()
    val listState = rememberLazyListState()
    LaunchedEffect(chat.id, chat.messages.lastOrNull()?.text?.length) { if (chat.messages.isNotEmpty()) listState.animateScrollToItem(chat.messages.lastIndex) }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box { TextButton(onClick = { history = true }) { Text("会话 ▾") }; DropdownMenu(expanded = history, onDismissRequest = { history = false }) {
                ui.data.conversations.forEach { c -> DropdownMenuItem(text = { Text(c.title) }, onClick = { vm.selectChat(c.id); history = false }, trailingIcon = { IconButton(onClick = { vm.deleteChat(c.id); history = false }, enabled = !ui.busy) { Icon(Icons.Default.DeleteOutline, "删除对话") } }) }
            } }
            TextButton(onClick = vm::newChat, enabled = !ui.busy) { Text("新对话") }
            Spacer(Modifier.weight(1f)); TextButton(onClick = vm::notes, enabled = !ui.busy && (chat.messages.isNotEmpty() || ui.pending != null)) { Text("生成笔记") }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.data.providers, key = { it.id }) { p -> FilterChip(selected = p.id == ui.data.provider().id, onClick = { vm.selectProvider(p.id) }, enabled = !ui.busy, label = { Text(p.name) }) } }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (chat.messages.isEmpty()) item { Column(Modifier.padding(vertical = 32.dp)) { Text("从一页课件，开始学懂。", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(12.dp)); Text("先在模型页填写自己的 API 配置，再到雨课堂选择课件。也可以直接聊天。", color = MaterialTheme.colorScheme.onSurfaceVariant); Spacer(Modifier.height(16.dp)); TextButton(onClick = { vm.tab(3) }) { Text("配置 DeepSeek 或其他模型 →") } } }
            items(chat.messages) { m -> Surface(color = if (m.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(if (m.role == "user") "你" else "AI", style = MaterialTheme.typography.labelMedium); m.source?.let { Text(it, style = MaterialTheme.typography.labelSmall) }; m.image?.let { DataImage(it, m.source ?: "课件") }; Markdown(m.text.ifBlank { "正在思考…" }) }
            } }
        }
        ui.pending?.let { a -> ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Default.AttachFile, null); Text(a.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); IconButton(onClick = vm::clearAttachment, enabled = !ui.busy) { Icon(Icons.Default.Close, "移除课件附件") } } } }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            OutlinedTextField(input, { if (it.length <= 20000) input = it }, Modifier.weight(1f), placeholder = { Text("问问题，或让 AI 帮你理解课件…") }, maxLines = 4, enabled = !ui.busy)
            FilledIconButton(onClick = { if (vm.send(input.trim())) input = "" }, enabled = !ui.busy && input.isNotBlank()) { Icon(Icons.Default.ArrowUpward, "发送给所选模型") }
        }
    }
}

@Composable fun RainScreen(ui: UiState, vm: StudyViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("连接自己的雨课堂", style = MaterialTheme.typography.headlineSmall); Text("登录记录只加密保存在这台手机。当前支持手动选择课件页，尚未实现老师页码自动同步。", style = MaterialTheme.typography.bodySmall) }
        item { Box { OutlinedButton(onClick = { versions = true }, enabled = !ui.loginActive && !ui.busy) { Text("${RAIN_NAMES[ui.data.region]} ▾") }; DropdownMenu(expanded = versions, onDismissRequest = { versions = false }) { RAIN_NAMES.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { vm.region(id); versions = false }) } } } }
        item { Text(if (ui.data.session.isBlank()) "尚未连接账号" else "已连接：${ui.data.username}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = vm::startLogin, enabled = !ui.loginActive && !ui.busy) { Text("微信扫码登录") }; if (ui.loginActive) OutlinedButton(onClick = vm::cancelLogin) { Text("取消") }; if (ui.data.session.isNotBlank()) TextButton(onClick = vm::logout, enabled = !ui.busy && !ui.loginActive) { Text("移除登录") } }
        }
        ui.qr?.let { bytes -> item { val bitmap = remember(bytes) { preview(bytes) }; bitmap?.let { Image(it.asImageBitmap(), "微信登录二维码", Modifier.fillMaxWidth().height(260.dp), contentScale = ContentScale.Fit) }; Button(onClick = ::saveQr) { Text("保存二维码到相册") }; Text("微信扫一扫 → 相册 → 选择二维码。是否允许由微信决定；不支持时仍需另一台设备协助。", style = MaterialTheme.typography.bodySmall) } }
        if (ui.data.session.isNotBlank()) {
            item { Button(onClick = vm::loadCourses, enabled = !ui.busy) { Text("读取课程") } }
            items(ui.courses, key = { it.id }) { c -> OutlinedCard(onClick = { vm.loadActivities(c) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(c.title, Modifier.padding(16.dp)) } }
            ui.course?.let { c -> item { Text("${c.title} · 活动第 ${ui.activityPage + 1} 批", style = MaterialTheme.typography.titleMedium); Row { TextButton(onClick = { vm.loadActivities(c, ui.activityPage - 1) }, enabled = !ui.busy && ui.activityPage > 0) { Text("上一批") }; TextButton(onClick = { vm.loadActivities(c, ui.activityPage + 1) }, enabled = !ui.busy && ui.activityPage < 1000) { Text("下一批") } } } }
            items(ui.presentations, key = { it.id }) { p -> OutlinedButton(onClick = { vm.loadSlides(p.id) }, enabled = !ui.busy, modifier = Modifier.fillMaxWidth()) { Text(p.title) } }
            item { HorizontalDivider(); Text("已知课件编号", style = MaterialTheme.typography.titleSmall); OutlinedTextField(manualId, { if (it.length <= 24 && it.all(Char::isDigit)) manualId = it }, label = { Text("presentation_id") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { vm.loadSlides(manualId) }, enabled = !ui.busy && manualId.isNotBlank()) { Text("读取该课件") }; Text("编号须来自明确的 presentation_id 字段，不能使用课程或活动编号代替。", style = MaterialTheme.typography.bodySmall) }
            if (ui.slides.isNotEmpty()) item { Text(ui.slideTitle, style = MaterialTheme.typography.titleLarge) }
            items(ui.slides, key = { it.page }) { slide -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("第 ${slide.page} 页", Modifier.weight(1f)); Button(onClick = { vm.attach(slide) }, enabled = !ui.busy) { Text("添加到学习") } } }
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
        Row { TextButton(onClick = { if (editing) vm.editNote(note.id, draft); editing = !editing }) { Text(if (editing) "保存修改" else "编辑") }; TextButton(onClick = { export.launch(note.title.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(50) + ".md") }) { Text("导出 Markdown") }; TextButton(onClick = { vm.deleteNote(note.id); active = null }) { Text("删除") } }
        if (editing) OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 400.dp), label = { Text("Markdown 笔记") }) else Markdown(note.content)
    } else LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("把理解留成笔记", style = MaterialTheme.typography.headlineSmall); Row(verticalAlignment = Alignment.CenterVertically) { Text("添加课件后自动整理", Modifier.weight(1f)); Switch(ui.data.autoNotes, vm::autoNotes, enabled = !ui.busy) }; Text("使用当前选择的模型，课件内容会发送给该服务商。每添加一页生成一份，可编辑、删除和导出。", style = MaterialTheme.typography.bodySmall); Button(onClick = vm::notes, enabled = !ui.busy) { Text("从当前课件或对话生成") } }
        if (ui.noteDraft.isNotBlank()) item { ElevatedCard { Column(Modifier.padding(16.dp)) { Text("正在生成…"); Markdown(ui.noteDraft) } } }
        if (ui.data.notes.isEmpty()) item { Text("还没有笔记。可以开启自动整理，再去雨课堂添加一页课件。", Modifier.padding(vertical = 24.dp)) }
        items(ui.data.notes, key = { it.id }) { n -> OutlinedCard(onClick = { active = n.id; editing = false }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(n.title, style = MaterialTheme.typography.titleMedium); Text(n.source, style = MaterialTheme.typography.bodySmall); Text(n.content.take(100).replace('\n', ' '), maxLines = 3, style = MaterialTheme.typography.bodyMedium) } } }
    }
}

@Composable fun ModelScreen(ui: UiState, vm: StudyViewModel) {
    var adding by remember { mutableStateOf<Provider?>(null) }
    val current = adding ?: ui.data.provider()
    var name by remember(current.id) { mutableStateOf(current.name) }
    var base by remember(current.id) { mutableStateOf(current.baseUrl) }
    var key by remember(current.id) { mutableStateOf(current.key) }
    var model by remember(current.id) { mutableStateOf(current.model) }
    var images by remember(current.id) { mutableStateOf(current.images) }
    var visible by remember { mutableStateOf(false) }
    var showPrompts by rememberSaveable { mutableStateOf(false) }
    var tutor by remember(ui.data.tutorPrompt) { mutableStateOf(ui.data.tutorPrompt) }
    var notes by remember(ui.data.notesPrompt) { mutableStateOf(ui.data.notesPrompt) }
    fun provider() = current.copy(name = name.trim(), baseUrl = base.trim(), key = key.trim(), model = model.trim(), images = images)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("用你选择的 AI", style = MaterialTheme.typography.headlineSmall)
        Text("首版支持兼容 OpenAI Chat Completions 的服务。API Key 加密保存在手机，模型请求直接发往你填写的服务商。", style = MaterialTheme.typography.bodySmall)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(ui.data.providers, key = { it.id }) { p -> FilterChip(selected = adding == null && current.id == p.id, onClick = { adding = null; vm.selectProvider(p.id) }, label = { Text(p.name) }, enabled = !ui.busy) } }
        Row { TextButton(onClick = { adding = Provider() }, enabled = !ui.busy) { Text("新增 DeepSeek") }; TextButton(onClick = { adding = Provider(name = "自定义模型", baseUrl = "https://api.example.com/v1", model = "", images = false) }, enabled = !ui.busy) { Text("新增自定义") } }
        OutlinedTextField(name, { name = it }, label = { Text("配置名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy)
        OutlinedTextField(base, { base = it }, label = { Text("API Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !ui.busy)
        OutlinedTextField(key, { key = it }, label = { Text("API Key") }, singleLine = true, visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "切换密钥显示") } }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !ui.busy)
        OutlinedTextField(model, { model = it }, label = { Text("模型名称（以服务商提供的名称为准）") }, singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !ui.busy)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("此模型支持图片输入", Modifier.weight(1f)); Switch(images, { images = it }, enabled = !ui.busy) }
        Text("图片课件需要模型支持图片。纯文字模型可用于文字聊天和对话笔记；不会自动假定它能看图。", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { vm.saveProvider(provider()); adding = null }, enabled = !ui.busy) { Text("保存并使用") }; OutlinedButton(onClick = { vm.testProvider(provider()) }, enabled = !ui.busy && key.isNotBlank()) { Text("测试连接") } }
        if (adding != null) TextButton(onClick = { adding = null }) { Text("取消新增") } else TextButton(onClick = { vm.removeProvider(current.id) }, enabled = !ui.busy && ui.data.providers.size > 1) { Text("删除此配置") }
        HorizontalDivider()
        TextButton(onClick = { showPrompts = !showPrompts }) { Text(if (showPrompts) "收起内置角色 Prompt" else "查看 / 编辑内置角色 Prompt") }
        Text("学习助教和笔记助手的提示词会在每次请求中作为 system 消息发送，不依赖服务商知道本软件的定位。", style = MaterialTheme.typography.bodySmall)
        if (showPrompts) {
            OutlinedTextField(tutor, { tutor = it }, label = { Text("学习助教 Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.busy)
            OutlinedTextField(notes, { notes = it }, label = { Text("笔记助手 Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp), maxLines = 12, enabled = !ui.busy)
            Row { Button(onClick = { vm.savePrompts(tutor, notes) }, enabled = !ui.busy) { Text("保存提示词") }; TextButton(onClick = { tutor = TUTOR_PROMPT; notes = NOTES_PROMPT; vm.savePrompts(tutor, notes) }, enabled = !ui.busy) { Text("恢复默认") } }
        }
    }
}
