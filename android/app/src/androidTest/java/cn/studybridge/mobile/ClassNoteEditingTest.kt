package cn.studybridge.mobile

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ClassNoteEditingTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun render(vm: StudyViewModel) {
        compose.setContent { val ui by vm.ui.collectAsState(); StudyTheme(false) { StudyFrame(ui.copy(tab = 2), vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { NotesScreen(ui.copy(tab = 2), vm) } } } }
    }
    @Test fun editsRemainEnabledWhileClassPageIsInFlightAndSavingKeepsBothChanges() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val page = LivePage("12", 2)
        val run = ClassRun(LiveLesson("42", "并行编辑", "7"), providerId = "class-fixture", pending = listOf(page), ended = true)
        val original = "# 并行编辑\n\n原来的定义。\n\n"
        val note = Note(run.noteId, "并行编辑", original, "课堂", "测试模型")
        val provider = Provider("class-fixture", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model", true)
        Vault(app).save(Saved(providers = listOf(provider), selectedProvider = provider.id, session = "unit-test-session-123", notes = listOf(note), classRun = run))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val rain = RainApi(OkHttpClient.Builder().addInterceptor { chain -> Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("{\"code\":0,\"data\":{\"title\":\"并行编辑\",\"slideList\":[{\"index\":2,\"text\":\"当前页原文\",\"visible\":true}]}}".toResponseBody()).build() }.build())
        val ai = fixtureAiApi("新生成的第二页讲解", beforeResponse = { requested.countDown(); check(release.await(60, TimeUnit.SECONDS)) })
        compose.runOnUiThread { vm = noteTestViewModel(app, store, rain, ai) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.runOnIdle {
            vm.noteReader.open(note)
            vm.resumeClass()
        }
        compose.waitUntil(5000) { requested.count == 0L || vm.ui.value.classUi.paused }
        assertEquals(vm.ui.value.classUi.status, 0L, requested.count)
        render(vm)
        compose.openNoteTool("编辑笔记")
        compose.onNodeWithText("Markdown 笔记").assertIsEnabled().performTextReplacement("# 并行编辑\n\n我修正后的定义。\n\n")
        compose.runOnIdle { release.countDown() }
        compose.waitUntil(10000) { vm.ui.value.data.classRun?.completed?.contains(page.key) == true }
        compose.onNodeWithText("保存修改").assertIsEnabled().performClick()
        compose.runOnIdle {
            val content = vm.ui.value.data.notes.single().content
            assertTrue(content.contains("我修正后的定义")); assertTrue(content.contains("新生成的第二页讲解")); assertFalse(content.contains("原来的定义"))
        }
        compose.waitUntil(5000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants)
                .filterIsInstance<NoteSelectableTextView>().any { it.text.contains("我修正后的定义") }
        }
        compose.waitForIdle()
        compose.onRoot().captureToImage()
        advancedUiShot(compose.activity, "sample-class-edit-saved")
        compose.runOnIdle { store.clear() }
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    @Test fun readingLongPressOpensAppToolbarAndRemarkCanBeSavedEditedAndRemoved() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note(title = "选词与备注", content = "# 选词与备注\n\nDNA复制需要模板，方向需要分清。", source = "测试课件", model = "测试")
        Vault(app).save(Saved(notes = listOf(note)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.runOnIdle { vm.noteReader.open(note) }
        render(vm)
        lateinit var textView: NoteSelectableTextView
        compose.waitUntil(5000) {
            android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<NoteSelectableTextView>().any { it.text.toString().startsWith("DNA复制") }
        }
        var x = 0f; var y = 0f
        compose.runOnIdle {
            textView = android.view.inspector.WindowInspector.getGlobalWindowViews().flatMap(::descendants).filterIsInstance<NoteSelectableTextView>().first { it.text.toString().startsWith("DNA复制") }
            assertFalse("Long-press selection must remain silent", textView.isHapticFeedbackEnabled)
            val location = IntArray(2); textView.getLocationOnScreen(location)
            x = location[0] + textView.totalPaddingLeft + textView.layout.getPrimaryHorizontal(1)
            y = location[1] + textView.totalPaddingTop + textView.layout.getLineBottom(0) / 2f
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val now = SystemClock.uptimeMillis()
        instrumentation.sendPointerSync(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0))
        SystemClock.sleep(700)
        instrumentation.sendPointerSync(MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
        compose.waitUntil(5000) { compose.onAllNodesWithTag("正文选词工具条").fetchSemanticsNodes().isNotEmpty() }
        advancedUiShot(compose.activity, "sample-note-selection-tools")
        compose.onNodeWithText("添加备注").performClick()
        compose.onNodeWithText("写下自己的理解或疑问").performTextInput("我需要再确认模板链的方向。")
        compose.onNodeWithText("保存备注").performClick()
        compose.waitUntil(5000) { vm.ui.value.data.notes.single().annotations.size == 1 }
        compose.openNoteTool("我的标记与备注")
        compose.onNodeWithText("我需要再确认模板链的方向。").assertExists().performClick()
        compose.onNodeWithText("我的备注").performTextReplacement("已经弄明白了。")
        compose.onNodeWithText("保存备注").performClick()
        compose.onNodeWithText("已经弄明白了。").assertExists()
        advancedUiShot(compose.activity, "sample-personal-note-remark")
        compose.onNodeWithText("已经弄明白了。").performClick()
        compose.onNodeWithText("删除这条标记").performClick()
        compose.runOnIdle { assertTrue(vm.ui.value.data.notes.single().annotations.isEmpty()); assertEquals(note.content, vm.ui.value.data.notes.single().content); store.clear() }
    }

    @Test fun searchJumpsToMatchingPageAndSuperFocusSettingSurvivesReload() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val note = Note(title = "查找测试", content = "## 第 1 页\n\n" + "首页原文，先理解基本定义。\n\n".repeat(40) + "## 第 2 页\n\n复制起点在这一页。\n\n" + "继续说明模板链与复制方向。\n\n".repeat(40), source = "测试", model = "测试")
        Vault(app).save(Saved(notes = listOf(note)))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnUiThread { vm = noteTestViewModel(app, store) }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.runOnIdle { vm.noteReader.open(note); vm.superFocusNotes(true) }
        render(vm)
        compose.openNoteTool("笔记内查找")
        compose.onNodeWithText("查找完整正文").performTextInput("复制起点")
        compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("第 2 段").performClick()
        compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "阅读分段 2 / 2 · 完整笔记已保留"))
        compose.onNodeWithTag("查找命中导航").assertIsDisplayed()
        advancedUiShot(compose.activity, "sample-note-search-result")
        compose.waitUntil(5000) { Vault(app).load().superFocusNotes }
        compose.runOnIdle { store.clear() }
    }
}

