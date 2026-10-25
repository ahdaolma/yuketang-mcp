package cn.studybridge.mobile

import android.content.Context
import android.util.Base64
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

private val imageIdPattern = Regex("[a-f0-9]{64}")
val sourceImagePattern = Regex("!\\[([^\\]\\n]{0,200})]\\(study-image:([a-f0-9]{64})\\)")
fun sourceImageMarkdown(id: String, label: String): String {
    if (!imageIdPattern.matches(id)) return ""
    val safeLabel = label.replace(Regex("[\\[\\]\\r\\n]"), " ").take(200)
    return "![$safeLabel](study-image:$id)\n\n"
}
fun withSourceImage(a: Attachment, text: String) = sourceImageMarkdown(a.imageRef, a.label) + text
fun imageDigest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

// Referenced originals are durable private files, separate from the encrypted text
// record. They are content-addressed, so repeated pages do not duplicate storage.
class SourceImages(context: Context) {
    private val root = File(context.noBackupFilesDir, "note-images").apply { mkdirs() }
    private val aspectRatios = android.util.LruCache<String, Float>(128)
    val markupStore = ImageMarkupStore(context)
    private fun file(id: String): File { require(imageIdPattern.matches(id)); return File(root, "$id.image") }
    @Synchronized fun save(data: String): String {
        require(data.length <= 24 * 1024 * 1024) { "图片超过保存上限。" }
        require(Regex("^data:image/(png|jpeg|webp|gif);base64,").containsMatchIn(data.take(40)))
        val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
        val bitmap = boundedBitmap(bytes); bitmap.recycle()
        val id = imageDigest(bytes); val target = file(id)
        if (!target.isFile) {
            val atomic = AtomicFile(target); val out = atomic.startWrite()
            try { out.write(bytes); atomic.finishWrite(out) } catch (e: Exception) { atomic.failWrite(out); throw e }
        }
        return id
    }
    fun prune(saved: Saved) {
        val active = (saved.imageBearingNotes().map { it.content } + saved.noteDrafts.map { it.content }).flatMap { text -> sourceImagePattern.findAll(text).map { it.groupValues[2] }.toList() }.toMutableSet()
        root.listFiles()?.filter { it.extension == "image" && it.nameWithoutExtension !in active }?.forEach { it.delete() }
    }
    fun bytes(id: String): ByteArray = file(id).let { require(it.isFile) { "本机原页图片不可用。" }; it.readBytes() }
    // Decode headers only; pixel decoding and annotation rendering remain on
    // their existing workers. Neighbor preparation warms this bounded cache.
    fun aspectRatio(id: String): Float? {
        aspectRatios.get(id)?.let { return it }
        return runCatching {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(file(id).path, options)
            if (options.outWidth > 0 && options.outHeight > 0)
                (options.outWidth.toFloat() / options.outHeight).also { aspectRatios.put(id, it) }
            else null
        }.getOrNull()
    }
    fun data(id: String): String {
        val bytes = bytes(id)
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val mime = options.outMimeType ?: error("图片无法识别。")
        return "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    // Saved vectors are composited only for viewing/export. bytes()/data() always
    // return the immutable original so edits and backup never silently flatten it.
    fun exportData(id: String): String {
        if (markupStore.load(id).isEmpty()) return data(id)
        val original = boundedBitmap(bytes(id))
        try {
            val composite = markupStore.render(id, original)
            try {
                val out = java.io.ByteArrayOutputStream()
                check(composite.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out))
                return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally { if (composite !== original) composite.recycle() }
        } finally { original.recycle() }
    }
    fun writeExport(markdown: String, out: java.io.OutputStream) {
        var start = 0
        sourceImagePattern.findAll(markdown).forEach { match ->
            out.write(markdown.substring(start, match.range.first).toByteArray(Charsets.UTF_8))
            out.write("![${match.groupValues[1]}](".toByteArray(Charsets.UTF_8))
            out.write(exportData(match.groupValues[2]).toByteArray(Charsets.UTF_8))
            out.write(')'.code); start = match.range.last + 1
        }
        out.write(markdown.substring(start).toByteArray(Charsets.UTF_8))
    }
    fun export(markdown: String): String = sourceImagePattern.replace(markdown) { m -> "![${m.groupValues[1]}](${exportData(m.groupValues[2])})" }
}
