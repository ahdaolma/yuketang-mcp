package cn.studybridge.mobile

import androidx.compose.runtime.staticCompositionLocalOf

val NOTE_READING_MODES = linkedMapOf("continuous" to "连续滚动", "buttons" to "点击翻页", "swipe" to "横向滑屏")
val LocalNoteFontSize = staticCompositionLocalOf { 16 }
val LocalNoteRenderReady = staticCompositionLocalOf<(() -> Unit)?> { null }
val LocalNoteContentWidth = staticCompositionLocalOf<androidx.compose.ui.unit.Dp?> { null }
data class ReaderProgress(val text: String, val description: String, val title: String? = null, val titleVisible: Boolean = false)
val LocalReaderProgress = staticCompositionLocalOf<(ReaderProgress?) -> Unit> { {} }
fun validNoteReadingMode(value: String) = value.takeIf { it in NOTE_READING_MODES } ?: "continuous"

// A small overlap makes line wraps visible on both screens. The final advance
// always reaches the exact end of the current segment before changing segments.
fun nextReadingScreen(offset: Int, maximum: Int, viewport: Int, direction: Int): Int =
    (offset.toLong() + direction * (viewport * .88f).toInt().coerceAtLeast(1)).coerceIn(0L, maximum.toLong().coerceAtLeast(0)).toInt()

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
