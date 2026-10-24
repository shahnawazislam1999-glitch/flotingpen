package com.raj.flotingpen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * The full-screen writing surface inside the overlay window. It only draws pen strokes.
 *
 * Two input paths feed the same gesture code:
 *  - [onPenCaptured]: pen events delivered by the accessibility service (Android 14+, pen capture mode)
 *  - [onTouchEvent]: window mode - pen strokes are drawn, finger touches are re-played to the app below
 *
 * Pen gestures:
 *  - write / stroke-erase / lasso depending on the tool chosen with the smart pen buttons or the bubble menu
 *  - hold the nib still (time and action chosen in the app)
 *  - a quick pen tap without writing clicks the app below (can be switched off)
 */
class InkView(context: Context, private val ctrl: PadController) : View(context) {

    private val density = resources.displayMetrics.density
    val engine = InkEngine(this, ctrl.inkConfig, false)

    private var announced = false
    private var penSeenAt = 0L
    private val loc = IntArray(2)

    // ---- current pen gesture ----
    private var gTool = -1
    private var downX = 0f
    private var downY = 0f
    private var downSx = 0f
    private var downSy = 0f
    private var downT = 0L
    private var moved = false
    private var holdFired = false
    private val holdRun = Runnable {
        if (gTool == Pen.TOOL_WRITE && !moved) {
            holdFired = true
            engine.abort()
            ctrl.holdAction()
        }
    }

    // ---- window mode ----
    private var mode = M_NONE
    private var penId = -1
    private var rpId = -1
    private var rpT0 = 0L
    private var rpN = 0
    private val rpX = FloatArray(64)
    private val rpY = FloatArray(64)

