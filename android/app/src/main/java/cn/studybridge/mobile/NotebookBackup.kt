package cn.studybridge.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class NotebookBackup(val data: Saved, val images: Map<String, ByteArray>, val marks: Map<String, ByteArray>)

const val NOTEBOOK_BACKUP_JSON_LIMIT = 16 * 1024 * 1024
const val NOTEBOOK_BACKUP_IMAGE_LIMIT = 24 * 1024 * 1024
const val NOTEBOOK_BACKUP_MARK_LIMIT = 16 * 1024 * 1024
const val NOTEBOOK_BACKUP_TOTAL_LIMIT = 128L * 1024 * 1024
const val NOTEBOOK_BACKUP_ASSET_LIMIT = 2000
const val NOTEBOOK_BACKUP_RECORD_LIMIT = 5000
private const val BACKUP_FORMAT = "studybridge-notebook"
private val backupImageEntry = Regex("images/([a-f0-9]{64})\\.image")
private val backupMarkEntry = Regex("marks/([a-f0-9]{64})\\.json")
private val backupFields = setOf("notes", "noteFolders", "noteTrash", "noteHistory", "noteModifiedTimes", "noteSort", "noteDrafts", "noteReadingPositions")
private val backupNoteFields = setOf("id", "title", "content", "source", "model", "createdAt", "pinned", "folderId", "annotations", "tags", "reviewState", "relatedNoteIds")

private fun Note.portable() = copy(materialId = "", classNotebook = null)
private fun Note.portableJson() = portable().notebookJson().apply { remove("materialId"); remove("classNotebook") }
private fun JSONArray.backupObjects() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.requireKeys(allowed: Set<String>) { require(keys().asSequence().all { it in allowed }) { "备份包含不支持的字段。" } }
private fun validBackupId(id: String) = id.isNotBlank() && id.length <= 160 && id.none { it.isISOControl() }

// Local history can legitimately outlive a folder or a linked notebook. Clean
// only the exported snapshot; never rewrite the user's live records here.
private fun sanitizedBackupData(data: Saved): Saved {
    val ids = data.imageBearingNotes().map { it.id }.toSet()
    val activeIds = data.notes.map { it.id }.toSet()
    val folders = data.noteFolders.map { it.id }.toSet()
    fun clean(note: Note) = note.portable().copy(
        folderId = note.folderId.takeIf { it in folders }.orEmpty(),
        relatedNoteIds = note.relatedNoteIds.filter { it in ids && it != note.id }.distinct().take(100)
    )
    return data.copy(notes = data.notes.map(::clean), noteFolders = data.noteFolders.map { it.copy(classroomKey = "") }, noteTrash = data.noteTrash.map { it.copy(note = clean(it.note)) },
        noteHistory = data.noteHistory.map { it.copy(note = clean(it.note)) },
        noteModifiedTimes = data.noteModifiedTimes.filterKeys { it in ids },
        noteDrafts = data.noteDrafts.filter { it.noteId in activeIds },
        noteReadingPositions = data.noteReadingPositions.filter { it.noteId in activeIds })
}

private fun backupDataJson(data: Saved) = JSONObject().apply {
    put("notes", JSONArray(data.notes.map { it.portableJson() }))
    put("noteFolders", JSONArray(data.noteFolders.map { it.json(includeAssociation = false) }))
    put("noteTrash", JSONArray(data.noteTrash.map { JSONObject().put("note", it.note.portableJson()).put("deletedAt", it.deletedAt) }))
    put("noteHistory", JSONArray(data.noteHistory.map { JSONObject().put("id", it.id).put("note", it.note.portableJson()).put("savedAt", it.savedAt) }))
    put("noteModifiedTimes", JSONObject(data.noteModifiedTimes)); put("noteSort", data.noteSort)
    put("noteDrafts", JSONArray(data.noteDrafts.map { JSONObject().put("noteId", it.noteId).put("content", it.content).put("updatedAt", it.updatedAt).put("baseContent", it.baseContent ?: JSONObject.NULL) }))
    put("noteReadingPositions", JSONArray(data.noteReadingPositions.map { JSONObject().put("noteId", it.noteId).put("index", it.index).put("scroll", it.scroll).put("anchor", it.anchor).put("contentHash", it.contentHash).put("textAnchor", it.textAnchor?.toJson()) }))
}

