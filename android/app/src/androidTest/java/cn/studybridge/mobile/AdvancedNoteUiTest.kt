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
import androidx.compose.ui.test.junit4.ComposeContentTestRule
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
fun noteTestViewModel(app: Application, store: ViewModelStore, rain: RainApi = RainApi(), ai: AiApi = AiApi()): StudyViewModel = ViewModelProvider(object : ViewModelStoreOwner { override val viewModelStore = store }, object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = StudyViewModel(app, rain, ai) as T
})[StudyViewModel::class.java]
fun advancedUiShot(context: Context, name: String) {
    val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
    try { saveUiReviewBitmap(context, name, bitmap) } finally { bitmap.recycle() }
}
fun saveUiReviewBitmap(context: Context, name: String, bitmap: Bitmap) {
    if (android.os.Build.VERSION.SDK_INT < 29) {
        val directory = File(context.getExternalFilesDir(null), "StudyUiReview").apply { check(isDirectory || mkdirs()) }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return
    }
    val resolver = context.contentResolver
    val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
    }))
    requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
}

fun focusedAppStatusBarsHidden(): Boolean {
    val focused = android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isAttachedToWindow && it.hasWindowFocus() }
    return focused.isNotEmpty() && focused.all { androidx.core.view.ViewCompat.getRootWindowInsets(it)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == false }
}

