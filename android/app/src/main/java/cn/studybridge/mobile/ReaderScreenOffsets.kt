package cn.studybridge.mobile

internal data class ReaderLineBand(val top: Int, val bottom: Int)

// Stops are based on measured lines, never estimated font metrics. Keep enough
// overlap for any edge line hidden by the tool or the viewport to fit completely
// on another screen. Adjacent (touching) lines remain separate safe boundaries.
internal fun readerScreenOffsets(maximum: Int, viewport: Int, clearance: Int, lines: List<ReaderLineBand>): List<Int> {
    val end = maximum.coerceAtLeast(0)
    if (end == 0) return listOf(0)
    val usable = (viewport - clearance).coerceAtLeast(1)
    val overlap = lines.map { it.bottom - it.top }.filter { it in 1 until usable }.maxOrNull() ?: 0
    val step = minOf((viewport * .88f).toInt(), usable - overlap).coerceAtLeast(1)
    val merged = mutableListOf<ReaderLineBand>()
    for (line in lines.sortedBy { it.top }) {
        val previous = merged.lastOrNull()
        if (previous != null && line.top < previous.bottom)
            merged[merged.lastIndex] = ReaderLineBand(previous.top, maxOf(previous.bottom, line.bottom))
        else merged += line
    }
    val boundaries = (listOf(0, end) + merged.flatMap { listOf(it.top, it.bottom) }).filter { it in 0..end }.distinct().sorted()
    val result = mutableListOf(0)
    var current = 0
    while (current < end) {
        val wanted = (current.toLong() + step).coerceAtMost(end.toLong()).toInt()
        val found = boundaries.binarySearch(wanted)
        val floor = if (found >= 0) found else -found - 2
        val before = boundaries[floor.coerceAtLeast(0)]
        val after = boundaries.getOrNull(floor + 1)
        val next = when {
            before > current -> before
            after != null && after.toLong() <= current.toLong() + usable -> after
            else -> wanted
        }
        result += next
        current = next
    }
    return result
}
