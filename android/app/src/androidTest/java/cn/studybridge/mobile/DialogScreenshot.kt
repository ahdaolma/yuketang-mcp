package cn.studybridge.mobile

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry

// Compose's dialog semantics may precede SurfaceFlinger, and captureToImage can
// resolve the underlying Activity window. Check the real screen against its
// pre-dialog pixels, then capture the physical windows after the modal paints.
fun dialogScreenshot(rule: ComposeContentTestRule, before: Bitmap): Bitmap {
    val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
    try {
        rule.waitForIdle()
        rule.waitUntil(10000) {
            automation.takeScreenshot()?.let { shot ->
                try {
                    require(shot.width == before.width && shot.height == before.height)
                    var changed = 0; var total = 0
                    for (y in shot.height / 12 until shot.height * 11 / 12 step 12) for (x in 12 until shot.width - 12 step 12) {
                        total++; if (shot.getPixel(x, y) != before.getPixel(x, y)) changed++
                    }
                    changed > total / 10
                } finally { shot.recycle() }
            } ?: false
        }
        return requireNotNull(automation.takeScreenshot())
    } finally { before.recycle() }
}
