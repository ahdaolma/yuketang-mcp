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
    @Test fun imageDocxAndUtf16TextProduceReadableAttachmentsAndInvalidFilesLeaveNoCopy() {
        val store = MaterialStore(context)
        val image = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        val bytes = try { image.eraseColor(Color.GREEN); ByteArrayOutputStream().also { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray() } finally { image.recycle() }
        val picture = store.importStream(bytes.inputStream(), "外部图片.png", "courseware")
        try {
            val a = store.attachment(picture, 1)
            val jpeg = android.util.Base64.decode(a.image!!.substringAfter(','), android.util.Base64.DEFAULT)
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
            try { assertEquals(120, decoded.width); assertEquals(80, decoded.height); assertTrue(Color.green(decoded.getPixel(60, 40)) > 200) } finally { decoded.recycle() }
        } finally { store.remove(picture.material.id) }
        val word = archive(mapOf("word/document.xml" to """<w:document xmlns:w="urn:w"><w:body><w:p><w:r><w:t>病理学 Pathology</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>炎症机制</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""))
        val docx = store.importStream(word.inputStream(), "笔记.docx", "notes")
        try { assertTrue(docx.noteText.contains("Pathology")); assertTrue(docx.noteText.contains("炎症机制")); assertTrue(store.attachment(docx, 1).text.contains("分段编号")) } finally { store.remove(docx.material.id) }
        val text = "# 原始笔记\n中文 English"
        val utf16 = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
        val txt = store.importStream(utf16.inputStream(), "笔记.txt", "notes")
        try { assertEquals(text, txt.noteText); assertTrue(store.attachment(txt, 1).text.contains(text)) } finally { store.remove(txt.material.id) }
        val directory = File(context.noBackupFilesDir, "learning-materials")
        val before = directory.list().orEmpty().toSet()
        for ((name, content) in listOf("empty.md" to byteArrayOf(), "bad.pdf" to "%PDF-invalid".toByteArray(), "bad.txt" to byteArrayOf(0, 0, 0), "unknown.ppt" to byteArrayOf(1, 2, 3))) {
            try { store.importStream(content.inputStream(), name, "notes"); fail("Invalid import must fail: $name") } catch (_: Exception) { }
            assertEquals(before, directory.list().orEmpty().toSet())
        }
    }
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
