package cn.studybridge.mobile

import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.json.JSONTokener

// Check window pixels: WebView accessibility/DOM text can exist behind a white surface.
// The synthetic colors never occur in the app theme; no live account is needed.
class WebDisplayTest {
    @get:Rule val compose = createAndroidComposeRule<WebFixtureActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun painted(bitmap: Bitmap): Boolean {
        var blue = 0; var yellow = 0
        for (y in 0 until bitmap.height step 6) for (x in 0 until bitmap.width step 6) {
            val color = bitmap.getPixel(x, y) and 0xffffff
            if (color == 0x164e63) blue++
            if (color == 0xeab308) yellow++
        }
        return blue > 100 && yellow > 30
    }
    private fun waitForPaint() {
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10000) {
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                try { painted(bitmap) } finally { bitmap.recycle() }
            } ?: false
        }
    }
    @Test fun actualWindowPaintsRetainedPageAfterHiddenLoadTabChangesAndResize() {
        val state = mutableStateOf(UiState(ready = true, tab = 3))
        val dark = mutableStateOf(false)
        val narrow = mutableStateOf(false)
        val revision = mutableIntStateOf(0)
        lateinit var page: ChatGptWebSession
        lateinit var vm: StudyViewModel
        compose.activityRule.scenario.onActivity {
            vm = ViewModelProvider(it)[StudyViewModel::class.java]
            page = ChatGptWebSession(it, { revision.intValue++ }, {})
        }
        compose.setContent {
            StudyTheme(dark.value) {
                Box(if (narrow.value) Modifier.fillMaxHeight().width(320.dp) else Modifier.fillMaxSize()) {
                    StudyFrame(state.value, onTab = { state.value = state.value.copy(tab = it) }, onBack = { state.value = state.value.copy(tab = 0) }, onStop = {}) { padding ->
                        Box(Modifier.fillMaxSize().padding(padding)) { StudyPageContent(state.value, vm, page, revision.intValue) }
                    }
                }
            }
        }
        try {
            compose.runOnIdle {
                page.view.loadDataWithBaseURL(CHATGPT_WEB, """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>html,body{margin:0;min-height:100%;background:#164e63;color:white}main{padding:24px}.mark{height:100px;background:#eab308}textarea{display:block;width:90%;height:80px;margin-top:20px}</style></head><body><main><h1>网页显示测试</h1><div class="mark"></div><textarea id="draft">保留我的课件草稿</textarea></main></body></html>""", "text/html", "UTF-8", CHATGPT_WEB)
            }
            compose.waitUntil(10000) { page.progress == 100 }
            compose.runOnIdle { state.value = state.value.copy(tab = 0) }
            waitForPaint()
            for (tab in listOf(1, 2, 3)) {
                compose.runOnIdle { state.value = state.value.copy(tab = tab) }
                compose.waitForIdle()
                compose.waitUntil(5000) {
                    instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                        try { !painted(bitmap) } finally { bitmap.recycle() }
                    } ?: false
                }
                compose.runOnIdle { state.value = state.value.copy(tab = 0) }
                waitForPaint()
            }
            compose.runOnIdle { narrow.value = true; dark.value = true }
            waitForPaint()
            var draft: String? = null
            compose.runOnIdle { page.view.evaluateJavascript("document.getElementById('draft').value") { draft = it } }
            compose.waitUntil(5000) { draft != null }
            assertEquals("保留我的课件草稿", JSONTokener(requireNotNull(draft)).nextValue())
            val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                assertTrue(painted(bitmap))
                val resolver = compose.activity.contentResolver
                val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "sample-web-restored.png"); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview"); put(MediaStore.Images.Media.IS_PENDING, 1) }
                val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } finally { bitmap.recycle() }
        } finally { compose.runOnIdle { page.destroy() } }
    }
}
