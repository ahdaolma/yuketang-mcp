package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

fun replaceNoteImage(text: String, start: Int, expected: String, replacement: String): String {
    require(start >= 0 && start + expected.length <= text.length && text.regionMatches(start, expected, 0, expected.length)) { "图片位置已变化，请重新选择。" }
    return text.replaceRange(start, start + expected.length, replacement)
}

fun SourceImages.rotated(id: String): String {
    val bitmap = boundedBitmap(bytes(id))
    try {
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(90f) }, true)
        try {
            val bytes = ByteArrayOutputStream().also { check(rotated.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            return save("data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        } finally { if (rotated !== bitmap) rotated.recycle() }
    } finally { bitmap.recycle() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun NoteImageEditor(text: String, onChange: (String) -> Unit, images: SourceImages, enabled: Boolean, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(text)
    val matches = remember(text) { sourceImagePattern.findAll(text).toList() }
    var open by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(8) }
    var busy by remember { mutableStateOf(false) }
    var pickerSnapshot by remember { mutableStateOf<String?>(null) }
    var target by remember { mutableStateOf<Pair<String, MatchResult>?>(null) }
    var removing by remember { mutableStateOf<Pair<String, MatchResult>?>(null) }
    var renaming by remember { mutableStateOf<Pair<String, MatchResult>?>(null) }
    var label by remember { mutableStateOf("") }
    var undo by remember { mutableStateOf<Pair<String, String>?>(null) }
    val previews = remember(context) { MaterialPreviews(context) }
    fun apply(snapshot: String, match: MatchResult?, replacement: String) {
        if (latest != snapshot) { onError("草稿已变化，请重新选择图片再操作。"); return }
        val next = if (match == null) snapshot + (when { snapshot.isEmpty() || snapshot.endsWith("\n\n") -> ""; snapshot.endsWith("\n") -> "\n"; else -> "\n\n" }) + replacement + "\n\n"
            else replaceNoteImage(snapshot, match.range.first, match.value, replacement)
        undo = snapshot to next; onChange(next)
    }
    fun importImage(uri: Uri, selection: Pair<String, MatchResult>?, snapshot: String) {
        busy = true
        scope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val out = ByteArrayOutputStream()
                    requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val n = input.read(buffer); if (n < 0) break; require(out.size() + n <= 16 * 1024 * 1024) { "图片最多 16 MB，请先压缩。" }; out.write(buffer, 0, n) }
                    }
                    val bitmap = boundedBitmap(out.toByteArray())
                    try { val normalized = ByteArrayOutputStream(); check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, normalized)); images.save("data:image/png;base64," + Base64.encodeToString(normalized.toByteArray(), Base64.NO_WRAP)) }
                    finally { bitmap.recycle() }
                }
                apply(snapshot, selection?.second, sourceImageMarkdown(id, selection?.second?.groupValues?.get(1) ?: "插入的图片").trimEnd())
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; onError(e.message ?: "图片导入失败。") }
            finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val selection = target; target = null
        val snapshot = pickerSnapshot; pickerSnapshot = null
        if (uri != null && snapshot != null) importImage(uri, selection, snapshot)
        else if (uri != null) onError("编辑页已重建，请重新选择图片；原文未改变。")
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FeedbackOutlinedButton(onClick = { open = !open }) { Text("图片 ${matches.size} ${if (open) "▴" else "▾"}") }
            FeedbackTextButton(onClick = { target = null; pickerSnapshot = text; picker.launch(arrayOf("image/*")) }, enabled = enabled && !busy) { Text("插入图片") }
            if (undo?.second == text) FeedbackTextButton(onClick = { undo?.let { onChange(it.first) }; undo = null }, enabled = enabled && !busy) { Text("撤销图片修改") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (open) {
            matches.take(shown).forEachIndexed { index, match ->
                val id = match.groupValues[2]
                StudyPanel { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PagePreview("edit:$id", "图片 ${index + 1} · ${match.groupValues[1]}", previews) { withContext(Dispatchers.IO) { Attachment(images.data(id), match.groupValues[1], "") } }
                    FlowRow {
                        FeedbackTextButton(onClick = { target = text to match; pickerSnapshot = text; picker.launch(arrayOf("image/*")) }, enabled = enabled && !busy) { Text("替换") }
                        FeedbackTextButton(onClick = {
                            val snapshot = text; busy = true
                            scope.launch { try { val rotated = withContext(Dispatchers.IO) { images.rotated(id) }; apply(snapshot, match, sourceImageMarkdown(rotated, match.groupValues[1]).trimEnd()) }
                                catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; onError(e.message ?: "旋转失败。") } finally { busy = false } }
                        }, enabled = enabled && !busy) { Text("旋转 90°") }
                        FeedbackTextButton(onClick = { renaming = text to match; label = match.groupValues[1] }, enabled = enabled && !busy) { Text("图片说明") }
                        FeedbackTextButton(onClick = { removing = text to match }, enabled = enabled && !busy) { Text("移除图片") }
                    }
                } }
            }
            if (matches.size > shown) FeedbackTextButton(onClick = { shown += 8 }) { Text("继续显示图片") }
        }
    }
    removing?.let { (snapshot, match) -> AlertDialog(onDismissRequest = { removing = null }, title = { Text("移除这张图片？") }, text = { Text("只移除“${match.groupValues[1]}”的图片引用，保留正文和其他笔记中的图片。") }, confirmButton = { FeedbackTextButton(onClick = { apply(snapshot, match, ""); removing = null }, enabled = enabled && !busy) { Text("确认移除图片") } }, dismissButton = { FeedbackTextButton(onClick = { removing = null }) { Text("保留图片") } }) }
    renaming?.let { (snapshot, match) -> AlertDialog(onDismissRequest = { renaming = null }, title = { Text("修改图片说明") }, text = { OutlinedTextField(label, { label = it.take(200) }, label = { Text("图片说明") }, singleLine = true) }, confirmButton = { FeedbackTextButton(onClick = { apply(snapshot, match, sourceImageMarkdown(match.groupValues[2], label).trimEnd()); renaming = null }, enabled = enabled && !busy) { Text("保存说明") } }, dismissButton = { FeedbackTextButton(onClick = { renaming = null }) { Text("取消") } }) }
}
