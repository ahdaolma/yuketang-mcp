package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.Spanned
import android.text.TextPaint
import io.noties.markwon.ext.tables.TableRowSpan

// Markwon 4.6.2 discovers table cell height during its first draw, then posts
// another setText. Prime that same public draw/getSize path at the real reader
// width before the LazyColumn measures the view, so the row never grows later.
// The one-pixel target is only a canvas; no page-sized bitmap is allocated.
internal fun prepareReaderTables(text: Spanned, paint: TextPaint, width: Int, debug: Boolean, textColor: Int = paint.color) {
    if (width <= 0) return
    val rows = text.getSpans(0, text.length, TableRowSpan::class.java)
    if (rows.isEmpty()) return
    val began = System.nanoTime()
    // TextView updates currentTextColor before onDraw updates its TextPaint.
    // TableRowSpan retains the first draw paint while its width stays equal.
    val tablePaint = TextPaint(paint).apply { color = textColor }
    val bitmap = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(bitmap)
        val metrics = android.graphics.Paint.FontMetricsInt()
        for (row in rows) {
            val start = text.getSpanStart(row); val end = text.getSpanEnd(row)
            row.draw(canvas, text, start, end, 0f, 0, 0, tablePaint.fontMetricsInt.run { descent - ascent }, tablePaint)
            row.getSize(tablePaint, text, start, end, metrics)
        }
    } finally { bitmap.recycle() }
    if (debug) android.util.Log.d("ReaderFormat", "tableRows=${rows.size} width=$width ns=${System.nanoTime() - began}")
}
