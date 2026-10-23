package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImageMarkupTool(val title: String) { VIEW("查看"), PEN("画笔"), HIGHLIGHT("荧光笔"), ERASER("橡皮") }

@Composable fun ImageMarkupEditor(bitmap: Bitmap, imageId: String, label: String, store: ImageMarkupStore, onClose: () -> Unit, setCloseRequest: (() -> Unit) -> Unit = {}) {
    val drafts: ImageMarkupDrafts = viewModel()
    val session = drafts.session(imageId)
    var history by session::history
    var saved by session::saved
    var ready by session::ready
    val saving = session.saving
    var error by session::error
    var status by session::status
    var loadAttempt by remember(imageId) { mutableIntStateOf(0) }
    var confirmClose by session::confirmClose
    var tool by session::tool
    var zoom by session::zoom
    var pan by session::pan
    var pending by remember(imageId) { mutableStateOf<List<MarkPoint>>(emptyList()) }
    var erasing by remember(imageId) { mutableStateOf<List<ImageStroke>?>(null) }
    val dirty = history.strokes != saved
    fun closeEditor() { drafts.discard(imageId); onClose() }
    fun requestClose() { if (!saving) { if (dirty) confirmClose = true else closeEditor() } }
    fun save(close: Boolean = false) { drafts.save(imageId, store, close) }
    LaunchedEffect(session.closeAfterSave) { if (session.closeAfterSave) closeEditor() }
    SideEffect { setCloseRequest { requestClose() } }
    LaunchedEffect(imageId, loadAttempt) {
        if (ready) return@LaunchedEffect
        try { val strokes = withContext(Dispatchers.IO) { store.load(imageId) }; history = MarkupHistory(strokes); saved = strokes; ready = true; error = "" }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "批注读取失败，请重试后再编辑。" }
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), maxLines = 2, style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { requestClose() }, enabled = !saving) { Text("关闭预览") }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ImageMarkupTool.entries.forEach { option -> FilterChip(selected = tool == option, onClick = { tool = option }, enabled = ready && !saving, label = { Text(option.title) }) }
            TextButton(onClick = { history = history.undo(); status = "" }, enabled = history.past.isNotEmpty() && !saving) { Text("撤销") }
            TextButton(onClick = { history = history.redo(); status = "" }, enabled = history.future.isNotEmpty() && !saving) { Text("重做") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(if (saving) "正在保存…" else if (dirty) "有未保存批注" else status.ifBlank { "双指缩放 · 查看时单指移动" }, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { zoom = 1f; pan = Offset.Zero }, enabled = !saving) { Text("重置缩放") }
            Button(onClick = { save() }, enabled = ready && !saving && dirty) { Text("保存批注") }
        }
        if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (!ready) TextButton(onClick = { loadAttempt++ }) { Text("重试批注") }
        }
        Canvas(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(MaterialTheme.colorScheme.surfaceContainerLow)
            .testTag("image-markup-canvas")
            .semantics { contentDescription = label; stateDescription = "${history.strokes.size} 笔批注，缩放 ${zoom}" }
            .pointerInput(bitmap, tool, ready, saving) {
                if (!ready || saving) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun viewport() = MarkupViewport(size.width.toFloat(), size.height.toFloat(), bitmap.width.toFloat(), bitmap.height.toFloat(), zoom, pan.x, pan.y)
                    var multitouch = false
                    var erased = history.strokes
                    pending = if (tool == ImageMarkupTool.PEN || tool == ImageMarkupTool.HIGHLIGHT) listOfNotNull(viewport().point(down.position.x, down.position.y)) else emptyList()
                    if (tool == ImageMarkupTool.ERASER) viewport().point(down.position.x, down.position.y)?.let { erased = eraseImageStrokes(erased, it, bitmap.height.toFloat() / bitmap.width, .025f / zoom); erasing = erased }
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed > 1) {
                                multitouch = true; pending = emptyList(); erased = history.strokes; erasing = null
                                val centroid = event.calculateCentroid(useCurrent = false)
                                val oldZoom = zoom; val nextZoom = (oldZoom * event.calculateZoom()).coerceIn(1f, 5f)
                                val center = Offset(size.width / 2f, size.height / 2f)
                                pan = (pan - (centroid - center)) * (nextZoom / oldZoom) + (centroid - center) + event.calculatePan()
                                zoom = nextZoom
                            } else if (!multitouch && pressed == 1) {
                                val change = event.changes.first { it.pressed }
                                if (tool == ImageMarkupTool.VIEW) pan += change.position - change.previousPosition
                                else viewport().point(change.position.x, change.position.y)?.let { p ->
                                    if (tool == ImageMarkupTool.ERASER) { erased = eraseImageStrokes(erased, p, bitmap.height.toFloat() / bitmap.width, .025f / zoom); erasing = erased }
                                    else if (pending.size < 20000 && pending.lastOrNull() != p) pending = pending + p
                                }
                            }
                            val v = viewport(); val boundX = ((v.width * zoom - size.width) / 2).coerceAtLeast(0f); val boundY = ((v.height * zoom - size.height) / 2).coerceAtLeast(0f)
                            pan = Offset(pan.x.coerceIn(-boundX, boundX), pan.y.coerceIn(-boundY, boundY))
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                        if (!multitouch) {
                            if (tool == ImageMarkupTool.ERASER) history = history.change(erased)
                            else if (pending.isNotEmpty() && history.strokes.size < 1000) history = history.change(history.strokes + ImageStroke(pending, if (tool == ImageMarkupTool.HIGHLIGHT) .028f else .006f, tool == ImageMarkupTool.HIGHLIGHT))
                            status = ""
                        }
                    } finally { pending = emptyList(); erasing = null }
                }
            }) {
            val v = MarkupViewport(size.width, size.height, bitmap.width.toFloat(), bitmap.height.toFloat(), zoom, pan.x, pan.y)
            val canvas = drawContext.canvas.nativeCanvas
            val checkpoint = canvas.save()
            canvas.translate(size.width / 2 + pan.x, size.height / 2 + pan.y)
            canvas.scale(zoom, zoom); canvas.translate(-v.width / 2, -v.height / 2)
            canvas.drawBitmap(bitmap, null, RectF(0f, 0f, v.width, v.height), null)
            val active = erasing ?: if (pending.isEmpty()) history.strokes else history.strokes + ImageStroke(pending, if (tool == ImageMarkupTool.HIGHLIGHT) .028f else .006f, tool == ImageMarkupTool.HIGHLIGHT)
            drawImageStrokes(canvas, active, v.width, v.height)
            canvas.restoreToCount(checkpoint)
        }
    }
    if (confirmClose) AlertDialog(onDismissRequest = { if (!saving) confirmClose = false }, title = { Text("保存图片批注？") }, text = { Text("这次画的内容还没有保存。") }, confirmButton = { TextButton(onClick = { save(close = true) }, enabled = !saving) { Text("保存并关闭") } }, dismissButton = {
        Row { TextButton(onClick = { confirmClose = false }, enabled = !saving) { Text("继续编辑") }; TextButton(onClick = { closeEditor() }, enabled = !saving) { Text("放弃更改") } }
    })
}