/** Open notebook actions through the same floating tool flow used by readers. */
fun ComposeContentTestRule.ensureNoteTools() {
    if (onAllNodesWithContentDescription("关闭阅读工具").fetchSemanticsNodes().isEmpty()) {
        waitUntil(5000) { onAllNodesWithContentDescription("悬浮阅读工具").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithContentDescription("悬浮阅读工具").assertIsDisplayed().performClick()
    }
    onNodeWithText("阅读工具").assertIsDisplayed()
    onNodeWithTag("阅读操作列表").assertExists()
}

fun ComposeContentTestRule.openNoteTool(label: String) {
    ensureNoteTools()
    onNode(hasContentDescription(label) or hasText(label)).performScrollTo().assertIsDisplayed().performClick()
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
        val vault = Vault(app); vault.save(Saved(notes = listOf(note), ))
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
            compose.waitForIdle()
            compose.onNodeWithText("草稿已自动保存").assertIsDisplayed()
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
            compose.runOnIdle { third.createManualNote(); third.noteReader.draft = "刚新建就输入并自动保存的内容。" }
            val newId = requireNotNull(third.noteReader.id)
            compose.waitUntil(5000) { !third.ui.value.noteDraftSaving && vault.load().notes.any { it.id == newId } && Vault(app, "note-drafts-v1.enc").load().noteDrafts.any { it.noteId == newId } }
            lateinit var fourth: StudyViewModel
            compose.runOnIdle { stores[2].clear(); stores += ViewModelStore(); fourth = noteTestViewModel(app, stores.last()); current.value = fourth }
            compose.waitUntil(5000) { fourth.ui.value.ready && fourth.noteReader.id == newId }
            assertEquals("刚新建就输入并自动保存的内容。", fourth.noteReader.draft)
            assertEquals(note, fourth.ui.value.data.notes.single { it.id == note.id })
        } finally { compose.runOnIdle { stores.forEach { it.clear() } } }
    }
    @Test fun pageDeletionAndImageRotationRemovalUndoKeepOtherPagesAndSharedOriginals() {
        val app = noteTestApplication(compose.activity.applicationContext)
        val images = SourceImages(app); val image = images.save(syntheticSlideImage()); val originalBytes = images.bytes(image)
        val firstPage = "# 图文笔记\n\n## 第 1 页\n\n" + sourceImageMarkdown(image, "第一张原图") + "第一页的解释。\n\n"
        val note = Note("pages", "图文笔记", firstPage + "## 第 2 页\n\n删除这一页。\n\n" + sourceImageMarkdown(image, "第二张原图"), "fixture", "手写")
        val other = Note("shared", "另一份笔记", sourceImageMarkdown(image, "共享原图"), "fixture", "手写")
        val vault = Vault(app); vault.save(Saved(notes = listOf(note, other), ))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        compose.runOnIdle { vm = noteTestViewModel(app, store) }; render(mutableStateOf(vm))
        try {
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2); vm.noteReader.open(note) }
            compose.openNoteTool("按页删除内容…")
            compose.onNodeWithText("第 2 页").performClick()
            compose.waitForIdle()
            compose.onNodeWithText("删除所选 1 页…").assertIsEnabled()
            advancedUiShot(compose.activity, "sample-note-delete-pages")
            compose.onNodeWithText("删除所选 1 页…").performClick()
            compose.onNodeWithText("返回选择").performClick()
            assertEquals(note, vm.ui.value.data.notes.single { it.id == note.id })
            compose.onNodeWithText("删除所选 1 页…").performClick()
            compose.onNodeWithText("确认删除所选页").performClick()
            compose.waitUntil(5000) { vm.ui.value.data.notes.single { it.id == note.id }.content == firstPage }
            compose.openNoteTool("编辑笔记")
            compose.onNodeWithText("图片 1 ▾").performScrollTo().performClick()
            compose.onNodeWithText("旋转 90°").performScrollTo().performClick()
            compose.waitUntil(5000) { sourceImagePattern.find(vm.noteReader.draft)?.groupValues?.get(2) != image }
            val rotated = sourceImagePattern.find(vm.noteReader.draft)!!.groupValues[2]
            assertTrue(images.bytes(rotated).isNotEmpty()); assertArrayEquals(originalBytes, images.bytes(image))
            compose.onNodeWithText("移除图片").performScrollTo().performClick(); compose.onNodeWithText("确认移除图片").performClick()
            assertFalse(sourceImagePattern.containsMatchIn(vm.noteReader.draft))
            compose.onNodeWithText("撤销图片修改").performScrollTo().performClick()
            assertEquals(rotated, sourceImagePattern.find(vm.noteReader.draft)!!.groupValues[2])
            compose.waitForIdle()
            compose.onNodeWithText("图片 1 ▴").assertIsDisplayed()
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("图片 1 · 第一张原图 缩略图").fetchSemanticsNodes().isNotEmpty() }
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
        Vault(app).save(Saved(notes = listOf(note), ))
        val stores = mutableListOf(ViewModelStore()); lateinit var first: StudyViewModel
        compose.runOnIdle { first = noteTestViewModel(app, stores.last()) }; val current = mutableStateOf(first); render(current)
        try {
            compose.waitUntil(5000) { first.ui.value.ready }
            compose.runOnIdle { first.tab(2); first.noteReader.open(note) }
            compose.openNoteTool("目录跳转"); compose.onNodeWithText("2. 第 2 页").performClick()
            compose.waitUntil(5000) { compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f }
            compose.onNodeWithTag("笔记正文").performTouchInput { swipeUp(durationMillis = 500) }
            compose.waitUntil(5000) { first.readingMemory.position(note.id)?.let { it.index == 1 && it.scroll > 100 } == true }
            val expectedScrollAxis = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            android.util.Log.i("ReaderRegression", "before close axis=$expectedScrollAxis position=" + first.readingMemory.position(note.id))
            compose.runOnIdle { first.noteReader.close() }
            compose.waitUntil(5000) { Vault(app, "note-reading-v1.enc").load().noteReadingPositions.singleOrNull()?.let { it.index == 1 && it.scroll > 100 } == true }
            lateinit var second: StudyViewModel
            compose.runOnIdle { stores.first().clear(); stores += ViewModelStore(); second = noteTestViewModel(app, stores.last()); current.value = second }
            compose.waitUntil(5000) { second.ui.value.ready }
            compose.runOnIdle { second.tab(2); second.noteReader.open(note) }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记正文").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5000) {
                val restoredScrollAxis = compose.onNodeWithTag("笔记正文").fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
                android.util.Log.i("ReaderRegression", "restored axis=$restoredScrollAxis position=" + second.readingMemory.position(note.id))
                restoredScrollAxis > 0f && kotlin.math.abs(restoredScrollAxis - expectedScrollAxis) < .0001f
            }
            compose.onNodeWithContentDescription("当前阅读页码").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "阅读分段 2 / 3 · 完整笔记已保留"))
            compose.waitForIdle()
            advancedUiShot(compose.activity, "sample-note-memory-restored")
        } finally { compose.runOnIdle { stores.forEach { it.clear() } } }
    }
}
