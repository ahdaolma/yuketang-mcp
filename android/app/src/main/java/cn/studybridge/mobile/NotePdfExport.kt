package cn.studybridge.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Base64
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.core.spans.TextLayoutSpan
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableRowSpan
import java.io.OutputStream
import kotlin.math.min

// The caller owns its output stream and should invoke this on a worker thread.
// Only exportData composites saved image marks; original image bytes stay intact.
fun writeNotePdf(context: Context, note: Note, images: SourceImages, output: OutputStream) {
    val renderer = Markwon.builder(context.applicationContext)
        .usePlugin(TablePlugin.create { table ->
            table.tableBorderColor(Color.LTGRAY).tableBorderWidth(1).tableCellPadding(5)
                .tableHeaderRowBackgroundColor(0xFFF0F3F7.toInt())
        })
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.headingBreakHeight(0).headingTextSizeMultipliers(floatArrayOf(1.5f, 1.3f, 1.15f, 1.05f, 1f, 1f))
                    .linkColor(0xFF285A83.toInt()).codeTextColor(Color.DKGRAY).codeBlockTextColor(Color.DKGRAY)
                    .codeBackgroundColor(0xFFF2F3F5.toInt()).codeBlockBackgroundColor(0xFFF2F3F5.toInt())
            }
        }).build()
    val document = PdfDocument()
    try {
        NotePdfPages(document).use { writer ->
            fun markdown(raw: String, sourceStart: Int) {
                if (raw.isBlank()) return
                for (segment in noteReadingSegments(raw)) {
                    val shown = segment.display(raw)
                    val parsed = renderStudyMarks(renderer.toMarkdown(markdownForStudyMarks(shown)), 0.75f, false)
                    writer.text(renderPersonalAnnotations(parsed, raw.substring(segment.start, segment.end), sourceStart + segment.start, note.annotations))
                }
            }
            writer.text(note.title.ifBlank { "学习笔记" }, size = 22f, bold = true)
            if (note.source.isNotBlank()) writer.text("来源：${note.source}", size = 10f, color = Color.DKGRAY)
            var start = 0
            sourceImagePattern.findAll(note.content).forEach { match ->
                markdown(note.content.substring(start, match.range.first), start)
                val data = images.exportData(match.groupValues[2])
                val bytes = Base64.decode(data.substringAfter(','), Base64.DEFAULT)
                val bitmap = boundedBitmap(bytes)
                try { writer.image(bitmap, match.groupValues[1]) } finally { bitmap.recycle() }
                start = match.range.last + 1
            }
            markdown(note.content.substring(start), start)
            // Keep every personal remark, including anchors made stale by later edits.
            // Treat quotes/comments as plain text so user-entered Markdown is never lost.
            if (note.annotations.isNotEmpty()) {
                writer.text("我的标记与备注", size = 17f, bold = true)
                note.annotations.forEach { mark ->
                    val label = when (mark.kind) { "highlight" -> "重点"; "underline" -> "下划线"; else -> "备注" }
                    writer.text("$label${if (mark.isAttached(note.content)) "" else "（原文位置待核对）"}：${mark.quote}", size = 12f, bold = true)
                    if (mark.comment.isNotBlank()) writer.text(mark.comment)
                }
            }
        }
        document.writeTo(output)
    } finally { document.close() }
}

private class NotePdfPages(private val document: PdfDocument) : java.io.Closeable {
    private val pageWidth = 595
    private val pageHeight = 842
    private val margin = 40f
    private val bottom = 792f
    private val contentWidth = pageWidth - 80
    private var page: PdfDocument.Page? = null
    private var pageNumber = 0
    private var y = margin

    private fun canvas(): Canvas {
        if (page == null) {
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
            page!!.canvas.drawColor(Color.WHITE)
            y = margin
        }
        return page!!.canvas
    }

    private fun nextPage() {
        page?.let { current ->
            current.canvas.drawText("$pageNumber", pageWidth / 2f, 818f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.GRAY; textSize = 9f; textAlign = Paint.Align.CENTER })
            document.finishPage(current)
        }
        page = null
        y = margin
    }

    fun text(value: CharSequence, size: Float = 13f, color: Int = Color.BLACK, bold: Boolean = false) {
        if (value.isEmpty()) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = if (bold) Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD) else Typeface.SANS_SERIF
        }
        val text = SpannableStringBuilder(value)
        // TableRowSpan learns its cell layouts on first draw. Prime those layouts
        // on a tiny scratch canvas before pagination, then pin the content width.
        val rows = text.getSpans(0, text.length, TableRowSpan::class.java)
        if (rows.isNotEmpty()) {
            val scratch = Bitmap.createBitmap(contentWidth, 1, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(scratch)
                rows.forEach { row -> row.draw(canvas, text, text.getSpanStart(row), text.getSpanEnd(row), 0f, 0, 0, 1, paint) }
            } finally { scratch.recycle() }
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(3f, 1.15f).build()
        TextLayoutSpan.applyTo(text, layout)
        var first = 0
        while (first < layout.lineCount) {
            canvas()
            val top = layout.getLineTop(first)
            val lineHeight = layout.getLineBottom(first) - top
            if (lineHeight > bottom - y && y > margin) { nextPage(); continue }
            if (lineHeight > bottom - margin) {
                // An exceptionally tall table row still exports in full across
                // pages, rather than being clipped or creating an infinite loop.
                var offset = top.toFloat()
                val end = layout.getLineBottom(first).toFloat()
                while (offset < end) {
                    canvas()
                    val height = min(end - offset, bottom - y)
                    draw(layout, offset, height)
                    offset += height
                    if (offset < end) nextPage()
                }
                first++
                continue
            }
            var end = first + 1
            while (end < layout.lineCount && layout.getLineBottom(end) - top <= bottom - y) end++
            draw(layout, top.toFloat(), (layout.getLineBottom(end - 1) - top).toFloat())
            first = end
            if (first < layout.lineCount) nextPage()
        }
        y += 9f
    }

    private fun draw(layout: StaticLayout, top: Float, height: Float) {
        val canvas = canvas()
        canvas.save()
        canvas.clipRect(margin, y, margin + contentWidth, y + height)
        canvas.translate(margin, y - top)
        layout.draw(canvas)
        canvas.restore()
        y += height
    }

    fun image(bitmap: Bitmap, label: String) {
        canvas()
        val scale = min(contentWidth.toFloat() / bitmap.width, (bottom - margin) / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        if (height > bottom - y) nextPage()
        canvas().drawBitmap(bitmap, null, RectF(margin + (contentWidth - width) / 2, y, margin + (contentWidth + width) / 2, y + height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        y += height + 6f
        if (label.isNotBlank()) text(label, size = 10f, color = Color.DKGRAY)
    }

    override fun close() { if (page == null && pageNumber == 0) canvas(); nextPage() }
}
