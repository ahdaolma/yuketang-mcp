package cn.studybridge.mobile

import android.provider.DocumentsContract
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatLearningStateTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun provider(images: Boolean = true) = Provider("chat-fixture", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model", images)
    private fun rain(data: (Request) -> String) = RainApi(OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{\"code\":0,\"data\":${data(request)}}".toResponseBody()).build()
    }.build())

    @Test fun defaultTitlesRepairOnLoadAndDraftsBelongToChatsUntilAnAcceptedSendOrDeletion() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val legacy = Conversation("legacy", messages = listOf(Message("user", "", source = "历史课件 · 第 3 页"), Message("assistant", "旧解释")))
        val first = Conversation("first", title = "保留空会话名称")
        val second = Conversation("second")
        Vault(app).save(Saved(conversations = listOf(first, second, legacy), currentChat = first.id))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, rain { "{\"title\":\"来源快照\",\"slides\":[{\"index\":1,\"text\":\"原文\"}]}" }, fixtureAiApi("回答")) }
            compose.setContent { Text("会话状态回归") }
            compose.waitUntil(5000) { vm.ui.value.ready }
            assertEquals("历史课件 · 第 3 页", vm.ui.value.data.conversations.first { it.id == legacy.id }.title)
            compose.runOnIdle {
                vm.chatDraft(first.id, "第一份草稿")
                vm.selectChat(second.id); vm.chatDraft(second.id, "第二份草稿")
                vm.tab(2); vm.tab(0); vm.selectChat(first.id)
                vm.selectChat("不存在的 ID")
                assertEquals(first.id, vm.ui.value.data.chat().id)
                assertFalse(vm.send("第一份草稿"))
                assertEquals("第一份草稿", vm.ui.value.chatDrafts[first.id])
                val p = provider(); assertTrue(vm.saveProvider(p))
                assertTrue(vm.send("第一份草稿"))
                assertFalse(vm.ui.value.chatDrafts.containsKey(first.id))
                assertEquals("第二份草稿", vm.ui.value.chatDrafts[second.id])
            }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(first.title, vm.ui.value.data.conversations.first { it.id == first.id }.title)
            compose.runOnIdle { vm.selectChat(second.id); vm.loadSlides("12") }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.slides.isNotEmpty() }
            compose.runOnIdle { vm.attach(vm.ui.value.slides.single()) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.pending != null }
            compose.runOnIdle {
                vm.deleteChat(first.id)
                assertNotNull(vm.ui.value.pending)
                vm.deleteChat(second.id)
                assertNull(vm.ui.value.pending)
                assertFalse(vm.ui.value.chatDrafts.containsKey(second.id))
            }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun emptyDeckBackRestoresLessonThenActivitySnapshotWithoutAnotherRequest() {
        val app = noteTestApplication(compose.activity.applicationContext); val p = provider()
        Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id, session = "fixture-session-123", sessionVerified = true))
        val calls = AtomicInteger()
        val api = rain { request ->
            calls.incrementAndGet()
            when {
                request.url.encodedPath.contains("/logs/learn/") -> """{"activities":[{"type":14,"courseware_id":42,"title":"第一节"},{"presentation_id":99,"title":"独立课件"}]}"""
                request.url.encodedPath.endsWith("lesson-info") -> """{"presentationIds":[12],"lessonName":"第一节"}"""
                else -> """{"title":"空课件","slideList":[]}"""
            }
        }
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, api) }
            compose.setContent { Text("资料层级回归") }; compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.openMaterials(); vm.materialPane("rain"); vm.loadActivities(Course("7", "课程"), 2) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentations.size == 2 }
            val activities = vm.ui.value.presentations
            compose.runOnIdle { vm.openPresentation(activities.first { it.isLesson }) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.lesson != null }
            val lessonDecks = vm.ui.value.presentations
            compose.runOnIdle { vm.openPresentation(lessonDecks.single()) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentationId == "12" }
            assertTrue(vm.ui.value.slides.isEmpty())
            val readCount = calls.get()
            compose.runOnIdle {
                vm.backRainMaterials(); assertEquals("", vm.ui.value.presentationId); assertEquals(lessonDecks, vm.ui.value.presentations)
                vm.backMaterials(); assertNull(vm.ui.value.lesson); assertEquals(activities, vm.ui.value.presentations); assertEquals(2, vm.ui.value.activityPage)
                vm.backMaterials(); assertNull(vm.ui.value.course)
                vm.backMaterials(); assertEquals("home", vm.ui.value.materialPane)
                vm.backMaterials(); assertFalse(vm.ui.value.materialsOpen)
            }
            assertEquals(readCount, calls.get())
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun fullDeckSavesFiftyOnePagesAndFailedPreflightKeepsMaterialsOpen() {
        val app = noteTestApplication(compose.activity.applicationContext); val p = provider()
        Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id))
        val pages = (1..51).joinToString(",") { "{\"index\":${it * 2},\"text\":\"第 $it 段\"}" }
        val calls = AtomicInteger()
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, rain { "{\"title\":\"整份资料\",\"slides\":[$pages]}" }, fixtureAiApi("逐页解释", inspect = { calls.incrementAndGet() })) }
            compose.setContent { Text("整份资料回归") }; compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.openMaterials(); vm.batchAllNotes() }
            assertTrue(vm.ui.value.materialsOpen); assertFalse(vm.ui.value.busy); assertEquals(0, calls.get())
            compose.runOnIdle { vm.loadSlides("12") }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.slides.size == 51 }
            compose.runOnIdle { vm.batchNotes("2", "102") }
            assertTrue(vm.ui.value.materialsOpen); assertEquals(0, calls.get())
            compose.runOnIdle { assertTrue(vm.saveProvider(p.copy(key = ""))); vm.batchAllNotes() }
            assertTrue(vm.ui.value.materialsOpen); assertEquals(0, calls.get()); assertFalse(vm.ui.value.busy)
            compose.runOnIdle { assertTrue(vm.saveProvider(p)) }
            compose.runOnIdle { vm.batchAllNotes() }
            compose.waitUntil(60000) { !vm.ui.value.busy }
            assertEquals(51, calls.get()); assertFalse(vm.ui.value.materialsOpen)
            val saved = Vault(app).load().notes.single()
            assertTrue(saved.content.contains("已完成 51/51")); assertTrue(saved.content.contains("## 第 102 页"))
            assertEquals(51, Regex("(?m)^## 第 ").findAll(saved.content).count())
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun closedOrNavigatedMaterialsIgnoreInFlightReadsAndImportsButKeepImportedRecords() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved())
        val requested = CountDownLatch(1); val release = CountDownLatch(1)
        val api = rain { requested.countDown(); check(release.await(30, TimeUnit.SECONDS)); """{"title":"晚到的课件","slides":[{"index":1,"text":"原文"}]}""" }
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        val context = compose.activity
        val root = DocumentsContract.buildDocumentUri("cn.studybridge.mobile.downloadfixture", "root")
        val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "text/markdown", "异步笔记-${newId()}.md"))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write("# 导入原文\n原文不能丢失".toByteArray()) }
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, api, fixtureAiApi("新解释")) }
            compose.setContent { Text("异步资料回归") }; compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.openMaterials(); vm.materialPane("rain"); vm.loadSlides("12") }
            compose.waitUntil(5000) { requested.count == 0L }
            compose.runOnIdle { vm.backMaterials(); vm.closeMaterials(); release.countDown() }
            compose.waitUntil(5000) { !vm.ui.value.busy }
            assertFalse(vm.ui.value.materialsOpen); assertEquals("", vm.ui.value.presentationId); assertTrue(vm.ui.value.slides.isEmpty())
            // Import is also valid when called while the sheet is closed. A subsequent
            // close invalidates only its presentation, never its durable imported data.
            compose.runOnIdle { vm.importMaterial(uri, "notes"); vm.closeMaterials() }
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.materials.size == 1 }
            assertFalse(vm.ui.value.materialsOpen); assertNull(vm.ui.value.localDocument)
            val material = vm.ui.value.data.materials.single()
            compose.runOnIdle { vm.openMaterial(material) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.materialsOpen }
            compose.runOnIdle { vm.explainPage(vm.ui.value.slides.single()) }
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 2 }
            assertEquals((material.title + " · 第 1 段").take(24), vm.ui.value.data.chat().title)
            compose.runOnIdle { vm.openMaterial(material) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.materialsOpen }
            compose.runOnIdle { vm.backMaterials(); assertEquals("notes", vm.ui.value.materialPane); assertNull(vm.ui.value.localDocument) }
        } finally { release.countDown(); compose.runOnIdle { store.clear() }; DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}
