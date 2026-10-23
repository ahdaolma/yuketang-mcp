package cn.studybridge.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Open editor sessions survive configuration changes, without overwriting saved marks. */
class ImageMarkupDrafts : ViewModel() {
    private val sessions = mutableMapOf<String, ImageMarkupDraft>()
    fun session(id: String) = sessions.getOrPut(id) { ImageMarkupDraft() }
    fun discard(id: String) { sessions.remove(id) }

    fun save(id: String, store: ImageMarkupStore, close: Boolean) {
        val draft = session(id)
        if (!draft.ready || draft.saving) return
        val snapshot = draft.history.strokes
        draft.saving = true; draft.error = ""; draft.status = ""
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.save(id, snapshot) }
                draft.saved = snapshot; draft.status = "批注已保存"; draft.confirmClose = false
                draft.closeAfterSave = close
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { draft.error = "批注保存失败，请重试。" }
            finally { draft.saving = false }
        }
    }
}

class ImageMarkupDraft {
    var history by mutableStateOf(MarkupHistory())
    var saved by mutableStateOf<List<ImageStroke>>(emptyList())
    var ready by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var error by mutableStateOf("")
    var status by mutableStateOf("")
    var confirmClose by mutableStateOf(false)
    var closeAfterSave by mutableStateOf(false)
    var tool by mutableStateOf(ImageMarkupTool.VIEW)
    var zoom by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)
}
