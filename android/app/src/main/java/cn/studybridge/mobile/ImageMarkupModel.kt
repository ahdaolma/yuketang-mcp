package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.min

data class MarkPoint(val x: Float, val y: Float)
data class ImageStroke(val points: List<MarkPoint>, val width: Float = .006f, val highlight: Boolean = false)

// Coordinates and pen width are fractions of the original image, never screen pixels.
data class MarkupViewport(val viewWidth: Float, val viewHeight: Float, val imageWidth: Float, val imageHeight: Float, val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {
    val fit = min(viewWidth / imageWidth, viewHeight / imageHeight)
    val width = imageWidth * fit
    val height = imageHeight * fit
    fun point(x: Float, y: Float): MarkPoint? {
        val nx = ((x - viewWidth / 2 - panX) / zoom + width / 2) / width
        val ny = ((y - viewHeight / 2 - panY) / zoom + height / 2) / height
        return if (nx.isFinite() && ny.isFinite() && nx in 0f..1f && ny in 0f..1f) MarkPoint(nx, ny) else null
    }
    fun screen(point: MarkPoint) = MarkPoint(viewWidth / 2 + panX + (point.x - .5f) * width * zoom, viewHeight / 2 + panY + (point.y - .5f) * height * zoom)
}

data class MarkupHistory(val strokes: List<ImageStroke> = emptyList(), val past: List<List<ImageStroke>> = emptyList(), val future: List<List<ImageStroke>> = emptyList()) {
    fun change(next: List<ImageStroke>) = if (next == strokes) this else MarkupHistory(next, (past + listOf(strokes)).takeLast(50))
    fun undo() = if (past.isEmpty()) this else MarkupHistory(past.last(), past.dropLast(1), listOf(strokes) + future)
    fun redo() = if (future.isEmpty()) this else MarkupHistory(future.first(), past + listOf(strokes), future.drop(1))
}

fun eraseImageStrokes(strokes: List<ImageStroke>, point: MarkPoint, aspect: Float, radius: Float): List<ImageStroke> {
    fun distance(a: MarkPoint, b: MarkPoint): Float {
        val dx = b.x - a.x; val dy = (b.y - a.y) * aspect
        val px = point.x - a.x; val py = (point.y - a.y) * aspect
        val length = dx * dx + dy * dy
        val t = if (length == 0f) 0f else ((px * dx + py * dy) / length).coerceIn(0f, 1f)
        return hypot(px - t * dx, py - t * dy)
    }
    return strokes.filterNot { stroke -> stroke.points.zipWithNext().any { (a, b) -> distance(a, b) <= radius + stroke.width / 2 } || (stroke.points.size == 1 && distance(stroke.points[0], stroke.points[0]) <= radius + stroke.width / 2) }
}

fun imageMarkupJson(strokes: List<ImageStroke>): String {
    require(strokes.size <= 1000)
    val array = JSONArray()
    strokes.forEach { stroke ->
        require(stroke.points.isNotEmpty() && stroke.points.size <= 20000 && stroke.width.isFinite() && stroke.width in .001f.. .1f)
        val points = JSONArray()
        stroke.points.forEach { p -> require(p.x.isFinite() && p.y.isFinite() && p.x in 0f..1f && p.y in 0f..1f); points.put(JSONArray().put(p.x.toDouble()).put(p.y.toDouble())) }
        array.put(JSONObject().put("width", stroke.width.toDouble()).put("highlight", stroke.highlight).put("points", points))
    }
    return JSONObject().put("version", 1).put("strokes", array).toString()
}

fun imageMarkupFromJson(text: String): List<ImageStroke> {
    require(text.length <= 16 * 1024 * 1024)
    val json = JSONObject(text); require(json.get("version") == 1)
    val array = json.getJSONArray("strokes"); require(array.length() <= 1000)
    return List(array.length()) { index ->
        val stroke = array.getJSONObject(index)
        require(stroke.get("width") is Number && stroke.get("highlight") is Boolean)
        val width = stroke.getDouble("width").toFloat()
        require(width.isFinite() && width in .001f.. .1f)
        val points = stroke.getJSONArray("points"); require(points.length() in 1..20000)
        ImageStroke(List(points.length()) { p ->
            val xy = points.getJSONArray(p); require(xy.length() == 2 && xy.get(0) is Number && xy.get(1) is Number)
            val x = xy.getDouble(0).toFloat(); val y = xy.getDouble(1).toFloat()
            require(x.isFinite() && y.isFinite() && x in 0f..1f && y in 0f..1f)
            MarkPoint(x, y)
        }, width, stroke.getBoolean("highlight"))
    }
}
