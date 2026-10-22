package cn.studybridge.mobile

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable fun StudyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFB1D3C4), onPrimary = Color(0xFF183A2F), primaryContainer = Color(0xFF293D34), onPrimaryContainer = Color(0xFFE0EDE4),
        secondary = Color(0xFFBDC8BF), onSecondary = Color(0xFF2C352F), secondaryContainer = Color(0xFF303B34), onSecondaryContainer = Color(0xFFE1E8DE),
        background = Color(0xFF151918), onBackground = Color(0xFFEAECE6), surface = Color(0xFF151918), onSurface = Color(0xFFEAECE6),
        surfaceVariant = Color(0xFF303631), onSurfaceVariant = Color(0xFFB0BAB1), surfaceContainer = Color(0xFF202623), surfaceContainerHigh = Color(0xFF2B322D),
        surfaceContainerLowest = Color(0xFF101311), surfaceContainerLow = Color(0xFF1C211E), surfaceContainerHighest = Color(0xFF363E37),
        outline = Color(0xFF859086), outlineVariant = Color(0xFF39413A)
    ) else lightColorScheme(
        primary = Color(0xFF315B4C), onPrimary = Color.White, primaryContainer = Color(0xFFE7EEE6), onPrimaryContainer = Color(0xFF284B3D),
        secondary = Color(0xFF59695C), onSecondary = Color.White, secondaryContainer = Color(0xFFEBEEE7), onSecondaryContainer = Color(0xFF3C4E40),
        background = Color(0xFFF5F4F0), onBackground = Color(0xFF222B25), surface = Color(0xFFF5F4F0), onSurface = Color(0xFF222B25),
        surfaceVariant = Color(0xFFE9EBE4), onSurfaceVariant = Color(0xFF636E63), surfaceContainer = Color(0xFFFFFEFA), surfaceContainerHigh = Color(0xFFEFEEE8),
        surfaceContainerLowest = Color(0xFFFFFEFA), surfaceContainerLow = Color(0xFFF1F0EA), surfaceContainerHighest = Color(0xFFE4E6DD),
        outline = Color(0xFF8C968A), outlineVariant = Color(0xFFE0E2D8)
    )
    val regular = TextStyle(letterSpacing = 0.sp)
    val activity = LocalContext.current as? Activity
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle) {
        fun hideStatusBar() { activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            }
        } }
        hideStatusBar()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) hideStatusBar() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark }
            if (Build.VERSION.SDK_INT < 35) { @Suppress("DEPRECATION") window.statusBarColor = colors.background.toArgb(); @Suppress("DEPRECATION") window.navigationBarColor = colors.background.toArgb() }
        }
    }
    MaterialTheme(colorScheme = colors, shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp)), typography = Typography(
        headlineSmall = regular.copy(fontSize = 24.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = regular.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = regular.copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = regular.copy(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
        bodyLarge = regular.copy(fontSize = 16.sp, lineHeight = 26.sp), bodyMedium = regular.copy(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = regular.copy(fontSize = 13.sp, lineHeight = 20.sp), labelLarge = regular.copy(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelMedium = regular.copy(fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium), labelSmall = regular.copy(fontSize = 11.sp, lineHeight = 16.sp)
    ), content = content)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable fun StudyFrame(ui: UiState, onTab: (Int) -> Unit, onBack: () -> Unit, onStop: () -> Unit, snackbar: SnackbarHostState = remember { SnackbarHostState() }, noteOpen: Boolean = false, onClassToggle: () -> Unit = {}, classServiceRunning: Boolean = false, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE && maxWidth > maxHeight && maxHeight < 500.dp
    val landscapeKeyboard = wide && WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(ui.tab) { focus.clearFocus(); keyboard?.hide() }
    val reading = ui.tab == 0 || noteOpen
    val inlineHeader = reading
    val density = LocalDensity.current
    val chrome = remember(ui.tab, noteOpen, ui.data.currentChat) { ReadingChromeState(with(density) { 24.dp.toPx() }) }
    LaunchedEffect(ui.busy) { if (ui.busy) chrome.restore() }
    val slide by animateFloatAsState(if (reading && !ui.busy) { if (wide) 1f else chrome.fraction } else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "reading-toolbar-slide")
    val labels = listOf("学习", "课程", "笔记")
    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.School, Icons.Outlined.AutoStories)
    val selectedIcons = listOf(Icons.Filled.ChatBubble, Icons.Filled.School, Icons.Filled.AutoStories)
    val expandedNavigation = 68f + (density.fontScale - 1f).coerceAtLeast(0f) * 24f
    val navigationHeight = (expandedNavigation + (48f - expandedNavigation) * slide).dp
    var readerProgress by remember(noteOpen) { mutableStateOf<ReaderProgress?>(null) }
    val progressChanged = remember(noteOpen) { { value: ReaderProgress? -> readerProgress = value } }
    CompositionLocalProvider(LocalReadingChrome provides if (reading) chrome else null, LocalInlineReadingHeader provides inlineHeader, LocalReadingCompactFraction provides if (wide) 0f else slide, LocalReaderProgress provides progressChanged) {
    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        topBar = { Column(Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
            StudyTimeBar(readingProgress = if (noteOpen) readerProgress else null)
            if (ui.tab == 3) TopAppBar(
            modifier = Modifier.testTag("应用顶部栏"), expandedHeight = 56.dp, windowInsets = WindowInsets(0, 0, 0, 0),
            title = { Text(if (ui.tab == 3) settingsPageTitle(ui.settingsPage) else labels.getOrElse(ui.tab) { "学习" }, style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp), maxLines = 1) },
            navigationIcon = { if (ui.tab == 3 || noteOpen) FeedbackIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, if (noteOpen) "返回笔记列表" else if (ui.tab == 3 && ui.settingsPage.isNotEmpty()) "返回设置" else "返回学习", Modifier.size(22.dp)) } },
            actions = { if (ui.busy) FeedbackTextButton(onClick = onStop) { Text("停止") }; ClassManagerButton(); if (ui.tab != 3) FeedbackIconButton(onClick = { onTab(3) }) { Icon(Icons.Outlined.Settings, "设置", Modifier.size(22.dp)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        ) } },
        bottomBar = { if (ui.tab != 3 && !wide && !noteOpen) Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            if (reading) Row(Modifier.fillMaxWidth().height(navigationHeight).clipToBounds().testTag("应用底部导航")) {
                labels.forEachIndexed { index, label ->
                    val isSelected = ui.tab == index
                    FeedbackIconButton(onClick = { onTab(index) }, modifier = Modifier.weight(1f).fillMaxHeight().semantics { contentDescription = label; selected = isSelected; role = Role.Tab }) {
                        Box(Modifier.fillMaxSize()) {
                            Surface(Modifier.align(Alignment.TopCenter).offset(y = (6f + 4f * slide).dp), shape = RoundedCornerShape(11.dp), color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                                Icon(if (isSelected) selectedIcons[index] else icons[index], null, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(21.dp), tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(label, Modifier.align(Alignment.BottomCenter).offset(y = (20f * slide - 4f).dp).alpha(1f - slide).clearAndSetSemantics {}, style = MaterialTheme.typography.labelMedium, color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else NavigationBar(Modifier.height(navigationHeight).testTag("应用底部导航"), containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                labels.forEachIndexed { index, label ->
                    val selected = ui.tab == index
                    FeedbackNavigationBarItem(selected = selected, onClick = { onTab(index) },
                        icon = { Surface(shape = RoundedCornerShape(11.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) { Icon(if (selected) selectedIcons[index] else icons[index], null, Modifier.padding(horizontal = 12.dp, vertical = 6.dp).size(21.dp)) } },
                        label = { Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal) },
                        modifier = Modifier.semantics { contentDescription = label })
                }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        } },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (wide && ui.tab != 3 && !noteOpen) Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            FlowRow(Modifier.width(if (landscapeKeyboard) 96.dp else 64.dp).fillMaxHeight().testTag("横屏导航"), horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.Center, maxItemsInEachRow = if (landscapeKeyboard) 2 else 1) {
                labels.forEachIndexed { index, label ->
                    FeedbackIconButton(onClick = { onTab(index) }, modifier = Modifier.size(48.dp).semantics { contentDescription = label; selected = ui.tab == index; role = Role.Tab }) {
                        Surface(shape = RoundedCornerShape(12.dp), color = if (ui.tab == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent) {
                            Icon(if (ui.tab == index) selectedIcons[index] else icons[index], null, Modifier.padding(10.dp).size(22.dp), tint = if (ui.tab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            Box(Modifier.weight(1f).fillMaxHeight()) { content(PaddingValues(0.dp)) }
        } else content(padding)
    }
    if (showClassControl(ui, classServiceRunning)) ClassFloatingControl(ui.classUi.paused, ui.data.classRun!!.noteId, onClassToggle)
    }
    }
}

// Recompute at minute boundaries and on every foreground entry. Formatting uses
// the device timezone, including a timezone change while the app was paused.
fun studyClockText(now: Long = System.currentTimeMillis()): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date(now))

@Composable fun StudyTimeBar(clockCutoutHeight: androidx.compose.ui.unit.Dp? = null, readingProgress: ReaderProgress? = null) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock by produceState(studyClockText(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val now = System.currentTimeMillis()
                value = studyClockText(now)
                delay(60000L - now % 60000L)
            }
        }
    }
    val density = LocalDensity.current
    val view = LocalView.current
    val cutoutTop = WindowInsets.displayCutout.getTop(density)
    val barHeight = maxOf(36.dp, (clockCutoutHeight ?: with(density) { cutoutTop.toDp() }) + 8.dp)
    // The clock shares the safe cutout row; it is not placed below a second
    // full-width cutout spacer. Move it sideways if a left camera occupies it.
    val defaultStart = with(density) { 20.dp.roundToPx() }
    val clockWidth = with(density) { 60.dp.roundToPx() }
    val gap = with(density) { 8.dp.roundToPx() }
    var start = defaultStart
    if (Build.VERSION.SDK_INT >= 28) view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty().sortedBy { it.left }.forEach { rect ->
        if (rect.top < with(density) { barHeight.roundToPx() } && rect.left < start + clockWidth && rect.right > start) start = rect.right + gap
    }
    Row(Modifier.fillMaxWidth().height(barHeight).testTag("应用时间栏").padding(start = with(density) { start.toDp() }, end = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(clock, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp), maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { contentDescription = "当前时间 $clock" })
        // Reserve the middle slot even while hidden. Animation never changes
        // the row height or moves the body, clock, or page counter.
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds(), contentAlignment = Alignment.Center) {
            if (readingProgress?.title != null) androidx.compose.animation.AnimatedVisibility(
                readingProgress.titleVisible,
                enter = fadeIn(tween(320)) + slideInVertically(tween(360)) { it },
                exit = fadeOut(tween(280)) + slideOutVertically(tween(320)) { it }
            ) {
                Text(readingProgress.title, Modifier.testTag("笔记吸顶标题"), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (readingProgress != null) Text(readingProgress.text, Modifier.testTag("阅读页码").semantics { contentDescription = "当前阅读页码"; stateDescription = readingProgress.description }, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable fun StudyOperationProgress(ui: UiState) {
    if (!ui.busy) return
    val inline = LocalInlineReadingHeader.current
    LinearProgressIndicator(Modifier.fillMaxWidth().height(if (inline) 2.dp else 4.dp))
    if (!inline) Text(ui.operation, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
}

@Composable fun StudySectionLabel(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable fun StudyPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)) { Column(content = content) }
}

@Composable fun StudyEmpty(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, actions: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) { Icon(icon, null, Modifier.padding(16.dp).size(28.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(2.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, Modifier.widthIn(max = 300.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        actions()
    }
}

@Composable fun SettingOption(title: String, summary: String, icon: ImageVector, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val selectionColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent, label = "setting-selection")
    FeedbackCard(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { this.selected = selected; role = Role.RadioButton }, colors = CardDefaults.cardColors(containerColor = selectionColor), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(11.dp), color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) { Icon(icon, null, Modifier.padding(9.dp).size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable fun SettingsToggle(title: String, summary: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit, unavailableReason: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FeedbackSwitch(checked, onChecked, modifier = Modifier.semantics { contentDescription = title }, enabled = enabled, unavailableReason = unavailableReason)
    }
}

// ViewModel-owned: switching tabs or rotating does not silently discard an unsaved draft.
data class NoteDraft(val noteId: String, val content: String, val updatedAt: Long = System.currentTimeMillis(), val baseContent: String? = null)

class NoteReader(private val storedDraft: (String) -> NoteDraft? = { null }, private val onDraft: (String, String) -> Unit = { _, _ -> }) {
    var id by mutableStateOf<String?>(null)
    var editing by mutableStateOf(false)
    var baseContent: String = ""
        private set
    private var draftText by mutableStateOf("")
    var draft: String
        get() = draftText
        set(value) { if (value == draftText) return; draftText = value; if (editing) id?.let { onDraft(it, value) } }
    var closeRequested by mutableStateOf(false)
    fun open(note: Note) {
        editing = false; id = note.id
        val recovered = storedDraft(note.id)
        baseContent = recovered?.baseContent ?: note.content
        draftText = recovered?.content ?: note.content
        editing = recovered != null; closeRequested = false
    }
    fun beginEditing(note: Note) { if (id != note.id) open(note); if (!editing) { baseContent = note.content; draftText = note.content; editing = true } }
    fun acceptSaved(content: String) { baseContent = content; draftText = content }
    fun close() { editing = false; id = null; draftText = ""; closeRequested = false }
}

private val previewTags = Regex("</?(?:p|br|div|span|strong|em|b|i|h[1-6]|li|ul|ol|table|tr|td|th)(?:\\s[^<>]*)?/?>", RegexOption.IGNORE_CASE)
private val previewMarkers = Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s+|[-*+]\\s+)")
private val previewImages = Regex("!\\[([^\\]\\r\\n]*)\\]\\([^\\r\\n)]*\\)")
private val previewLinks = Regex("\\[([^\\]\\r\\n]+)\\]\\([^\\r\\n)]*\\)")
private val previewWhitespace = Regex("\\s+")
// Preview a bounded prefix; scrolling the list must never parse an entire long notebook.
fun notePreview(text: String) = text.take(2048)
    .replace(studyMarkPattern) { it.groupValues[2] }
    .replace(previewImages, "[图片]").replace(previewLinks) { it.groupValues[1] }
    .replace(previewTags, " ").replace(previewMarkers, "")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(previewWhitespace, " ").trim().take(120)
