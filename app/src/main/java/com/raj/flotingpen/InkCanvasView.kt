package com.raj.flotingpen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * The practice / scratch pad on the app's home screen. Same engine and same settings as the overlay,
 * so every pen mode can be tried and tuned here: pen writes, finger erases whole strokes
 * (or writes too when "finger writes" is on).
 */
class InkCanvasView(context: Context) : View(context) {

    val engine = InkEngine(this, PrefsInkConfig(context), true)
    var tool = Pen.TOOL_WRITE
    var fingerWrites = false
    var paper = true
        set(v) {
            field = v
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val linePaint = Paint().apply {
        color = 0x2A3D6BCF
        strokeWidth = 1f * density
    }
    private var activeId = -1

    init {
        engine.events = object : InkEvents {
            override fun onLassoResult(count: Int) {}
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.onSize(w, h)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        engine.release()
    }

    override fun onDraw(c: Canvas) {
        if (paper) {
            c.drawColor(0xFFFBFAF4.toInt())
            val step = 32f * density
            var y = step
            while (y < height) {
                c.drawLine(0f, y, width.toFloat(), y, linePaint)
                y += step
            }
        } else {
            c.drawColor(0xFF0B0B0B.toInt())
        }
        engine.draw(c)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeId = e.getPointerId(0)
                val pen = PenDetect.isPenPointer(e, 0)
                var t = if (pen || fingerWrites) tool else Pen.TOOL_ERASE
                if (e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) t = Pen.TOOL_ERASE
                engine.begin(t, e.getX(0), e.getY(0), e.getPressure(0).coerceIn(0f, 1f), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(activeId)
                if (i >= 0) {
                    for (h in 0 until e.historySize) {
                        engine.move(
                            e.getHistoricalX(i, h), e.getHistoricalY(i, h),
                            e.getHistoricalPressure(i, h).coerceIn(0f, 1f), e.getHistoricalEventTime(h),
                        )
                    }
                    engine.move(e.getX(i), e.getY(i), e.getPressure(i).coerceIn(0f, 1f), e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val i = e.findPointerIndex(activeId)
                val k = if (i >= 0) i else 0
                engine.end(e.getX(k), e.getY(k), e.getPressure(k).coerceIn(0f, 1f), e.eventTime)
                activeId = -1
            }
        }
        return true
    }
}