// Scan before invoking JSONObject: expanded byte limits alone do not stop a deeply
// nested, tiny JSON record from exhausting the parser's stack.
private fun checkBackupJson(text: String) {
    var depth = 0; var quoted = false; var escaped = false
    text.forEach { c ->
        if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
        else when (c) { '"' -> quoted = true; '{', '[' -> { depth++; require(depth <= 64) { "备份内容嵌套过深。" } }; '}', ']' -> { depth--; require(depth >= 0) } }
    }
    require(!quoted && depth == 0) { "备份内容不完整。" }
}

private fun checkBackupMarkup(text: String) {
    checkBackupJson(text)
    val json = JSONObject(text); json.requireKeys(setOf("version", "strokes"))
    val strokes = json.getJSONArray("strokes"); require(strokes.length() <= 1000)
    strokes.backupObjects().forEach { it.requireKeys(setOf("width", "highlight", "points")) }
    imageMarkupFromJson(text)
}

private fun validateBackupData(data: Saved) {
    require(data.notes.size + data.noteTrash.size + data.noteHistory.size <= NOTEBOOK_BACKUP_RECORD_LIMIT) { "备份中的笔记和历史版本过多。" }
    require(data.noteFolders.size <= 1000 && data.noteDrafts.size <= 5000 && data.noteReadingPositions.size <= 5000)
    val all = data.imageBearingNotes(); val ids = all.map { it.id }.toSet(); val activeIds = data.notes.map { it.id }.toSet(); val folders = data.noteFolders.map { it.id }.toSet()
    require(all.all { validBackupId(it.id) && (it.folderId.isEmpty() || it.folderId in folders) && it.relatedNoteIds.all { link -> link in ids && link != it.id } }) { "备份中的笔记引用不完整。" }
    require(data.notes.map { it.id }.distinct().size == data.notes.size)
    require(data.noteTrash.map { it.note.id }.distinct().size == data.noteTrash.size)
    require(data.noteHistory.map { it.id }.distinct().size == data.noteHistory.size && data.noteHistory.all { validBackupId(it.id) })
    require(data.notes.none { note -> data.noteTrash.any { it.note.id == note.id } }) { "备份中同一笔记同时出现在笔记库和回收站。" }
    require(folders.size == data.noteFolders.size && data.noteFolders.all { validBackupId(it.id) && it.name.isNotBlank() && it.name.length <= 1000 })
    require(data.noteFolders.all { folder -> folder.parentId.isEmpty() || folder.parentId != folder.id && data.noteFolders.any { it.id == folder.parentId && it.parentId.isEmpty() } }) { "备份中的笔记本层级无效。" }
    require(data.noteDrafts.map { it.noteId }.distinct().size == data.noteDrafts.size && data.noteDrafts.all { it.noteId in activeIds })
    require(data.noteReadingPositions.map { it.noteId }.distinct().size == data.noteReadingPositions.size && data.noteReadingPositions.all { it.noteId in activeIds && it.index >= 0 && it.scroll >= 0 })
    require(data.noteModifiedTimes.keys.all { it in ids } && data.noteSort in NOTE_SORT_OPTIONS)
}

private fun backupReferences(data: Saved): Set<String> = (data.imageBearingNotes().map { it.content } + data.noteDrafts.flatMap { listOfNotNull(it.content, it.baseContent) }).flatMap { text -> sourceImagePattern.findAll(text).map { it.groupValues[2] }.toList() }.toSortedSet()

