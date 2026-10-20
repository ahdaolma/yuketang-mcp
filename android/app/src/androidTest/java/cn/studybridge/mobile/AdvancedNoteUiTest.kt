package cn.studybridge.mobile

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

fun noteTestApplication(context: Context): Application {
    val root = File(context.cacheDir, "advanced-notes-${newId()}")
    return object : Application() { init { attachBaseContext(object : ContextWrapper(context) {
        override fun getNoBackupFilesDir() = File(root, "private").apply { mkdirs() }
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
    }) } }
}
fun noteTestViewModel(app: Application, store: ViewModelStore): StudyViewModel = ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store }, object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = StudyViewModel(app) as T
})[StudyViewModel::class.java]
fun advancedUiShot(context: Context, name: String) {
    val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    val resolver = context.contentResolver
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
    }))
    requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
}

class AdvancedNoteUiTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun render(current: MutableState<StudyViewModel>) {
        compose.setContent {
            val vm = current.value
            val ui by vm.ui.collectAsState()
            StudyTheme(false) { StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
            } }
        }
    }
    @Test fun encryptedAutoDraftRestoresAfterViewModelDestructionAndExplicitDiscardKeepsOriginal() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val images = SourceImages(app)
        val originalImage = images.save(syntheticSlideImage())
        val note = Note("draft", "自动保存测试", "原文不能被草稿覆盖。\n\n" + sourceImageMarkdown(originalImage, "原图"), "fixture", "手写")
        val vault = Vault(app); vault.save(Saved(notes = listOf(note), learningMode = "native"))
        val stores = mutableListOf(ViewModelStore())
        lateinit var first: StudyViewModel
        compose.runOnIdle { first = noteTestViewModel(app, stores.last()) }
        val current = mutableStateOf(first); render(current)
        try {
            compose.waitUntil(5000) { first.ui.value.ready }
            val addedImage = images.rotated(originalImage)
            val draft = "==新的关键结论==\n\n" + sourceImageMarkdown(addedImage, "仅草稿引用的图片")
            compose.runOnIdle { first.tab(2); first.noteReader.open(note); first.noteReader.editing = true; first.noteReader.draft = draft }
            compose.waitUntil(5000) { !first.ui.value.noteDraftSaving && Vault(app, "note-drafts-v1.enc").load().noteDrafts.singleOrNull()?.content == draft }
            assertEquals(note, vault.load().notes.single())
            advancedUiShot(compose.activity, "sample-note-autosave")
            lateinit var second: StudyViewModel
            compose.runOnIdle { stores.first().clear(); stores += ViewModelStore(); second = noteTestViewModel(app, stores.last()); current.value = second }
            compose.waitUntil(5000) { second.ui.value.ready && second.noteReader.editing }
            assertEquals(note.id, second.noteReader.id)
            assertEquals(draft, second.noteReader.draft)
            assertTrue(images.bytes(addedImage).isNotEmpty())
            assertEquals(note, second.ui.value.data.notes.single())
            compose.runOnIdle { second.discardNoteDraft(note.id); second.noteReader.close() }
            compose.waitUntil(5000) { Vault(app, "note-drafts-v1.enc").load().noteDrafts.isEmpty() && !second.ui.value.noteDraftSaving }
            lateinit var third: StudyViewModel
            compose.runOnIdle { stores[1].clear(); stores += ViewModelStore(); third = noteTestViewModel(app, stores.last()); current.value = third }
            compose.waitUntil(5000) { third.ui.value.ready }
            assertNull(third.noteReader.id)
            assertEquals(note, third.ui.value.data.notes.single())
        } finally { compose.runOnIdle { stores.forEach { it.clear() } } }
    }
    @Test fun pageDeletionAndImageRotationRemovalUndoKeepOtherPagesAndSharedOriginals() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val images = SourceImages(app); val image = images.save(syntheticSlideImage()); val originalBytes = images.bytes(image)
        val firstPage = "# 图文笔记\n\n## 第 1 页\n\n" + sourceImageMarkdown(image, "第一张原图") + "第一页的解释。\n\n"
        val note = Note("pages", "图文笔记", firstPage + "## 第 2 页\n\n删除这一页。\n\n" + sourceImageMarkdown(image, "第二张原图"), "fixture", "手写")
        val other = Note("shared", "另一份笔记", sourceImageMarkdown(image, "共享原图"), "fixture", "手写")
        val vault = Vault(app); vault.save(Saved(notes = listOf(note, other), learningMode = "native"))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnIdle { vm = noteTestViewModel(app, store) }; render(mutableStateOf(vm))
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2); vm.noteReader.open(note) }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("笔记选项").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("笔记选项").performClick()
            compose.onNodeWithText("按页删除内容…").performClick()
            compose.onNodeWithText("第 2 页").performClick()
            advancedUiShot(compose.activity, "sample-note-delete-pages")
            compose.onNodeWithText("删除所选 1 页…").performClick()
            compose.onNodeWithText("返回选择").performClick()
            assertEquals(note, vm.ui.value.data.notes.single { it.id == note.id })
            compose.onNodeWithText("删除所选 1 页…").performClick()
            compose.onNodeWithText("确认删除所选页").performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single { it.id == note.id }.content == firstPage }
            compose.onNodeWithContentDescription("笔记选项").performClick(); compose.onNodeWithText("编辑笔记").performClick()
            compose.onNodeWithText("图片 1 ▾").performScrollTo().performClick()
            compose.onNodeWithText("旋转 90°").performScrollTo().performClick()
            compose.waitUntil(5000) { sourceImagePattern.find(vm.noteReader.draft)?.groupValues?.get(2) != image }
            val rotated = sourceImagePattern.find(vm.noteReader.draft)!!.groupValues[2]
            assertTrue(images.bytes(rotated).isNotEmpty()); assertArrayEquals(originalBytes, images.bytes(image))
            compose.onNodeWithText("移除图片").performScrollTo().performClick(); compose.onNodeWithText("确认移除图片").performClick()
            assertFalse(sourceImagePattern.containsMatchIn(vm.noteReader.draft))
            compose.onNodeWithText("撤销图片修改").performScrollTo().performClick()
            assertEquals(rotated, sourceImagePattern.find(vm.noteReader.draft)!!.groupValues[2])
            advancedUiShot(compose.activity, "sample-note-image-editor")
            compose.onNodeWithText("保存修改").performClick()
            compose.waitUntil(5000) { vault.load().notes.single { it.id == note.id }.content.contains(rotated) }
            assertEquals(other, vault.load().notes.single { it.id == other.id })
        } finally { compose.runOnIdle { store.clear() } }
    }
    @Test fun readingPageAndScrollRestoreFromEncryptedMemoryWhenTheViewModelIsRecreated() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val content = (1..3).joinToString("\n") { "## 第 $it 页\n\n" + "这一页的机制和关键结论需要结合图示理解。\n\n".repeat(100) }
        val note = Note("memory", "记忆阅读测试", content, "fixture", "手写")
        Vault(app).save(Saved(notes = listOf(note), learningMode = "native"))
        val stores = mutableListOf(ViewModelStore()); lateinit var first: StudyViewModel
        compose.runOnIdle { first = noteTestViewModel(app, stores.last()) }; val current = mutableStateOf(first); render(current)
        try {
            compose.waitUntil(5000) { first.ui.value.ready }
            compose.runOnIdle { first.tab(2); first.noteReader.open(note) }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("目录跳转").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("目录跳转").performClick(); compose.onNodeWithText("2. 第 2 页").performClick()
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeUp(durationMillis = 500) }
            compose.waitUntil(5000) { first.readingMemory.position(note.id)?.let { it.index == 1 && it.scroll > 100 } == true }
            compose.runOnIdle { first.noteReader.close() }
            compose.waitUntil(5000) { Vault(app, "note-reading-v1.enc").load().noteReadingPositions.singleOrNull()?.let { it.index == 1 && it.scroll > 100 } == true }
            lateinit var second: StudyViewModel
            compose.runOnIdle { stores.first().clear(); stores += ViewModelStore(); second = noteTestViewModel(app, stores.last()); current.value = second }
            compose.waitUntil(5000) { second.ui.value.ready }
            compose.runOnIdle { second.tab(2); second.noteReader.open(note) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) { compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value() > 100f }
            compose.onNodeWithContentDescription("目录跳转").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 2 / 3 · 完整笔记已保留"))
            advancedUiShot(compose.activity, "sample-note-memory-restored")
        } finally { compose.runOnIdle { stores.forEach { it.clear() } } }
    }
}
