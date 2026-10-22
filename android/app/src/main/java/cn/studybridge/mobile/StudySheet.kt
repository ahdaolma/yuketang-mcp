package cn.studybridge.mobile

import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet as NativeModalBottomSheet
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

// A modal sheet owns a different Android Window. Hiding the Activity's status
// bar does not hide that window's bars; apply the same policy at attachment/focus.
@Composable private fun SheetWindowChrome() {
    val view = LocalView.current
    DisposableEffect(view) {
        val hide = Runnable {
            var parent: android.view.ViewParent? = view.parent
            var window: android.view.Window? = (view as? DialogWindowProvider)?.window
            while (window == null && parent != null) {
                window = (parent as? DialogWindowProvider)?.window
                parent = parent.parent
            }
            window?.let { WindowCompat.getInsetsController(it, it.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.statusBars())
            } }
        }
        view.post(hide)
        val focus = ViewTreeObserver.OnWindowFocusChangeListener { focused -> if (focused) view.post(hide) }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focus)
        onDispose { view.removeCallbacks(hide); if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnWindowFocusChangeListener(focus) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun StudyModalBottomSheet(onDismissRequest: () -> Unit, sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), content: @Composable ColumnScope.() -> Unit) {
    // Keep the draggable anchor's constraints equal to the whole window.
    // Restricting the Surface itself makes Material calculate a zero/top
    // expanded anchor; only the CONTENT should have a maximum height.
    NativeModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) }, modifier = Modifier.testTag("展开面板容器")) {
        Column(Modifier.fillMaxWidth().heightIn(max = expandedPanelHeight()).windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))) {
            SheetWindowChrome()
            content()
        }
    }
}