private fun readBackupData(json: JSONObject): Saved {
    json.requireKeys(backupFields)
    require(backupFields.all { json.has(it) }) { "备份缺少笔记字段。" }
    fun note(o: JSONObject): Note {
        o.requireKeys(backupNoteFields + "overviewSources")
        require(backupNoteFields.all { o.has(it) }) { "备份缺少笔记字段。" }
        // Validate raw links before the normal vault parser deduplicates/trims.
        val links = o.getJSONArray("relatedNoteIds").let { array -> (0 until array.length()).map { array.getString(it) } }
        require(links.size <= 100 && links.distinct().size == links.size)
        o.getString("folderId")
        return notebookNoteFromJson(o).portable()
    }
    val notes = json.getJSONArray("notes"); val trash = json.getJSONArray("noteTrash"); val history = json.getJSONArray("noteHistory")
    require(notes.length() + trash.length() + history.length() <= NOTEBOOK_BACKUP_RECORD_LIMIT)
    require(json.getJSONArray("noteFolders").length() <= 1000 && json.getJSONArray("noteDrafts").length() <= 5000 && json.getJSONArray("noteReadingPositions").length() <= 5000)
    val data = Saved(
        providers = emptyList(), conversations = emptyList(),
        notes = notes.backupObjects().map(::note),
        noteFolders = json.getJSONArray("noteFolders").backupObjects().map { it.requireKeys(setOf("id", "name", "parentId")); noteFolderFromJson(it) },
        noteTrash = trash.backupObjects().map { it.requireKeys(setOf("note", "deletedAt")); TrashedNote(note(it.getJSONObject("note")), it.getLong("deletedAt")) },
        noteHistory = history.backupObjects().map { it.requireKeys(setOf("id", "note", "savedAt")); NoteRevision(it.getString("id"), note(it.getJSONObject("note")), it.getLong("savedAt")) },
        noteModifiedTimes = json.getJSONObject("noteModifiedTimes").let { times -> times.keys().asSequence().associateWith { times.getLong(it) } },
        noteSort = json.getString("noteSort"),
        noteDrafts = json.getJSONArray("noteDrafts").backupObjects().map { it.requireKeys(setOf("noteId", "content", "updatedAt", "baseContent")); NoteDraft(it.getString("noteId"), it.getString("content"), it.getLong("updatedAt"), if (it.isNull("baseContent")) null else it.getString("baseContent")) },
        noteReadingPositions = json.getJSONArray("noteReadingPositions").backupObjects().map { it.requireKeys(setOf("noteId", "index", "scroll", "anchor", "contentHash", "textAnchor")); it.optJSONObject("textAnchor")?.requireKeys(setOf("sourceStart", "offset", "inset")); NoteReadingPosition(it.getString("noteId"), it.getInt("index"), it.getInt("scroll"), it.getString("anchor"), it.getString("contentHash"), noteTextAnchorFromJson(it.optJSONObject("textAnchor"))) }
    )
    validateBackupData(data)
    return data
}

fun writeNotebookBackup(output: OutputStream, data: Saved, image: (String) -> ByteArray, mark: (String) -> ByteArray?) {
    val portable = sanitizedBackupData(data)
    validateBackupData(portable)
    val references = backupReferences(portable); require(references.size <= NOTEBOOK_BACKUP_ASSET_LIMIT)
    var total = 0L; var entries = 0; val marked = mutableListOf<String>()
    // Do not close the caller's stream (SAF owns its lifetime).
    val zip = ZipOutputStream(object : java.io.FilterOutputStream(output) { override fun close() { flush() } })
    fun entry(name: String, bytes: ByteArray, limit: Int) {
        require(bytes.size <= limit); total += bytes.size
        require(total <= NOTEBOOK_BACKUP_TOTAL_LIMIT && ++entries <= NOTEBOOK_BACKUP_ASSET_LIMIT + 1) { "备份超过大小或图片数量上限。" }
        zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
    }
    try {
        references.forEach { id ->
            val original = image(id); require(original.size <= NOTEBOOK_BACKUP_IMAGE_LIMIT && imageDigest(original) == id) { "原页图片校验失败。" }
            entry("images/$id.image", original, NOTEBOOK_BACKUP_IMAGE_LIMIT)
            mark(id)?.let { bytes ->
                require(bytes.size <= NOTEBOOK_BACKUP_MARK_LIMIT); checkBackupMarkup(bytes.toString(Charsets.UTF_8))
                entry("marks/$id.json", bytes, NOTEBOOK_BACKUP_MARK_LIMIT); marked += id
            }
        }
        val manifest = JSONObject().put("format", BACKUP_FORMAT).put("version", 1).put("data", backupDataJson(portable)).put("markedImages", JSONArray(marked)).toString().toByteArray(Charsets.UTF_8)
        entry("notebook.json", manifest, NOTEBOOK_BACKUP_JSON_LIMIT)
        zip.finish(); zip.flush()
    } finally { zip.close() }
}

