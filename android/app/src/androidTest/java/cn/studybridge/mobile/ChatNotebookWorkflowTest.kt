package cn.studybridge.mobile

import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Collections

class ChatNotebookWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun realChatRequestReadsSavedOriginalAndItsNextSavedEditWithRemarks() = exerciseEdits("chat")
    @Test fun responsesRequestReadsSavedOriginalAndItsNextSavedEditWithRemarks() = exerciseEdits("responses")

    private fun exerciseEdits(protocol: String) {
        val app = noteTestApplication(compose.activity.applicationContext)
        val p = Provider("chat-notes", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model", protocol = protocol)
        val old = Note("original-note", "生物化学", "活性中心原结论", "旧课堂", "fixture")
        val chat = Conversation("note-chat")
        Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id, conversations = listOf(chat), currentChat = chat.id, notes = listOf(old)))
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val requests = AiApi(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request(); val buffer = Buffer(); request.body!!.writeTo(buffer); bodies += buffer.readUtf8()
            val stream = if (protocol == "responses") {
                "data: ${JSONObject().put("type", "response.output_text.delta").put("delta", "依据笔记回答")}\n\n" +
                    "data: ${JSONObject().put("type", "response.completed")}\n\n"
            } else "data: {\"choices\":[{\"delta\":{\"content\":\"依据笔记回答\"}}]}\n\ndata: [DONE]\n\n"
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(stream.toResponseBody("text/event-stream".toMediaType())).build()
        }.build())
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = requests) }
            compose.setContent { Text("可交互原笔记上下文回归") }; compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { assertTrue(vm.send("活性中心的机制是什么？")) }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(1, bodies.size)
            assertTrue(bodies[0].contains(old.id)); assertTrue(bodies[0].contains(old.content))
            assertEquals(old.id, vm.ui.value.data.chat().messages.last().noteRefs.single().noteId)
            val edited = "活性中心最新保存的原结论"
            compose.runOnIdle {
                assertTrue(vm.openChatNote(old.id, edit = true))
                vm.noteReader.draft = edited
                assertTrue(vm.editNote(old.id, vm.noteReader.draft))
                val remark = NoteAnnotation(sourceStart = 0, sourceLength = edited.length, sourceHash = imageDigest(edited.toByteArray()), start = 0, end = 4, quote = "活性中心", kind = "comment", comment = "人工保存的核对备注")
                assertTrue(vm.saveNoteAnnotation(old.id, remark))
                vm.noteReader.editing = false
                vm.tab(0)
                vm.attachNote(vm.ui.value.data.notes.single())
                assertEquals(old.id, vm.ui.value.pending?.noteId)
                assertTrue(vm.send("继续解释活性中心"))
            }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(2, bodies.size)
            assertTrue(bodies[1].contains(old.id))
            assertTrue(bodies[1].contains(edited))
            assertTrue(bodies[1].contains("人工保存的核对备注"))
            assertTrue(bodies[1].contains("已保存版本已变化"))
            compose.runOnIdle {
                assertTrue(vm.openChatNote(old.id, edit = true))
                vm.noteReader.draft = "仍未保存的原笔记草稿"
                vm.tab(0)
                assertTrue(vm.openChatNote(old.id, edit = true))
                assertEquals("仍未保存的原笔记草稿", vm.noteReader.draft)
            }
        } finally { compose.runOnIdle { store.clear() } }
    }

    @Test fun rawTranscriptSavesWithoutRequestAndOrganizedResultReturnsActualSavedCard() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val p = Provider("chat-notes", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model")
        val chat = Conversation("raw-chat", "真实对话", listOf(Message("user", "原始 **问题**", source = "原课件"), Message("assistant", "原始回答"), Message("assistant", "")))
        Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id, conversations = listOf(chat), currentChat = chat.id))
        val bodies = Collections.synchronizedList(mutableListOf<String>())
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = fixtureAiApi("AI整理正文", inspect = { request -> val buffer = Buffer(); request.body!!.writeTo(buffer); bodies += buffer.readUtf8() })) }
            compose.setContent { Text("保存对话原文回归") }; compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.saveChatAsNote() }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(0, bodies.size)
            val raw = vm.ui.value.data.notes.single()
            assertTrue(raw.content.contains("原始 **问题**")); assertTrue(raw.content.contains("原始回答")); assertTrue(raw.content.contains("原课件"))
            assertEquals(raw.id, vm.ui.value.data.chat().messages.last().noteRefs.single().noteId)
            assertEquals("note_result", vm.ui.value.data.chat().messages.last().kind)
            compose.runOnIdle { vm.saveChatAsNote() }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertFalse(vm.ui.value.data.notes.first().content.contains("对话原文已保存"))
            assertEquals(0, bodies.size)
            compose.runOnIdle { vm.notes() }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(1, bodies.size)
            assertFalse(bodies.single().contains("对话原文已保存"))
            val result = vm.ui.value.data.chat().messages.last()
            assertEquals("note_result", result.kind)
            assertTrue(vm.ui.value.data.notes.any { it.id == result.noteRefs.single().noteId && it.content.contains("AI整理正文") })
            compose.runOnIdle {
                vm.newChat(); vm.attachNote(raw)
                assertEquals("新对话", vm.ui.value.data.chat().title)
                assertTrue(vm.ui.value.data.chat().messages.isEmpty())
                vm.notes()
            }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(2, bodies.size)
            assertTrue(bodies.last().contains(raw.id))
            assertTrue(bodies.last().contains("原始 **问题**"))
            assertEquals(raw.title, vm.ui.value.data.chat().title)
            assertEquals("note_result", vm.ui.value.data.chat().messages.last().kind)
        } finally { compose.runOnIdle { store.clear() } }
    }
}
