package com.raj.flotingpen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The full-screen writing surface inside the overlay window. It only draws pen strokes.
 *
 * Two input paths feed the same drawing code:
 *  - [onPenCaptured]: pen events delivered by the accessibility service (Android 14+, pen capture mode)
 *  - [onTouchEvent]: window mode - pen strokes are drawn, finger touches are re-played to the app below
 */
class InkView(context: Context, private val ctrl: PadController) : View(context) {

    private val density = resources.displayMetrics.density

    // ---- ink (one screen-sized bitmap, only allocated while there is ink) ----
    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    // ---- live stroke ----
    private var drawing = false
    private var lastX = 0f
    private var lastY = 0f
    private var midX = 0f
    private var midY = 0f
    private var lastW = 1f
    private var announced = false
    private var penSeenAt = 0L
    private val loc = IntArray(2)

    // ---- window mode ----
    private var mode = M_NONE
    private var penId = -1
    private var rpId = -1
    private var rpT0 = 0L
    private var rpN = 0
    private val rpX = FloatArray(64)
    private val rpY = FloatArray(64)

    // ---- little status banner (proves the layer is visible and the pen is seen) ----
    private var banner: String? = null
    private var bannerUntil = 0L
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61A2133.toInt() }
    private val rect = RectF()

    // ====================================================================================
    //  lifecycle / drawing
    // ====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            drawing = false
            releaseBitmap() // rotation: the ink does not fit any more
        }
    }

    fun release() {
        drawing = false
        releaseBitmap()
    }

    private fun releaseBitmap() {
        bmp?.recycle()
        bmp = null
        bc = null
    }

    fun clearInk() {
        drawing = false
        releaseBitmap()
        invalidate()
    }

    fun flash(msg: String, ms: Long = 2200L) {
        banner = msg
        bannerUntil = SystemClock.uptimeMillis() + ms
        invalidate()
        postInvalidateDelayed(ms + 60L)
    }

    override fun onDraw(c: Canvas) {
        bmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        val t = banner ?: return
        if (SystemClock.uptimeMillis() >= bannerUntil) {
            banner = null
            return
        }
        val w = textPaint.measureText(t)
        val padH = 14f * density
        val padV = 9f * density
        val top = 36f * density
        val cx = width / 2f
        rect.set(cx - w / 2f - padH, top, cx + w / 2f + padH, top + textPaint.textSize + 2f * padV)
        c.drawRoundRect(rect, 12f * density, 12f * density, bgPaint)
        c.drawText(t, cx - w / 2f, top + padV + textPaint.textSize * 0.85f, textPaint)
    }

    private fun canvas(): Canvas? {
        bc?.let { return it }
        if (width <= 0 || height <= 0) return null
        return try {
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bmp = b
            Canvas(b).also { bc = it }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    // ====================================================================================
    //  stroke drawing
    // ====================================================================================

    private fun widthFor(pressure: Float): Float {
        val p = if (pressure > 0f) min(1f, pressure) else 0.5f
        return max(1.2f * density, ctrl.sizePx() * (0.45f + 0.9f * p))
    }

    private fun begin(x: Float, y: Float, pressure: Float) {
        val c = canvas() ?: return
        drawing = true
        lastX = x
        lastY = y
        midX = x
        midY = y
        lastW = widthFor(pressure)
        paint.color = ctrl.color()
        paint.strokeWidth = lastW
        c.drawPoint(x, y, paint)
        val r = lastW + 2f
        invalidate((x - r).toInt(), (y - r).toInt(), (x + r).toInt() + 1, (y + r).toInt() + 1)
    }

    private fun add(x: Float, y: Float, pressure: Float) {
        if (!drawing) return
        if (abs(x - lastX) + abs(y - lastY) < 0.7f * density) return
        val c = canvas() ?: return
        lastW = lastW * 0.6f + widthFor(pressure) * 0.4f
        paint.strokeWidth = lastW
        val mx = (lastX + x) / 2f
        val my = (lastY + y) / 2f
        path.reset()
        path.moveTo(midX, midY)
        path.quadTo(lastX, lastY, mx, my)
        c.drawPath(path, paint)
        val r = lastW + 3f
        val l = min(min(midX, lastX), mx) - r
        val t = min(min(midY, lastY), my) - r
        val rr = max(max(midX, lastX), mx) + r
        val b = max(max(midY, lastY), my) + r
        invalidate(l.toInt(), t.toInt(), rr.toInt() + 1, b.toInt() + 1)
        lastX = x
        lastY = y
        midX = mx
        midY = my
    }

    private fun end() {
        if (!drawing) return
        drawing = false
        val c = bc ?: return
        paint.strokeWidth = lastW
        c.drawLine(midX, midY, lastX, lastY, paint)
        val r = lastW + 3f
        invalidate(
            (min(midX, lastX) - r).toInt(), (min(midY, lastY) - r).toInt(),
            (max(midX, lastX) + r).toInt() + 1, (max(midY, lastY) + r).toInt() + 1,
        )
    }

    private fun addWithHistory(e: MotionEvent, idx: Int, ox: Float, oy: Float) {
        for (h in 0 until e.historySize) {
            add(e.getHistoricalX(idx, h) - ox, e.getHistoricalY(idx, h) - oy, e.getHistoricalPressure(idx, h))
        }
        add(e.getX(idx) - ox, e.getY(idx) - oy, e.getPressure(idx))
    }

    private fun toolName(t: Int) = when (t) {
        MotionEvent.TOOL_TYPE_STYLUS -> "stylus"
        MotionEvent.TOOL_TYPE_ERASER -> "eraser"
        MotionEvent.TOOL_TYPE_FINGER -> "finger"
        MotionEvent.TOOL_TYPE_MOUSE -> "mouse"
        else -> "unknown"
    }

    /** First time the pen is seen after the pad was switched on: show what Android reports. */
    private fun announce(e: MotionEvent) {
        penSeenAt = SystemClock.uptimeMillis()
        if (announced) return
        announced = true
        flash("Pen seen: " + toolName(e.getToolType(0)) + ", source 0x" + Integer.toHexString(e.source), 2500L)
    }

    // ====================================================================================
    //  input path 1: pen events delivered by the accessibility service (pen capture mode)
    // ====================================================================================

    fun onPenCaptured(e: MotionEvent) {
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        var idx = 0
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                idx = i
                break
            }
        }
        announce(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!drawing) begin(e.getX(idx) - ox, e.getY(idx) - oy, e.getPressure(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                // A MOVE is always pen-on-glass (hover has its own actions): start late if the DOWN was missed.
                if (!drawing) begin(e.getX(idx) - ox, e.getY(idx) - oy, e.getPressure(idx))
                else addWithHistory(e, idx, ox, oy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawing) {
                    add(e.getX(idx) - ox, e.getY(idx) - oy, e.getPressure(idx))
                    end()
                }
            }
            else -> {} // hover and button events
        }
    }

    // ====================================================================================
    //  input path 2: window mode
    // ====================================================================================

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) != MotionEvent.TOOL_TYPE_MOUSE) announce(e)
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!ctrl.windowMode()) return false
        val a = e.actionMasked
        if (a == MotionEvent.ACTION_DOWN) {
            mode = when {
                PenDetect.isPenPointer(e, 0) -> M_PEN
                drawing || SystemClock.uptimeMillis() - penSeenAt < PALM_MS -> M_PALM
                else -> M_FINGER
            }
            when (mode) {
                M_PEN -> {
                    announce(e)
                    penId = e.getPointerId(0)
                }
                M_FINGER -> {
                    rpId = e.getPointerId(0)
                    rpT0 = e.eventTime
                    rpN = 0
                    rpPush(e.getX(0), e.getY(0))
                }
            }
        }
        when (mode) {
            M_PEN -> penTouch(e)
            M_FINGER -> fingerReplay(e)
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) mode = M_NONE
        return true
    }

    private fun penTouch(e: MotionEvent) {
        penSeenAt = SystemClock.uptimeMillis()
        val idx = e.findPointerIndex(penId)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(e.getX(0), e.getY(0), e.getPressure(0))
            MotionEvent.ACTION_MOVE -> if (idx >= 0) {
                if (!drawing) begin(e.getX(idx), e.getY(idx), e.getPressure(idx)) else addWithHistory(e, idx, 0f, 0f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drawing && idx >= 0) add(e.getX(idx), e.getY(idx), e.getPressure(idx))
                end()
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == penId) end()
        }
    }

    private fun fingerReplay(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(rpId)
                if (i >= 0) rpPush(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP -> {
                val i = e.findPointerIndex(rpId)
                if (i >= 0) rpPush(e.getX(i), e.getY(i))
                if (rpN > 0) ctrl.replay(rpX, rpY, rpN, e.eventTime - rpT0)
            }
        }
    }

    private fun rpPush(x: Float, y: Float) {
        if (rpN < rpX.size) {
            rpX[rpN] = x
            rpY[rpN] = y
            rpN++
        } else {
            rpX[rpX.size - 1] = x
            rpY[rpY.size - 1] = y
        }
    }

    companion object {
        private const val M_NONE = 0
        private const val M_PEN = 1
        private const val M_FINGER = 2
        private const val M_PALM = 3
        private const val PALM_MS = 350L
    }
}
