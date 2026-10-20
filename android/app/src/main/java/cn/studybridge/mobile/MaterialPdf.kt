package cn.studybridge.mobile

import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Locale

// Images are streamed into the PDF one page at a time, keeping large slide decks
// from retaining every decoded bitmap in memory. Inputs are JPEGs produced locally.
class ImagePdfWriter(private val output: OutputStream, private val title: String) {
    private var written = 0L
    private val offsets = mutableMapOf<Int, Long>()
    private val pages = mutableListOf<Int>()
    private var finished = false
    init { write("%PDF-1.4\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1)) }
    private fun write(bytes: ByteArray) { require(written + bytes.size <= 250L * 1024 * 1024) { "导出超过 250 MB，请按较小范围下载。" }; output.write(bytes); written += bytes.size }
    private fun text(s: String) = write(s.toByteArray(Charsets.US_ASCII))
    private fun obj(id: Int, body: String) { offsets[id] = written; text("$id 0 obj\n$body\nendobj\n") }
    fun add(bitmap: Bitmap) {
        check(!finished)
        val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out)); out.toByteArray() }
        val id = 3 + pages.size * 3; val imageId = id + 1; val streamId = id + 2
        val width = 595.0; val height = width * bitmap.height / bitmap.width
        val h = String.format(Locale.ROOT, "%.3f", height)
        obj(id, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 $h] /Resources << /XObject << /Im0 $imageId 0 R >> >> /Contents $streamId 0 R >>")
        offsets[imageId] = written
        text("$imageId 0 obj\n<< /Type /XObject /Subtype /Image /Width ${bitmap.width} /Height ${bitmap.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${bytes.size} >>\nstream\n")
        write(bytes); text("\nendstream\nendobj\n")
        val content = "q\n595 0 0 $h 0 0 cm\n/Im0 Do\nQ\n"
        obj(streamId, "<< /Length ${content.toByteArray(Charsets.US_ASCII).size} >>\nstream\n${content}endstream")
        pages.add(id)
    }
    fun finish() {
        check(!finished && pages.isNotEmpty()); finished = true
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count ${pages.size} /Kids [${pages.joinToString(" ") { "$it 0 R" }}] >>")
        val info = 3 + pages.size * 3
        val encoded = (byteArrayOf(0xfe.toByte(), 0xff.toByte()) + title.toByteArray(Charsets.UTF_16BE)).joinToString("") { "%02x".format(it) }
        obj(info, "<< /Title <$encoded> /Producer (Rain Study) >>")
        val xref = written; text("xref\n0 ${info + 1}\n0000000000 65535 f \n")
        for (id in 1..info) text(String.format(Locale.ROOT, "%010d 00000 n \n", requireNotNull(offsets[id])))
        text("trailer\n<< /Size ${info + 1} /Root 1 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }
}
fun captionedBitmap(source: Bitmap, label: String): Bitmap {
    val header = 70; val bitmap = Bitmap.createBitmap(source.width.coerceAtLeast(800), source.height + header, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
    canvas.drawText(label, 20f, 42f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 26f })
    canvas.drawBitmap(source, 0f, header.toFloat(), null)
    return bitmap
}
fun textPageBitmaps(text: String, label: String): Sequence<Bitmap> = sequence {
    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 30f }
    val layout = StaticLayout.Builder.obtain(text.ifBlank { "此页没有可导出的图片或文字。" }, 0, text.ifBlank { "此页没有可导出的图片或文字。" }.length, paint, 1100).setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(8f, 1f).build()
    var first = 0
    while (first < layout.lineCount) {
        var end = first + 1
        while (end < layout.lineCount && layout.getLineBottom(end) - layout.getLineTop(first) < 1450) end++
        val height = (layout.getLineBottom(end - 1) - layout.getLineTop(first) + 150).coerceAtLeast(400)
        val bitmap = Bitmap.createBitmap(1200, height, Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        canvas.drawText(label, 50f, 48f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = 26f })
        canvas.save(); canvas.clipRect(50, 95, 1150, height - 40); canvas.translate(50f, 95f - layout.getLineTop(first)); layout.draw(canvas); canvas.restore()
        yield(bitmap); first = end
    }
}
