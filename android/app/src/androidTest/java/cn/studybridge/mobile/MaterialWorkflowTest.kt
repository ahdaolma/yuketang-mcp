package cn.studybridge.mobile

import android.provider.DocumentsContract
import android.util.Base64
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

class MaterialWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun provider() = Provider("material-fixture", "测试模型", "https://fixture.example/v1", "fixture-key", "fixture-model", true)
    @Test fun nativeImageExplanationRetainsOriginalImageAndEncryptedNotebook() {
        val context = compose.activity
        val app = noteTestApplication(context.applicationContext)
        val p = provider(); Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id))
        val root = DocumentsContract.buildDocumentUri("cn.studybridge.mobile.downloadfixture", "root")
        val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "image/png", "原页-${newId()}.png"))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(Base64.decode(syntheticSlideImage().substringAfter(','), Base64.DEFAULT)) }
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = fixtureAiApi("完整的图片讲解")) }
            compose.setContent { Text("原页流程回归") }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.importMaterial(uri, "courseware") }
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.materials.size == 1 }
            val slide = vm.ui.value.slides.single()
            compose.runOnIdle { vm.explainPage(slide) }
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 1 }
            val note = Vault(app).load().notes.single()
            assertTrue(note.content.contains("完整的图片讲解"))
            val image = sourceImagePattern.find(note.content)!!.groupValues[2]
            assertTrue(vm.sourceImages.bytes(image).isNotEmpty())
            assertTrue(vm.sourceImages.export(note.content).contains("data:image/"))
        } finally { compose.runOnIdle { store.clear() }; DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
    @Test fun importedOriginalSurvivesFailedGenerationAndSuccessfulExplanationKeepsProfileAndFocusMode() {
        val context = compose.activity; val app = noteTestApplication(context.applicationContext)
        val original = "# 酶的活性中心\n\n结合基团与催化基团 **不能混淆**。\n"
        val p = provider(); Vault(app).save(Saved(providers = listOf(p), selectedProvider = p.id, learnerProfile = "基础薄弱，先讲术语", superFocusNotes = true))
        val root = DocumentsContract.buildDocumentUri("cn.studybridge.mobile.downloadfixture", "root")
        val uri = requireNotNull(DocumentsContract.createDocument(context.contentResolver, root, "text/markdown", "外部笔记-${newId()}.md"))
        requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(original.toByteArray()) }
        val accept = AtomicBoolean(false)
        val ai = fixtureAiApi("正确的酶机制讲解", inspect = { request ->
            val buffer = okio.Buffer(); request.body!!.writeTo(buffer); val sent = buffer.readUtf8()
            assertTrue(sent.contains("基础薄弱")); assertTrue(sent.contains("超重点模式")); assertTrue(sent.contains("结合基团")); assertNull(request.header("Cookie"))
            if (!accept.get()) throw IOException("模拟网络中断")
        })
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store, ai = ai) }
            compose.setContent { Text("导入资料回归") }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.importMaterial(uri, "notes") }
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 1 }
            val imported = vm.ui.value.data.notes.single()
            assertEquals(original, imported.content)
            val slide = vm.ui.value.slides.first()
            compose.runOnIdle { vm.explainPage(slide) }
            compose.waitUntil(10000) { !vm.ui.value.busy }
            assertEquals(listOf(imported), vm.ui.value.data.notes)
            accept.set(true)
            compose.runOnIdle { vm.explainPage(slide) }
            compose.waitUntil(10000) { !vm.ui.value.busy && vm.ui.value.data.notes.size == 2 }
            val loaded = Vault(app).load()
            assertEquals(original, loaded.notes.first { it.id == imported.id }.content)
            assertTrue(loaded.notes.any { it.content.contains("正确的酶机制讲解") }); assertTrue(loaded.superFocusNotes)
        } finally { compose.runOnIdle { store.clear() }; DocumentsContract.deleteDocument(context.contentResolver, uri) }
    }
}
