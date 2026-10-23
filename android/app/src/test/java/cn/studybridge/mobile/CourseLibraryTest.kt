package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CourseLibraryTest {
    @Test fun timestampsAreStrictAndPreserveSecondsAndOffsets() {
        assertEquals(parseSourceEpoch("1760000000"), parseSourceEpoch("1760000000000"))
        assertEquals(parseSourceEpoch("2026-10-20T00:00:00Z"), parseSourceEpoch("2026-10-20T08:00:00+08:00"))
        assertEquals(parseSourceEpoch("2026-10-20 08:00:01"), parseSourceEpoch("2026年10月20日 08:00:01"))
        assertEquals(parseSourceEpoch("2026-10-20 08:00:01"), parseSourceEpoch("2026年10月20日8时0分1秒"))
        assertEquals(1L, requireNotNull(parseSourceEpoch("1760000000001")) - requireNotNull(parseSourceEpoch("1760000000000")))
        listOf("", "null", "0", "-1", "9999999999999999999999", "2026-02-30", "2026-13-01", "today").forEach { assertNull(it, parseSourceEpoch(it)) }
    }
    @Test fun metadataStaysWithItsPresentationAndRootDatesAreActivityDates() {
        val items = activityPresentations(JSONArray("""[{"created_at":"2026-10-22","content":[{"presentation_id":1,"created_at":"2026-10-20"},{"presentationId":2,"created_at":"2026-10-21"}]}]"""))
        assertEquals(listOf("2026-10-20", "2026-10-21"), items.map { it.times.first { t -> t.kind == SourceTimeKind.PRESENTATION_CREATED }.raw })
        assertTrue(items.all { p -> p.times.any { it.kind == SourceTimeKind.ACTIVITY_RECORDED && it.raw == "2026-10-22" } })
        val root = activityPresentations(JSONArray("""[{"presentation_id":1,"create_time":"2026-10-22"}]""")).single()
        assertTrue(root.times.none { it.kind == SourceTimeKind.PRESENTATION_CREATED })
        assertTrue(presentationMetadata(root).contains("创建时间：未提供"))
        val siblings = activityPresentations(JSONArray("""[{"content":[{"presentation_id":1},{"created_at":"2026-10-21"}]}]""")).single()
        assertTrue(siblings.times.none { it.kind == SourceTimeKind.PRESENTATION_CREATED })
        val explicit = activityPresentations(JSONArray("""[{"presentation_id":1,"presentation_create_time":"invalid"}]""")).single()
        assertTrue(presentationMetadata(explicit).contains("创建时间：invalid（无法解析）"))
    }
    @Test fun stringRefsAndLessonMeaningAndMetadataInheritanceRemainSafe() {
        val items = activityPresentations(JSONArray("""[{"content":"{\"presentation_id\":3,\"created_at\":\"2026-10-20\"}"},{"type":14,"courseware_id":42,"start_time":"2026-10-21"}]"""))
        assertEquals("3", items[0].id)
        assertEquals("lesson:42", items[1].id)
        val child = lessonPresentation("9", "第1份", items[1].copy(times = items[1].times + sourceTime(SourceTimeKind.PRESENTATION_CREATED, "2026-10-20", "created_at")))
        assertTrue(child.times.none { it.kind == SourceTimeKind.PRESENTATION_CREATED })
        assertTrue(child.times.any { it.kind == SourceTimeKind.LESSON_STARTED })
    }
    @Test fun unknownDatesAreLastBothWaysAndTiesKeepOriginalOrder() {
        fun p(id: String, raw: String) = Presentation(id, id, times = listOf(sourceTime(SourceTimeKind.PRESENTATION_CREATED, raw, "created_at")))
        val items = listOf(p("unknown", "invalid"), p("a", "2026-10-20 08:00:01"), p("b", "2026-10-20 08:00:02"), p("same", "2026-10-20 08:00:01"))
        assertEquals(listOf("b", "a", "same", "unknown"), orderedPresentations(items, CourseOrder.NEWEST).map { it.id })
        assertEquals(listOf("a", "same", "b", "unknown"), orderedPresentations(items, CourseOrder.OLDEST).map { it.id })
    }
    @Test fun teachersAreExplicitAndDuplicateEnrichmentIsStable() {
        val courses = listOf(Course("1", "B", "旧详情", "王 · 老师"), Course("2", "A"), Course("3", "C", teacher = "陈老师"))
        assertEquals(listOf("王 · 老师", "陈老师", "老师未提供"), groupedCourses(courses, CourseOrder.TEACHER).map { it.first })
        val list = mergePresentations(listOf(Presentation("1", "原名"), Presentation("1", "重复名", teacher = "张老师", times = listOf(sourceTime(SourceTimeKind.ACTIVITY_PUBLISHED, "2026-10-20", "publish_time")))))
        assertEquals(1, list.size); assertEquals("原名", list.single().title); assertEquals("张老师", list.single().teacher)
        assertEquals("", Presentation("1", "兼容", "", false, "旧时间").teacher)
        assertEquals("", Course("1", "兼容", "详情").teacher)
    }
    @Test fun columnsFollowAvailableWidthAndFontScale() {
        assertEquals(1, courseLibraryColumns(320f, 1f))
        assertEquals(2, courseLibraryColumns(900f, 1f))
        assertEquals(3, courseLibraryColumns(1080f, 1f))
        assertEquals(1, courseLibraryColumns(1000f, 2f))
    }
    @Test fun courseNameOrderIsGlobalAndOnlyTeacherOrderAddsGroups() {
        val backend = listOf(Course("1", "Zebra", teacher = "A老师"), Course("2", "Alpha", teacher = "Z老师"), Course("3", "Beta"))
        val names = groupedCourses(backend, CourseOrder.NAME)
        assertEquals(listOf(""), names.map { it.first })
        assertEquals(listOf("2", "3", "1"), names.flatMap { it.second }.map { it.id })
        for (order in listOf(CourseOrder.NEWEST, CourseOrder.OLDEST)) {
            assertEquals(listOf(""), groupedCourses(backend, order).map { it.first })
            assertEquals(backend, groupedCourses(backend, order).flatMap { it.second })
        }
        assertEquals(listOf("A老师", "Z老师", "老师未提供"), groupedCourses(backend, CourseOrder.TEACHER).map { it.first })
    }
    @Test fun duplicateInvalidTimeDoesNotDiscardLaterValidTimeOrChangeIdentity() {
        val invalid = sourceTime(SourceTimeKind.PRESENTATION_CREATED, "invalid", "created_at")
        val valid = sourceTime(SourceTimeKind.PRESENTATION_CREATED, "2026-10-21 08:00:02", "create_time")
        val merged = mergePresentations(listOf(Presentation("1", "原名", times = listOf(invalid)), Presentation("2", "第二份", times = listOf(sourceTime(SourceTimeKind.PRESENTATION_CREATED, "2026-10-20", "created_at"))), Presentation("1", "重复名", times = listOf(valid))))
        assertEquals(listOf("1", "2"), merged.map { it.id })
        assertEquals("原名", merged.first().title)
        assertEquals(listOf(invalid, valid), merged.first().times)
        assertEquals(listOf("1", "2"), orderedPresentations(merged, CourseOrder.NEWEST).map { it.id })
        assertTrue(presentationMetadata(merged.first()).contains("创建时间：2026-10-21 08:00:02"))
    }
}
