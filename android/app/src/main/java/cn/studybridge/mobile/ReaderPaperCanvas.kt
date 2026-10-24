package cn.studybridge.mobile

import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

data class ReaderPaperImages(val current: ImageBitmap, val target: ImageBitmap) {
    // Draw acknowledgement, deliberately not observable composition state.
    var drawn = false
}

internal fun Modifier.readerMotionTouchShield() = pointerInput(Unit) {
    // A new pointer must never reach an invisible native frame, including
    // while the target is still preparing its first snapshot.
    awaitEachGesture {
        val down = awaitFirstDown(false); down.consume()
        do { val event = awaitPointerEvent(); event.changes.forEach { it.consume() } }
        while (event.changes.any { it.pressed })
    }
}

private fun paperPath(points: List<PaperPoint>, fold: PaperFold? = null, bow: Float = 0f): Path = Path().apply {
    if (points.isNotEmpty()) {
        moveTo(points[0].x, points[0].y)
        for (i in 1..points.size) {
            val previous = points[i - 1]; val next = points[i % points.size]
            val curved = fold != null && abs(previous.x * fold.normal.x + previous.y * fold.normal.y - fold.offset) < .2f &&
                abs(next.x * fold.normal.x + next.y * fold.normal.y - fold.offset) < .2f
            if (curved && fold != null) quadTo((previous.x + next.x) / 2f - fold.normal.x * bow,
                (previous.y + next.y) / 2f - fold.normal.y * bow, next.x, next.y)
            else lineTo(next.x, next.y)
        }
        close()
    }
}

/** Only the moving paper is rasterized; resting pages retain native selection and images. */
@Composable fun ReaderPaperCanvas(images: ReaderPaperImages, animation: String, progress: () -> Float, direction: Int,
    bottom: Boolean, touchY: () -> Float, paper: Color, modifier: Modifier = Modifier) {
    val bitmapPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG) }
    val shadePaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val front = if (direction > 0) images.current.asAndroidBitmap() else images.target.asAndroidBitmap()
    val underneath = if (direction > 0) images.target.asAndroidBitmap() else images.current.asAndroidBitmap()
    Canvas(modifier.testTag("笔记翻页画面").semantics {
        contentDescription = "正在翻页"
        stateDescription = "${NOTE_PAGE_ANIMATIONS[animation]} · ${if (direction > 0) "向后阅读" else "向前阅读"} · ${(progress() * 100).toInt()}%"
    }.readerMotionTouchShield()) {
        // A newly started pointer cannot select hidden text during completion.
        // The already-owned drag keeps its original hit path on the parent.
        val w = size.width; val h = size.height
        // Read motion state in drawing, keeping native text composition out of
        // the per-finger-move / per-animation-frame path.
        val p = progress().coerceIn(0f, 1f)
        drawIntoCanvas { composeCanvas ->
            val canvas = composeCanvas.nativeCanvas
            val saved = canvas.save(); canvas.clipRect(0f, 0f, w, h)
            bitmapPaint.alpha = 255; bitmapPaint.shader = null
            shadePaint.color = android.graphics.Color.BLACK
            shadePaint.style = Paint.Style.FILL
            canvas.drawColor(paper.toArgb())
            canvas.drawBitmap(underneath, null, RectF(0f, 0f, w, h), bitmapPaint)
            if (animation == "cover") {
                val x = if (direction > 0) -p * w else (p - 1f) * w
                val edge = x + w
                shadePaint.shader = LinearGradient(edge, 0f, edge + 24.dp.toPx(), 0f,
                    intArrayOf(0x38000000, 0x00000000), null, Shader.TileMode.CLAMP)
                canvas.drawRect(edge, 0f, (edge + 24.dp.toPx()).coerceAtMost(w), h, shadePaint)
                shadePaint.shader = null
                canvas.drawBitmap(front, null, RectF(x, 0f, x + w, h), bitmapPaint)
            } else {
                val q = if (direction > 0) p else 1f - p
                val fold = readerPaperFold(w, h, q, bottom, touchY())
                val bow = 14.dp.toPx() * sin(PI * q).toFloat()
                val nx = fold.normal.x; val ny = fold.normal.y
                val ax = nx * fold.offset; val ay = ny * fold.offset
                if (fold.crease.size == 2) {
                    val spread = 28.dp.toPx()
                    shadePaint.shader = LinearGradient(ax - nx * spread, ay - ny * spread, ax + nx * spread, ay + ny * spread,
                        intArrayOf(0x00000000, 0x50000000, 0x00000000), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, w, h, shadePaint); shadePaint.shader = null
                }
                if (fold.front.isNotEmpty()) {
                    val clipped = canvas.save(); canvas.clipPath(paperPath(fold.front, fold, bow))
                    canvas.drawBitmap(front, null, RectF(0f, 0f, w, h), bitmapPaint)
                    canvas.restoreToCount(clipped)
                }
                if (fold.back.isNotEmpty()) {
                    val reflection = Matrix().apply { setValues(floatArrayOf(1f - 2f * nx * nx, -2f * nx * ny, 2f * nx * fold.offset,
                        -2f * nx * ny, 1f - 2f * ny * ny, 2f * ny * fold.offset, 0f, 0f, 1f)) }
                    // The reflected control point must meet the same bowed
                    // crease as the front; otherwise a slit opens in the paper.
                    val back = paperPath(fold.folded, fold, -bow).apply { transform(reflection) }
                    val clipped = canvas.save(); canvas.clipPath(back)
                    canvas.drawColor(paper.toArgb())
                    bitmapPaint.alpha = 38
                    reflection.preScale(w / front.width, h / front.height)
                    canvas.drawBitmap(front, reflection, bitmapPaint); bitmapPaint.alpha = 255
                    val spread = 64.dp.toPx()
                    shadePaint.shader = LinearGradient(ax - nx * spread, ay - ny * spread, ax + nx * spread, ay + ny * spread,
                        intArrayOf(0x08000000, 0x38FFFFFF, 0x40000000), floatArrayOf(0f, .58f, 1f), Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, w, h, shadePaint); shadePaint.shader = null
                    canvas.restoreToCount(clipped)
                }
                if (fold.crease.size == 2) {
                    val a = fold.crease[0]; val b = fold.crease[1]
                    val ridge = Path().apply { moveTo(a.x, a.y); quadTo((a.x + b.x) / 2f - nx * bow, (a.y + b.y) / 2f - ny * bow, b.x, b.y) }
                    shadePaint.style = Paint.Style.STROKE; shadePaint.strokeWidth = .8.dp.toPx(); shadePaint.color = 0x40FFFFFF
                    canvas.drawPath(ridge, shadePaint); shadePaint.style = Paint.Style.FILL
                }
            }
            canvas.restoreToCount(saved)
        }
        images.drawn = true
    }
}
