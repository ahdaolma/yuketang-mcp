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
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable fun StudyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF8ED7C2), onPrimary = Color(0xFF08382C), primaryContainer = Color(0xFF1B4136), onPrimaryContainer = Color(0xFFC4EFDF),
        secondary = Color(0xFFADC5BA), onSecondary = Color(0xFF20352C), secondaryContainer = Color(0xFF293D33), onSecondaryContainer = Color(0xFFDEEEE5),
        background = Color(0xFF111614), onBackground = Color(0xFFE7EDE9), surface = Color(0xFF111614), onSurface = Color(0xFFE7EDE9),
        surfaceVariant = Color(0xFF29332E), onSurfaceVariant = Color(0xFFAABBB1), surfaceContainer = Color(0xFF1B231F), surfaceContainerHigh = Color(0xFF242E28),
        outline = Color(0xFF75857B), outlineVariant = Color(0xFF344039)
    ) else lightColorScheme(
        primary = Color(0xFF22634F), onPrimary = Color.White, primaryContainer = Color(0xFFE7F1EB), onPrimaryContainer = Color(0xFF214F3E),
        secondary = Color(0xFF53695D), onSecondary = Color.White, secondaryContainer = Color(0xFFEDF2EE), onSecondaryContainer = Color(0xFF344F40),
        background = Color(0xFFF6F8F6), onBackground = Color(0xFF1B2921), surface = Color(0xFFF6F8F6), onSurface = Color(0xFF1B2921),
        surfaceVariant = Color(0xFFE8EEEA), onSurfaceVariant = Color(0xFF5D7064), surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDF2EE),
        outline = Color(0xFF8B9C91), outlineVariant = Color(0xFFDCE5DE)
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
    val labels = listOf("学习", "课程", "笔记")
    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.School, Icons.Outlined.AutoStories)
    val navigationHeight = (68f + (LocalDensity.current.fontScale - 1f).coerceAtLeast(0f) * 24f).dp
    Scaffold(
        topBar = { if (!(ui.data.usesWeb && ui.tab == 0)) TopAppBar(
            title = { Text(if (ui.tab == 3) "设置" else labels.getOrElse(ui.tab) { "学习" }, style = MaterialTheme.typography.titleLarge) },
            navigationIcon = { if (ui.tab == 3 || noteOpen) FeedbackIconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, if (noteOpen) "返回笔记列表" else "返回学习", Modifier.size(22.dp)) } },
            actions = { if (ui.busy) FeedbackTextButton(onClick = onStop) { Text("停止") }; if (ui.tab != 3) FeedbackIconButton(onClick = { onTab(3) }) { Icon(Icons.Outlined.Settings, "设置", Modifier.size(22.dp)) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
        ) },
        bottomBar = { if (ui.tab != 3) Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
            NavigationBar(Modifier.height(navigationHeight), containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                labels.forEachIndexed { index, label -> FeedbackNavigationBarItem(selected = ui.tab == index, onClick = { onTab(index) }, icon = { Icon(icons[index], label, Modifier.size(22.dp)) }, label = { Text(label, style = MaterialTheme.typography.labelMedium) }) }
            }
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        } },
        snackbarHost = { SnackbarHost(snackbar) }, content = content
    )
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
    FeedbackCard(onClick = onClick, enabled = enabled, colors = CardDefaults.cardColors(containerColor = Color.Transparent), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, Modifier.size(22.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(if (selected) Icons.Default.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable fun SettingsToggle(title: String, summary: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit, unavailableReason: String? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FeedbackSwitch(checked, onChecked, enabled = enabled, unavailableReason = unavailableReason)
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

fun notePreview(text: String) = text
    .replace(Regex("</?(?:p|br|div|span|strong|em|b|i|h[1-6]|li|ul|ol|table|tr|td|th)(?:\\s[^<>]*)?/?>", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("(?m)^\\s{0,3}(#{1,6}\\s+|>\\s+|[-*+]\\s+)"), "")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(Regex("\\s+"), " ").trim().take(120)
