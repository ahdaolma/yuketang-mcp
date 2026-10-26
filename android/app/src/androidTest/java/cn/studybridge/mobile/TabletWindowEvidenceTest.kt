package cn.studybridge.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Actual MainActivity, including its window policy, after platform resizing. */
class TabletWindowEvidenceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).let { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { stream -> stream.readText() } }

    @Test fun actualTabletWindowKeepsOneClockAfterResizeSettles() {
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        val owner = compose.activity.application as StudyApplication
        val previous = owner.studyViewModel
        val app = noteTestApplication(owner)
        val note = Note(id = "tablet-native", title = "真实主界面平板检查", content = "保留笔记内容。", source = "fixture", model = "fixture", folderId = "course")
        Vault(app).save(Saved(notes = listOf(note), noteFolders = listOf(NoteFolder("course", "遗传学"))))
        val store = ViewModelStore(); lateinit var vm: StudyViewModel
        try {
            compose.runOnUiThread { vm = noteTestViewModel(app, store); owner.installClassFixture(vm) }
            compose.waitUntil(5000) { vm.ui.value.ready }
            compose.activityRule.scenario.recreate()
            compose.runOnIdle { vm.tab(2) }
            for ((width, height) in listOf(2560 to 1600, 1600 to 2560, 920 to 1600)) {
                shell("wm size ${width}x$height"); shell("wm density 320")
                compose.waitUntil(15000) {
                    val metrics = compose.activity.resources.displayMetrics
                    kotlin.math.abs(metrics.widthPixels - width) < 16 && kotlin.math.abs(metrics.density - 2f) < .01f && compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED
                }
                compose.onNodeWithTag("笔记列表").performScrollToNode(hasText(note.title))
                compose.onNodeWithText(note.title).assertIsDisplayed()
                stableShot("actual-tablet-$width-$height")
                android.os.SystemClock.sleep(1000) // Observe the later platform state, not a product delay.
                stableShot("actual-tablet-$width-$height-later")
                var hidden = false
                compose.runOnIdle { hidden = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()) == false }
                assertTrue("Actual main window must request hidden status bars after resize", hidden)
            }
        } finally {
            shell("wm size reset"); shell("wm density reset")
            compose.runOnUiThread { owner.installClassFixture(previous); store.clear() }
        }
    }

    private fun stableShot(name: String) {
        var previous: android.graphics.Bitmap? = null
        var identical = 0
        try {
            repeat(80) { sample ->
                compose.waitForIdle()
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                identical = if (previous?.sameAs(bitmap) == true) identical + 1 else 0
                android.util.Log.i("ActualTabletWindow", "$name sample=$sample identical=$identical")
                previous?.recycle(); previous = bitmap
                if (identical >= 3) { saveUiReviewBitmap(compose.activity, name, bitmap); return }
                android.os.SystemClock.sleep(80)
            }
            error("Actual tablet pixels did not settle for $name")
        } finally { previous?.recycle() }
    }
}
