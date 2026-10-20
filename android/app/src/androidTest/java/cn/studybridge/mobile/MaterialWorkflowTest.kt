package cn.studybridge.mobile

import android.provider.DocumentsContract
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

// Exercise the same import, generation and encrypted save path as the app using
// a local document provider and synthetic web response, never a real account.
class MaterialWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()

    @Test fun manuallySendingAttachedPageThenSavingWebAnswerRetainsOriginalImage() {
        val context = compose.activity; val vault = Vault(context); val previous = vault.load()
        val root = DocumentsContract.buildDocumentUri("cn.studybridge.mobile.downloadfixture", "root")
        val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "image/png", "原页图片.png"))
        val image = syntheticSlideImage()
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(android.util.Base64.decode(image.substringAfter(','), android.util.Base64.DEFAULT)) }
        lateinit var vm: StudyViewModel
        var initialized = false; var sent = 0; var filled = ""
        try {
            vault.save(Saved())
            compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java]; initialized = true }
            compose.setContent { Text("图文笔记流程回归") }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.importMaterial(uri, "courseware") }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.slides.size == 1 }
            compose.runOnIdle { vm.attach(vm.ui.value.slides.single()) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.pending != null }
            val source = requireNotNull(vm.ui.value.pending)
            assertTrue(source.imageRef.isNotBlank())
            compose.runOnIdle {
                vm.bindWeb(object : WebPagePort {
                    override suspend fun call(action: String, payload: JSONObject): JSONObject = when (action) {
                        "prepare" -> { filled = payload.getString("text"); assertEquals(source.image, payload.getString("image")); JSONObject().put("ok", true) }
                        "send" -> { sent++; error("Manual fill must never send") }
                        "answer" -> {
                            val pending = requireNotNull(vault.load().webPending)
                            assertTrue(filled.contains(pending.marker)); assertEquals(pending.marker, payload.getString("marker"))
                            JSONObject().put("finished", true).put("markdown", "图片中的 DNA 与 RNA 对照讲解")
                        }
                        else -> error("Unexpected action: $action")
                    }
                })
                vm.fillWebAttachment()
            }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.data.webPending != null }
            assertEquals(0, sent); assertNull(vm.ui.value.pending)
            assertEquals(source.imageRef, vault.load().webPending!!.imageRef)
            compose.runOnIdle { vm.saveWebAnswer() }
            compose.waitUntil(6000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 1 }
            val note = vault.load().notes.single()
            assertNull(vault.load().webPending)
            assertEquals(source.imageRef, sourceImagePattern.find(note.content)!!.groupValues[2])
            assertEquals(source.image, SourceImages(context).data(source.imageRef))
            assertTrue(note.content.contains("DNA 与 RNA")); assertEquals(0, sent)
        } finally {
            if (initialized) compose.runOnIdle { vm.stop(); vm.bindWeb(null) }
            runCatching { vault.load().materials.filter { m -> previous.materials.none { it.id == m.id } }.forEach { MaterialStore(context).remove(it.id) } }
            vault.save(previous); DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    @Test fun importedNotesSurviveRejectedDraftThenExplainAndReloadWithoutOverwritingOriginal() {
        val context = compose.activity
        val title = "酶与催化 Enzymes 完整外部笔记.md"
        val original = "# 酶的活性中心\n\n结合基团与催化基团 **不能混淆**。\n"
        val vault = Vault(context)
        val previous = vault.load()
        val root = DocumentsContract.buildDocumentUri("cn.studybridge.mobile.downloadfixture", "root")
        val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "text/markdown", title))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(original.toByteArray()) }
        lateinit var vm: StudyViewModel
        var initialized = false
        try {
            vault.save(Saved(webClassConsent = true, learnerProfile = "基础薄弱，先讲术语"))
            compose.activityRule.scenario.onActivity { vm = ViewModelProvider(it)[StudyViewModel::class.java]; initialized = true }
            compose.setContent { Text("资料流程回归") }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.importMaterial(uri, "notes") }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.data.materials.size == 1 }
            val imported = vm.ui.value.data.notes.single()
            val material = vm.ui.value.data.materials.single()
            assertEquals(original, imported.content)
            assertEquals(title, imported.title)
            assertEquals(material.id, imported.materialId)
            assertEquals(imported.id, material.noteId)
            assertEquals(original, MaterialStore(context).read(material).noteText)

            var acceptDraft = false
            var sends = 0
            val prompts = mutableListOf<String>()
            compose.runOnIdle {
                vm.bindWeb(object : WebPagePort {
                    override suspend fun call(action: String, payload: JSONObject): JSONObject = when (action) {
                        "prepare" -> { prompts.add(payload.getString("text")); JSONObject().put("ok", acceptDraft) }
                        "ready" -> JSONObject().put("ready", true)
                        "send" -> {
                            // The pending marker must be durable before sending once.
                            assertEquals(payload.getString("marker"), vault.load().webPending!!.marker)
                            sends++
                            JSONObject().put("sent", true)
                        }
                        "answer" -> JSONObject().put("matchedUser", true).put("finished", true).put("markdown", "### 概念与原理讲解\n\nAI 讲解：结合基团参与底物结合，催化基团参与反应。")
                        else -> error("Unexpected action: $action")
                    }
                })
                vm.explainPage(vm.ui.value.slides.single())
            }
            compose.waitUntil(5000) { !vm.ui.value.busy }
            assertEquals(0, sends)
            assertNull(vm.ui.value.data.webPending)
            assertEquals(listOf(imported), vm.ui.value.data.notes)
            assertEquals(original, vault.load().notes.single().content)

            compose.runOnIdle { acceptDraft = true; vm.explainPage(vm.ui.value.slides.single()) }
            compose.waitUntil(12000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 2 }
            assertEquals(1, sends)
            assertNull(vm.ui.value.data.webPending)
            assertTrue(prompts.all { it.contains(NOTE_FORMAT) && it.contains(APP_CONTEXT) && it.contains("基础薄弱，先讲术语") && it.contains(original.trim()) && it.contains(title) })
            val loaded = vault.load()
            assertNull(loaded.webPending)
            assertEquals(original, loaded.notes.single { it.id == imported.id }.content)
            assertTrue(loaded.notes.single { it.id != imported.id }.content.contains("AI 讲解"))
            assertEquals(listOf(material), loaded.materials)
            compose.runOnIdle { vm.explainNote(imported) }
            compose.waitUntil(5000) { !vm.ui.value.busy && vm.ui.value.materialsOpen }
            assertEquals(title, vm.ui.value.slideTitle)
            assertEquals(material.id, vm.ui.value.localDocument!!.material.id)
        } finally {
            if (initialized) compose.runOnIdle { vm.stop(); vm.bindWeb(null) }
            runCatching { vault.load().materials.filter { m -> previous.materials.none { it.id == m.id } }.forEach { MaterialStore(context).remove(it.id) } }
            vault.save(previous)
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }
}
