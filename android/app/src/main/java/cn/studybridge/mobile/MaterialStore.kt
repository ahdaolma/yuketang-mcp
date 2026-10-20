package cn.studybridge.mobile

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import android.util.Base64
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.*
import java.net.URI
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

const val MAX_MATERIAL_BYTES = 50L * 1024 * 1024
private const val MAX_TEXT_BYTES = 2 * 1024 * 1024

data class StudyMaterial(val id: String = newId(), val title: String, val format: String, val kind: String = "courseware", val pages: Int, val importedAt: Long = System.currentTimeMillis(), val modifiedAt: Long = 0, val noteId: String = "") {
    fun json() = JSONObject().put("id", id).put("title", title).put("format", format).put("kind", kind).put("pages", pages).put("importedAt", importedAt).put("modifiedAt", modifiedAt).put("noteId", noteId)
}
fun materialFromJson(o: JSONObject) = StudyMaterial(o.getString("id"), o.getString("title"), o.getString("format"), o.optString("kind", "courseware"), o.getInt("pages"), o.getLong("importedAt"), o.optLong("modifiedAt"), o.optString("noteId"))
data class MaterialPage(val page: Int, val text: String = "", val images: List<String> = emptyList(), val warning: String = "")
data class MaterialDocument(val material: StudyMaterial, val pages: List<MaterialPage>, val noteText: String = "")
fun readableTime(value: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(value))
fun sourceRecordTime(o: JSONObject): String {
    for (key in listOf("created_at", "create_time", "created_time", "createdAt", "createTime", "start_time", "startTime", "time", "timestamp")) {
        if (!o.has(key) || o.isNull(key)) continue
        val raw = o.optString(key).trim()
        val n = raw.toLongOrNull()
        if (n != null && n in 1_000_000_000L..99_999_999_999_999L) return readableTime(if (n < 100_000_000_000L) n * 1000 else n)
        if (raw.length in 8..80 && raw.any(Char::isDigit) && raw.any { it == '-' || it == '/' || it == ':' }) return raw
    }
    return ""
}
fun textMaterialPages(text: String): List<MaterialPage> {
    require(text.isNotBlank()) { "文件没有可读取的文字。" }
    require(text.length <= 500_000) { "文字超过 50 万字，请拆分文件后导入。" }
    val result = mutableListOf<MaterialPage>(); val current = StringBuilder()
    for (line in text.lineSequence()) {
        for (part in (line + "\n").chunked(6000)) {
            if (current.length + part.length > 6000 && current.isNotEmpty()) { result.add(MaterialPage(result.size + 1, current.toString())); current.clear() }
            current.append(part)
        }
    }
    if (current.isNotBlank()) result.add(MaterialPage(result.size + 1, current.toString()))
    return result
}

