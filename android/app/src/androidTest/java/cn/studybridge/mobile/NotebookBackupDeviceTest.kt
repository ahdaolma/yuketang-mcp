package cn.studybridge.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class NotebookBackupDeviceTest {
    @Test fun backupRoundtripKeepsImmutableOriginalImageAndEditableVectorMarks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = SourceImages(noteTestApplication(context))
        val png = ByteArrayOutputStream()
        Bitmap.createBitmap(80, 48, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)); bitmap.recycle()
        }
        val original = png.toByteArray()
        val id = first.save("data:image/png;base64," + Base64.encodeToString(original, Base64.NO_WRAP))
        val strokes = listOf(ImageStroke(listOf(MarkPoint(.2f, .5f), MarkPoint(.8f, .5f)), .05f), ImageStroke(listOf(MarkPoint(.25f, .25f), MarkPoint(.75f, .25f)), .08f, highlight = true))
        first.markupStore.save(id, strokes)
        val source = Note("original-page", "有原图与批注的课堂笔记", sourceImageMarkdown(id, "第 1 页") + "正文与备注保留。", "测试课堂", "测试模型", createdAt = 100,
            classNotebook = ClassNotebookBinding("standard", "not-an-account-token", "42", "7"), materialId = "local-material", tags = listOf("重点"), reviewState = "reviewing")
        val saved = Saved(notes = listOf(source), noteHistory = listOf(NoteRevision("old-page", source.copy(content = sourceImageMarkdown(id, "旧版原页") + "旧版正文", folderId = "deleted-folder"), 90)),
            noteDrafts = listOf(NoteDraft(source.id, source.content + "\n尚未保存的修改。", 110, source.content)))
        val zip = ByteArrayOutputStream()
        writeNotebookBackup(zip, saved, first::bytes, first.markupStore::exportRaw)
        val restored = readNotebookBackup(ByteArrayInputStream(zip.toByteArray()))
        assertArrayEquals(original, restored.images.getValue(id))
        assertEquals(strokes, imageMarkupFromJson(restored.marks.getValue(id).toString(Charsets.UTF_8)))
        assertNull(restored.data.notes.single().classNotebook); assertEquals("", restored.data.notes.single().materialId)
        assertEquals("", restored.data.noteHistory.single().note.folderId)

        val secondApp = noteTestApplication(context)
        val second = SourceImages(secondApp)
        restored.images.forEach { (key, bytes) -> assertEquals(key, second.save("data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))) }
        restored.marks.forEach { (key, bytes) -> second.markupStore.importRaw(key, bytes) }
        val merged = mergeNotebookBackup(Saved(), restored.data)
        Vault(secondApp).save(merged)
        val durable = Vault(secondApp).load()
        assertEquals(2, durable.notes.size)
        assertTrue(durable.notes.any { it.title.endsWith("（恢复草稿）") && it.content.contains("尚未保存的修改") })
        assertTrue(durable.noteDrafts.isEmpty())
        assertEquals(listOf("重点"), durable.notes.first { it.id == source.id }.tags)
        assertArrayEquals(original, second.bytes(id))
        assertEquals(strokes, ImageMarkupStore(secondApp).load(id))

        val bitmap = boundedBitmap(second.bytes(id))
        val composite = second.markupStore.render(id, bitmap)
        try {
            assertEquals(Color.WHITE, bitmap.getPixel(40, 24))
            assertNotEquals("saved vectors must still render after a backup roundtrip", bitmap.getPixel(40, 24), composite.getPixel(40, 24))
        } finally { if (composite !== bitmap) composite.recycle(); bitmap.recycle() }
        // Backup/import must retain the original bytes rather than flattening
        // pen/highlighter marks into the image supplied to future model calls.
        assertArrayEquals(original, second.bytes(id))
        assertEquals(merged, mergeNotebookBackup(merged, restored.data))
    }
}
