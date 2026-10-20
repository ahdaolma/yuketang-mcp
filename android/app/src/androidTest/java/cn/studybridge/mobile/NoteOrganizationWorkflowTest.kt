package cn.studybridge.mobile

import android.app.Application
import android.content.ContextWrapper
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NoteOrganizationWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private fun shot(name: String) {
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val resolver = compose.activity.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }; bitmap.recycle()
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
    }
    @Test fun longPressFoldersCancelAndConfirmedDeletionPersistWithoutTouchingOtherNotes() {
        val directory = File(compose.activity.cacheDir, "note-organization-${newId()}")
        val app = object : Application() { init { attachBaseContext(object : ContextWrapper(compose.activity.applicationContext) {
            override fun getNoBackupFilesDir() = File(directory, "private").apply { mkdirs() }
            override fun getFilesDir() = File(directory, "files").apply { mkdirs() }
            override fun getCacheDir() = File(directory, "cache").apply { mkdirs() }
        }) } }
        val first = Note("organize", "遗传学第一讲", "保留正文与图片 ![原页](study-image://fixture)", "课程课件", "fixture", pinned = true)
        val other = Note("untouched", "英语词汇", "另一份独立原文。", "手写笔记", "手写")
        val vault = Vault(app)
        vault.save(Saved(notes = listOf(first, other), learningMode = "native"))
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        lateinit var vm: StudyViewModel
        fun hideKeyboard() {
            // A category text field lives in a Dialog window, so the Activity's
            // insets controller cannot close its keyboard. Use the system Back
            // action, exactly as a user does, only while the IME is visible.
            compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            if (ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
                androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").let { descriptor -> android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() } }
            }
            compose.waitUntil(5000) { ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) != true }
        }
        compose.runOnIdle { vm = ViewModelProvider(owner, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>) = StudyViewModel(app) as T
        })[StudyViewModel::class.java] }
        try {
            compose.setContent {
                val ui by vm.ui.collectAsState()
                StudyTheme(false) { StudyFrame(ui, vm::tab, {}, vm::stop) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) { NotesScreen(ui, vm) }
                } }
            }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.runOnIdle { vm.tab(2) }
            compose.onNodeWithContentDescription("管理笔记分类").performClick()
            compose.onNodeWithText("新建分类").performClick()
            compose.onNodeWithText("分类名称").performTextInput("遗传学")
            hideKeyboard()
            compose.onNodeWithText("保存分类").performClick()
            compose.onNodeWithText("关闭").performClick()
            compose.onNodeWithTag("笔记卡片 organize").performTouchInput { longClick() }
            listOf("编辑笔记", "重命名笔记", "取消置顶", "移动到分类", "删除笔记…").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
            shot("sample-note-longpress")
            compose.onNodeWithText("移动到分类").performClick()
            compose.onNodeWithText("遗传学").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("遗传学 1").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("遗传学 1").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记卡片 untouched").fetchSemanticsNodes().isEmpty() }
            assertEquals(first.copy(folderId = vm.ui.value.data.noteFolders.single().id), vm.ui.value.data.notes.single { it.id == first.id })
            compose.waitUntil(5000) { vault.load().noteFolders.singleOrNull()?.name == "遗传学" && vault.load().notes.single { it.id == first.id }.folderId.isNotEmpty() }
            shot("sample-note-categories")
            compose.onNodeWithText("搜索笔记").performTextInput("保留正文")
            compose.waitUntil(5000) { compose.onAllNodesWithText("找到 1 份").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("清除笔记搜索").performClick()
            hideKeyboard()
            compose.onNodeWithContentDescription("管理笔记分类").performClick()
            compose.onNodeWithContentDescription("重命名分类 遗传学").performClick()
            compose.onNodeWithText("分类名称").performTextReplacement("生物学")
            hideKeyboard()
            compose.onNodeWithText("保存分类").performClick()
            compose.onNodeWithContentDescription("删除分类 生物学").performClick()
            compose.onNodeWithText("保留分类").performClick()
            assertEquals(2, vm.ui.value.data.notes.size)
            assertEquals("生物学", vm.ui.value.data.noteFolders.single().name)
            compose.onNodeWithContentDescription("删除分类 生物学").performClick()
            compose.onNodeWithText("只删除分类").performClick()
            compose.onNodeWithText("关闭").performClick()
            compose.waitUntil(5000) { vm.ui.value.data.noteFolders.isEmpty() && compose.onAllNodesWithTag("笔记卡片 organize").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(first, other), vm.ui.value.data.notes)
            compose.onNodeWithTag("笔记卡片 organize").performTouchInput { longClick() }
            compose.onNodeWithText("删除笔记…").performClick()
            compose.onNodeWithText("删除这份笔记？").assertIsDisplayed()
            shot("sample-note-delete-confirmation")
            compose.onNodeWithText("保留笔记").performClick()
            assertEquals(listOf(first, other), vm.ui.value.data.notes)
            compose.onNodeWithContentDescription("笔记操作 遗传学第一讲").performClick()
            compose.onNodeWithText("删除笔记…").performClick()
            compose.onNodeWithText("删除笔记").performClick()
            compose.waitUntil(5000) { vault.load().notes == listOf(other) && vault.load().noteFolders.isEmpty() }
            compose.waitUntil(5000) { compose.onAllNodesWithTag("笔记卡片 organize").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("笔记卡片 untouched").assertIsDisplayed()
        } finally { compose.runOnIdle { store.clear() } }
    }
}
