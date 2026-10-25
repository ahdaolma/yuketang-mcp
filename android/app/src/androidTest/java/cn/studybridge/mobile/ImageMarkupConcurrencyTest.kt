package cn.studybridge.mobile

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ImageMarkupConcurrencyTest {
    @Test fun separateStoresCannotDiscardAnInFlightSaveWhileReadingOrExporting() {
        val app = noteTestApplication(InstrumentationRegistry.getInstrumentation().targetContext)
        val writer = ImageMarkupStore(app)
        val reader = ImageMarkupStore(app)
        val id = "b".repeat(64)
        fun strokes(generation: Int) = listOf(ImageStroke((0..511).map { MarkPoint(it / 511f, generation / 100f) }))
        writer.save(id, strokes(0))
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val reads = pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(400) { iteration ->
                    val value = if (iteration % 2 == 0) reader.load(id) else imageMarkupFromJson(requireNotNull(reader.exportRaw(id)).toString(Charsets.UTF_8))
                    assertEquals(1, value.size)
                    assertEquals(512, value.single().points.size)
                    assertTrue("Readers must see a complete committed generation", value.single().points.all { it.y == value.single().points.first().y })
                }
            }
            val writes = pool.submit {
                assertTrue(start.await(5, TimeUnit.SECONDS))
                repeat(80) { iteration ->
                    val expected = strokes(iteration + 1)
                    writer.save(id, expected)
                    assertEquals("A concurrent read must not delete the successful save", expected, writer.load(id))
                }
            }
            start.countDown()
            writes.get(45, TimeUnit.SECONDS)
            reads.get(45, TimeUnit.SECONDS)
            assertEquals(strokes(80), ImageMarkupStore(app).load(id))
        } finally { start.countDown(); pool.shutdownNow() }
    }
}
