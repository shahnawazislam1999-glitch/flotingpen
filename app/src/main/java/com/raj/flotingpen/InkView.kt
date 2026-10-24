package com.raj.flotingpen

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * The full-screen writing surface that lives inside the overlay window.
 *
 *  - Pen (stylus) writes.
 *  - While the pen is active, fingers become gesture tools (erase, colour, clear, pan, box, annotation).
 *  - When the pen is idle the window stops catching touches, so fingers simply use the apps below.
 */
class InkView(context: Context, private val ctrl: PadController) : View(context) {

    private val density = resources.displayMetrics.density
    val engine = InkEngine(density)

    // ---- canvas pan ("floating" canvas) ----
    private var panX = 0f
    private var panY = 0f
    private var panning = false
    private var panPrevX = 0f
    private var panPrevY = 0f
    private var recenterAnim: ValueAnimator? = null

    // ---- live stroke ----
    private val annots = ArrayList<Stroke>()
    private var live: InkEngine.Live? = null
    private var liveOwner = NONE

    // ---- pointers ----
    private class Finger(val sx: Float, val sy: Float, val t0: Long) {
        var x = sx
        var y = sy
        var ox = sx
        var oy = sy
    }

    private val fingers = LinkedHashMap<Int, Finger>()
    private var stylusId = NONE
    private var scrubId = NONE         // pen eraser end (tool type eraser)
    private var scrubbing = false      // finger scrub in progress
    private var scrubX = 0f
    private var scrubY = 0f
    private var multi = false
    private var maxFingers = 0
    private var holdFired = false
    private var twoStartT = 0L
    private var twoMoved = false
    private var boxFired = false
    private var fingerInkId = NONE     // Finger mode: the finger that is writing

    private val slop = 12f * density
    private val eraseR = 20f * density
    private val circle = CircleRegion()

    private val hold1 = Runnable {
        if (fingers.size == 1 && !multi && !scrubbing && handleId == NONE) {
            holdFired = true
            ctrl.toggleAnnot()
        }
    }
    private val hold2 = Runnable {
        if (fingers.size == 2 && multi && !twoMoved) {
            boxFired = true
            toggleBox()
        }
    }

    // ---- lasso ----
    private var lassoArmed = false
    private var lassoPid = NONE
    private var lx = FloatArray(128)
    private var ly = FloatArray(128)
    private var ln = 0
    private val lassoTimeout = Runnable { disarmLasso() }

    // ---- white writing box ----
    private class Box(var cx: Float, var cy: Float, var w: Float, var h: Float, var angle: Float)

    private var box: Box? = null
    private var handleId = NONE
    private var handleType = 0
    private var handleX = 0f
    private var handleY = 0f

    // ---- replay of finger gestures to the app underneath ----
    private var replayMode = false
    private var rpId = NONE
    private var rpT0 = 0L
    private var rpN = 0
    private val rpX = FloatArray(64)
    private val rpY = FloatArray(64)

