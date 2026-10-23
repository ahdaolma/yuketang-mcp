package cn.studybridge.mobile

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.widget.TextView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.ceil
import kotlin.math.floor

data class NoteSelectionBlock(val source: String, val sourceStart: Int)
data class NoteSelectionGeometry(val boundsInWindow: IntRect, val safeWindowBounds: IntRect)
data class NoteTextSelection(val view: NoteSelectableTextView, val block: NoteSelectionBlock, val start: Int, val end: Int, val quote: String, val geometry: NoteSelectionGeometry? = null) {
    fun annotation(kind: String, comment: String = "") = NoteAnnotation(sourceStart = block.sourceStart, sourceLength = block.source.length, sourceHash = imageDigest(block.source.toByteArray()), start = start, end = end, quote = quote, kind = kind, comment = comment)
}
class NoteSelectionController {
    var selection by mutableStateOf<NoteTextSelection?>(null)
    var openedRemark by mutableStateOf<String?>(null)
}
val LocalNoteSelection = staticCompositionLocalOf<NoteSelectionController?> { null }
val LocalNoteAnnotations = staticCompositionLocalOf { emptyList<NoteAnnotation>() }

// Both native geometry and the PopupPositionProvider contract use window pixels,
// not screen pixels or dp. Keep the measured toolbar beside the visible range.
internal class NoteSelectionPositionProvider(private val geometry: NoteSelectionGeometry, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val selected = geometry.boundsInWindow
        val safe = geometry.safeWindowBounds
        val minX = safe.left + minOf(gap, ((safe.width - popupContentSize.width) / 2).coerceAtLeast(0))
        val maxX = (safe.right - gap - popupContentSize.width).coerceAtLeast(minX)
        val minY = safe.top + minOf(gap, ((safe.height - popupContentSize.height) / 2).coerceAtLeast(0))
        val maxY = (safe.bottom - gap - popupContentSize.height).coerceAtLeast(minY)
        val above = selected.top - gap - popupContentSize.height
        val below = selected.bottom + gap
        val y = when {
            above >= minY -> above
            below <= maxY -> below
            selected.top - safe.top >= safe.bottom - selected.bottom -> above
            else -> below
        }.coerceIn(minY, maxY)
        return IntOffset((selected.center.x - popupContentSize.width / 2).coerceIn(minX, maxX), y)
    }
}

