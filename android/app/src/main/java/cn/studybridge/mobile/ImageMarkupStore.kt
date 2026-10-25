package cn.studybridge.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AtomicFile
import java.io.File

class ImageMarkupStore(context: Context) {
    private val root = File(context.noBackupFilesDir, "note-image-marks").canonicalFile.apply { mkdirs() }
    private fun file(id: String): AtomicFile { require(Regex("[a-f0-9]{64}").matches(id)); return AtomicFile(File(root, "$id.json")) }
    // AtomicFile.openRead may remove a pending .new file. All instances must
    // share the same lock, including backup export and preview reads. Bounded
    // stripes avoid retaining one lock/path for every image ever opened.
    private fun <T> access(id: String, block: (AtomicFile) -> T): T {
        val file = file(id)
        return synchronized(locks[(file.baseFile.path.hashCode() and Int.MAX_VALUE) % locks.size]) { block(file) }
    }
    fun load(id: String): List<ImageStroke> = access(id) { file ->
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) emptyList()
        else file.openRead().use { input -> require(input.channel.size() <= 16 * 1024 * 1024); input.bufferedReader().use { imageMarkupFromJson(it.readText()) } }
    }
    fun save(id: String, strokes: List<ImageStroke>) {
        val bytes = imageMarkupJson(strokes).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "批注过多，请减少笔画后保存。" }
        access(id) { file ->
            val out = file.startWrite()
            try { out.write(bytes); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        }
    }
    fun exportRaw(id: String): ByteArray? = access(id) { file ->
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else file.openRead().use { require(it.channel.size() <= 16 * 1024 * 1024); it.readBytes() }.also { imageMarkupFromJson(it.toString(Charsets.UTF_8)) }
    }
    fun importRaw(id: String, bytes: ByteArray) {
        require(bytes.size <= 16 * 1024 * 1024)
        save(id, imageMarkupFromJson(bytes.toString(Charsets.UTF_8)))
    }
    fun render(id: String, original: Bitmap): Bitmap {
        val strokes = load(id)
        if (strokes.isEmpty()) return original
        return original.copy(Bitmap.Config.ARGB_8888, true).also { drawImageStrokes(Canvas(it), strokes, it.width.toFloat(), it.height.toFloat()) }
    }
    private companion object { val locks = Array(64) { Any() } }
}

fun drawImageStrokes(canvas: Canvas, strokes: List<ImageStroke>, width: Float, height: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    strokes.forEach { stroke ->
        paint.color = if (stroke.highlight) Color.argb(90, 255, 197, 0) else Color.rgb(215, 44, 56)
        paint.strokeWidth = stroke.width * width
        if (stroke.points.size == 1) {
            paint.style = Paint.Style.FILL; val p = stroke.points[0]; canvas.drawCircle(p.x * width, p.y * height, paint.strokeWidth / 2, paint)
        } else {
            paint.style = Paint.Style.STROKE
            val path = Path(); stroke.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * width, p.y * height) else path.lineTo(p.x * width, p.y * height) }
            canvas.drawPath(path, paint)
        }
    }
}