    // ---- paints ----
    private val boxFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val boxEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF9AA3B5.toInt()
        strokeWidth = 1.5f * density
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2f * density
    }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2D6EE0.toInt()
        strokeWidth = 1.5f * density
    }
    private val lassoStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE7B94F.toInt()
        strokeWidth = 2f * density
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val lassoFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24E7B94F }
    private val lassoPath = Path()
    private val tmpRect = RectF()

    val busy: Boolean get() = lassoArmed

    // ====================================================================================
    //  lifecycle
    // ====================================================================================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        engine.resize(w, h, panX, panY)
    }

    override fun onDetachedFromWindow() {
        cancelTimers()
        cancelRecenter()
        super.onDetachedFromWindow()
    }

    /** Pad switched off or service stopped: drop everything from RAM. */
    fun release() {
        cancelTimers()
        cancelRecenter()
        engine.clearAll()
        annots.clear()
        live = null
        fingers.clear()
        box = null
    }

    private fun cancelRecenter() {
        val a = recenterAnim
        recenterAnim = null
        a?.cancel()
    }

    private fun cancelTimers() {
        removeCallbacks(hold1)
        removeCallbacks(hold2)
        removeCallbacks(lassoTimeout)
    }

    // ====================================================================================
    //  drawing
    // ====================================================================================

    override fun onDraw(c: Canvas) {
        drawBox(c)
        engine.draw(c, panX, panY)

        if (annots.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            var animating = false
            c.save()
            c.translate(panX, panY)
            val it = annots.iterator()
            while (it.hasNext()) {
                val s = it.next()
                var a = 1f
                if (s.endedAt > 0L) {
                    val age = now - s.endedAt
                    if (age > ANNOT_LIFE_MS) {
                        a = 1f - (age - ANNOT_LIFE_MS).toFloat() / ANNOT_FADE_MS
                        if (a <= 0f) {
                            it.remove()
                            continue
                        }
                    }
                    animating = true
                }
                engine.drawAnnot(c, s, a)
            }
            c.restore()
            if (animating) postInvalidateOnAnimation()
        }

        if (lassoPid != NONE && ln > 1) {
            lassoPath.reset()
            lassoPath.moveTo(lx[0], ly[0])
            for (i in 1 until ln) lassoPath.lineTo(lx[i], ly[i])
            lassoPath.close()
            c.drawPath(lassoPath, lassoFill)
            c.drawPath(lassoPath, lassoStroke)
        }

        drawHandles(c)
    }

    private fun drawBox(c: Canvas) {
        val b = box ?: return
        c.save()
        c.translate(panX + b.cx, panY + b.cy)
        c.rotate(b.angle)
        tmpRect.set(-b.w / 2f, -b.h / 2f, b.w / 2f, b.h / 2f)
        val r = 6f * density
        c.drawRoundRect(tmpRect, r, r, boxFill)
        c.drawRoundRect(tmpRect, r, r, boxEdge)
        c.restore()
    }

    private fun drawHandles(c: Canvas) {
        val b = box ?: return
        val rot = handlePos(b, HANDLE_ROTATE)
        val top = handlePos(b, HANDLE_TOP)
        c.drawLine(top[0], top[1], rot[0], rot[1], handleLine)
        drawHandle(c, rot, 0xFF2D6EE0.toInt())
        drawHandle(c, handlePos(b, HANDLE_RESIZE), 0xFFE7A400.toInt())
        drawHandle(c, handlePos(b, HANDLE_MOVE), 0xFF5C6B8A.toInt())
    }

    private fun drawHandle(c: Canvas, p: FloatArray, color: Int) {
        handleFill.color = color
        c.drawCircle(p[0], p[1], 11f * density, handleFill)
        c.drawCircle(p[0], p[1], 11f * density, handleRing)
    }

    /** Screen position of a box handle. */
    private fun handlePos(b: Box, type: Int): FloatArray {
        val hx: Float
        val hy: Float
        when (type) {
            HANDLE_ROTATE -> { hx = 0f; hy = -b.h / 2f - 34f * density }
            HANDLE_TOP -> { hx = 0f; hy = -b.h / 2f }
            HANDLE_RESIZE -> { hx = b.w / 2f; hy = b.h / 2f }
            else -> { hx = -b.w / 2f; hy = b.h / 2f }
        }
        val a = Math.toRadians(b.angle.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        return floatArrayOf(panX + b.cx + hx * ca - hy * sa, panY + b.cy + hx * sa + hy * ca)
    }

    // ====================================================================================
    //  touch entry points
    // ====================================================================================

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER

    override fun onHoverEvent(e: MotionEvent): Boolean {
        if (isPen(e.getToolType(0))) ctrl.markActivity()
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val action = e.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            // Compatibility mode: the window always catches touches, so a finger that arrives while
            // the pen is idle is replayed to the app below instead of being used as a gesture.
            replayMode = ctrl.compat() && !ctrl.fingerMode() &&
                !isPen(e.getToolType(0)) && !ctrl.sessionActive()
        }
        if (replayMode) {
            handleReplay(e)
            return true
        }
        ctrl.markActivity()
        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> down(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e, e.actionIndex)
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    // ---------------------------------------------------------------- finger replay

    private fun handleReplay(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                rpId = e.getPointerId(0)
                rpT0 = e.eventTime
                rpN = 0
                rpPush(e.getX(0), e.getY(0))
            }
            MotionEvent.ACTION_MOVE -> {
                val i = e.findPointerIndex(rpId)
                if (i >= 0) rpPush(e.getX(i), e.getY(i))
            }
            MotionEvent.ACTION_UP -> {
                val i = e.findPointerIndex(rpId)
                if (i >= 0) rpPush(e.getX(i), e.getY(i))
                replayMode = false
                if (rpN > 0) ctrl.replay(rpX, rpY, rpN, e.eventTime - rpT0)
            }
            MotionEvent.ACTION_CANCEL -> replayMode = false
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

    // ---------------------------------------------------------------- pointer down / move / up

    private fun down(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val tool = e.getToolType(i)
        val x = e.getX(i)
        val y = e.getY(i)

        if (tool == MotionEvent.TOOL_TYPE_ERASER) {
            scrubId = id
            scrubX = x
            scrubY = y
            eraseAt(x, y)
            return
        }
        if (lassoArmed && lassoPid == NONE) {
            startLasso(id, x, y)
            return
        }
        if (tool == MotionEvent.TOOL_TYPE_STYLUS) {
            cancelFingerGestures()
            stylusId = id
            liveOwner = id
            beginInk(x, y, e.getPressure(i), e.eventTime)
            return
        }

        // ---- finger ----
        if (stylusId != NONE || liveOwner == OBSERVED) return // palm rejection while the pen is writing
        fingers[id] = Finger(x, y, e.eventTime)
        val n = fingers.size
        if (n == 1) {
            resetGesture()
            if (hitHandle(x, y)) {
                handleId = id
                handleX = x
                handleY = y
                return
            }
            if (ctrl.fingerMode()) {
                fingerInkId = id
                liveOwner = id
                beginInk(x, y, 0.5f, e.eventTime)
            } else {
                holdFired = false
                postDelayed(hold1, HOLD_MS)
            }
        } else {
            if (handleId != NONE) return
            removeCallbacks(hold1)
            scrubbing = false
            if (fingerInkId != NONE) {
                // A short accidental dab before the second finger landed: undo it.
                val l = live
                if (l != null && l.stroke.n < 12) discardInk() else endInk()
                fingerInkId = NONE
                liveOwner = NONE
            }
            multi = true
            if (n > maxFingers) maxFingers = n
            if (n == 2) startTwoFinger(e.eventTime) else removeCallbacks(hold2)
        }
    }

    private fun move(e: MotionEvent) {
        for (i in 0 until e.pointerCount) {
            val id = e.getPointerId(i)
            val x = e.getX(i)
            val y = e.getY(i)
            if (id == lassoPid) {
                lassoAdd(x, y)
            } else if (id == stylusId) {
                moveInk(e, i)
            } else if (id == scrubId) {
                eraseSeg(scrubX, scrubY, x, y)
                scrubX = x
                scrubY = y
            } else {
                val f = fingers[id]
                if (f != null) {
                    f.x = x
                    f.y = y
                    fingerMove(e, i, id, f)
                }
            }
        }
        if (fingers.size == 2 && multi && handleId == NONE) twoFingerMove()
    }

    private fun up(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        if (id == lassoPid) {
            finishLasso()
            return
        }
        if (id == stylusId) {
            endInk()
            stylusId = NONE
            liveOwner = NONE
            return
        }
        if (id == scrubId) {
            scrubId = NONE
            return
        }
        val f = fingers.remove(id) ?: return
        if (id == handleId) {
            handleId = NONE
            return
        }
        if (id == fingerInkId) {
            endInk()
            fingerInkId = NONE
            liveOwner = NONE
            return
        }
        val n = fingers.size
        val now = e.eventTime
        if (multi) {
            if (twoStartT > 0L && maxFingers == 2 && !twoMoved && !boxFired &&
                now - twoStartT < TAP_MS && n == 1
            ) {
                // Two-finger tap: wipe everything.
                removeCallbacks(hold2)
                twoStartT = 0L
                clearAll()
            } else if (panning && n < 2) {
                endPan()
            }
        } else if (n == 0 && !scrubbing && !holdFired && now - f.t0 < TAP_MS) {
            removeCallbacks(hold1)
            ctrl.flipColor()
        }
        if (n == 0) {
            cancelTimers()
            resetGesture()
        }
    }

    private fun cancelAll() {
        if (live != null) endInk()
        stylusId = NONE
        scrubId = NONE
        fingerInkId = NONE
        liveOwner = NONE
        handleId = NONE
        fingers.clear()
        if (panning) endPan()
        if (lassoPid != NONE) {
            lassoPid = NONE
            ln = 0
            invalidate()
        }
        cancelTimers()
        resetGesture()
    }

    private fun cancelFingerGestures() {
        fingers.clear()
        removeCallbacks(hold1)
        removeCallbacks(hold2)
        if (panning) endPan()
        if (fingerInkId != NONE) {
            fingerInkId = NONE
            if (live != null) endInk()
        }
        handleId = NONE
        resetGesture()
    }

    private fun resetGesture() {
        multi = false
        maxFingers = fingers.size
        holdFired = false
        scrubbing = false
        twoStartT = 0L
        twoMoved = false
        boxFired = false
    }

    // ---------------------------------------------------------------- single finger

    private fun fingerMove(e: MotionEvent, i: Int, id: Int, f: Finger) {
        if (id == handleId) {
            dragHandle(f.x, f.y)
            return
        }
        if (id == fingerInkId) {
            moveInk(e, i)
            return
        }
        if (fingers.size == 1 && !multi) {
            if (!scrubbing && hypot(f.x - f.sx, f.y - f.sy) > slop) {
                scrubbing = true
                removeCallbacks(hold1)
                scrubX = f.sx
                scrubY = f.sy
            }
            if (scrubbing) {
                eraseSeg(scrubX, scrubY, f.x, f.y)
                scrubX = f.x
                scrubY = f.y
            }
        }
    }

    private fun eraseSeg(x0: Float, y0: Float, x1: Float, y1: Float) {
        val steps = max(1, ceil(hypot(x1 - x0, y1 - y0) / (eraseR * 0.5f)).toInt())
        for (k in 1..steps) {
            val t = k.toFloat() / steps
            eraseAt(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
    }

    private fun eraseAt(x: Float, y: Float) {
        if (engine.erase(circle.set(x - panX, y - panY, eraseR))) invalidate()
    }

    // ---------------------------------------------------------------- two fingers

    private fun startTwoFinger(t: Long) {
        twoStartT = t
        twoMoved = false
        boxFired = false
        panning = false
        var cx = 0f
        var cy = 0f
        for (f in fingers.values) {
            f.ox = f.x
            f.oy = f.y
            cx += f.x
            cy += f.y
        }
        panPrevX = cx / fingers.size
        panPrevY = cy / fingers.size
        removeCallbacks(hold2)
        postDelayed(hold2, HOLD_MS)
    }

    private fun twoFingerMove() {
        val it = fingers.values.iterator()
        val a = it.next()
        val b = it.next()
        val cx = (a.x + b.x) / 2f
        val cy = (a.y + b.y) / 2f
        if (!twoMoved) {
            if (hypot(a.x - a.ox, a.y - a.oy) > slop || hypot(b.x - b.ox, b.y - b.oy) > slop) {
                twoMoved = true
                panning = true
                removeCallbacks(hold2)
                cancelRecenter()
                panPrevX = cx
                panPrevY = cy
            }
        }
        if (twoMoved && panning) {
            panX += cx - panPrevX
            panY += cy - panPrevY
            panPrevX = cx
            panPrevY = cy
            invalidate()
        }
    }

    private fun endPan() {
        panning = false
        engine.rebuild(panX, panY)
        invalidate()
    }

    // ---------------------------------------------------------------- ink

    private fun beginInk(x: Float, y: Float, pressure: Float, t: Long) {
        val annot = ctrl.annotOn()
        val l = engine.begin(
            x - panX, y - panY, pressure, t,
            ctrl.color(), ctrl.penType(), ctrl.sizePx(), annot, panX, panY,
        )
        live = l
        if (annot) annots.add(l.stroke)
        invalidate()
    }

    private fun moveInk(e: MotionEvent, idx: Int) {
        val l = live ?: return
        var changed = false
        for (h in 0 until e.historySize) {
            changed = engine.move(
                l, e.getHistoricalX(idx, h) - panX, e.getHistoricalY(idx, h) - panY,
                e.getHistoricalPressure(idx, h), e.getHistoricalEventTime(h),
            ) || changed
        }
        changed = engine.move(l, e.getX(idx) - panX, e.getY(idx) - panY, e.getPressure(idx), e.eventTime) || changed
        if (changed) invalidate()
    }

    private fun endInk() {
        val l = live ?: return
        live = null
        engine.end(l)
        invalidate()
    }

    private fun discardInk() {
        val l = live ?: return
        live = null
        annots.remove(l.stroke)
        engine.discard(l.stroke)
        invalidate()
    }

    // ---------------------------------------------------------------- pen observed by the service

    private val observedLoc = IntArray(2)

    /**
     * The overlay does not catch touches while the pen is idle, so the accessibility service
     * forwards the pen's own events here. That lets the very first stroke appear even before
     * the window has switched to "touchable".
     */
    fun observedStylus(e: MotionEvent) {
        getLocationOnScreen(observedLoc)
        val ox = observedLoc[0].toFloat()
        val oy = observedLoc[1].toFloat()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (ctrl.isCapturing() || live != null || lassoArmed) return
                ctrl.markActivity()
                cancelFingerGestures()
                liveOwner = OBSERVED
                beginInk(e.getX(0) - ox, e.getY(0) - oy, e.getPressure(0), e.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                if (liveOwner != OBSERVED) return
                val l = live ?: return
                var changed = false
                for (h in 0 until e.historySize) {
                    changed = engine.move(
                        l, e.getHistoricalX(0, h) - ox - panX, e.getHistoricalY(0, h) - oy - panY,
                        e.getHistoricalPressure(0, h), e.getHistoricalEventTime(h),
                    ) || changed
                }
                changed = engine.move(l, e.getX(0) - ox - panX, e.getY(0) - oy - panY, e.getPressure(0), e.eventTime) || changed
                ctrl.markActivity()
                if (changed) invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (liveOwner != OBSERVED) return
                endInk()
                liveOwner = NONE
                ctrl.markActivity()
            }
        }
    }

    // ====================================================================================
    //  actions
    // ====================================================================================

    /** Two-finger tap: instantly clear ink, annotations and the white box, and free the memory. */
    fun clearAll() {
        cancelRecenter()
        panning = false
        live = null
        liveOwner = NONE
        engine.clearAll()
        annots.clear()
        box = null
        lassoPid = NONE
        ln = 0
        ctrl.vibrate(25)
        invalidate()
    }

    /** Pen "up" button: slide the canvas back to its normal position. */
    fun recenter() {
        if (panX == 0f && panY == 0f) {
            ctrl.vibrate(15)
            return
        }
        cancelRecenter()
        val sx = panX
        val sy = panY
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 220
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            val k = 1f - it.animatedFraction
            panX = sx * k
            panY = sy * k
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (recenterAnim === a) {
                    panX = 0f
                    panY = 0f
                    recenterAnim = null
                    engine.rebuild(0f, 0f)
                    invalidate()
                }
            }
        })
        recenterAnim = a
        a.start()
        ctrl.vibrate(20)
    }

    // ---------------------------------------------------------------- lasso

    /** Pen "down" button: arm / disarm the lasso eraser. */
    fun toggleLasso() {
        if (lassoArmed) {
            disarmLasso()
            ctrl.vibrate(25)
        } else {
            lassoArmed = true
            removeCallbacks(lassoTimeout)
            postDelayed(lassoTimeout, 12000)
            ctrl.markActivity()
            ctrl.vibrate(45)
        }
    }

    private fun disarmLasso() {
        lassoArmed = false
        lassoPid = NONE
        ln = 0
        removeCallbacks(lassoTimeout)
        invalidate()
    }

    private fun startLasso(id: Int, x: Float, y: Float) {
        removeCallbacks(lassoTimeout)
        lassoPid = id
        ln = 0
        lassoAdd(x, y)
    }

    private fun lassoAdd(x: Float, y: Float) {
        if (ln > 0 && abs(x - lx[ln - 1]) + abs(y - ly[ln - 1]) < 2f * density) return
        if (ln == lx.size) {
            lx = lx.copyOf(ln * 2)
            ly = ly.copyOf(ln * 2)
        }
        lx[ln] = x
        ly[ln] = y
        ln++
        invalidate()
    }

    private fun finishLasso() {
        val n = ln
        lassoPid = NONE
        if (n < 5) {
            ln = 0
            postDelayed(lassoTimeout, 12000)
            invalidate()
            return
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val wx = FloatArray(n)
        val wy = FloatArray(n)
        for (i in 0 until n) {
            wx[i] = lx[i] - panX
            wy[i] = ly[i] - panY
            minX = minOf(minX, lx[i]); maxX = maxOf(maxX, lx[i])
            minY = minOf(minY, ly[i]); maxY = maxOf(maxY, ly[i])
        }
        ln = 0
        if ((maxX - minX) < 14f * density || (maxY - minY) < 14f * density) {
            postDelayed(lassoTimeout, 12000) // loop too small, stay armed
            invalidate()
            return
        }
        engine.erase(PolyRegion(wx, wy, n))
        disarmLasso()
        ctrl.vibrate(35)
    }

    // ---------------------------------------------------------------- white box

    /** Two fingers held still for 3 s: show (or hide) the white writing box. */
    private fun toggleBox() {
        if (box != null) {
            box = null
            ctrl.vibrate(30)
            invalidate()
            return
        }
        val w = width * 0.62f
        val h = height * 0.42f
        box = Box(width / 2f - panX, height / 2f - panY, w, h, 0f)
        ctrl.vibrate(50)
        invalidate()
    }

    private fun hitHandle(x: Float, y: Float): Boolean {
        val b = box ?: return false
        val r = 30f * density
        for (type in intArrayOf(HANDLE_ROTATE, HANDLE_RESIZE, HANDLE_MOVE)) {
            val p = handlePos(b, type)
            if (hypot(x - p[0], y - p[1]) <= r) {
                handleType = type
                return true
            }
        }
        return false
    }

    private fun dragHandle(x: Float, y: Float) {
        val b = box ?: return
        when (handleType) {
            HANDLE_MOVE -> {
                b.cx += x - handleX
                b.cy += y - handleY
            }
            HANDLE_ROTATE -> {
                val rx = x - panX - b.cx
                val ry = y - panY - b.cy
                b.angle = Math.toDegrees(atan2(ry.toDouble(), rx.toDouble())).toFloat() + 90f
            }
            HANDLE_RESIZE -> {
                val rx = x - panX - b.cx
                val ry = y - panY - b.cy
                val a = Math.toRadians(b.angle.toDouble())
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val localX = rx * ca + ry * sa
                val localY = -rx * sa + ry * ca
                b.w = max(120f * density, localX * 2f)
                b.h = max(80f * density, localY * 2f)
            }
        }
        handleX = x
        handleY = y
        invalidate()
    }

    companion object {
        private const val NONE = -1
        private const val OBSERVED = -2
        private const val TAP_MS = 300L
        private const val HOLD_MS = 3000L
        private const val ANNOT_LIFE_MS = 1000L
        private const val ANNOT_FADE_MS = 350f
        private const val HANDLE_ROTATE = 0
        private const val HANDLE_RESIZE = 1
        private const val HANDLE_MOVE = 2
        private const val HANDLE_TOP = 3
    }
}
