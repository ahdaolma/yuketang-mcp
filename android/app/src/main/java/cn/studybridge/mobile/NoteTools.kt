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
    return notes.filter { note -> terms.all { term -> listOf(note.title, note.content, note.source).any { it.contains(term, ignoreCase = true) } } }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.createdAt })
}

data class NoteTextEdit(val text: String, val start: Int, val end: Int)

fun formatNoteSelection(text: String, start: Int, end: Int, kind: String): NoteTextEdit {
    val first = minOf(start, end).coerceIn(0, text.length)
    val last = maxOf(start, end).coerceIn(first, text.length)
    if (kind == "bold" || kind == "code") {
        val mark = if (kind == "bold") "**" else "`"
        val selected = text.substring(first, last).ifEmpty { if (kind == "bold") "文字" else "代码" }
        return NoteTextEdit(text.replaceRange(first, last, mark + selected + mark), first + mark.length, first + mark.length + selected.length)
    }
    val prefix = when (kind) { "heading" -> "## "; "list" -> "- "; "todo" -> "- [ ] "; "quote" -> "> "; else -> return NoteTextEdit(text, first, last) }
    val lineStart = if (first == 0) 0 else text.lastIndexOf('\n', first - 1) + 1
    val lastCharacter = if (last > first) last - 1 else last
    val lineEnd = text.indexOf('\n', lastCharacter).let { if (it < 0) text.length else it }
    val replacement = text.substring(lineStart, lineEnd).split('\n').joinToString("\n") { prefix + it }
    return NoteTextEdit(text.replaceRange(lineStart, lineEnd, replacement), lineStart + prefix.length, lineStart + replacement.length)
}

@Composable fun NoteEditor(text: String, onChange: (String) -> Unit, enabled: Boolean) {
    var value by remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) { if (text != value.text) value = TextFieldValue(text, TextRange(text.length)) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            listOf("标题" to "heading", "加粗" to "bold", "列表" to "list", "待办" to "todo", "引用" to "quote", "代码" to "code").forEach { (label, kind) ->
                FeedbackTextButton(onClick = {
                    val edit = formatNoteSelection(value.text, value.selection.start, value.selection.end, kind)
                    value = TextFieldValue(edit.text, TextRange(edit.start, edit.end)); onChange(edit.text)
                }, enabled = enabled) { Text(label) }
            }
        }
        OutlinedTextField(value, { value = it; onChange(it.text) }, Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp), label = { Text("Markdown 笔记") }, minLines = 8, maxLines = 18, enabled = enabled)
    }
}
