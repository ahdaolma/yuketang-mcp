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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NoteToolsWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private class IsolatedApplication(base: Context, directory: File) : Application() {
        init { attachBaseContext(object : ContextWrapper(base) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }) }
    }
    @Test fun writeFormatRenamePinSearchAndEncryptedReloadPreserveTheCompleteNote() {
        val app = IsolatedApplication(compose.activity.applicationContext, File(compose.activity.cacheDir, "note-tools-${newId()}"))
        val vault = Vault(app)
        val old = Note("existing", "旧的课程笔记", "保留的原文和图片 ![原页](study-image://fixture)", "第一周", "fixture")
        vault.save(Saved(notes = listOf(old), ))
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        lateinit var vm: StudyViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = StudyViewModel(app) as T
            })[StudyViewModel::class.java]
        }
        try {
            var clearFocus: () -> Unit = {}
            compose.setContent {
                val ui by vm.ui.collectAsState()
                val focus = LocalFocusManager.current
                SideEffect { clearFocus = { focus.clearFocus() } }
                StudyTheme(false) { StudyFrame(ui, vm::tab, { vm.noteReader.closeRequested = true }, vm::stop, noteOpen = vm.noteReader.id != null) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
                } }
            }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithContentDescription("新建笔记").performClick()
            compose.onNodeWithText("Markdown 笔记").performTextInput("正文独有关键词：复制起点。")
            compose.runOnIdle { clearFocus(); androidx.core.view.WindowInsetsControllerCompat(compose.activity.window, compose.activity.window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime()) }
            compose.waitUntil(5000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) != true }
            compose.onNodeWithText("待办").performScrollTo().performClick()
            assertEquals("- [ ] 正文独有关键词：复制起点。", vm.noteReader.draft)
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            val resolver = compose.activity.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "sample-note-tools.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            compose.onNodeWithText("保存修改").performClick()
            compose.onNodeWithContentDescription("笔记选项").performClick()
            compose.onNodeWithText("重命名笔记").performClick()
            compose.onNodeWithText("笔记标题").performTextReplacement("我的遗传学复习")
            compose.onNodeWithText("保存标题").performClick()
            compose.onNodeWithContentDescription("笔记选项").performClick()
            compose.onNodeWithText("置顶笔记").performClick()
            compose.onNodeWithContentDescription("返回笔记列表").performClick()
            compose.onNodeWithText("搜索笔记").performTextInput("复制起点")
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 份").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("我的遗传学复习").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("已置顶").assertExists()
            compose.onNodeWithText("搜索笔记").performTextReplacement("不存在的内容")
            compose.waitUntil(5000) { compose.onAllNodesWithText("没有找到相关笔记").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("清除笔记搜索").performClick()
            compose.waitUntil(5000) { vault.load().notes.any { it.title == "我的遗传学复习" && it.pinned && it.content.startsWith("- [ ] ") } }
            val saved = vault.load().notes
            assertEquals(old, saved.single { it.id == old.id })
            assertEquals("- [ ] 正文独有关键词：复制起点。", saved.single { it.id != old.id }.content)
            assertEquals("我的遗传学复习", findNotes(saved, "").first().title)
        } finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { store.clear() } }
    }
}
