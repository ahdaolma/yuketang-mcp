package cn.studybridge.mobile

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NoteReadingPosition(val noteId: String, val index: Int, val scroll: Int, val anchor: String, val contentHash: String)
fun noteContentHash(text: String) = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
fun restoredNoteIndex(position: NoteReadingPosition?, hash: String, labels: List<String>): Int {
    if (labels.isEmpty()) return 0
    if (position == null) return 0
    return if (position.contentHash == hash) position.index.coerceIn(labels.indices) else labels.indexOf(position.anchor).takeIf { it >= 0 } ?: position.index.coerceIn(labels.indices)
}

class NoteReadingMemory(context: Context, scope: CoroutineScope, private val onError: (String) -> Unit) {
    private val vault = Vault(context, "note-reading-v1.enc")
    @Volatile private var entries = emptyList<NoteReadingPosition>()
    private var blocked = false
    private val writes = Channel<Unit>(Channel.CONFLATED)
    init { scope.launch { for (signal in writes) {
        val snapshot = entries
        try { withContext(NonCancellable + Dispatchers.IO) { if (!blocked) vault.save(Saved(noteReadingPositions = snapshot)) } }
        catch (_: Exception) { onError("阅读位置暂未保存，请检查存储空间。") }
    } } }
    suspend fun load(noteIds: Set<String>) {
        entries = try { withContext(Dispatchers.IO) { vault.load().noteReadingPositions.filter { it.noteId in noteIds } } }
        catch (_: Exception) { blocked = true; onError("上次阅读位置暂时无法读取，已保留原记录。"); emptyList() }
    }
    fun position(id: String) = entries.find { it.noteId == id }
    fun merge(imported: List<NoteReadingPosition>, noteIds: Set<String>) {
        if (blocked) return
        entries = (entries + imported.filter { it.noteId in noteIds }).distinctBy { it.noteId }
        writes.trySend(Unit)
    }
    fun remember(position: NoteReadingPosition) {
        if (blocked || entries.find { it.noteId == position.noteId } == position) return
        entries = entries.filterNot { it.noteId == position.noteId } + position
        writes.trySend(Unit)
    }
}
