package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ReaderScreenOffsetsTest {
    @Test fun allOrdinaryStopsUseRealLineBoundariesAndEveryLineFitsAboveTheTool() {
        val lines = (0 until 70).map { ReaderLineBand(8 + it * 43, 8 + (it + 1) * 43) }
        val viewport = 333; val clearance = 64
        val stops = readerScreenOffsets(lines.last().bottom + 86 - viewport, viewport, clearance, lines)
        assertEquals(0, stops.first())
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
        for (stop in stops.dropLast(1)) assertFalse(lines.any { stop > it.top && stop < it.bottom })
        for (line in lines) assertTrue("Must fully reveal $line on some screen", stops.any { line.top >= it && line.bottom <= it + viewport - clearance })
    }
    @Test fun staggeredTableCellLinesStillProgressAndRemainReachable() {
        val lines = (0 until 100).flatMap { listOf(ReaderLineBand(it * 30, it * 30 + 30), ReaderLineBand(it * 30 + 9, it * 30 + 39)) }
        val stops = readerScreenOffsets(2800, 333, 64, lines)
        assertEquals(2800, stops.last())
        assertTrue(stops.size < 30)
        assertTrue(stops.zipWithNext().all { (a, b) -> b > a })
        assertTrue(lines.filter { it.bottom <= 3000 }.all { line -> stops.any { line.top >= it && line.bottom <= it + 269 } })
    }
    @Test fun shortContentAndEmptyGeometryHaveFiniteUsefulBoundaries() {
        assertEquals(listOf(0), readerScreenOffsets(0, 10, 64, emptyList()))
        assertEquals(listOf(0, 1, 2, 3), readerScreenOffsets(3, 1, 64, emptyList()))
        assertEquals(listOf(0, 88, 176, 250), readerScreenOffsets(250, 100, 0, emptyList()))
    }
}
