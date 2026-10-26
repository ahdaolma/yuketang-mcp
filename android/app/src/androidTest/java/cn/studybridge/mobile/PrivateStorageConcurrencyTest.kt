package cn.studybridge.mobile

import android.graphics.Bitmap
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PrivateStorageConcurrencyTest {
    @Test fun encryptedRecordReadFromAnotherInstanceCannotDiscardTheLatestSave() {
        val app = noteTestApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val writer = Vault(app)
        val reader = Vault(app)
        val original = Saved(notes = listOf(Note(id = "sentinel", title = "并发存储", content = "", source = "fixture", model = "fixture")))
        fun snapshot(n: Int) = original.copy(learnerProfile = "generation-$n",
            notes = original.notes.map { it.copy(content = "generation-$n\n" + "完整加密记录。".repeat(1024)) })
        writer.save(snapshot(0))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reads = pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(240) {
                    val read = reader.load()
                    assertTrue("Readers must receive one complete encrypted snapshot", read.notes.single().content.startsWith(read.learnerProfile + "\n"))
                }
            }
            val writes = pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(64) { index ->
                    val expected = snapshot(index + 1)
                    writer.save(expected)
                    assertEquals("A separate Vault reader must not remove the pending write", expected.learnerProfile, writer.load().learnerProfile)
                }
            }
            start.countDown()
            writes.get(60, TimeUnit.SECONDS)
            reads.get(60, TimeUnit.SECONDS)
            assertEquals("generation-64", Vault(app).load().learnerProfile)
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun simultaneousOriginalImageImportsKeepTheContentAddressedBytes() {
        val app = noteTestApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val stores = List(4) { SourceImages(app) }
        val pool = Executors.newFixedThreadPool(stores.size)
        try {
            repeat(20) { round ->
                val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
                val bytes = try {
                    bitmap.eraseColor(0xff000000.toInt() or (round * 1007))
                    ByteArrayOutputStream().use { stream -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); stream.toByteArray() }
                } finally { bitmap.recycle() }
                val data = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                val expected = imageDigest(bytes)
                val start = CountDownLatch(1)
                val tasks = stores.map { store -> pool.submit {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    assertEquals(expected, store.save(data))
                    assertArrayEquals("Import completion must expose the whole original", bytes, store.bytes(expected))
                } }
                start.countDown()
                tasks.forEach { it.get(30, TimeUnit.SECONDS) }
                assertArrayEquals(bytes, SourceImages(app).bytes(expected))
            }
        } finally { pool.shutdownNow() }
    }
}
