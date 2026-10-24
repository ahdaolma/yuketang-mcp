package cn.studybridge.mobile

import androidx.compose.runtime.staticCompositionLocalOf

val NOTE_READING_MODES = linkedMapOf("continuous" to "连续滚动", "buttons" to "点击翻页", "swipe" to "横向滑屏")
val LocalNoteFontSize = staticCompositionLocalOf { 16 }
val LocalNoteRenderReady = staticCompositionLocalOf<((Boolean) -> Unit)?> { null }
val LocalNoteTextRegistered = staticCompositionLocalOf<((android.widget.TextView, Int) -> Unit)?> { null }
val LocalNoteContentWidth = staticCompositionLocalOf<androidx.compose.ui.unit.Dp?> { null }
// AndroidView consumes ordinary text taps before Compose's Final pass. Native
// text forwards only taps that do not belong to selection, a link or a remark.
val LocalNotePageTap = staticCompositionLocalOf<((Float) -> Unit)?> { null }
data class ReaderProgress(val text: String, val description: String, val title: String? = null, val titleVisible: Boolean = false)
val LocalReaderProgress = staticCompositionLocalOf<(ReaderProgress?) -> Unit> { {} }
fun validNoteReadingMode(value: String) = value.takeIf { it in NOTE_READING_MODES } ?: "continuous"

// A small overlap makes line wraps visible on both screens. The final advance
// always reaches the exact end of the current segment before changing segments.
fun nextReadingScreen(offset: Int, maximum: Int, viewport: Int, direction: Int): Int {
    val end = maximum.toLong().coerceAtLeast(0)
    val current = offset.toLong().coerceIn(0, end)
    val step = (viewport * .88f).toInt().coerceAtLeast(1).toLong()
    // The last screen can be shorter than step. Return to the previous screen
    // boundary instead of subtracting a full step from this shortened advance.
    return when {
        direction > 0 -> ((current / step + 1) * step).coerceAtMost(end)
        direction < 0 -> ((current - 1).coerceAtLeast(0) / step) * step
        else -> current
    }.toInt()
}

fun readerPageLabels(content: String, segments: List<NoteReadingSegment>): List<String> {
    val pattern = Regex("第\\s*(\\d+)\\s*页")
    val pages = readingHeadings(content).filter { content.startsWith("## ", it.first) && pattern.containsMatchIn(it.second) }
    if (pages.isEmpty()) return segments.indices.map { "第 ${it + 1} / ${segments.size} 页" }
    return segments.map { part ->
        val ordinal = pages.indexOfLast { it.first <= part.start }.coerceAtLeast(0)
        val actual = pattern.find(pages[ordinal].second)?.groupValues?.get(1)?.toIntOrNull() ?: ordinal + 1
        if (actual == ordinal + 1) "第 ${ordinal + 1} / ${pages.size} 页" else "第 $actual 页 · ${ordinal + 1}/${pages.size}"
    }
}
