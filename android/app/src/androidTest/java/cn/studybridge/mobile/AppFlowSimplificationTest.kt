package cn.studybridge.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real production screens with synthetic local storage and intercepted HTTP. */
class AppFlowSimplificationTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val provider = Provider("flow-model", "流程测试服务", "https://fixture.example/v1", "fixture-key", "old-model", true)

    private fun back() = compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

    @Test fun readerToolsExposeActualEditSearchAndRemarksWithoutAnotherActionMenu() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note("flow-reader", "直接操作测试", "复制起点需要核对。", "合成笔记", "手写")
        Vault(app).save(Saved(notes = listOf(note)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2); vm.noteReader.open(note) }
            compose.setContent { StudyTheme(false) { StudyApp(vm) } }
            compose.ensureNoteTools()
            compose.onAllNodesWithTag("阅读操作面板").assertCountEquals(1)
            compose.onNodeWithText("笔记选项").assertDoesNotExist()
            compose.onNodeWithText("阅读跳转").assertDoesNotExist()
            for (action in listOf("笔记内查找", "编辑笔记", "我的标记与备注", "导出 PDF", "整本末尾")) {
                compose.onNodeWithText(action).performScrollTo().assertIsDisplayed()
                compose.onNodeWithContentDescription("关闭阅读工具").assertIsDisplayed()
            }
            compose.openNoteTool("编辑笔记")
            compose.onNodeWithTag("阅读操作面板").assertDoesNotExist()
            compose.onNodeWithText("Markdown 笔记").performTextReplacement("复制起点已核对，保留用户修改。")
            compose.onNodeWithText("保存修改").performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single().content.contains("保留用户修改") }
            compose.openNoteTool("笔记内查找")
            compose.onNodeWithTag("阅读操作面板").assertDoesNotExist()
            compose.onNodeWithText("查找完整正文").performTextInput("保留用户修改")
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭").performClick()
            compose.openNoteTool("我的标记与备注")
            compose.onNodeWithText("长按正文，选中文字后添加自己的标记或备注。").assertIsDisplayed()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("笔记阅读页").assertIsDisplayed()
            compose.waitUntil(5000) { Vault(app).load().notes.single().content == "复制起点已核对，保留用户修改。" }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun courseSystemBackRestoresDeckLessonAndCourseParentsWithoutReloading() {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), selectedProvider = provider.id, session = "synthetic-session", sessionVerified = true))
        val requests = AtomicInteger()
        val rain = RainApi(OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            val data = when (chain.request().url.encodedPath) {
                "/v2/api/web/courses/list" -> """{"list":[{"classroom_id":1,"course":{"name":"系统返回课程"},"teacher":{"name":"测试老师"}}]}"""
                "/v2/api/web/logs/learn/1" -> """{"activities":[{"type":14,"courseware_id":10,"title":"系统返回课堂"}]}"""
                "/api/v3/classroom-report/student/lesson-info" -> """{"lessonName":"系统返回课堂","presentationIds":[101]}"""
                "/api/v3/classroom-report/student/ppt" -> """{"title":"空课件","slideList":[]}"""
                else -> error("Unexpected synthetic course request ${chain.request().url.encodedPath}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"code\":0,\"data\":$data}".toResponseBody()).build()
        }.build())
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, rain) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(1); vm.loadCourses() }
            compose.setContent { StudyTheme(false) { StudyApp(vm) } }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.courses.isNotEmpty() }
            compose.onNodeWithText("系统返回课程").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentations.isNotEmpty() }
            val activities = vm.ui.value.presentations
            compose.onNodeWithText("系统返回课堂").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.lesson != null }
            val decks = vm.ui.value.presentations
            compose.onNodeWithText("系统返回课堂 · 课件 1").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentationId == "101" }
            val reads = requests.get()
            back()
            compose.runOnIdle { assertEquals("", vm.ui.value.presentationId); assertEquals(decks, vm.ui.value.presentations) }
            compose.onNodeWithText("系统返回课堂 · 课件 1").performScrollTo().assertIsDisplayed()
            back()
            compose.runOnIdle { assertNull(vm.ui.value.lesson); assertEquals(activities, vm.ui.value.presentations) }
            compose.onNodeWithText("系统返回课堂").performScrollTo().assertIsDisplayed()
            back()
            compose.runOnIdle { assertNull(vm.ui.value.course); assertEquals(1, vm.ui.value.tab); assertEquals(reads, requests.get()) }
            compose.onNodeWithText("系统返回课程").performScrollTo().assertIsDisplayed()
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun delayedModelCatalogCannotReopenAfterLeavingItsPageAndCanStillBeChosenAndSaved() = delayedCatalog("page")
    @Test fun delayedModelCatalogCannotReopenAfterLeavingItsTab() = delayedCatalog("tab")
    @Test fun cancelledModelPopupIntentKeepsCompletedCatalogAvailable() = delayedCatalog("cancel")

    private fun delayedCatalog(exit: String) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(Saved(providers = listOf(provider), selectedProvider = provider.id))
        val requested = CountDownLatch(1); val release = CountDownLatch(1)
        val ai = AiApi(OkHttpClient.Builder().addInterceptor { chain ->
            assertTrue(chain.request().url.encodedPath.endsWith("/models"))
            requested.countDown(); check(release.await(30, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"data":[{"id":"new-model"},{"id":"old-model"}]}""".toResponseBody()).build()
        }.build())
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = ai) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(3); vm.settingsPage("models") }
            compose.setContent { StudyTheme(false) { StudyApp(vm) } }
            compose.onNodeWithText("API Base URL").assertDoesNotExist()
            compose.onNodeWithText("API Key").assertDoesNotExist()
            compose.onNodeWithText("old-model").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存并使用").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("读取可用模型").performScrollTo().performClick()
            compose.waitUntil(5000) { requested.count == 0L && vm.ui.value.busy }
            when (exit) {
                "page" -> {
                    compose.onNodeWithContentDescription("返回设置").performClick()
                    compose.onNodeWithText("模型与账号").performClick()
                }
                "tab" -> {
                    compose.onNodeWithContentDescription("返回设置").performClick()
                    compose.onNodeWithContentDescription("返回学习").performClick()
                    compose.runOnIdle { assertEquals(0, vm.ui.value.tab) }
                    compose.onNodeWithContentDescription("设置").performClick()
                    compose.onNodeWithText("模型与账号").performClick()
                }
                else -> compose.onNodeWithText("取消自动打开模型列表").performScrollTo().performClick()
            }
            compose.runOnIdle {
                assertTrue("Navigation must finish while the catalog request is still pending", vm.ui.value.busy)
                release.countDown()
            }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.modelList.isNotEmpty() }
            compose.onNodeWithText("选择可用模型").assertDoesNotExist()
            compose.onNodeWithText("API Base URL").assertDoesNotExist()
            compose.onNodeWithText("old-model").performScrollTo().performClick()
            compose.onNodeWithText("new-model").performClick()
            // Choosing only changes the form; saving remains an explicit action.
            assertEquals("old-model", vm.ui.value.data.provider().model)
            compose.onNodeWithText("所选模型支持图片输入").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("保存并使用").performScrollTo().performClick()
            compose.waitUntil(5000) { vm.ui.value.data.provider().model == "new-model" && Vault(app).load().provider().model == "new-model" }
            assertEquals("new-model", Vault(app).load().provider().model)
        } finally { release.countDown(); compose.runOnIdle { store.clear() } }
    }
}
