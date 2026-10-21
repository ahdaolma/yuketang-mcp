package cn.studybridge.mobile

import android.content.ContentValues
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Base64
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

@SdkSuppress(minSdkVersion = 35)
class NotePdfExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun images(root: File) = SourceImages(object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = root
    })

    private fun render(file: File, inspect: (PdfRenderer) -> Unit) {
        PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use(inspect)
    }

    private fun screenshot(page: PdfRenderer.Page, name: String): Bitmap {
        val bitmap = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        val resolver = context.contentResolver
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/StudyUiReview")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        requireNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return bitmap
    }

    @Test fun longRichNoteExportsEveryParagraphAndPersonalRemarkAcrossA4Pages() {
        val directory = File(context.cacheDir, "note-pdf-${newId()}").apply { mkdirs() }
        val file = File(directory, "full-note.pdf")
        val paragraphCount = 180
        val content = buildString {
            append("# DNA 复制\n\n**BOLD_CONTENT** 与 ==重点掌握==。\n\n")
            append("| 对照 | 提示 |\n| --- | --- |\n| TABLE_CELL_ALPHA | TABLE_CELL_BETA |\n\n")
            repeat(paragraphCount) { append("PARA_${it.toString().padStart(4, '0')} 模板链与互补链共同决定复制。理解方向与酶的作用，然后对照两条子代分子。\n\n") }
            append("PDF_BODY_TAIL")
        }
        val note = Note(title = "DNA 的结构与复制", content = content, source = "测试课件 · 全部内容", model = "fixture",
            annotations = listOf(NoteAnnotation(sourceStart = 0, sourceLength = 1, sourceHash = "stale-anchor", start = 0, end = 1,
                quote = "原来的重点", kind = "remark", comment = "个人备注 **保留字面输入** PDF_REMARK_TAIL")))
        try {
            file.outputStream().use { writeNotePdf(context, note, images(directory), it) }
            render(file) { pdf ->
                assertTrue("Long notes must paginate instead of being truncated", pdf.pageCount >= 5)
                val text = buildString {
                    repeat(pdf.pageCount) { index ->
                        pdf.openPage(index).use { page ->
                            assertEquals(595, page.width); assertEquals(842, page.height)
                            append(page.textContents.joinToString("") { it.text })
                        }
                    }
                }.filterNot(Char::isWhitespace)
                repeat(paragraphCount) { assertTrue("Paragraph $it is missing", text.contains("PARA_${it.toString().padStart(4, '0')}")) }
                listOf("BOLD_CONTENT", "TABLE_CELL_ALPHA", "TABLE_CELL_BETA", "PDF_BODY_TAIL", "PDF_REMARK_TAIL", "原文位置待核对").forEach { assertTrue("Missing PDF content: $it", text.contains(it)) }
                assertFalse("Markdown formatting must render rather than leak into prose", text.contains("**BOLD_CONTENT**"))
                pdf.openPage(0).use { screenshot(it, "sample-note-pdf-first").recycle() }
                pdf.openPage(pdf.pageCount - 1).use { page ->
                    assertTrue(page.textContents.joinToString("") { it.text }.filterNot(Char::isWhitespace).contains("PDF_REMARK_TAIL"))
                    screenshot(page, "sample-note-pdf-last").recycle()
                }
            }
            val resolver = context.contentResolver
            val uri = requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "sample-note-export.pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/StudyUiReview")
            }))
            requireNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
        } finally { directory.deleteRecursively() }
    }

    @Test fun originalImageAndSavedInkAreExportedTogetherWithoutChangingOriginalBytes() {
        val directory = File(context.cacheDir, "note-pdf-image-${newId()}").apply { mkdirs() }
        val file = File(directory, "image-note.pdf")
        val store = images(directory)
        try {
            val bitmap = Bitmap.createBitmap(240, 180, Bitmap.Config.ARGB_8888)
            val original = try {
                bitmap.eraseColor(Color.rgb(35, 90, 220))
                ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
            } finally { bitmap.recycle() }
            val id = store.save("data:image/png;base64," + Base64.encodeToString(original, Base64.NO_WRAP))
            store.markupStore.save(id, listOf(ImageStroke(listOf(MarkPoint(.15f, .5f), MarkPoint(.85f, .5f)), .05f)))
            val note = Note(title = "原页与图片批注", content = sourceImageMarkdown(id, "原课件第 1 页") + "\n\nIMAGE_NOTE_TAIL", source = "fixture", model = "fixture")
            file.outputStream().use { writeNotePdf(context, note, store, it) }
            assertArrayEquals("Export may not flatten or overwrite the original", original, store.bytes(id))
            render(file) { pdf ->
                var red = 0
                var blue = 0
                repeat(pdf.pageCount) { index -> pdf.openPage(index).use { page ->
                    val image = screenshot(page, "sample-note-pdf-image-$index")
                    try {
                        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
                            val pixel = image.getPixel(x, y)
                            if (Color.red(pixel) > 170 && Color.green(pixel) < 100 && Color.blue(pixel) < 130) red++
                            if (Color.blue(pixel) > 170 && Color.red(pixel) < 100) blue++
                        }
                    } finally { image.recycle() }
                } }
                assertTrue("Saved red ink must be present in the PDF", red > 100)
                assertTrue("The underlying blue original must remain visible", blue > 1000)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun missingSourceImageReportsTheRealFailureAndCallerOwnsTheStream() {
        val directory = File(context.cacheDir, "note-pdf-missing-${newId()}").apply { mkdirs() }
        val output = object : ByteArrayOutputStream() {
            var closed = false
            override fun close() { closed = true; super.close() }
        }
        try {
            val store = images(directory)
            writeNotePdf(context, Note(title = "空白笔记", content = "", source = "", model = ""), store, output)
            assertFalse(output.closed)
            try {
                writeNotePdf(context, Note(title = "缺失图片", content = sourceImageMarkdown("0".repeat(64), "缺失原页"), source = "", model = ""), store, output)
                fail("Missing originals must never silently disappear from an exported note")
            } catch (expected: IllegalArgumentException) { assertTrue(expected.message.orEmpty().contains("原页图片不可用")) }
            assertFalse(output.closed)
        } finally { output.close(); directory.deleteRecursively() }
    }
}
