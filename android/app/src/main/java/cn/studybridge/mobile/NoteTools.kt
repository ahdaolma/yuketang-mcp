package cn.studybridge.mobile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

fun findNotes(notes: List<Note>, query: String): List<Note> {
    val terms = query.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    return notes.filter { note -> terms.all { term -> (listOf(note.title, note.content, note.source) + note.tags).any { it.contains(term, ignoreCase = true) } } }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAt })
}

data class NoteTextEdit(val text: String, val start: Int, val end: Int)

fun formatNoteSelection(text: String, start: Int, end: Int, kind: String): NoteTextEdit {
    val first = minOf(start, end).coerceIn(0, text.length)
    val last = maxOf(start, end).coerceIn(first, text.length)
    if (kind == "bold" || kind == "code" || kind.startsWith("mark:")) {
        val mark = if (kind.startsWith("mark:")) StudyMark.entries.firstOrNull { it.name == kind.removePrefix("mark:") }?.marker ?: return NoteTextEdit(text, first, last) else if (kind == "bold") "**" else "`"
        val selected = text.substring(first, last).ifEmpty { if (kind.startsWith("mark:")) "关键内容" else if (kind == "bold") "文字" else "代码" }
        return NoteTextEdit(text.replaceRange(first, last, mark + selected + mark), first + mark.length, first + mark.length + selected.length)
    }
    val prefix = when (kind) { "heading" -> "## "; "list" -> "- "; "todo" -> "- [ ] "; "quote" -> "> "; else -> return NoteTextEdit(text, first, last) }
    val lineStart = if (first == 0) 0 else text.lastIndexOf('\n', first - 1) + 1
    val lastCharacter = if (last > first) last - 1 else last
    val lineEnd = text.indexOf('\n', lastCharacter).let { if (it < 0) text.length else it }
    val replacement = text.substring(lineStart, lineEnd).split('\n').joinToString("\n") { prefix + it }
    return NoteTextEdit(text.replaceRange(lineStart, lineEnd, replacement), lineStart + prefix.length, lineStart + replacement.length)
}

private class NoteEditHistory(initial: TextFieldValue) {
    private val values = mutableStateListOf(initial)
    private var index by mutableIntStateOf(0)
    var revision = 0L
        private set
    val value get() = values[index]
    val canUndo get() = index > 0
    val canRedo get() = index < values.lastIndex
    fun record(next: TextFieldValue) {
        if (next == value) return
        revision++
        if (next.text == value.text) { values[index] = next; return }
        while (values.lastIndex > index) values.removeAt(values.lastIndex)
        values += next; index++
        if (values.size > 100) { values.removeAt(0); index-- }
    }
    fun undo(): TextFieldValue { if (canUndo) { revision++; index-- }; return value }
    fun redo(): TextFieldValue { if (canRedo) { revision++; index++ }; return value }
}

@Composable fun NoteEditor(text: String, onChange: (String) -> Unit, enabled: Boolean) {
    val history = remember { NoteEditHistory(TextFieldValue(text, TextRange(text.length))) }
    val value = history.value
    val revision = history.revision
    // A parent echo can be queued before a newer key/selection/composition or
    // undo operation. It must not reset the live input buffer to that old text.
    // Re-key on revision so a genuine pending external replacement can still
    // reconcile against the latest local state on the following composition.
    LaunchedEffect(text, revision) {
        if (revision == history.revision && text != history.value.text)
            history.record(TextFieldValue(text, TextRange(text.length)))
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            FeedbackTextButton(onClick = { onChange(history.undo().text) }, enabled = enabled && history.canUndo) { Text("撤销") }
            FeedbackTextButton(onClick = { onChange(history.redo().text) }, enabled = enabled && history.canRedo) { Text("重做") }
            (listOf("标题" to "heading", "加粗" to "bold", "列表" to "list", "待办" to "todo", "引用" to "quote", "代码" to "code") + StudyMark.entries.map { it.label to "mark:${it.name}" }).forEach { (label, kind) ->
                FeedbackTextButton(onClick = {
                    val current = history.value
                    val edit = formatNoteSelection(current.text, current.selection.start, current.selection.end, kind)
                    history.record(TextFieldValue(edit.text, TextRange(edit.start, edit.end))); onChange(edit.text)
                }, enabled = enabled) { Text(label) }
            }
        }
        OutlinedTextField(value, { history.record(it); onChange(it.text) }, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp), label = { Text("Markdown 笔记") }, minLines = 8, maxLines = 18, enabled = enabled)
    }
}