// Internal copies keep user-selected documents available after a picker grant expires.
// Archive members are read in place; imported archives are never extracted to paths.
class MaterialStore(private val context: Context) {
    private val root = File(context.noBackupFilesDir, "learning-materials").apply { mkdirs() }
    private fun path(id: String): File { require(id.matches(Regex("[a-fA-F0-9-]{36}"))); return File(root, "$id.bin") }
    fun remove(id: String) { path(id).delete() }
    fun importDocument(uri: Uri, kind: String): MaterialDocument {
        require(uri.scheme == "content") { "请从手机文件选择器导入。" }
        var name = "导入资料"; var modified = 0L
        runCatching { context.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it)?.takeIf(String::isNotBlank) ?: name }
            c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED).takeIf { it >= 0 }?.let { modified = c.getLong(it).coerceAtLeast(0) }
        } } }
        require(name.length <= 1024) { "文件名过长，请缩短后重试。" }
        return requireNotNull(context.contentResolver.openInputStream(uri)) { "无法打开所选文件。" }.use { importStream(it, name, kind, modified) }
    }
    internal fun importStream(input: InputStream, name: String, kind: String, modified: Long = 0): MaterialDocument {
        require(name.length <= 1024) { "文件名过长，请缩短后重试。" }
        val id = newId(); val file = path(id)
        try {
            file.outputStream().use { out -> val buffer = ByteArray(16384); var total = 0L
                while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= MAX_MATERIAL_BYTES) { "单份资料最多 50 MB，请压缩或拆分后导入。" }; out.write(buffer, 0, n) }
                require(total > 0) { "文件为空。" }
            }
            val format = detect(file, name)
            val provisional = StudyMaterial(id, name, format, kind.takeIf { it == "notes" } ?: "courseware", 0, modifiedAt = modified)
            val doc = read(provisional)
            return doc.copy(material = provisional.copy(pages = doc.pages.size))
        } catch (e: Exception) { file.delete(); throw e }
    }
    private fun detect(file: File, name: String): String {
        val head = file.inputStream().use { input -> ByteArray(12).also { input.read(it) } }
        if (String(head, Charsets.ISO_8859_1).startsWith("%PDF-")) return "pdf"
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth > 0 && bounds.outHeight > 0) { require(bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "图片尺寸过大。" }; return "image" }
        if (head[0] == 0x50.toByte() && head[1] == 0x4b.toByte()) return ZipFile(file).use { zip ->
            when { zip.getEntry("ppt/presentation.xml") != null -> "pptx"; zip.getEntry("word/document.xml") != null -> "docx"; else -> error("此压缩文件不是 PPTX 或 DOCX。") }
        }
        return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "txt" -> "txt"; "md", "markdown" -> "md"
            "ppt", "doc" -> error("暂不支持旧版 PPT/DOC，请先另存为 PPTX、DOCX 或 PDF。")
            else -> error("支持 PDF、PPTX、图片、DOCX、Markdown 和 TXT。")
        }
    }
    fun read(m: StudyMaterial): MaterialDocument {
        val file = path(m.id); require(file.isFile) { "原始文件不存在，请重新导入。" }
        val pages: List<MaterialPage>; var noteText = ""
        when (m.format) {
            "pdf" -> { val count = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { it.pageCount }; require(count in 1..1000) { "PDF 需为 1–1000 页，请拆分后导入。" }; pages = (1..count).map { MaterialPage(it) } }
            "image" -> pages = listOf(MaterialPage(1))
            "txt", "md" -> { require(file.length() <= MAX_TEXT_BYTES); noteText = decodeText(file.readBytes()); pages = textMaterialPages(noteText) }
            "pptx", "docx" -> {
                val parsed = readOffice(file, m.format); pages = parsed.first; noteText = parsed.second
            }
            else -> error("文件格式无法识别，请重新导入。")
        }
        require(pages.isNotEmpty()) { "文件没有可读取的页面。" }
        return MaterialDocument(m.copy(pages = pages.size), pages, noteText)
    }
    fun attachment(doc: MaterialDocument, page: Int): Attachment {
        val p = doc.pages.find { it.page == page } ?: error("此页不存在。")
        val m = doc.material
        val bitmap = when (m.format) {
            "pdf" -> PdfRenderer(ParcelFileDescriptor.open(path(m.id), ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer -> renderer.openPage(page - 1).use { pdf ->
                val scale = 1600f / maxOf(pdf.width, pdf.height); val w = (pdf.width * scale).toInt().coerceAtLeast(1); val h = (pdf.height * scale).toInt().coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap -> try { bitmap.eraseColor(Color.WHITE); pdf.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) } catch (e: Exception) { bitmap.recycle(); throw e } }
            } }
            "image" -> path(m.id).inputStream().use { boundedBitmap(it.readBytes()) }
            "pptx", "docx" -> if (p.images.isNotEmpty()) officeImage(path(m.id), p.images) else null
            else -> null
        }
        val image = bitmap?.let { try { val out = ByteArrayOutputStream(); check(it.compress(Bitmap.CompressFormat.JPEG, 88, out)); "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP) } finally { it.recycle() } }
        val text = buildString { append("用户导入的${if (m.kind == "notes") "笔记" else "课件"}：${m.title}\n"); if (m.format in listOf("txt", "md", "docx")) append("以下页码为应用分段编号，不是原文件印刷页码。\n"); append(p.text); if (p.warning.isNotBlank()) append("\n资料读取说明：${p.warning}") }
        return Attachment(image, "${m.title} · ${if (m.format in listOf("txt", "md", "docx")) "第 $page 段" else "第 $page 页"}", text)
    }
    private fun readOffice(file: File, format: String): Pair<List<MaterialPage>, String> = ZipFile(file).use { zip ->
        require(zip.size() <= 5000) { "文件内资源过多，请转为 PDF 后导入。" }
        if (format == "docx") {
            val xml = xml(zip, "word/document.xml"); val paragraphs = xml.getElementsByTagNameNS("*", "p")
            val text = (0 until paragraphs.length).joinToString("\n\n") { i -> val nodes = (paragraphs.item(i) as Element).getElementsByTagNameNS("*", "t"); (0 until nodes.length).joinToString("") { nodes.item(it).textContent } }
            val images = relationships(zip, "word/_rels/document.xml.rels", "word/document.xml")
            val pages = textMaterialPages(text.ifBlank { "此笔记包含图片，请结合图片讲解。" }).mapIndexed { i, p -> p.copy(images = if (i == 0) images.take(6) else emptyList(), warning = "DOCX 按文字分段；表格和版式未完整保留。${if (images.size > 6) "图片超过 6 张，建议转为 PDF 保留全部图像。" else ""}") }
            pages to text
        } else {
            val presentation = xml(zip, "ppt/presentation.xml"); val ids = presentation.getElementsByTagNameNS("*", "sldId")
            require(ids.length in 1..500) { "PPTX 需为 1–500 页，请拆分后导入。" }
            val rels = relationMap(zip, "ppt/_rels/presentation.xml.rels", "ppt/presentation.xml")
            var totalText = 0
            val pages = (0 until ids.length).map { index ->
                val id = (ids.item(index) as Element).getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                val part = rels[id] ?: error("PPTX 页面引用无法读取。")
                require(part.startsWith("ppt/slides/"))
                val slide = xml(zip, part); val nodes = slide.getElementsByTagNameNS("*", "t")
                val text = (0 until nodes.length).joinToString("\n") { nodes.item(it).textContent }; require(text.length <= 100_000) { "单页文字过多，请转为 PDF。" }
                totalText += text.length; require(totalText <= 500_000) { "文档文字过多，请拆分后导入。" }
                val folder = part.substringBeforeLast('/'); val name = part.substringAfterLast('/')
                val images = relationships(zip, "$folder/_rels/$name.rels", part)
                val warning = "PPTX 为文字及嵌入图片提取视图，矢量图、公式和复杂版式可能未保留；需要原版视觉内容时请导入 PDF。" + if (images.size > 6) "本页图片超过 6 张，仅带入前 6 张。" else ""
                MaterialPage(index + 1, text, images.take(6), warning)
            }
            require(pages.sumOf { it.text.length } <= 500_000) { "文档文字过多，请拆分后导入。" }
            pages to pages.joinToString("\n\n") { it.text }
        }
    }
    private fun xml(zip: ZipFile, part: String): org.w3c.dom.Document {
        val entry = zip.getEntry(part) ?: error("文档资源缺失：$part")
        val bytes = zip.getInputStream(entry).use { readSmall(it, MAX_TEXT_BYTES) }; val text = String(bytes, Charsets.ISO_8859_1).replace("\u0000", "")
        require(!Regex("<!DOCTYPE|<!ENTITY", RegexOption.IGNORE_CASE).containsMatchIn(text)) { "文档 XML 格式不受支持。" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }; runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }.parse(ByteArrayInputStream(bytes))
    }
    private fun relationMap(zip: ZipFile, part: String, base: String): Map<String, String> {
        if (zip.getEntry(part) == null) return emptyMap()
        val rels = xml(zip, part).getElementsByTagNameNS("*", "Relationship")
        return (0 until rels.length).mapNotNull { i -> val el = rels.item(i) as Element
            if (el.getAttribute("TargetMode") == "External") null else {
                val target = runCatching { URI(base).resolve(el.getAttribute("Target")).normalize() }.getOrNull()
                if (target == null || target.isAbsolute || target.path.startsWith("/") || ".." in target.path.split('/')) null else el.getAttribute("Id") to target.path
            }
        }.toMap()
    }
    private fun relationships(zip: ZipFile, part: String, base: String): List<String> = relationMap(zip, part, base).values.filter { it.startsWith("ppt/media/") || it.startsWith("word/media/") }.filter { it.substringAfterLast('.').lowercase(Locale.ROOT) in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp") }.distinct()
    private fun officeImage(file: File, images: List<String>): Bitmap = ZipFile(file).use { zip ->
        val height = ((images.size + 1) / 2 * 600).coerceAtLeast(600); val output = Bitmap.createBitmap(1600, height, Bitmap.Config.ARGB_8888); val canvas = Canvas(output); canvas.drawColor(Color.WHITE)
        try { images.forEachIndexed { i, name -> val entry = zip.getEntry(name) ?: error("文档图片缺失。")
            val bytes = zip.getInputStream(entry).use { readSmall(it, 8 * 1024 * 1024) }; val bitmap = boundedBitmap(bytes)
            try { val cell = RectF((i % 2 * 800 + 12).toFloat(), (i / 2 * 600 + 12).toFloat(), (i % 2 * 800 + 788).toFloat(), (i / 2 * 600 + 588).toFloat()); val ratio = minOf(cell.width() / bitmap.width, cell.height() / bitmap.height); val w = bitmap.width * ratio; val h = bitmap.height * ratio; canvas.drawBitmap(bitmap, null, RectF(cell.centerX() - w / 2, cell.centerY() - h / 2, cell.centerX() + w / 2, cell.centerY() + h / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)) } finally { bitmap.recycle() }
        }; output } catch (e: Exception) { output.recycle(); throw e }
    }
}
fun readSmall(input: InputStream, max: Int): ByteArray { val out = ByteArrayOutputStream(); val buffer = ByteArray(8192); var total = 0; while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= max) { "文档内容超过读取上限，请拆分后导入。" }; out.write(buffer, 0, n) }; return out.toByteArray() }
fun decodeText(bytes: ByteArray): String {
    val text = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, Charsets.UTF_16LE).removePrefix("\uFEFF")
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, Charsets.UTF_16BE).removePrefix("\uFEFF")
        else -> runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }.getOrElse { String(bytes, java.nio.charset.Charset.forName("GB18030")) }
    }
    require(text.none { it == '\u0000' }) { "此文件不是可读取的文字笔记。" }; return text.replace("\r\n", "\n")
}
fun boundedBitmap(bytes: ByteArray): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000) { "图片格式或尺寸不支持，请转换为 PNG/JPEG 或 PDF。" }
    var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("图片无法解码。")
}
