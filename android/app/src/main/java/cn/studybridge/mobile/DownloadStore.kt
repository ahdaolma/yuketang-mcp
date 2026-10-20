package cn.studybridge.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.InputStream

fun downloadName(title: String, extension: String): String {
    val clean = title.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().removeSuffix(".$extension").ifBlank { "学习资料" }
    val name = StringBuilder(); var offset = 0; var bytes = 0
    while (offset < clean.length && name.length < 120) {
        val count = Character.charCount(clean.codePointAt(offset)); val part = clean.substring(offset, offset + count)
        val size = part.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 220) break
        name.append(part); bytes += size; offset += count
    }
    return name.toString() + ".$extension"
}

class DownloadStore(private val context: Context) {
    private val resolver get() = context.contentResolver
    fun retain(uri: Uri): String {
        require(uri.scheme == "content" && DocumentsContract.isTreeUri(uri)) { "请使用系统选择器选择文件夹。" }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return resolver.query(root, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "已选择的文件夹"
    }
    fun release(uri: String) { if (uri.isNotBlank()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } }
    fun write(tree: String, destination: Uri?, name: String, mime: String, input: () -> InputStream) {
        var created: Uri? = null
        try {
            val target = destination ?: run {
                require(tree.isNotBlank()) { "请选择保存位置。" }
                val uri = Uri.parse(tree)
                val root = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
                requireNotNull(DocumentsContract.createDocument(resolver, root, mime, name)) { "无法在下载文件夹创建文件，请到设置重新选择。" }.also { created = it }
            }
            require(target.scheme == "content")
            input().use { source -> requireNotNull(resolver.openOutputStream(target, "w")) { "无法写入文件。" }.use { source.copyTo(it) } }
        } catch (e: Exception) {
            created?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
            if (e is SecurityException) error("下载文件夹授权已失效，请在设置中重新选择。")
            throw e
        }
    }
}
