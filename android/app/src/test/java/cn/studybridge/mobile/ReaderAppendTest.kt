package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class ReaderAppendTest {
    private val old = "## 第 1 页\n\n第一页。\n\n---\n\n## 第 2 页\n\n第二页。\n\n---\n\n"
    private fun retains(next: String) = readerAppendKeepsSegments(old, noteReadingSegments(old), next, noteReadingSegments(next))

    @Test fun completeClassPagePreservesEveryOldSourceBoundary() {
        assertTrue(retains(old + "## 第 3 页\n\n新完成的页。\n\n---\n\n"))
        assertFalse(retains(old))
        assertFalse(retains(old.replace("第二页", "修改旧页") + "## 第 3 页\n\n新页。"))
    }
    @Test fun extendingTheExistingLastPageIsNotACompletedPageAppend() {
        assertFalse(retains(old + "还没有新源页的补充内容。"))
        val fence = "## 第 1 页\n\n```kotlin\nval answer = 42\n"
        val next = fence + "## 第 2 页\n\n仍在代码块中。"
        assertFalse(readerAppendKeepsSegments(fence, noteReadingSegments(fence), next, noteReadingSegments(next)))
    }
    @Test fun annotationDependenciesIgnoreOnlyContentOutsideVerifiedRanges() {
        val source = "旧的重复引用重复"
        val mark = NoteAnnotation(sourceStart = 0, sourceLength = source.length, sourceHash = imageDigest(source.toByteArray()),
            start = 2, end = 4, quote = source.substring(2, 4), kind = "remark", comment = "保持备注")
        fun key(content: String, marks: List<NoteAnnotation> = listOf(mark)) = noteAnnotationContentKey(content, source, 0, marks)
        assertEquals(listOf(true, true), key(source))
        assertEquals(key(source), key(source + "\n\n## 新页\n\n新引用"))
        assertNotEquals(key(source), key(source.replaceFirst("旧", "新")))
        assertNotEquals(key(source), key(source, listOf(mark.copy(sourceStart = -1))))
        assertEquals(emptyList<Boolean>(), key(source, emptyList()))
    }
    @Test fun anUnrelatedOldMarkBecomingAttachedMustNotReplaceCurrentText() {
        val source = "当前正在阅读的正文。\n\n---\n\n"
        val later = "## 第 2 页\n\n后来重新补齐的旧页。"
        val quoteStart = later.indexOf("旧页") - 3 // Rendered heading drops only the leading Markdown marker.
        val mark = NoteAnnotation(sourceStart = source.length, sourceLength = later.length,
            sourceHash = imageDigest(later.toByteArray()), start = quoteStart, end = quoteStart + 2, quote = "旧页", kind = "remark")
        assertFalse(mark.isAttached(source))
        assertTrue(mark.isAttached(source + later))
        assertEquals("Marks outside this source cannot change its rendering dependency",
            noteAnnotationContentKey(source, source, 0, listOf(mark)),
            noteAnnotationContentKey(source + later, source, 0, listOf(mark)))
    }
}
