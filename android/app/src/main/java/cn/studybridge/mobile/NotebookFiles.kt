package cn.studybridge.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class NotebookFileActions(val busy: Boolean, val backup: () -> Unit, val restore: () -> Unit, val markdown: () -> Unit)

private suspend fun writeNotebookDocument(context: Context, uri: Uri, suffix: String, write: (java.io.OutputStream) -> Unit) = withContext(Dispatchers.IO) {
    val temp = File.createTempFile("notebook-export-", suffix, context.cacheDir)
    try {
        temp.outputStream().use(write)
        requireNotNull(context.contentResolver.openOutputStream(uri, "w")) { "无法写入所选文件。" }.use { output -> temp.inputStream().use { it.copyTo(output) } }
    } catch (error: Exception) {
        runCatching { android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) }
        throw error
    } finally { temp.delete() }
}

@Composable fun rememberNotebookFileActions(ui: UiState, vm: StudyViewModel): NotebookFileActions {
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<Saved?>(null) }
    fun runFileTask(label: String, task: suspend () -> Unit) {
        if (busy) return
        busy = true
        vm.viewModelScope.launch {
            try { task() } catch (error: Exception) { vm.notice(error.message?.take(180) ?: "$label 失败。") }
            finally { busy = false }
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val frozen = snapshot; snapshot = null
        if (uri != null && frozen != null) runFileTask("备份") {
            writeNotebookDocument(context, uri, ".zip") { output -> writeNotebookBackup(output, frozen, vm.sourceImages::bytes, vm.sourceImages.markupStore::exportRaw) }
            vm.notice("笔记、原图、批注与恢复记录已备份。")
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runFileTask("恢复备份") {
            val restored = withContext(Dispatchers.IO) {
                val archive = requireNotNull(context.contentResolver.openInputStream(uri)) { "无法读取备份。" }.use(::readNotebookBackup)
                // Validate every source before publishing any imported notebook.
                archive.images.values.forEach { bytes -> boundedBitmap(bytes).recycle() }
                archive.marks.values.forEach { bytes -> imageMarkupFromJson(bytes.toString(Charsets.UTF_8)) }
                archive.images.forEach { (id, bytes) ->
                    val info = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, info)
                    val data = "data:${info.outMimeType ?: error("备份图片无法识别。")};base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                    require(vm.sourceImages.save(data) == id) { "图片校验失败。" }
                }
                // An existing local drawing is never silently replaced by import.
                archive.marks.forEach { (id, bytes) -> if (vm.sourceImages.markupStore.exportRaw(id) == null) vm.sourceImages.markupStore.importRaw(id, bytes) }
                archive.data
            }
            check(!vm.ui.value.locked) { "请先暂停当前操作，再重新恢复备份。" }
            check(vm.updateNotebookState { current -> mergeNotebookBackup(current, restored) }) { "笔记当前不可写。" }
            vm.readingMemory.merge(vm.ui.value.data.noteReadingPositions, vm.ui.value.data.notes.map { it.id }.toSet())
            vm.notice("备份已合并；已有笔记和同图批注保留，草稿作为恢复副本保存。")
        }
    }
    val markdown = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runFileTask("导入 Markdown") {
            val content = withContext(Dispatchers.IO) {
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size() + count <= 4 * 1024 * 1024) { "文本超过 4 MB，请拆分后导入。" }; out.write(buffer, 0, count) }
                    out.toByteArray()
                }
                require(bytes.size <= 4 * 1024 * 1024) { "文本超过 4 MB，请拆分后导入。" }
                val text = if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) bytes.toString(Charsets.UTF_16LE).removePrefix("\uFEFF") else bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
                require(!text.contains('\u0000') && !text.contains('\uFFFD')) { "请选择 UTF-8 或 UTF-16LE 编码的 Markdown/文本文件。" }
                text
            }
            check(!vm.ui.value.locked) { "请先暂停当前操作，再导入笔记。" }
            val title = content.lineSequence().firstOrNull { it.trimStart().startsWith("# ") }?.trim()?.removePrefix("# ")?.take(120) ?: "导入的笔记"
            val note = Note(title = title, content = content, source = "本机 Markdown 导入", model = "手写")
            check(vm.updateNotebookState { it.copy(notes = listOf(note) + it.notes) })
            vm.tab(2); vm.noteReader.open(note); vm.notice("Markdown 已直接导入笔记。")
        }
    }
    return NotebookFileActions(busy, {
        if (!busy && snapshot == null && !vm.ui.value.locked) { snapshot = vm.notebookSnapshot(); backup.launch("雨课伴学-笔记备份.zip") }
    }, { if (!busy && !vm.ui.value.locked) restore.launch(arrayOf("application/zip", "application/octet-stream")) }, { if (!busy && !vm.ui.value.locked) markdown.launch(arrayOf("text/*", "application/octet-stream")) })
}

@Composable fun rememberNotePdfExport(vm: StudyViewModel): (Note) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Note?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val note = pending; pending = null
        if (uri != null && note != null) vm.viewModelScope.launch {
            try { writeNotebookDocument(context, uri, ".pdf") { writeNotePdf(context, note, vm.sourceImages, it) }; vm.notice("PDF 已导出，包含保存的图片批注和个人备注。") }
            catch (error: Exception) { vm.notice(error.message?.take(180) ?: "PDF 导出失败。") }
        }
    }
    return { note -> if (pending == null) { pending = note; launcher.launch(downloadName(note.title, "pdf")) } }
}
