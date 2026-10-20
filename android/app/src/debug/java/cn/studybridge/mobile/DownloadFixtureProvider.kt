package cn.studybridge.mobile

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File

// Disposable CI document tree; debug-only and never included in the signed release.
class DownloadFixtureProvider : DocumentsProvider() {
    private val root get() = File(requireNotNull(context).cacheDir, "download-provider").apply { mkdirs() }
    private fun file(id: String): File { require(id == "root" || id.matches(Regex("[a-f0-9-]{36}"))); return if (id == "root") root else File(root, id) }
    override fun onCreate() = true
    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID, DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS)).apply { newRow().apply { add(DocumentsContract.Root.COLUMN_ROOT_ID, "fixture"); add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root"); add(DocumentsContract.Root.COLUMN_TITLE, "测试下载文件夹"); add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE) } }
    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = rows(projection).also { add(it, documentId) }
    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = rows(projection).also { cursor -> require(parentDocumentId == "root"); root.listFiles().orEmpty().forEach { add(cursor, it.name) } }
    private fun rows(projection: Array<out String>?) = MatrixCursor(projection ?: arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE))
    private fun add(cursor: MatrixCursor, id: String) { val f = file(id); val values = mapOf<String, Any>(DocumentsContract.Document.COLUMN_DOCUMENT_ID to id, DocumentsContract.Document.COLUMN_DISPLAY_NAME to if (id == "root") "测试下载文件夹" else id, DocumentsContract.Document.COLUMN_MIME_TYPE to if (id == "root") DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream", DocumentsContract.Document.COLUMN_FLAGS to if (id == "root") DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE else (DocumentsContract.Document.FLAG_SUPPORTS_WRITE or DocumentsContract.Document.FLAG_SUPPORTS_DELETE), DocumentsContract.Document.COLUMN_SIZE to f.length()); cursor.addRow(cursor.columnNames.map { values[it] }) }
    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String { require(parentDocumentId == "root"); return newId().also { file(it).writeBytes(byteArrayOf()) } }
    override fun deleteDocument(documentId: String) { require(documentId != "root"); check(file(documentId).delete()) }
    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor = ParcelFileDescriptor.open(file(documentId), ParcelFileDescriptor.parseMode(mode))
    override fun isChildDocument(parentDocumentId: String, documentId: String) = parentDocumentId == "root" && documentId != "root" && file(documentId).isFile
}