// Native text layout and drag handles remain accurate across fonts and line
// wraps; all visible selection actions belong to the application's Compose UI.
class NoteSelectableTextView(context: Context) : TextView(context) {
    init { isHapticFeedbackEnabled = false }
    var searchRevealKey: String? = null
    var block: NoteSelectionBlock? = null
    var controller: NoteSelectionController? = null
    private var applicationActionMode: ActionMode? = null
    private val selectionLayoutListener = ViewTreeObserver.OnPreDrawListener {
        // Parent scrolling and window/IME changes do not call onSelectionChanged.
        // Only refresh an existing selection, so dismissal cannot reopen it.
        if (controller?.selection?.view === this) reportSelection()
        true
    }
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    internal fun selectionGeometry(first: Int, last: Int): NoteSelectionGeometry? {
        val textLayout = layout ?: return null
        if (!isAttachedToWindow || !isShown || first < 0 || last <= first || last > text.length) return null
        val root = rootView
        val rootLocation = IntArray(2); root.getLocationInWindow(rootLocation)
        val screenLocation = IntArray(2); root.getLocationOnScreen(screenLocation)
        val visibleFrame = Rect(); root.getWindowVisibleDisplayFrame(visibleFrame)
        // getWindowVisibleDisplayFrame is in screen coordinates (including IME
        // clipping); convert once before combining it with window coordinates.
        visibleFrame.offset(rootLocation[0] - screenLocation[0], rootLocation[1] - screenLocation[1])
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val safe = Rect(rootLocation[0] + (insets?.left ?: 0), rootLocation[1] + (insets?.top ?: 0), rootLocation[0] + root.width - (insets?.right ?: 0), rootLocation[1] + root.height - (insets?.bottom ?: 0))
        if (!safe.intersect(visibleFrame) || safe.isEmpty) return null
        val visibleText = Rect()
        // Global visible rects are root-relative, whereas text layout coordinates
        // are local. This also excludes lines clipped by the scrolling reader.
        if (!getGlobalVisibleRect(visibleText)) return null
        visibleText.offset(rootLocation[0], rootLocation[1])
        if (!visibleText.intersect(safe)) return null
        val path = Path(); textLayout.getSelectionPath(first, last, path)
        val bounds = RectF(); path.computeBounds(bounds, true)
        if (bounds.isEmpty) {
            val line = textLayout.getLineForOffset(first)
            bounds.set(textLayout.getPrimaryHorizontal(first), textLayout.getLineTop(line).toFloat(), textLayout.getPrimaryHorizontal(first) + 1f, textLayout.getLineBottom(line).toFloat())
        }
        val location = IntArray(2); getLocationInWindow(location)
        bounds.offset((location[0] + totalPaddingLeft - scrollX).toFloat(), (location[1] + totalPaddingTop - scrollY).toFloat())
        if (!bounds.intersect(RectF(visibleText))) return null
        return NoteSelectionGeometry(IntRect(floor(bounds.left).toInt(), floor(bounds.top).toInt(), ceil(bounds.right).toInt(), ceil(bounds.bottom).toInt()), IntRect(safe.left, safe.top, safe.right, safe.bottom))
    }
    fun reportSelection() {
        val owner = controller ?: return
        val source = block ?: return
        val first = minOf(selectionStart, selectionEnd); val last = maxOf(selectionStart, selectionEnd)
        if (first >= 0 && last > first && last <= text.length) owner.selection = NoteTextSelection(this, source, first, last, text.subSequence(first, last).toString(), selectionGeometry(first, last))
        else if (owner.selection?.view === this) owner.selection = null
    }
    override fun onSelectionChanged(selStart: Int, selEnd: Int) { super.onSelectionChanged(selStart, selEnd); post { reportSelection() } }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(selectionLayoutListener) }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(selectionLayoutListener)
        if (controller?.selection?.view === this) controller?.selection = null
        super.onDetachedFromWindow()
    }
    fun finishSelection() {
        (text as? Spannable)?.let(Selection::removeSelection)
        applicationActionMode?.finish()
        clearFocus()
        if (controller?.selection?.view === this) controller?.selection = null
    }
    fun useApplicationMenu() {
        customSelectionActionModeCallback = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean { applicationActionMode = mode; menu.clear(); post { reportSelection() }; return true }
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean { menu.clear(); post { reportSelection() }; return true }
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) {
                if (applicationActionMode === mode) applicationActionMode = null
                post { if (applicationActionMode == null && controller?.selection?.view === this@NoteSelectableTextView) controller?.selection = null }
            }
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y; downAt = event.eventTime }
        if (event.actionMasked == MotionEvent.ACTION_UP && event.eventTime - downAt < 240 && kotlin.math.abs(event.x - downX) < 12 * resources.displayMetrics.density && kotlin.math.abs(event.y - downY) < 12 * resources.displayMetrics.density) {
            val value = text as? Spanned
            val offset = getOffsetForPosition(event.x, event.y)
            val remark = value?.getSpans(offset, offset, NoteAnnotationSpan::class.java)?.firstOrNull { it.annotation.kind == "remark" }
            if (remark != null && controller != null) { controller?.openedRemark = remark.annotation.id; performClick(); return true }
        }
        return super.onTouchEvent(event)
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

class NoteAnnotationSpan(val annotation: NoteAnnotation) : CharacterStyle() {
    override fun updateDrawState(tp: TextPaint) {
        when (annotation.kind) {
            "highlight" -> tp.bgColor = 0x70EAC14C
            "underline" -> tp.isUnderlineText = true
            "remark" -> { tp.bgColor = 0x405A9DC0; tp.isUnderlineText = true }
        }
    }
}
fun renderPersonalAnnotations(parsed: Spanned, source: String, sourceStart: Int?, annotations: List<NoteAnnotation>): Spanned {
    if (sourceStart == null || annotations.isEmpty()) return parsed
    val hash = imageDigest(source.toByteArray())
    val relevant = annotations.filter { it.sourceStart == sourceStart && it.sourceHash == hash && it.start >= 0 && it.end > it.start && it.end <= parsed.length && parsed.subSequence(it.start, it.end).toString() == it.quote }
    if (relevant.isEmpty()) return parsed
    return SpannableStringBuilder(parsed).apply { relevant.forEach { setSpan(NoteAnnotationSpan(it), it.start, it.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) } }
}

