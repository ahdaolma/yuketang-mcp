package cn.studybridge.mobile

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MaterialMetadataTest {
    @Test fun oldRecordMigrationAndDirectoryRoundTripKeepSourceNamesAndNotes() {
        val old = Saved(notes = listOf(Note(title = "旧笔记", content = "内容", source = "来源", model = "fixture")))
        val json = old.toJson().apply { remove("materials"); remove("downloadTreeUri"); remove("downloadDirectoryName"); getJSONArray("notes").getJSONObject(0).remove("materialId") }
        assertEquals(old, savedFromJson(json))
        val m = StudyMaterial(title = "医学遗传学 Medical Genetics 第三章.pptx", format = "pptx", kind = "notes", pages = 3, modifiedAt = 123)
        val saved = old.copy(materials = listOf(m), downloadTreeUri = "content://fixture/tree/course-notes", downloadDirectoryName = "学习资料", notes = old.notes.map { it.copy(materialId = m.id) })
        assertEquals(saved, savedFromJson(saved.toJson()))
    }
    @Test fun missingSourceTimeStaysUnknownAndExplicitSourceTimeIsPreserved() {
        assertEquals("", sourceRecordTime(JSONObject().put("title", "2026年的课程")))
        assertEquals("2026-10-20T08:00:00+08:00", sourceRecordTime(JSONObject().put("create_time", "2026-10-20T08:00:00+08:00")))
        assertEquals(sourceRecordTime(JSONObject().put("created_at", 1760000000L)), sourceRecordTime(JSONObject().put("created_at", 1760000000000L)))
    }
    @Test fun chineseNotesDecodeAndSegmentWithoutLosingText() {
        val original = "# 细胞机制\n" + "中文 English 解释\n".repeat(1600)
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + original.toByteArray(Charsets.UTF_16LE)
        assertEquals(original, decodeText(utf16)); assertEquals(original, decodeText(original.toByteArray()))
        val pages = textMaterialPages(original)
        assertTrue(pages.size > 1); assertTrue(pages.all { it.text.length <= 6000 })
        assertEquals(original.trimEnd(), pages.joinToString("") { it.text }.trimEnd())
        assertEquals("医学遗传学_Medical Genetics.pdf", downloadName("医学遗传学/Medical Genetics", "pdf"))
    }
}