    // ---- little status banner ----
    private var banner: String? = null
    private var bannerUntil = 0L
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * density
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE61A2133.toInt() }
    private val toolBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        engine.events = object : InkEvents {
            override fun onLassoResult(count: Int) {
                ctrl.vibrate(25)
                flash(if (count > 0) "Erased $count stroke" + (if (count > 1) "s" else "") else "Nothing selected", 1200L)
            }
        }
    }

    // ====================================================================================
    //  lifecycle / drawing
    // ====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            gTool = -1
            removeCallbacks(holdRun)
            engine.onSize(w, h)
        }
    }

    fun release() {
        removeCallbacks(holdRun)
        gTool = -1
        engine.release()
    }

    fun clearInk() {
        gTool = -1
        removeCallbacks(holdRun)
        engine.clearAll()
        invalidate()
    }

    fun undo(): Boolean {
        val r = engine.undo()
        invalidate()
        return r
    }

    fun onToolChanged(tool: Int) {
        gTool = -1
        removeCallbacks(holdRun)
        engine.abort()
        flash(
            when (tool) {
                Pen.TOOL_ERASE -> "Eraser - touch a stroke to erase it"
                Pen.TOOL_LASSO -> "Lasso - circle strokes to erase them"
                else -> "Writing"
            },
            1400L,
        )
    }

    /** On-screen status messages are switched off on purpose (no "pen selected" / "eraser selected" popups). */
    @Suppress("UNUSED_PARAMETER")
    fun flash(msg: String, ms: Long = 2200L) {
    }

    override fun onDraw(c: Canvas) {
        engine.draw(c)
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

    /** Small permanent label while the eraser or lasso is active, so you always know the mode. */
    private fun drawToolChip(c: Canvas) {
        val tool = ctrl.tool()
        if (tool == Pen.TOOL_WRITE) return
        val label = if (tool == Pen.TOOL_ERASE) "ERASER" else "LASSO"
        val w = textPaint.measureText(label)
        val padH = 10f * density
        val padV = 5f * density
        val top = 6f * density
        val cx = width / 2f
        toolBg.color = if (tool == Pen.TOOL_ERASE) 0xE6D92B2B.toInt() else 0xE6C98A00.toInt()
        rect.set(cx - w / 2f - padH, top, cx + w / 2f + padH, top + textPaint.textSize + 2f * padV)
        c.drawRoundRect(rect, 10f * density, 10f * density, toolBg)
        c.drawText(label, cx - w / 2f, top + padV + textPaint.textSize * 0.85f, textPaint)
    }

    // ====================================================================================
    //  gesture (shared by both input paths)
    // ====================================================================================

    private fun pressure(e: MotionEvent, i: Int): Float = e.getPressure(i).coerceIn(0f, 1f)

    private fun gestureBegin(x: Float, y: Float, p: Float, t: Long, toolType: Int, sx: Float, sy: Float) {
        var tool = ctrl.tool()
        if (toolType == MotionEvent.TOOL_TYPE_ERASER && tool == Pen.TOOL_WRITE) tool = Pen.TOOL_ERASE
        gTool = tool
        downX = x
        downY = y
        downSx = sx
        downSy = sy
        downT = t
        moved = false
        holdFired = false
        engine.begin(tool, x, y, p, t)
        removeCallbacks(holdRun)
        if (tool == Pen.TOOL_WRITE && ctrl.holdEnabled()) postDelayed(holdRun, ctrl.holdMs())
    }

    private fun gestureMove(x: Float, y: Float, p: Float, t: Long) {
        if (gTool < 0 || holdFired) return
        if (!moved && hypot(x - downX, y - downY) > HOLD_SLOP_DP * density) {
            moved = true
            removeCallbacks(holdRun)
        }
        engine.move(x, y, p, t)
    }

    private fun gestureEnd(x: Float, y: Float, p: Float, t: Long) {
        removeCallbacks(holdRun)
        if (gTool < 0) return
        val tool = gTool
        if (holdFired) {
            engine.abort()
        } else if (tool == Pen.TOOL_WRITE && !moved && t - downT < TAP_MS && ctrl.penTapClicks()) {
            engine.abort()
            ctrl.replay(floatArrayOf(downSx), floatArrayOf(downSy), 1, 40L)
        } else {
            engine.end(x, y, p, t)
        }
        gTool = -1
        // One erase / one lasso, then straight back to the pen (setting "auto switch", on by default).
        if ((tool == Pen.TOOL_ERASE || tool == Pen.TOOL_LASSO) && !holdFired && ctrl.autoSwitch() && ctrl.tool() != Pen.TOOL_WRITE) {
            ctrl.setTool(Pen.TOOL_WRITE)
        }
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
        var tt = e.getToolType(0)
        for (i in 0 until e.pointerCount) {
            val t = e.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                idx = i
                tt = t
                break
            }
        }
        announce(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (gTool < 0) {
                    gestureBegin(e.getX(idx) - ox, e.getY(idx) - oy, pressure(e, idx), e.eventTime, tt, e.getX(idx), e.getY(idx))
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // A MOVE is always pen-on-glass (hover has its own actions): start late if the DOWN was missed.
                if (gTool < 0) {
                    gestureBegin(e.getX(idx) - ox, e.getY(idx) - oy, pressure(e, idx), e.eventTime, tt, e.getX(idx), e.getY(idx))
                } else {
                    for (h in 0 until e.historySize) {
                        gestureMove(
                            e.getHistoricalX(idx, h) - ox, e.getHistoricalY(idx, h) - oy,
                            e.getHistoricalPressure(idx, h).coerceIn(0f, 1f), e.getHistoricalEventTime(h),
                        )
                    }
                    gestureMove(e.getX(idx) - ox, e.getY(idx) - oy, pressure(e, idx), e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (gTool >= 0) gestureEnd(e.getX(idx) - ox, e.getY(idx) - oy, pressure(e, idx), e.eventTime)
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
                gTool >= 0 || SystemClock.uptimeMillis() - penSeenAt < PALM_MS -> M_PALM
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
        getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        val idx = e.findPointerIndex(penId)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                gestureBegin(e.getX(0), e.getY(0), pressure(e, 0), e.eventTime, e.getToolType(0), e.getX(0) + ox, e.getY(0) + oy)
            MotionEvent.ACTION_MOVE -> if (idx >= 0) {
                if (gTool < 0) {
                    gestureBegin(e.getX(idx), e.getY(idx), pressure(e, idx), e.eventTime, e.getToolType(idx), e.getX(idx) + ox, e.getY(idx) + oy)
                } else {
                    for (h in 0 until e.historySize) {
                        gestureMove(
                            e.getHistoricalX(idx, h), e.getHistoricalY(idx, h),
                            e.getHistoricalPressure(idx, h).coerceIn(0f, 1f), e.getHistoricalEventTime(h),
                        )
                    }
                    gestureMove(e.getX(idx), e.getY(idx), pressure(e, idx), e.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (idx >= 0) gestureEnd(e.getX(idx), e.getY(idx), pressure(e, idx), e.eventTime)
                else gestureEnd(downX, downY, 0.5f, e.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == penId) {
                gestureEnd(e.getX(e.actionIndex), e.getY(e.actionIndex), pressure(e, e.actionIndex), e.eventTime)
            }
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
        private const val HOLD_SLOP_DP = 10f
        private const val TAP_MS = 260L
    }
}
