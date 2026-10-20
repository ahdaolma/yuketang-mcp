package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MaterialDocumentTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun archive(parts: Map<String, String>): ByteArray = ByteArrayOutputStream().also { out -> ZipOutputStream(out).use { zip -> parts.forEach { (name, text) -> zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry() } } }.toByteArray()
    @Test fun pptxUsesPresentationOrderAndTextImportPreservesOriginalNotes() {
        val store = MaterialStore(context)
        val ppt = archive(mapOf(
            "ppt/presentation.xml" to """<p:presentation xmlns:p="urn:p" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><p:sldIdLst><p:sldId r:id="second"/><p:sldId r:id="first"/></p:sldIdLst></p:presentation>""",
            "ppt/_rels/presentation.xml.rels" to """<Relationships xmlns="urn:rel"><Relationship Id="first" Target="slides/slide1.xml"/><Relationship Id="second" Target="slides/slide2.xml"/></Relationships>""",
            "ppt/slides/slide1.xml" to """<slide xmlns:a="urn:a"><a:t>第二页</a:t></slide>""",
            "ppt/slides/slide2.xml" to """<slide xmlns:a="urn:a"><a:t>第一页 English</a:t></slide>"""))
        val doc = store.importStream(ppt.inputStream(), "完整课程名称 Medical Genetics.pptx", "courseware")
        try { assertEquals(2, doc.pages.size); assertEquals("第一页 English", doc.pages.first().text); assertTrue(store.attachment(doc, 1).text.contains("复杂版式")); assertEquals(doc, store.read(doc.material)) } finally { store.remove(doc.material.id) }
        val original = "# 外部笔记\n\n基因表达 **重点** English"
        val notes = store.importStream(original.byteInputStream(), "原始笔记.md", "notes")
        try { assertEquals(original, notes.noteText); assertEquals("notes", notes.material.kind); assertTrue(store.attachment(notes, 1).label.endsWith("第 1 段")) } finally { store.remove(notes.material.id) }
        val malicious = archive(mapOf("word/document.xml" to """<!DOCTYPE x [<!ENTITY x SYSTEM "file:///does-not-exist">]><document>&x;</document>"""))
        try { store.importStream(malicious.inputStream(), "invalid.docx", "notes"); fail("DTD must be rejected") } catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("XML")) }
    }
    @Test fun exportedPdfReopensWithTheCorrectPagesAndCanBeImportedForExplanation() {
        val file = File.createTempFile("material-test-", ".pdf", context.cacheDir)
        try {
            file.outputStream().use { out -> val writer = ImagePdfWriter(out, "医学遗传学 Medical Genetics"); for (color in listOf(Color.RED, Color.BLUE)) { val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888); try { bitmap.eraseColor(color); writer.add(bitmap) } finally { bitmap.recycle() } }; writer.finish() }
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf -> assertEquals(2, pdf.pageCount); pdf.openPage(1).use { page -> val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888); try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); assertTrue(Color.blue(bitmap.getPixel(400, 300)) > 240); assertTrue(Color.red(bitmap.getPixel(400, 300)) < 10) } finally { bitmap.recycle() } } }
            val store = MaterialStore(context); val imported = file.inputStream().use { store.importStream(it, "导入 PDF.pdf", "notes") }
            try { assertEquals(2, imported.pages.size); assertTrue(store.attachment(imported, 2).image.orEmpty().startsWith("data:image/jpeg;base64,")) } finally { store.remove(imported.material.id) }
        } finally { file.delete() }
    }
    @Test fun selectedDirectoryReceivesBothFormatsAndFailedWritesRemoveOnlyNewFile() {
        val authority = "cn.studybridge.mobile.downloadfixture"
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "root")
        val root = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, "root")
        val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree, "root")
        val resolver = context.contentResolver
        fun ids(): List<String> = resolver.query(children, arrayOf(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)!!.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val before = ids().toSet(); val store = DownloadStore(context)
        try {
            store.write(tree.toString(), null, "课件.pdf", "application/pdf") { "%PDF-fixture".byteInputStream() }
            store.write(tree.toString(), null, "笔记.md", "text/markdown") { "# 原文笔记".byteInputStream() }
            val written = ids().filter { it !in before }; assertEquals(2, written.size)
            val contents = written.map { id -> resolver.openInputStream(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id))!!.bufferedReader().use { it.readText() } }
            assertTrue(contents.contains("# 原文笔记")); assertTrue(contents.contains("%PDF-fixture"))
            try { store.write(tree.toString(), null, "failed.pdf", "application/pdf") { throw java.io.IOException("fixture-write-failure") }; fail("expected write failure") } catch (_: java.io.IOException) { }
            assertEquals(before.size + 2, ids().size)
        } finally { ids().filter { it !in before }.forEach { android.provider.DocumentsContract.deleteDocument(resolver, android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, it)) } }
    }

}