fun readNotebookBackup(input: InputStream): NotebookBackup {
    val images = linkedMapOf<String, ByteArray>(); val marks = linkedMapOf<String, ByteArray>(); val names = mutableSetOf<String>()
    var total = 0L; var manifest: ByteArray? = null
    val zip = ZipInputStream(object : java.io.FilterInputStream(input) { override fun close() {} })
    try {
        while (true) {
            val entry = zip.nextEntry ?: break; val name = entry.name
            require(!entry.isDirectory && names.add(name) && names.size <= NOTEBOOK_BACKUP_ASSET_LIMIT + 1) { "备份条目重复或数量过多。" }
            val imageMatch = backupImageEntry.matchEntire(name); val markMatch = backupMarkEntry.matchEntire(name)
            val limit = when { name == "notebook.json" -> NOTEBOOK_BACKUP_JSON_LIMIT; imageMatch != null -> NOTEBOOK_BACKUP_IMAGE_LIMIT; markMatch != null -> NOTEBOOK_BACKUP_MARK_LIMIT; else -> error("备份包含不支持的文件。") }
            require(entry.size < 0 || entry.size <= limit)
            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(8192); var expanded = 0
            while (true) {
                val count = zip.read(buffer); if (count < 0) break
                expanded += count; total += count
                require(expanded <= limit && total <= NOTEBOOK_BACKUP_TOTAL_LIMIT) { "备份展开后超过大小上限。" }
                bytes.write(buffer, 0, count)
            }
            val value = bytes.toByteArray()
            when {
                name == "notebook.json" -> manifest = value
                imageMatch != null -> { val id = imageMatch.groupValues[1]; require(imageDigest(value) == id) { "原页图片校验失败。" }; images[id] = value }
                markMatch != null -> { checkBackupMarkup(value.toString(Charsets.UTF_8)); marks[markMatch.groupValues[1]] = value }
            }
            zip.closeEntry()
        }
    } finally { zip.close() }
    val text = requireNotNull(manifest) { "备份缺少笔记清单。" }.toString(Charsets.UTF_8); checkBackupJson(text)
    val json = JSONObject(text); json.requireKeys(setOf("format", "version", "data", "markedImages"))
    require(json.getString("format") == BACKUP_FORMAT && json.get("version") == 1) { "此备份格式或版本暂不支持。" }
    val data = readBackupData(json.getJSONObject("data")); val references = backupReferences(data)
    val marked = json.getJSONArray("markedImages").let { array -> (0 until array.length()).map { array.getString(it) } }
    require(marked.size == marked.distinct().size && marked.toSet() == marks.keys && marks.keys.all { it in references }) { "备份的图片批注引用不完整。" }
    require(images.keys == references) { "备份缺少原页图片或包含多余图片。" }
    return NotebookBackup(data, images, marks)
}

/** Merge only notebook fields. Credentials, running classes and existing drafts
 * always remain the exact objects supplied by the current local state. */
