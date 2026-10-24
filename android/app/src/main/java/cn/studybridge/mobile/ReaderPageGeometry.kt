package cn.studybridge.mobile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

data class PaperPoint(val x: Float, val y: Float)
data class PaperFold(
    val front: List<PaperPoint>, val folded: List<PaperPoint>, val back: List<PaperPoint>,
    val normal: PaperPoint, val offset: Float, val crease: List<PaperPoint>, val progress: Float
) {
    fun reflect(point: PaperPoint): PaperPoint {
        val distance = point.x * normal.x + point.y * normal.y - offset
        return PaperPoint(point.x - 2f * normal.x * distance, point.y - 2f * normal.y * distance)
    }
}

private fun clipPaper(points: List<PaperPoint>, nx: Float, ny: Float, offset: Float, positive: Boolean): List<PaperPoint> {
    if (points.isEmpty()) return emptyList()
    val result = mutableListOf<PaperPoint>()
    fun distance(p: PaperPoint) = (p.x * nx + p.y * ny - offset) * if (positive) 1f else -1f
    var previous = points.last(); var before = distance(previous)
    for (point in points) {
        val after = distance(point)
        if ((before >= 0f) != (after >= 0f)) {
            val fraction = before / (before - after)
            result.add(PaperPoint(previous.x + (point.x - previous.x) * fraction, previous.y + (point.y - previous.y) * fraction))
        }
        if (after >= 0f) result.add(point)
        previous = point; before = after
    }
    return result
}

/** A paper corner is reflected across the crease between its rest and touch positions. */
fun readerPaperFold(width: Float, height: Float, progress: Float, bottom: Boolean, touchY: Float): PaperFold {
    val w = width.coerceAtLeast(1f); val h = height.coerceAtLeast(1f)
    val p = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f
    val rectangle = listOf(PaperPoint(0f, 0f), PaperPoint(w, 0f), PaperPoint(w, h), PaperPoint(0f, h))
    if (p <= 0f) return PaperFold(rectangle, emptyList(), emptyList(), PaperPoint(1f, 0f), w, emptyList(), 0f)
    if (p >= 1f) return PaperFold(emptyList(), rectangle, emptyList(), PaperPoint(1f, 0f), 0f, emptyList(), 1f)
    val cy = if (bottom) h else 0f
    val py = cy + ((touchY.takeIf { it.isFinite() } ?: h / 2f).coerceIn(0f, h) - cy) * sin(PI * p).toFloat() * .75f
    val px = w - 2f * w * p
    val length = hypot(w - px, cy - py).coerceAtLeast(.001f)
    val nx = (w - px) / length; val ny = (cy - py) / length
    val offset = nx * (w + px) / 2f + ny * (cy + py) / 2f
    val front = clipPaper(rectangle, nx, ny, offset, false)
    val folded = clipPaper(rectangle, nx, ny, offset, true)
    val temporary = PaperFold(front, folded, emptyList(), PaperPoint(nx, ny), offset, emptyList(), p)
    var back = folded.map(temporary::reflect)
    back = clipPaper(back, 1f, 0f, 0f, true)
    back = clipPaper(back, 1f, 0f, w, false)
    back = clipPaper(back, 0f, 1f, 0f, true)
    back = clipPaper(back, 0f, 1f, h, false)
    val crease = (front + folded).filter { abs(it.x * nx + it.y * ny - offset) < .1f }
        .distinctBy { (it.x * 10).toInt() to (it.y * 10).toInt() }.take(2)
    return temporary.copy(back = back, crease = crease)
}

val NOTE_PAGE_ANIMATIONS = linkedMapOf("curl" to "仿真翻页", "cover" to "覆盖翻页")
fun validNotePageAnimation(value: String) = value.takeIf { it in NOTE_PAGE_ANIMATIONS } ?: "curl"
