package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NoteAnnotationProjectionTest {
    private fun mark(content: String, sourceStart: Int, sourceEnd: Int, start: Int, end: Int, kind: String = "highlight") =
        NoteAnnotation(id = "original-$sourceStart-$start-$kind", sourceStart = sourceStart, sourceLength = sourceEnd - sourceStart,
            sourceHash = imageDigest(content.substring(sourceStart, sourceEnd).toByteArray()), start = start, end = end,
            quote = content.substring(sourceStart + start, sourceStart + end), kind = kind, comment = "原备注")

    @Test fun frozen102AnchorProjectsWithoutRewritingSavedData() {
        val content = "旧标记" + "x".repeat(11967) + "![原图](study-image:" + "a".repeat(64) + ")尾文"
        val original = mark(content, 0, 12000, 0, 3)
        assertEquals("6e8a739b3a871be43398bf063d12c553830051bde43a9de0231adeedcf20ee58", original.sourceHash)
        val source = content.take(11970)
        val projected = projectNoteAnnotations(source, source, 0, listOf(original), content, render = { it }).single()
        assertSame(original, projected.annotation)
        assertEquals(0, projected.start); assertEquals(3, projected.end)
        assertEquals(12000, original.sourceLength)
    }

    @Test fun bothShiftedBoundariesKeepRepeatedQuoteAtItsSourceRank() {
        val content = "重复".repeat(200)
        val original = mark(content, 100, 300, 60, 62, "remark")
        val source = content.substring(70, 270)
        val projected = projectNoteAnnotations(source, source, 70, listOf(original), content, render = { it }).single()
        assertEquals(90, projected.start); assertEquals(92, projected.end)
        assertEquals("重复", source.substring(projected.start, projected.end))
        assertSame(original, projected.annotation)
        assertEquals("原备注", projected.annotation.comment)
    }

    @Test fun containedOldBlockMapsThroughItsSourceBoundariesRatherThanFirstDuplicate() {
        val content = "重复".repeat(200)
        val original = mark(content, 100, 200, 40, 42)
        val projected = projectNoteAnnotations(content, content, 0, listOf(original), content, render = { it }).single()
        assertEquals(140, projected.start); assertEquals(142, projected.end)
    }

    @Test fun annotationSpanningCurrentBlocksIsClippedToEachVerifiedOverlap() {
        val content = "0123456789".repeat(30)
        val original = mark(content, 20, 220, 70, 90, "underline")
        val left = content.substring(10, 100)
        val right = content.substring(100, 200)
        val first = projectNoteAnnotations(left, left, 10, listOf(original), content, render = { it }).single()
        val last = projectNoteAnnotations(right, right, 100, listOf(original), content, render = { it }).single()
        assertEquals(80, first.start); assertEquals(90, first.end)
        assertEquals(0, last.start); assertEquals(10, last.end)
        assertSame(first.annotation, last.annotation)
    }

    @Test fun staleHashAndInvalidOldRenderedOffsetsFailClosedDespiteDuplicateQuotes() {
        val content = "重复".repeat(200)
        val original = mark(content, 100, 300, 60, 62)
        val changed = content.replaceRange(120, 121, "改")
        val source = changed.substring(70, 270)
        assertTrue(projectNoteAnnotations(source, source, 70, listOf(original), changed, render = { it }).isEmpty())
        val broken = original.copy(start = 61, end = 63)
        assertTrue(projectNoteAnnotations(content.substring(70, 270), content.substring(70, 270), 70, listOf(broken), content, render = { it }).isEmpty())
    }

    @Test fun changedRenderContextDoesNotGuessAnotherOccurrence() {
        val content = "before|重复|after"
        val original = mark(content, 0, content.length, 7, 9)
        val source = content.substring(7)
        // A renderer/plugin that changes the common run cannot make a valid
        // source anchor by finding the same quote elsewhere in displayed text.
        assertTrue(projectNoteAnnotations("其他重复内容", source, 7, listOf(original), content, render = { it }).isEmpty())
    }

    @Test fun syntheticPrefixReceivesNoLegacyMarkAndBodyKeepsCorrectOffset() {
        val content = "重复正文尾文"
        val original = mark(content, 0, content.length, 2, 4)
        val source = content.take(6)
        val projected = projectNoteAnnotations("标题\n$source", source, 0, listOf(original), content, annotationStart = 3, renderPrefix = "标题\n", render = { it }).single()
        assertEquals(5, projected.start); assertEquals(7, projected.end)
        assertSame(original, projected.annotation)
    }

    @Test fun unchangedRawHashWithNewSyntheticPrefixProjectsBodyInsteadOfIdenticalHeader() {
        val content = "重复正文尾文"
        val original = mark(content, 0, content.length, 0, 2)
        val projected = projectNoteAnnotations("重复标题\n$content", content, 0, listOf(original), content, annotationStart = 5, renderPrefix = "重复标题\n", render = { it }).single()
        assertEquals(5, projected.start); assertEquals(7, projected.end)
        assertSame(original, projected.annotation)
    }

    @Test fun duplicateQuoteAndSameRawHashUseSavedRenderingContextToDistinguishLegacyAndNewMarks() {
        val content = "重复".repeat(10)
        val prefix = "重复"
        val legacy = mark(content, 0, content.length, 10, 12)
        val current = legacy.copy(id = "new-current-context", start = 12, end = 14, renderPrefix = prefix)
        val result = projectNoteAnnotations(prefix + content, content, 0, listOf(legacy, current), content,
            annotationStart = 2, renderPrefix = prefix, render = { it })
        assertEquals(2, result.size)
        result.forEach { assertEquals(12, it.start); assertEquals(14, it.end) }
        assertSame(legacy, result[0].annotation); assertSame(current, result[1].annotation)
        // A current anchor also projects back into a display without a prefix.
        val plain = projectNoteAnnotations(content, content, 0, listOf(current), content, render = { it }).single()
        assertEquals(10, plain.start); assertEquals(12, plain.end)
    }

    @Test fun savedRenderingContextRoundTripsAndOldJsonDefaultsToEmptyContext() {
        val content = "真实正文"
        val legacy = mark(content, 0, content.length, 0, 2)
        val oldJson = legacy.json().apply { remove("renderPrefix") }
        assertEquals(legacy, noteAnnotationFromJson(oldJson))
        val current = legacy.copy(renderPrefix = "|表头|\n|---|\n")
        assertEquals(current, noteAnnotationFromJson(current.json()))
        assertEquals(legacy.id, noteAnnotationFromJson(current.json()).id)
        assertTrue(noteAnnotationFromJson(current.json()).isAttached(content))
    }
}
