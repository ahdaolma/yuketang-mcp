package cn.studybridge.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Disposable emulator data; the fake page rejects every action except reading.
class ClassRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    @Test fun continueRestoresLastPageIntoItsNotebookAndFinishesWithoutResending() {
        val page = LivePage("12", 3)
        val run = ClassRun(LiveLesson("42", "恢复测试课堂", "7"), providerId = WEB_PROVIDER_ID, pending = listOf(page), ended = true)
        val note = Note(run.noteId, "恢复测试课堂", "已保存内容\n", "课堂", "网页")
        val saved = Saved(webClassConsent = true, classRun = run, notes = listOf(note), learnerProfile = "基础薄弱", webPending = WebPending("RS-recovery", "第 3 页", pageKey = page.key, noteId = note.id))
        Vault(compose.activity).save(saved)
        lateinit var vm: StudyViewModel
        val actions = mutableListOf<String>()
        compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java] }
        compose.setContent { androidx.compose.material3.Text("课堂恢复测试") }
        compose.waitUntil(5000) { vm.ui.value.ready }
        compose.runOnIdle {
            vm.bindWeb(object : WebPagePort {
                override suspend fun call(action: String, payload: JSONObject): JSONObject {
                    actions.add(action); check(action == "answer") { "Recovery must never send" }
                    return JSONObject().put("matchedUser", true).put("finished", true).put("markdown", "恢复后的完整讲解")
                }
            })
            vm.resumeClass()
        }
        compose.waitUntil(10000) { vm.ui.value.data.webPending == null && !vm.ui.value.classUi.running }
        compose.runOnIdle {
            val result = vm.ui.value.data
            assertTrue(actions.isNotEmpty()); assertTrue(actions.all { it == "answer" })
            assertEquals(listOf(page.key), result.classRun!!.completed)
            assertTrue(result.classRun!!.pending.isEmpty()); assertEquals("基础薄弱", result.learnerProfile)
            assertEquals(1, result.notes.single().content.split("恢复后的完整讲解").size - 1)
            vm.resumeClass()
            assertEquals(result.notes, vm.ui.value.data.notes)
        }
    }
}
