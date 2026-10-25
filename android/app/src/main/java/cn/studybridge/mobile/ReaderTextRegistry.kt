package cn.studybridge.mobile

import android.widget.TextView
import android.graphics.RectF
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import java.lang.ref.WeakReference

// Native line offsets survive font/width changes. Keep only weak references:
// continuous reading must remain free to discard offscreen page views.
internal class ReaderTextRegistry {
    private val views = mutableMapOf<Pair<Int, Int>, WeakReference<TextView>>()
    private val visuals = java.util.WeakHashMap<LayoutCoordinates, Unit>()
    fun registerVisual(coordinates: LayoutCoordinates) { visuals[coordinates] = Unit }
    // Only actual glyphs intersecting the floating tool require end clearance.
    // A full-width TextView box alone would treat a short left-aligned last
    // line as occluded and manufacture a padding-only reading screen.
    fun contentIntersects(obstacle: RectF): Boolean {
        if (visuals.keys.any { coordinates ->
            if (!coordinates.isAttached) false else {
                val p = coordinates.positionInWindow()
                RectF.intersects(obstacle, RectF(p.x, p.y, p.x + coordinates.size.width, p.y + coordinates.size.height))
            }
        }) return true
        return views.values.any { reference ->
            val view = reference.get()
            val layout = view?.layout
            if (view == null || !view.isAttachedToWindow || !view.isShown || view.alpha < .99f || layout == null) false
            else {
                val location = IntArray(2); view.getLocationInWindow(location)
                val x = location[0] + view.totalPaddingLeft - view.scrollX
                val y = location[1] + view.totalPaddingTop - view.scrollY
                val first = layout.getLineForVertical((obstacle.top - y).toInt().coerceAtLeast(0))
                val last = layout.getLineForVertical((obstacle.bottom - y).toInt().coerceAtLeast(0))
                (first..last).any { line ->
                    layout.getLineLeft(line) < layout.getLineRight(line) && RectF.intersects(obstacle,
                        RectF(x + layout.getLineLeft(line), (y + layout.getLineTop(line)).toFloat(),
                            x + layout.getLineRight(line), (y + layout.getLineBottom(line)).toFloat()))
                }
            }
        }
    }
    fun register(page: Int, sourceStart: Int, view: TextView) {
        if (sourceStart >= 0) views[page to sourceStart] = WeakReference(view)
    }
    fun find(page: Int, sourceStart: Int): TextView? = views[page to sourceStart]?.get()?.takeIf { it.isAttachedToWindow && it.layout != null }
    fun capture(top: Float, bottom: Float): Pair<Int, NoteTextAnchor>? {
        var firstY = Float.MAX_VALUE
        var first: Pair<Int, NoteTextAnchor>? = null
        val iterator = views.iterator()
        while (iterator.hasNext()) {
            val (key, reference) = iterator.next()
            val view = reference.get()
            if (view == null) { iterator.remove(); continue }
            if (!view.isAttachedToWindow || !view.isShown || view.alpha < .99f || view.text.isEmpty()) continue
            val layout = view.layout ?: continue
            val location = IntArray(2); view.getLocationInWindow(location)
            val origin = location[1] + view.totalPaddingTop - view.scrollY
            var line = layout.getLineForVertical((top - origin).toInt().coerceAtLeast(0))
            if (origin + layout.getLineTop(line) < top) line++
            if (line >= layout.lineCount) continue
            val y = (origin + layout.getLineTop(line)).toFloat()
            if (y < top || origin + layout.getLineBottom(line) > bottom || y >= firstY) continue
            firstY = y
            first = key.first to NoteTextAnchor(key.second, layout.getLineStart(line), (y - top).toInt())
        }
        return first
    }
}
