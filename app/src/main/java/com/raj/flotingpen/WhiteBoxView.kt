package com.raj.flotingpen

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/**
 * The adjustable white box. It lives in its own small overlay window (so touches outside it still reach the
 * apps) that sits UNDER the ink window: the pen writes above it. Drag it with a finger to move it,
 * drag the corner handle to resize it. Position and size are remembered.
 */
@SuppressLint("ViewConstructor")
class WhiteBoxView(
    ctx: Context,
    private val wm: WindowManager,
    private val prefs: SharedPreferences,
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val screenW = resources.displayMetrics.widthPixels
    private val screenH = resources.displayMetrics.heightPixels
    private val minW = (140f * d).toInt()
    private val minH = (100f * d).toInt()
    private val handle = (44f * d)

    private var bx: Int
    private var by: Int
    private var bw: Int
    private var bh: Int
    private lateinit var lp: WindowManager.LayoutParams

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x33000000
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
    }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55607090 }
    private val rect = RectF()
    private val tri = Path()

    init {
        bw = prefs.getInt(Prefs.BOX_W, (screenW * 0.7f).toInt()).coerceIn(minW, screenW)
        bh = prefs.getInt(Prefs.BOX_H, (screenH * 0.45f).toInt()).coerceIn(minH, screenH)
        bx = prefs.getInt(Prefs.BOX_X, (screenW - bw) / 2).coerceIn(0, max(0, screenW - bw))
        by = prefs.getInt(Prefs.BOX_Y, (screenH - bh) / 3).coerceIn(0, max(0, screenH - bh))
    }

    fun layoutParams(): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            bw, bh,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = bx
        p.y = by
        lp = p
        return p
    }

    override fun onDraw(c: Canvas) {
        val r = 10f * d
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, r, r, fill)
        rect.inset(0.5f * d, 0.5f * d)
        c.drawRoundRect(rect, r, r, edge)
        // resize grip, bottom-right
        val g = 22f * d
        val w = width.toFloat()
        val h = height.toFloat()
        tri.reset()
        tri.moveTo(w - 6f * d, h - g)
        tri.lineTo(w - 6f * d, h - 6f * d)
        tri.lineTo(w - g, h - 6f * d)
        tri.close()
        c.drawPath(tri, grip)
    }

    private var mode = 0 // 0 none, 1 move, 2 resize
    private var startRx = 0f
    private var startRy = 0f
    private var startX = 0
    private var startY = 0
    private var startW = 0
    private var startH = 0

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        // The pen never moves the box - it writes on it.
        if (PenDetect.isPenPointer(e, 0)) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = if (e.x > width - handle && e.y > height - handle) 2 else 1
                startRx = e.rawX
                startRy = e.rawY
                startX = bx
                startY = by
                startW = bw
                startH = bh
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - startRx).toInt()
                val dy = (e.rawY - startRy).toInt()
                if (mode == 1) {
                    bx = (startX + dx).coerceIn(0, max(0, screenW - bw))
                    by = (startY + dy).coerceIn(0, max(0, screenH - bh))
                } else if (mode == 2) {
                    bw = (startW + dx).coerceIn(minW, max(minW, screenW - bx))
                    bh = (startH + dy).coerceIn(minH, max(minH, screenH - by))
                }
                lp.x = bx
                lp.y = by
                lp.width = bw
                lp.height = bh
                try {
                    wm.updateViewLayout(this, lp)
                } catch (_: Throwable) {
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode != 0) {
                    prefs.edit()
                        .putInt(Prefs.BOX_X, bx).putInt(Prefs.BOX_Y, by)
                        .putInt(Prefs.BOX_W, bw).putInt(Prefs.BOX_H, bh)
                        .apply()
                }
                mode = 0
            }
        }
        return true
    }
}
