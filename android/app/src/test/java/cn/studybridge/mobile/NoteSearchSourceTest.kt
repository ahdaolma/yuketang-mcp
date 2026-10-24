package cn.studybridge.mobile

import org.junit.Assert.*
import org.junit.Test

class NoteSearchSourceTest {
    @Test fun linkDestinationsCannotShiftTheNextVisibleOccurrence() {
        val source = "[定位词](https://example.test/定位词)\n\n正文定位词。"
        val hits = searchNoteContent(source, "定位词")
        assertEquals(2, hits.size)
        assertEquals(source.lastIndexOf("定位词"), hits.last().offset)
        assertEquals(7, renderedNoteSearchOffset("定位词\n\n正文定位词。", "定位词\n\n正文", "定位词"))
    }

    @Test fun nestedDestinationAndTitleAreHiddenButTheirLabelRemainsSearchable() {
        val source = "[定位词](https://example.test/(定位词) \"定位词)\")\n\n定位词"
        val hits = searchNoteContent(source, "定位词")
        assertEquals(2, hits.size)
        assertEquals(source.lastIndexOf("定位词"), hits.last().offset)
    }

    @Test fun codeKeepsLiteralLinksWhileFenceLanguageIsNotBodyText() {
        val source = "```定位词\r\n[定位词](https://定位词)\r\n```\r\n`[定位词](https://定位词)`\r\n定位词"
        assertEquals(5, searchNoteContent(source, "定位词").size)
    }

    @Test fun referenceAddressesAndImageIdsAreNotInvisibleBodyHits() {
        val id = "a".repeat(64)
        val source = "[定位词][来源定位词]\n\n[来源定位词]: https://example.test/定位词\n\n" + sourceImageMarkdown(id, "定位词") + "\n定位词"
        assertEquals(2, searchNoteContent(source, "定位词").size)
        assertTrue(searchNoteContent(source, id).isEmpty())
    }

    @Test fun ordinaryLiteralBracketsAndEscapesDoNotHideBodyText() {
        assertEquals(1, searchNoteContent("普通[括号]正文定位词", "定位词").size)
        assertEquals(1, searchNoteContent("\\[标签](https://定位词)", "定位词").size)
        assertEquals(2, searchNoteContent("[标签](https://定位词 不完整\n\n定位词", "定位词").size)
    }

    @Test fun prefixLookupPreservesCaseAndNeverWrapsAnAbsentHit() {
        assertEquals(4, renderedNoteSearchOffset("hit HIT", "hit ", "hit"))
        assertEquals(-1, renderedNoteSearchOffset("hit", "hit", "hit"))
        assertEquals(-1, renderedNoteSearchOffset("body", "", ""))
    }

    @Test fun frequentWordsKeepTheFullCountAndRandomAccessWithoutEagerSnippets() {
        val text = "词 ".repeat(20000)
        val hits = searchNoteContent(text, "词")
        assertEquals(20000, hits.size)
        assertEquals(39998, hits.last().offset)
        assertEquals(19999, noteSearchHitIndex(hits, 39998))
        assertEquals(-1, noteSearchHitIndex(hits, 39999))
        assertTrue(hits[19000].snippet.contains("词"))
    }
}