fun mergeNotebookBackup(current: Saved, backup: Saved): Saved {
    validateBackupData(backup)
    val fingerprint = imageDigest(backupDataJson(backup).toString().toByteArray(Charsets.UTF_8)).take(20)
    val folders = current.noteFolders.toMutableList(); val folderMap = mutableMapOf<String, String>()
    backup.noteFolders.sortedBy { if (it.parentId.isEmpty()) 0 else 1 }.forEach { folder ->
        val parent = if (folder.parentId.isEmpty()) "" else folderMap.getValue(folder.parentId)
        val same = folders.firstOrNull { it.id == folder.id && it.name == folder.name && it.parentId == parent } ?: folders.firstOrNull { it.parentId == parent && it.name.equals(folder.name, true) }
        if (same != null) folderMap[folder.id] = same.id
        else {
            val base = if (folders.none { it.id == folder.id }) folder.id else "import-$fingerprint-folder-${imageDigest(folder.id.toByteArray()).take(16)}"
            var id = base; var suffix = 1
            while (folders.any { it.id == id }) { id = "$base-${suffix++}" }
            folders += folder.copy(id = id, parentId = parent, classroomKey = ""); folderMap[folder.id] = id
        }
    }
    val source = backup.imageBearingNotes().distinctBy { it.id }.associateBy { it.id }
    val occupied = current.imageBearingNotes().distinctBy { it.id }.associateBy { it.id }
    val currentActive = current.notes.map { it.id }.toSet(); val currentTrash = current.noteTrash.map { it.note.id }.toSet()
    val incomingActive = backup.notes.map { it.id }.toSet(); val incomingTrash = backup.noteTrash.map { it.note.id }.toSet()
    val orphanOwners = (current.noteDrafts.map { it.noteId } + listOfNotNull(current.classRun?.noteId)).toSet() - occupied.keys
    val noteMap = mutableMapOf<String, String>(); val reserved = mutableSetOf<String>()
    fun transformWith(note: Note, mapping: Map<String, String>) = note.portable().copy(id = mapping.getValue(note.id), content = remapKnowledgeLinks(note.content, mapping), overviewSources = note.overviewSources.mapKeys { mapping[it.key] ?: it.key }, folderId = folderMap[note.folderId].orEmpty(),
        relatedNoteIds = note.relatedNoteIds.map { mapping.getValue(it) }.distinct().filter { it != mapping[note.id] })
    // Linked components move together. A single deterministic generation lets
    // cyclic links be compared in full before any candidate ID is accepted.
    // This avoids half-reusing locally edited imported graphs on re-import.
    val neighbours = source.keys.associateWith { mutableSetOf<String>() }
    backup.imageBearingNotes().forEach { note ->
        // Provenance is independent of the user's editable related-note list.
        // Chat transcripts saved as Markdown also contain these source links.
        (note.relatedNoteIds + note.overviewSources.keys + knowledgeLinkedNoteIds(note.content)).filter { it in source }.forEach { linked ->
            neighbours.getValue(note.id).add(linked); neighbours.getValue(linked).add(note.id)
        }
    }
    val unseen = source.keys.toMutableSet()
    while (unseen.isNotEmpty()) {
        val component = linkedSetOf<String>(); val queue = java.util.ArrayDeque<String>()
        queue.add(unseen.minOrNull()!!)
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!unseen.remove(id)) continue
            component.add(id); neighbours.getValue(id).forEach(queue::addLast)
        }
        fun fits(mapping: Map<String, String>): Boolean = mapping.values.distinct().size == mapping.size && component.all { id ->
            val target = mapping.getValue(id)
            target !in reserved && target !in orphanOwners && !(id in incomingActive && target in currentTrash) && !(id in incomingTrash && target in currentActive) &&
                (occupied[target] == null || occupied[target] == transformWith(source.getValue(id), mapping))
        }
        var mapping = component.associateWith { it }; var generation = 0
        while (!fits(mapping)) {
            val suffix = if (generation == 0) "" else "-$generation"
            mapping = component.associateWith { "import-$fingerprint-note-${imageDigest(it.toByteArray(Charsets.UTF_8))}$suffix" }
            generation++
        }
        noteMap.putAll(mapping); reserved.addAll(mapping.values)
    }
    fun transform(note: Note) = transformWith(note, noteMap)
    val notes = current.notes.toMutableList()
    backup.notes.map(::transform).forEach { note -> if (notes.none { it.id == note.id }) notes += note }
    val activeIds = notes.map { it.id }.toSet()
    val trash = current.noteTrash.toMutableList()
    backup.noteTrash.forEach { item -> val note = transform(item.note); if (note.id !in activeIds && trash.none { it.note.id == note.id }) trash += item.copy(note = note) }
    val history = current.noteHistory.toMutableList()
    backup.noteHistory.forEach { item ->
        val note = transform(item.note)
        val base = "import-$fingerprint-revision-${imageDigest(item.id.toByteArray()).take(16)}"; var id = base; var suffix = 1
        while (history.any { it.id == id && (it.note != note || it.savedAt != item.savedAt) }) id = "$base-${suffix++}"
        if (history.none { it.id == id }) history += item.copy(id = id, note = note)
    }
    // The real editor keeps drafts in a separate encrypted vault. Restore an
    // imported draft as an ordinary, separate notebook instead of attaching it
    // to (or replacing) the user's current editor/classroom draft.
    backup.noteDrafts.forEach { draft ->
        val original = transform(source.getValue(draft.noteId))
        val base = "import-$fingerprint-draft-${imageDigest(draft.noteId.toByteArray(Charsets.UTF_8))}"
        fun recovered(id: String) = original.copy(id = id, title = original.title + "（恢复草稿）", content = draft.content,
            createdAt = draft.updatedAt, pinned = false, annotations = original.annotations.filter { it.isAttached(draft.content) },
            relatedNoteIds = (original.relatedNoteIds + original.id).distinct().filter { it != id })
        var id = base; var suffix = 1
        while (trash.any { it.note.id == id } || id in orphanOwners || notes.any { it.id == id && it != recovered(id) } || (occupied[id]?.let { it != recovered(id) } == true)) id = "$base-${suffix++}"
        if (notes.none { it.id == id }) notes += recovered(id)
    }
    val reading = current.noteReadingPositions.toMutableList()
    backup.noteReadingPositions.forEach { position -> val id = noteMap.getValue(position.noteId); if (reading.none { it.noteId == id }) reading += position.copy(noteId = id) }
    val modified = current.noteModifiedTimes.toMutableMap()
    backup.noteModifiedTimes.forEach { (id, time) -> modified.putIfAbsent(noteMap.getValue(id), time) }
    return current.copy(notes = notes, noteFolders = folders, noteTrash = trash, noteHistory = history, noteDrafts = current.noteDrafts, noteReadingPositions = reading, noteModifiedTimes = modified, noteSort = if (current.imageBearingNotes().isEmpty()) backup.noteSort else current.noteSort)
}
