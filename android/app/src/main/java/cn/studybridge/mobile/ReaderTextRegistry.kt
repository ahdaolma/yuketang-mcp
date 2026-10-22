package cn.studybridge.mobile

import android.widget.TextView
import java.lang.ref.WeakReference

// Native line offsets survive font/width changes. Keep only weak references:
// continuous reading must remain free to discard offscreen page views.
internal class ReaderTextRegistry {
    private val views = mutableMapOf<Pair<Int, Int>, WeakReference<TextView>>()
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
