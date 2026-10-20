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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.core.view.WindowCompat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudyFrame(ui: UiState, onTab: (Int) -> Unit, onBack: () -> Unit, onStop: () -> Unit, snackbar: SnackbarHostState = remember { SnackbarHostState() }, noteOpen: Boolean = false, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth > maxHeight && maxHeight < 500.dp
    val reading = (ui.tab == 0 && !ui.data.usesWeb) || noteOpen
    val inlineHeader = wide && reading && !ui.busy
    val density = LocalDensity.current
    val chrome = remember(ui.tab, noteOpen, ui.data.currentChat) { ReadingChromeState(with(density) { 24.dp.toPx() }) }
    LaunchedEffect(ui.busy) { if (ui.busy) chrome.restore() }
    val slide by animateFloatAsState(if (reading && !ui.busy) { if (wide) 1f else chrome.fraction } else 0f, spring(dampingRatio = 1f, stiffness = 500f), label = "reading-toolbar-slide")
    val topHeight = (64f - 16f * slide).dp
    val titleSize = 22f - 4f * slide
    val labels = listOf("学习", "课程", "笔记")
    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.School, Icons.Outlined.AutoStories)
    val selectedIcons = listOf(Icons.Filled.ChatBubble, Icons.Filled.School, Icons.Filled.AutoStories)
    val expandedNavigation = 68f + (density.fontScale - 1f).coerceAtLeast(0f) * 24f
    val navigationHeight = (expandedNavigation + (52f - expandedNavigation) * slide).dp
    CompositionLocalProvider(LocalReadingChrome provides if (reading) chrome else null, LocalInlineReadingHeader provides inlineHeader) {
    Scaffold(
        topBar = { if (!(ui.data.usesWeb && ui.tab == 0) && !inlineHeader) TopAppBar(
            modifier = Modifier.testTag("应用顶部栏"), expandedHeight = topHeight,
            title = { Text(if (ui.tab == 3) "设置" else labels.getOrElse(ui.tab) { "学习" }, style = MaterialTheme.typography.titleLarge.copy(fontSize = titleSize.sp), maxLines = 1) },
            navigationIcon = { if (ui.tab == 3 || noteOpen) FeedbackIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, if (noteOpen) "返回笔记列表" else "返回学习", Modifier.size(22.dp)) } },
            actions = { if (ui.busy) FeedbackTextButton(onClick = onStop) { Text("停止") }; if (ui.tab != 3) FeedbackIconButton(onClick = { onTab(3) }) { Icon(Icons.Outlined.Settings, "设置", Modifier.size(22.dp)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        ) },
        bottomBar = { if (ui.tab != 3 && !wide) Column {
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
        if (wide && ui.tab != 3) Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Column(Modifier.width(64.dp).fillMaxHeight().testTag("横屏导航"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                labels.forEachIndexed { index, label ->
                    FeedbackIconButton(onClick = { onTab(index) }, modifier = Modifier.size(56.dp).semantics { contentDescription = label; selected = ui.tab == index; role = Role.Tab }) {
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
    }
    }
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
class NoteReader {
    var id by mutableStateOf<String?>(null)
    var editing by mutableStateOf(false)
    var draft by mutableStateOf("")
    var closeRequested by mutableStateOf(false)
    fun open(note: Note) { id = note.id; draft = note.content; editing = false; closeRequested = false }
    fun close() { id = null; draft = ""; editing = false; closeRequested = false }
}

private val previewTags = Regex("</?(?:p|br|div|span|strong|em|b|i|h[1-6]|li|ul|ol|table|tr|td|th)(?:\\s[^<>]*)?/?>", RegexOption.IGNORE_CASE)
private val previewMarkers = Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s+|[-*+]\\s+)")
private val previewWhitespace = Regex("\\s+")
// Preview a bounded prefix; scrolling the list must never parse an entire long notebook.
fun notePreview(text: String) = text.take(2048)
    .replace(previewTags, " ").replace(previewMarkers, "")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(previewWhitespace, " ").trim().take(120)