@Composable fun NoteReadingSelection(note: Note, vm: StudyViewModel, modifier: Modifier = Modifier, content: @Composable (Modifier) -> Unit) {
    val controller = remember(note.id) { NoteSelectionController() }
    val context = LocalContext.current
    val density = LocalDensity.current
    var adding by remember(note.id) { mutableStateOf<NoteAnnotation?>(null) }
    var comment by remember(note.id) { mutableStateOf("") }
    val currentUi by vm.ui.collectAsState()
    fun clearSelection() { controller.selection?.view?.finishSelection(); controller.selection = null }
    Box(modifier) {
        CompositionLocalProvider(LocalNoteSelection provides controller, LocalNoteAnnotations provides note.annotations) { content(Modifier.fillMaxSize()) }
        val selected = controller.selection
        val geometry = selected?.geometry
        if (selected != null && geometry != null) Popup(popupPositionProvider = NoteSelectionPositionProvider(geometry, with(density) { 8.dp.roundToPx() }), properties = PopupProperties(focusable = false, dismissOnClickOutside = false)) {
            val availableWidth = with(density) { geometry.safeWindowBounds.width.toDp() } - 16.dp
            val availableHeight = with(density) { geometry.safeWindowBounds.height.toDp() } - 16.dp
            Surface(Modifier.widthIn(max = minOf(560.dp, availableWidth.coerceAtLeast(1.dp))).heightIn(max = availableHeight.coerceAtLeast(1.dp)).testTag("正文选词工具条"), shape = MaterialTheme.shapes.medium, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                  Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    FeedbackTextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("笔记文字", selected.quote)); clearSelection() }) { Text("复制") }
                    val canMark = currentUi.noteEditable(note.id) && selected.annotation("highlight").isAttached(note.content)
                    FeedbackTextButton(onClick = { if (vm.saveNoteAnnotation(note.id, selected.annotation("highlight"))) clearSelection() }, enabled = canMark) { Text("重点高亮") }
                    FeedbackTextButton(onClick = { if (vm.saveNoteAnnotation(note.id, selected.annotation("underline"))) clearSelection() }, enabled = canMark) { Text("下划线") }
                    FeedbackTextButton(onClick = { adding = selected.annotation("remark"); comment = ""; clearSelection() }, enabled = canMark) { Text("添加备注") }
                    val overlapping = note.annotations.filter { it.sourceStart == selected.block.sourceStart && it.sourceHash == selected.annotation("").sourceHash && it.start < selected.end && it.end > selected.start }.map { it.id }.toSet()
                    FeedbackTextButton(onClick = { vm.removeNoteAnnotations(note.id, overlapping); clearSelection() }, enabled = canMark && overlapping.isNotEmpty()) { Text("取消标记") }
                  }
                  FeedbackIconButton(onClick = { clearSelection() }) { Icon(Icons.Outlined.Close, "关闭选词工具") }
                }
            }
        }
    }
    adding?.let { target -> AlertDialog(onDismissRequest = { adding = null }, title = { Text("添加备注") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(target.quote.take(240), style = MaterialTheme.typography.bodySmall, maxLines = 4); OutlinedTextField(comment, { comment = it.take(2000) }, label = { Text("写下自己的理解或疑问") }, minLines = 3, maxLines = 7) }
    }, confirmButton = { FeedbackTextButton(onClick = { if (vm.saveNoteAnnotation(note.id, target.copy(comment = comment.trim()))) adding = null }, enabled = comment.isNotBlank() && currentUi.noteEditable(note.id)) { Text("保存备注") } }, dismissButton = { FeedbackTextButton(onClick = { adding = null }) { Text("取消") } }) }
    if (controller.openedRemark != null) NoteRemarksDialog(note, vm, controller.openedRemark) { controller.openedRemark = null }
}

@Composable fun NoteRemarksDialog(note: Note, vm: StudyViewModel, initialId: String? = null, onDismiss: () -> Unit) {
    var selected by remember(note.id) { mutableStateOf(initialId) }
    val item = note.annotations.find { it.id == selected }
    var text by remember(item?.id) { mutableStateOf(item?.comment.orEmpty()) }
    val ui by vm.ui.collectAsState()
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (item == null) "我的标记与备注" else "编辑备注") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = expandedListHeight(400.dp)).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (item != null) {
                Text(item.quote, style = MaterialTheme.typography.bodyMedium)
                if (!item.isAttached(note.content)) Text("原文已变化，位置待核对。备注仍完整保留。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(text, { text = it.take(2000) }, label = { Text("我的备注") }, minLines = 3, maxLines = 7)
                FeedbackTextButton(onClick = { vm.removeNoteAnnotations(note.id, setOf(item.id)); selected = null }, enabled = ui.noteEditable(note.id)) { Text("删除这条标记") }
            } else {
                if (note.annotations.isEmpty()) Text("长按正文，选中文字后添加自己的标记或备注。")
                note.annotations.forEach { a -> FeedbackTextButton(onClick = { selected = a.id }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text(a.quote, maxLines = 2); Text(a.comment.ifBlank { if (a.kind == "highlight") "重点高亮" else "下划线" }, style = MaterialTheme.typography.bodySmall, maxLines = 3); if (!a.isAttached(note.content)) Text("原文位置待核对", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                } }
            }
        }
    }, confirmButton = { FeedbackTextButton(onClick = { if (item == null) onDismiss() else if (vm.saveNoteAnnotation(note.id, item.copy(comment = text.trim(), kind = if (text.isBlank()) item.kind else "remark"))) selected = null }, enabled = item == null || ui.noteEditable(note.id) && (item.kind != "remark" || text.isNotBlank())) { Text(if (item == null) "关闭" else "保存备注") } }, dismissButton = if (item != null) ({ FeedbackTextButton(onClick = { selected = null }) { Text("返回列表") } }) else null)
}
