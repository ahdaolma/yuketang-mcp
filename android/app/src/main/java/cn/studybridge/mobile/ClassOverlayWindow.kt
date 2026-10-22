package cn.studybridge.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Gravity
import android.view.Display
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import kotlin.math.hypot
import kotlin.math.roundToInt

/** A bounded TYPE_APPLICATION_OVERLAY window; no Activity or Compose owner. */
internal class ClassOverlayWindow(
    private val context: Context,
    private val onToggle: () -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val windowContext = if (Build.VERSION.SDK_INT >= 30) runCatching {
        context.createDisplayContext(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }.getOrDefault(context) else context
    private val windows = windowContext.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("class-system-circle", Context.MODE_PRIVATE)
    private val size = (56 * context.resources.displayMetrics.density).roundToInt()
    private var view: ClassOverlayView? = null
    private var layout: WindowManager.LayoutParams? = null
    val visible: Boolean get() = view?.isAttachedToWindow == true

    private fun bounds(): android.graphics.Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = windows.maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return android.graphics.Rect(insets.left, insets.top, (metrics.bounds.width() - insets.right - size).coerceAtLeast(insets.left), (metrics.bounds.height() - insets.bottom - size).coerceAtLeast(insets.top))
        }
        @Suppress("DEPRECATION") val dimensions = android.util.DisplayMetrics().also { windows.defaultDisplay.getMetrics(it) }
        val margin = (24 * context.resources.displayMetrics.density).roundToInt()
        return android.graphics.Rect(0, margin, (dimensions.widthPixels - size).coerceAtLeast(0), (dimensions.heightPixels - size - margin).coerceAtLeast(margin))
    }

    fun show(paused: Boolean) {
        view?.let { it.paused = paused; it.invalidate(); return }
        if (!ClassFollowService.canDrawOverlays(context)) return
        val safe = bounds()
        val params = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            x = safe.left + ((safe.right - safe.left) * prefs.getFloat("xFraction", .88f).finiteFraction()).roundToInt()
            y = safe.top + ((safe.bottom - safe.top) * prefs.getFloat("yFraction", .35f).finiteFraction()).roundToInt()
            title = "雨课伴学上课悬浮球"
        }
        val circle = ClassOverlayView(windowContext, onToggle, onDrag = { dx, dy ->
            val area = bounds()
            params.x = (params.x + dx).coerceIn(area.left, area.right)
            params.y = (params.y + dy).coerceIn(area.top, area.bottom)
            try { view?.let { windows.updateViewLayout(it, params) } }
            catch (_: Exception) { hide(); onFailure("悬浮球已关闭，请检查悬浮窗权限；通知栏仍可控制上课。") }
        }, onDragEnd = {
            val area = bounds()
            prefs.edit().putFloat("xFraction", fraction(params.x, area.left, area.right)).putFloat("yFraction", fraction(params.y, area.top, area.bottom)).apply()
        }).also { it.paused = paused }
        try { windows.addView(circle, params); layout = params; view = circle }
        catch (_: Exception) { runCatching { windows.removeViewImmediate(circle) }; onFailure("无法显示悬浮球，请检查悬浮窗权限；上课仍由通知栏控制。") }
    }

    fun reconfigure() { val paused = view?.paused ?: return; hide(); show(paused) }
    fun hide() { view?.let { runCatching { windows.removeViewImmediate(it) } }; view = null; layout = null }
    private fun Float.finiteFraction() = if (isFinite()) coerceIn(0f, 1f) else .5f
    private fun fraction(value: Int, minimum: Int, maximum: Int) = if (maximum > minimum) ((value - minimum).toFloat() / (maximum - minimum)).coerceIn(0f, 1f) else .5f
}

internal class ClassOverlayView(
    context: Context,
    private val onToggle: () -> Unit,
    private val onDrag: (Int, Int) -> Unit,
    private val onDragEnd: () -> Unit
) : View(context) {
    var paused = true
        set(value) { field = value; contentDescription = if (value) "继续上课，点击继续，拖动可移动" else "暂停上课，点击暂停，拖动可移动" }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playPath = Path()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var pressedAt = 0L; private var dragging = false
    init { isClickable = true; isLongClickable = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; setLayerType(LAYER_TYPE_SOFTWARE, null) }
    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val r = width * .44f
        paint.color = Color.rgb(249, 248, 245); paint.setShadowLayer(width * .035f, 0f, width * .02f, 0x40000000)
        canvas.drawCircle(cx, cy, r, paint); paint.clearShadowLayer()
        paint.style = Paint.Style.STROKE; paint.strokeWidth = width * .045f; paint.color = if (paused) Color.rgb(164, 151, 132) else Color.rgb(55, 126, 107)
        canvas.drawCircle(cx, cy, r - width * .045f, paint); paint.style = Paint.Style.FILL
        if (paused) {
            playPath.rewind()
            playPath.moveTo(width * .43f, height * .32f); playPath.lineTo(width * .43f, height * .68f); playPath.lineTo(width * .68f, height * .5f); playPath.close()
            canvas.drawPath(playPath, paint)
        } else {
            canvas.drawRoundRect(width * .37f, height * .33f, width * .46f, height * .67f, 2f, 2f, paint)
            canvas.drawRoundRect(width * .55f, height * .33f, width * .64f, height * .67f, 2f, 2f, paint)
        }
    }
    override fun performClick(): Boolean { super.performClick(); onToggle(); return true }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; lastX = downX; lastY = downY; pressedAt = event.eventTime; dragging = false; isPressed = true; return true }
            MotionEvent.ACTION_MOVE -> {
                if (hypot((event.rawX - downX).toDouble(), (event.rawY - downY).toDouble()) > slop) dragging = true
                if (dragging) { onDrag((event.rawX - lastX).roundToInt(), (event.rawY - lastY).roundToInt()); lastX = event.rawX; lastY = event.rawY; isPressed = false }
                return true
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                if (dragging) onDragEnd()
                else if (event.eventTime - pressedAt < ViewConfiguration.getLongPressTimeout()) performClick()
                dragging = false; return true
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { isPressed = false; dragging = true; return true }
        }
        return true
    }
}
