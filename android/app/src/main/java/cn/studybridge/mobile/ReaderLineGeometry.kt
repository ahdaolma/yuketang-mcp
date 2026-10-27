package cn.studybridge.mobile

import android.graphics.RectF
import android.text.Spanned
import android.widget.TextView
import io.noties.markwon.ext.tables.TableRowSpan

// Layout coordinates, excluding TextView padding/scroll. A table's outer
// replacement-span line is a whole row; use the public cell Layouts instead.
internal fun readerNativeLines(view: TextView, cellPadding: Int): List<RectF> {
    val layout = view.layout ?: return emptyList()
    val text = view.text
    val result = mutableListOf<RectF>()
    for (line in 0 until layout.lineCount) {
        val rows = (text as? Spanned)?.getSpans(layout.getLineStart(line), layout.getLineEnd(line), TableRowSpan::class.java).orEmpty()
        if (rows.isEmpty()) {
            if (layout.getLineLeft(line) < layout.getLineRight(line)) result += RectF(layout.getLineLeft(line), layout.getLineTop(line).toFloat(), layout.getLineRight(line), layout.getLineBottom(line).toFloat())
        } else for (row in rows) {
            val width = row.cellWidth()
            if (width <= 0) continue
            val cells = (0 until (layout.width + width - 1) / width).mapNotNull { column ->
                row.findLayoutForHorizontalOffset(column * width)?.let { column to it }
            }
            val height = cells.maxOfOrNull { it.second.height } ?: continue
            val top = layout.getLineTop(line)
            val offset = top + cellPadding + (layout.getLineBottom(line) - top - height) / 4
            for ((column, cell) in cells) for (inner in 0 until cell.lineCount) {
                if (cell.getLineLeft(inner) < cell.getLineRight(inner)) result += RectF(
                    layout.getLineLeft(line) + (column * width + cellPadding) + cell.getLineLeft(inner),
                    (offset + cell.getLineTop(inner)).toFloat(),
                    layout.getLineLeft(line) + (column * width + cellPadding) + cell.getLineRight(inner),
                    (offset + cell.getLineBottom(inner)).toFloat())
            }
        }
    }
    return result.sortedBy { it.top }
}
