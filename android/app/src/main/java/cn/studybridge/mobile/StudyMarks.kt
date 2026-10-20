package cn.studybridge.mobile

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.LineBackgroundSpan
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

enum class StudyMark(val marker: String, val label: String) {
    CORE("==", "重点掌握"), UNDERSTAND("++", "理解为主"), OVERVIEW("%%", "了解即可"), PITFALL("!!", "易错点")
}
val studyMarkPattern = Regex("(==|\\+\\+|%%|!!)([^\\r\\n]{1,240}?)\\1")

// Work on the parsed Spanned so existing bold, formula, link and table spans
// move together with delimiter removal. Markers inside code remain literal.
fun renderStudyMarks(parsed: Spanned, density: Float, dark: Boolean): Spanned {
    val output = SpannableStringBuilder(parsed)
    studyMarkPattern.findAll(parsed).toList().asReversed().forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        val inCode = parsed.getSpans(start, end, Any::class.java).any { it.javaClass.simpleName.contains("Code", ignoreCase = true) }
        if (!inCode) {
            val kind = StudyMark.entries.first { it.marker == match.groupValues[1] }
            output.delete(end - 2, end); output.delete(start, start + 2)
            output.setSpan(StudyMarkerSpan(kind, density, dark), start, end - 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
    return output
}

class StudyMarkerSpan(val kind: StudyMark, private val density: Float, private val dark: Boolean) : LineBackgroundSpan {
    override fun drawBackground(canvas: Canvas, paint: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, lineNumber: Int) {
        val spanned = text as? Spanned ?: return
        val from = maxOf(start, spanned.getSpanStart(this))
        val to = minOf(end, spanned.getSpanEnd(this))
        if (from >= to) return
        val x1 = left + paint.measureText(text, start, from)
        val x2 = left + paint.measureText(text, start, to)
        val oldColor = paint.color; val oldWidth = paint.strokeWidth; val oldStyle = paint.style; val oldCap = paint.strokeCap
        try {
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            when (kind) {
                StudyMark.CORE -> {
                    paint.color = if (dark) 0x669E8630 else 0x70F4CB55
                    paint.strokeWidth = (paint.fontMetrics.descent - paint.fontMetrics.ascent) * 0.55f
                    val y = baseline + paint.fontMetrics.ascent * 0.25f
                    val path = Path().apply { moveTo(x1 + 1f, y + density); lineTo((x1 + x2) / 2f, y - density); lineTo(x2 - 1f, y) }
                    canvas.drawPath(path, paint)
                }
                StudyMark.UNDERSTAND -> {
                    paint.color = if (dark) 0xFF90BBD8.toInt() else 0xFF568BAF.toInt(); paint.strokeWidth = 1.5f * density
                    canvas.drawLine(x1, baseline + 3f * density, x2, baseline + 2f * density, paint)
                }
                StudyMark.OVERVIEW -> {
                    paint.color = if (dark) 0xFF9CA99D.toInt() else 0xFF88978B.toInt(); paint.strokeWidth = density
                    var x = x1
                    while (x < x2) { canvas.drawLine(x, baseline + 5f * density, minOf(x + 3f * density, x2), baseline + density, paint); x += 8f * density }
                }
                StudyMark.PITFALL -> {
                    paint.color = if (dark) 0xFFE5A48F.toInt() else 0xFFBB735B.toInt(); paint.strokeWidth = 1.3f * density
                    val path = Path(); path.moveTo(x1, baseline + 3f * density)
                    var x = x1
                    while (x < x2) { path.lineTo(minOf(x + 3f * density, x2), baseline + 5f * density); path.lineTo(minOf(x + 6f * density, x2), baseline + 3f * density); x += 6f * density }
                    canvas.drawPath(path, paint)
                }
            }
        } finally { paint.color = oldColor; paint.strokeWidth = oldWidth; paint.style = oldStyle; paint.strokeCap = oldCap }
    }
}

@Composable fun StudyMarkLegend(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("阅读标记") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StudyMark.entries.forEach { mark -> Markdown("${mark.marker}${mark.label}${mark.marker}") }
            Text("记号只标在关键短句上。除非资料明确给出考纲或老师强调，否则代表 AI 建议的学习顺序，不代表考试范围。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { FeedbackTextButton(onClick = onDismiss) { Text("知道了") } })
}
