package cn.studybridge.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Synthetic full UI flows: every HTTP request is intercepted, no real classroom/account. */
class ChatLearningUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val model = Provider("chat-ui", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model", true)
    private lateinit var vm: StudyViewModel
    private fun prepare(saved: Saved, store: ViewModelStore, scale: Float = 1f) {
        val app = noteTestApplication(compose.activity.applicationContext)
        Vault(app).save(saved)
        val rain = RainApi(OkHttpClient.Builder().addInterceptor { chain ->
            val data = when (chain.request().url.encodedPath) {
                "/v2/api/web/courses/list" -> """{"list":[{"classroom_id":1,"course":{"name":"生物化学"},"teacher":{"name":"陈老师"}}]}"""
                "/v2/api/web/logs/learn/1" -> """{"activities":[{"type":14,"courseware_id":10,"title":"酶学课堂"}]}"""
                "/api/v3/classroom-report/student/lesson-info" -> """{"lessonName":"酶学课堂","presentationIds":[101]}"""
                "/api/v3/classroom-report/student/ppt" -> """{"title":"酶的结构与功能","slideList":[{"index":2,"text":"结合基团","visible":true},{"index":100,"text":"催化基团","visible":true}]}"""
                else -> error("Unexpected synthetic request ${chain.request().url.encodedPath}")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{\"code\":0,\"data\":$data}".toResponseBody()).build()
        }.build())
        compose.runOnUiThread { vm = noteTestViewModel(app, store, rain, fixtureAiApi("有依据的逐页讲解")) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.setContent {
            val ui by vm.ui.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                StudyTheme(false) {
                    Column(Modifier.fillMaxSize().imePadding()) {
                        if (ui.tab == 0) ChatScreen(ui, vm) else Text("其他页面")
                    }
                    LearningMaterialsHost(ui, vm)
                }
            }
        }
    }
    private fun openDeck() {
        compose.onNodeWithContentDescription("学习资料").performClick()
        compose.onNodeWithText("雨课堂课件").performClick()
        compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.courses.isNotEmpty() }
        compose.onNodeWithText("生物化学").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.presentations.isNotEmpty() }
        compose.onNodeWithText("酶学课堂").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.lesson != null }
        compose.onNodeWithText("酶学课堂 · 课件 1").performScrollTo().performClick()
        compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.slides.size == 2 }
    }
    @Test fun draftsRemainWithTheirConversationAcrossNavigationAndNewChat() {
        val store = ViewModelStore()
        try {
            prepare(Saved(providers = listOf(model), conversations = listOf(Conversation("first", "第一门课")), currentChat = "first"), store)
            compose.onNode(hasSetTextAction()).performTextInput("第一个未发送问题")
            compose.runOnIdle { vm.tab(3) }
            compose.onNodeWithText("其他页面").assertIsDisplayed()
            compose.runOnIdle { vm.tab(0) }
            compose.onNode(hasSetTextAction()).assertTextEquals("第一个未发送问题")
            compose.onNodeWithContentDescription("新对话").performClick()
            compose.onNode(hasSetTextAction()).assertTextEquals("")
            compose.onNode(hasSetTextAction()).performTextInput("另一个问题")
            compose.onNodeWithContentDescription("会话").performClick()
            compose.onNodeWithText("第一门课").performClick()
            compose.onNode(hasSetTextAction()).assertTextEquals("第一个未发送问题")
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun noteExplanationNamesConversationWithoutManualQuestion() {
        val store = ViewModelStore()
        try {
            prepare(Saved(providers = listOf(model), notes = listOf(Note(title = "酶的活性中心", content = "结合基团和催化基团", source = "合成笔记", model = "手写"))), store)
            compose.onNodeWithContentDescription("学习资料").performClick()
            compose.onNodeWithText("已有笔记").performClick()
            compose.onNodeWithText("酶的活性中心").performClick()
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.chat().messages.size == 2 }
            assertEquals("酶的活性中心", vm.ui.value.data.chat().title)
            compose.onNodeWithContentDescription("会话").performClick()
            compose.onNode(hasText("酶的活性中心") and hasAnyAncestor(hasTestTag("阅读操作列表"))).assertIsDisplayed()
            advancedUiShot(compose.activity, "chat-learning-auto-title")
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun actualHierarchyReturnsToParentsAndWholeDeckIncludesSparseLastPage() {
        val store = ViewModelStore()
        try {
            prepare(Saved(providers = listOf(model), session = "synthetic-session", sessionVerified = true), store)
            openDeck()
            compose.onNodeWithText("整份课件 · 2 页/段").performScrollTo().assertIsDisplayed()
            advancedUiShot(compose.activity, "chat-learning-whole-deck")
            compose.onNodeWithContentDescription("返回本节课件").performClick()
            compose.onNodeWithText("下一批").assertDoesNotExist()
            compose.onNodeWithContentDescription("返回课程活动").performClick()
            compose.onNodeWithTag("学习资料列表").performScrollToNode(hasText("下一批"))
            compose.onNodeWithText("下一批").assertIsDisplayed()
            compose.onNodeWithText("酶学课堂").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.lesson != null }
            compose.onNodeWithText("酶学课堂 · 课件 1").performScrollTo().performClick()
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.slides.size == 2 }
            compose.onNodeWithText("指定范围").performScrollTo().performClick()
            compose.onNodeWithText("所选页面讲解并保存笔记").performScrollTo().assertIsNotEnabled()
            assertTrue(vm.ui.value.materialsOpen)
            compose.onNodeWithText("整份课件 · 2 页/段").performScrollTo().performClick()
            compose.onNodeWithText("整份讲解并保存笔记").performScrollTo().performClick()
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.notes.isNotEmpty() }
            assertFalse(vm.ui.value.materialsOpen)
            val note = vm.ui.value.data.notes.single()
            assertTrue(note.content.contains("## 第 2 页")); assertTrue(note.content.contains("## 第 100 页"))
            assertTrue(note.content.contains("已完成 2/2"))
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun largeFontMaterialControlsKeepCloseAndLastActionReachable() {
        val store = ViewModelStore()
        try {
            prepare(Saved(providers = listOf(model), session = "synthetic-session", sessionVerified = true), store, scale = 2f)
            openDeck()
            compose.onNodeWithText("指定范围").performScrollTo().performClick()
            compose.onNodeWithText("所选页面讲解并保存笔记").performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("关闭学习资料").assertIsDisplayed()
            advancedUiShot(compose.activity, "chat-learning-large-font")
            compose.onNodeWithContentDescription("关闭学习资料").performClick()
            assertFalse(vm.ui.value.materialsOpen)
        } finally { compose.runOnIdle { store.clear() } }
    }
}
