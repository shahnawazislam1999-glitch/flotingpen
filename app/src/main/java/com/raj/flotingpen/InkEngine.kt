package com.raj.flotingpen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.View
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pen types and tools shared by the overlay, the bubble menu and the in-app pad. */
object Pen {
    const val NIRMAL = 0
    const val CLASSIC = 1
    const val PENCIL = 2
    const val ANNOT = 3

    const val TOOL_WRITE = 0
    const val TOOL_ERASE = 1
    const val TOOL_LASSO = 2

    val NAMES = arrayOf("Nirmal Pen", "Classic Pen", "Pencil", "Annotation Pen")
    val SHORT = arrayOf("Nirmal", "Classic", "Pencil", "Glow")
}

interface InkConfig {
    fun penType(): Int
    fun color(): Int
    fun sizePx(): Float
    fun opacity(): Float
    fun eraserPx(): Float
}

interface InkEvents {
    fun onLassoResult(count: Int)
}

private val P_NIRMAL = PenProfile(0.62f, 1.3f, 4f, 12f, 2, 0.55f, 0.62f, 0.65f, 0.8f, 1.0f, 0.4f, 5, 0.22f, 1f, false)
private val P_CLASSIC = PenProfile(0.45f, 2.2f, 7f, 26f, 5, 0.35f, 0.45f, 1.0f, 0.55f, 1.05f, 0.28f, 0, 1f, 1f, false)
private val P_PENCIL = PenProfile(0.64f, 0.9f, 2.5f, 6.5f, 3, 0.42f, 0.58f, 0.48f, 0.78f, 1.0f, 0.44f, 4, 0.28f, 0.7f, true)

/** Pencil texture, baked once per point so a stroke always redraws identically. */
class Grain(
    @JvmField val jx1: Float, @JvmField val jy1: Float,
    @JvmField val jx2: Float, @JvmField val jy2: Float,
    @JvmField val jx3: Float, @JvmField val jy3: Float,
    @JvmField val ga1: Float, @JvmField val ga2: Float, @JvmField val ga3: Float,
    @JvmField val sx: FloatArray, @JvmField val sy: FloatArray,
    @JvmField val sr: FloatArray, @JvmField val sa: FloatArray,
)

class Pt(@JvmField var x: Float, @JvmField var y: Float, @JvmField var w: Float, @JvmField val g: Grain?)

/**
 * The drawing brain. Strokes are kept as vectors, so they can be erased stroke-by-stroke, lassoed and undone.
 *
 * Smoothness design:
 *  - the stroke being written is drawn as vectors straight on the (GPU) canvas, so no big bitmap is
 *    re-uploaded while the pen moves;
 *  - the finished stroke is baked into ONE screen bitmap (a single upload when the pen lifts);
 *  - input is stabilised, width follows pressure + speed and tapers at both ends (same maths as the HTML reference);
 *  - every historical sample of a move event is used.
 */
class InkEngine(
    private val view: View,
    private val cfg: InkConfig,
    private val keepOnResize: Boolean,
) {
    var events: InkEvents? = null

    private val d = view.resources.displayMetrics.density
    private val strokes = ArrayList<Stroke>()
    private val annots = ArrayList<Stroke>()

    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private var vw = 0
    private var vh = 0
    private var bmpDirty = false

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val tmp = Path()
    private val lassoPath = Path()
    private val rnd = Random()

    // ---- live stroke ----
    private var cur: Stroke? = null
    private var prof = P_NIRMAL
    private var stabX = 0f
    private var stabY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var lastT = 0L
    private var curW = 1f
    private var radius = 6f

    // ---- gesture ----
    private var gTool = -1
    private var ex = 0f
    private var ey = 0f
    private var lx = FloatArray(256)
    private var ly = FloatArray(256)
    private var ln = 0

    // ---- fade ----
    private var ticking = false
    private val fadeTick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            var changed = false
            val it = annots.iterator()
            while (it.hasNext()) {
                val a = it.next()
                if (a === cur || a.fadeAt == 0L || now < a.fadeAt) continue
                val k = (now - a.fadeAt) / 350f
                if (k >= 1f) {
                    it.remove()
                    changed = true
                } else {
                    a.alpha = 1f - k
                    changed = true
                }
            }
            if (changed) view.invalidate()
            if (annots.isNotEmpty()) view.postOnAnimation(this) else ticking = false
        }
    }

    // ====================================================================================
    //  public api
    // ====================================================================================

    fun hasInk(): Boolean = strokes.isNotEmpty() || annots.isNotEmpty()

    fun onSize(w: Int, h: Int) {
        if (w == vw && h == vh) return
        vw = w
        vh = h
        abort()
        if (keepOnResize) {
            releaseBitmap()
            bmpDirty = strokes.isNotEmpty()
        } else {
            clearAll()
        }
    }

    fun release() {
        abort()
        view.removeCallbacks(fadeTick)
        ticking = false
        strokes.clear()
        annots.clear()
        releaseBitmap()
    }

    fun clearAll() {
        abort()
        strokes.clear()
        annots.clear()
        releaseBitmap()
        view.invalidate()
    }

    fun undo(): Boolean {
        abort()
        if (strokes.isEmpty()) return false
        strokes.removeAt(strokes.size - 1)
        redrawAll()
        view.invalidate()
        return true
    }

    fun draw(c: Canvas) {
        if (bmpDirty) {
            bmpDirty = false
            redrawAll()
        }
        bmp?.let { c.drawBitmap(it, 0f, 0f, null) }
        val s = cur
        if (s != null && s.type != Pen.ANNOT && s.pts.isNotEmpty()) drawStrokeLayered(c, s)
        if (annots.isNotEmpty()) drawAnnots(c)
        if (gTool == Pen.TOOL_LASSO && ln > 1) drawLasso(c)
        if (gTool == Pen.TOOL_ERASE) drawRing(c)
    }

    // ---------------------------------------------------------------- gesture

    fun begin(tool: Int, x: Float, y: Float, p: Float, t: Long) {
        abort()
        gTool = tool
        when (tool) {
            Pen.TOOL_WRITE -> beginStroke(x, y, p, t)
            Pen.TOOL_ERASE -> {
                ex = x
                ey = y
                eraseAt(x, y)
                invalidateRing()
            }
            Pen.TOOL_LASSO -> {
                ln = 0
                lassoAdd(x, y)
            }
        }
    }

    fun move(x: Float, y: Float, p: Float, t: Long) {
        when (gTool) {
            Pen.TOOL_WRITE -> sample(x, y, p, t)
            Pen.TOOL_ERASE -> eraseMove(x, y)
            Pen.TOOL_LASSO -> lassoAdd(x, y)
        }
    }

    fun end(x: Float, y: Float, p: Float, t: Long) {
        when (gTool) {
            Pen.TOOL_WRITE -> endWrite(x, y)
            Pen.TOOL_ERASE -> {
                invalidateRing()
            }
            Pen.TOOL_LASSO -> endLasso()
        }
        gTool = -1
    }

    /** Throw away the stroke / lasso / eraser gesture in progress (nothing is committed). */
    fun abort() {
        val s = cur
        if (s != null) {
            if (s.type == Pen.ANNOT) annots.remove(s)
            cur = null
        }
        val wasRing = gTool == Pen.TOOL_ERASE
        val wasLasso = gTool == Pen.TOOL_LASSO
        gTool = -1
        ln = 0
        if (s != null || wasRing || wasLasso) view.invalidate()
    }

    // ====================================================================================
    //  writing
    // ====================================================================================

    private fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v

    private fun beginStroke(x: Float, y: Float, p: Float, t: Long) {
        val type = cfg.penType()
        prof = when (type) {
            Pen.CLASSIC -> P_CLASSIC
            Pen.PENCIL -> P_PENCIL
            else -> P_NIRMAL
        }
        val size = cfg.sizePx()
        val baseW = if (type == Pen.ANNOT) size else size * prof.baseScale
        val opacity = if (type == Pen.ANNOT) 1f else cfg.opacity()
        val s = Stroke(type, cfg.color() or 0xFF000000.toInt(), opacity, baseW)
        val pr = if (p > 0f) p else 0.5f
        val w0 = if (type == Pen.ANNOT) baseW else {
            val iw = max(0.5f * d, baseW * (prof.pBase + pr * prof.pMul))
            startTaper(iw, 0)
        }
        radius = clamp(baseW * prof.rMul, prof.rMin * d, prof.rMax * d)
        stabX = x
        stabY = y
        lastRawX = x
        lastRawY = y
        lastT = t
        curW = w0
        cur = s
        if (type == Pen.ANNOT) {
            val path = Path()
            path.moveTo(x, y)
            s.path = path
            annots.add(s)
            startFade()
        }
        addPoint(s, x, y, w0)
        dirty(s)
    }

    private fun startTaper(w: Float, idx: Int): Float {
        val n = prof.taperN
        if (n <= 0 || idx >= n) return w
        return w * (prof.taperFloor + (1f - prof.taperFloor) * (idx.toFloat() / n))
    }

    private fun addPoint(s: Stroke, x: Float, y: Float, w: Float) {
        val g = if (s.type == Pen.PENCIL) makeGrain(w) else null
        val n = s.pts.size
        if (s.type == Pen.ANNOT && n >= 1) {
            val pr = s.pts[n - 1]
            s.path?.quadTo(pr.x, pr.y, (pr.x + x) / 2f, (pr.y + y) / 2f)
        }
        s.add(Pt(x, y, w, g))
    }

    private fun sample(x: Float, y: Float, p: Float, t: Long) {
        val s = cur ?: return
        val dx = x - stabX
        val dy = y - stabY
        val dist = hypot(dx, dy)
        if (dist < MIN_MOVE * d) return
        val pull = min(1f, dist / radius) * prof.stab
        stabX += dx * pull
        stabY += dy * pull

        val w: Float
        if (s.type == Pen.ANNOT) {
            w = s.baseW
        } else {
            val dt = max(1L, t - lastT).toFloat()
            val speed = hypot(x - lastRawX, y - lastRawY) / d / dt // dp per ms
            val pr = if (p > 0f) p else 0.5f
            val pf = prof.pBase + pr * prof.pMul
            val sf = clamp(prof.spCeil - speed * 0.5f, prof.spFloor, prof.spCeil)
            val target = max(0.5f * d, s.baseW * pf * sf)
            curW += (target - curW) * prof.wSmooth
            w = startTaper(curW, s.pts.size)
            lastT = t
        }
        lastRawX = x
        lastRawY = y
        addPoint(s, stabX, stabY, w)
        dirty(s)
    }

    private fun endWrite(x: Float, y: Float) {
        val s = cur ?: return
        val n = s.pts.size
        // A tap with no movement is a full stop: make the dot full size (not the tiny start-taper size).
        if (n == 1 && s.type != Pen.ANNOT) {
            val p0 = s.pts[0]
            if (hypot(x - p0.x, y - p0.y) < 3f * d && p0.w < s.baseW) p0.w = s.baseW
        }
        if (n >= 1) {
            val last = s.pts[n - 1]
            // close the small gap between the smoothed line and the real pen position
            if (hypot(x - last.x, y - last.y) > 0.3f * d && hypot(x - last.x, y - last.y) < 40f * d) {
                addPoint(s, x, y, last.w)
            }
        }
        if (s.type == Pen.ANNOT) {
            val last = s.pts[s.pts.size - 1]
            s.path?.lineTo(last.x, last.y)
            s.fadeAt = SystemClock.uptimeMillis() + 1000L
            cur = null
            startFade()
            dirty(s)
            return
        }
        applyEndTaper(s)
        strokes.add(s)
        cur = null
        ensureBitmap()
        val c = bc
        if (c != null) drawStrokeLayered(c, s)
        invalidateBounds(s)
    }

    private fun applyEndTaper(s: Stroke) {
        val n = prof.endN
        if (n <= 0) return
        val pts = s.pts
        val count = min(n, pts.size)
        if (count < 2) return
        for (k in 0 until count) {
            val idx = pts.size - 1 - k
            val frac = k.toFloat() / (count - 1)
            val mult = prof.endFloor + (1f - prof.endFloor) * frac
            pts[idx].w = pts[idx].w * mult
        }
    }

    private fun makeGrain(w: Float): Grain {
        val wc = w / d
        val mag = clamp(wc * 0.24f + 0.22f, 0.24f, 1.15f) * d
        val a1 = rnd.nextFloat() * TWO_PI
        val a2 = rnd.nextFloat() * TWO_PI
        val a3 = rnd.nextFloat() * TWO_PI
        val spread = clamp(wc * 0.85f + 0.6f, 0.7f, 3.6f) * d
        val cnt = 3 + rnd.nextInt(4)
        val sx = FloatArray(cnt)
        val sy = FloatArray(cnt)
        val sr = FloatArray(cnt)
        val sa = FloatArray(cnt)
        for (i in 0 until cnt) {
            val ang = rnd.nextFloat() * TWO_PI
            val dist = rnd.nextFloat() * spread
            sx[i] = cos(ang) * dist
            sy[i] = sin(ang) * dist
            sr[i] = (0.14f + rnd.nextFloat() * 0.32f) * d
            sa[i] = 0.1f + rnd.nextFloat() * 0.32f
        }
        return Grain(
            cos(a1) * mag, sin(a1) * mag,
            cos(a2) * mag * 0.72f, sin(a2) * mag * 0.72f,
            cos(a3) * mag * 0.46f, sin(a3) * mag * 0.46f,
            0.5f + rnd.nextFloat() * 0.5f, 0.35f + rnd.nextFloat() * 0.45f, 0.25f + rnd.nextFloat() * 0.4f,
            sx, sy, sr, sa,
        )
    }

    // ====================================================================================
    //  rendering
    // ====================================================================================

    private fun col(color: Int, a: Float): Int {
        val ai = (clamp(a, 0f, 1f) * 255f + 0.5f).toInt()
        return (color and 0x00FFFFFF) or (ai shl 24)
    }

    private fun drawStrokeLayered(c: Canvas, s: Stroke) {
        if (s.opacity < 0.999f && s.type != Pen.PENCIL && s.type != Pen.ANNOT) {
            val pad = s.baseW * 2f + 4f * d
            val sc = c.saveLayerAlpha(s.l - pad, s.t - pad, s.r + pad, s.b + pad, (s.opacity * 255f + 0.5f).toInt())
            drawStroke(c, s)
            c.restoreToCount(sc)
        } else {
            drawStroke(c, s)
        }
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        val n = s.pts.size
        if (n == 0) return
        if (n == 1) {
            drawDot(c, s, s.pts[0])
            return
        }
        for (i in 1 until n) seg(c, s, i)
        tail(c, s, s.pts[n - 2], s.pts[n - 1])
    }

    private fun drawDot(c: Canvas, s: Stroke, p: Pt) {
        val r = max(0.4f * d, p.w / 2f)
        fillPaint.style = Paint.Style.FILL
        if (s.type == Pen.PENCIL) {
            val op = s.opacity
            fillPaint.color = col(s.color, 0.62f * op)
            c.drawCircle(p.x, p.y, r * 0.78f, fillPaint)
            val g = p.g
            if (g != null) {
                fillPaint.color = col(s.color, 0.28f * op)
                c.drawCircle(p.x + g.jx1, p.y + g.jy1, r * 0.55f, fillPaint)
                for (k in g.sx.indices) {
                    fillPaint.color = col(s.color, g.sa[k] * op)
                    c.drawCircle(p.x + g.sx[k], p.y + g.sy[k], g.sr[k], fillPaint)
                }
            }
        } else {
            fillPaint.color = col(s.color, 1f)
            c.drawCircle(p.x, p.y, r, fillPaint)
        }
    }

    /** One smooth curve segment ending at pts[i]: midpoint -> midpoint with the shared point as control. */
    private fun seg(c: Canvas, s: Stroke, i: Int) {
        val p1 = s.pts[i - 1]
        val p2 = s.pts[i]
        val pmx: Float
        val pmy: Float
        if (i >= 2) {
            val p0 = s.pts[i - 2]
            pmx = (p0.x + p1.x) / 2f
            pmy = (p0.y + p1.y) / 2f
        } else {
            pmx = p1.x
            pmy = p1.y
        }
        val nmx = (p1.x + p2.x) / 2f
        val nmy = (p1.y + p2.y) / 2f
        tmp.reset()
        tmp.moveTo(pmx, pmy)
        tmp.quadTo(p1.x, p1.y, nmx, nmy)
        paint.style = Paint.Style.STROKE
        if (s.type == Pen.PENCIL) {
            pencilSeg(c, s, p2, max(0.5f * d, (p1.w + p2.w) / 2f))
        } else {
            paint.color = col(s.color, 1f)
            paint.strokeWidth = max(0.6f * d, (p1.w + p2.w) / 2f)
            c.drawPath(tmp, paint)
        }
    }

    private fun pass(c: Canvas, s: Stroke, jx: Float, jy: Float, a: Float, w: Float) {
        paint.color = col(s.color, a)
        paint.strokeWidth = w
        c.save()
        c.translate(jx, jy)
        c.drawPath(tmp, paint)
        c.restore()
    }

    /** Graphite: firm core + three thin jittered passes + loose speckles (all alphas scaled by opacity). */
    private fun pencilSeg(c: Canvas, s: Stroke, p2: Pt, w: Float) {
        val op = s.opacity
        pass(c, s, 0f, 0f, 0.6f * op, max(0.45f * d, w * 0.62f))
        val g = p2.g ?: return
        pass(c, s, g.jx1, g.jy1, 0.3f * g.ga1 * op, max(0.35f * d, w * 0.48f))
        pass(c, s, -g.jx2, -g.jy2, 0.22f * g.ga2 * op, max(0.3f * d, w * 0.36f))
        pass(c, s, g.jx3, -g.jy3, 0.16f * g.ga3 * op, max(0.25f * d, w * 0.26f))
        fillPaint.style = Paint.Style.FILL
        for (k in g.sx.indices) {
            fillPaint.color = col(s.color, g.sa[k] * op)
            c.drawCircle(p2.x + g.sx[k], p2.y + g.sy[k], g.sr[k], fillPaint)
        }
    }

    /** Closes the half-segment gap the midpoint technique leaves at the pen tip. */
    private fun tail(c: Canvas, s: Stroke, p1: Pt, p2: Pt) {
        tmp.reset()
        tmp.moveTo((p1.x + p2.x) / 2f, (p1.y + p2.y) / 2f)
        tmp.lineTo(p2.x, p2.y)
        paint.style = Paint.Style.STROKE
        if (s.type == Pen.PENCIL) {
            val w = max(0.5f * d, p2.w)
            paint.color = col(s.color, 0.6f * s.opacity)
            paint.strokeWidth = w * 0.62f
            c.drawPath(tmp, paint)
        } else {
            paint.color = col(s.color, 1f)
            paint.strokeWidth = max(0.6f * d, p2.w)
            c.drawPath(tmp, paint)
        }
    }

    /** Annotation strokes: a soft glow (3 layered widths), fading away after 1 second. */
    private fun drawAnnots(c: Canvas) {
        paint.style = Paint.Style.STROKE
        for (a in annots) {
            val al = a.alpha
            if (al <= 0f) continue
            if (a.pts.size < 2) {
                val p = a.pts[0]
                fillPaint.style = Paint.Style.FILL
                fillPaint.color = col(a.color, 0.25f * al)
                c.drawCircle(p.x, p.y, a.baseW * 1.6f, fillPaint)
                fillPaint.color = col(a.color, al)
                c.drawCircle(p.x, p.y, a.baseW / 2f, fillPaint)
                continue
            }
            val path = a.path ?: continue
            paint.color = col(a.color, 0.16f * al)
            paint.strokeWidth = a.baseW * 3.4f
            c.drawPath(path, paint)
            paint.color = col(a.color, 0.32f * al)
            paint.strokeWidth = a.baseW * 1.9f
            c.drawPath(path, paint)
            paint.color = col(a.color, al)
            paint.strokeWidth = a.baseW
            c.drawPath(path, paint)
        }
    }

    private fun startFade() {
        if (ticking) return
        ticking = true
        view.postOnAnimation(fadeTick)
    }

    // ====================================================================================
    //  bitmap
    // ====================================================================================

    private fun ensureBitmap() {
        if (bmp != null || vw <= 0 || vh <= 0) return
        try {
            val b = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
            bmp = b
            bc = Canvas(b)
        } catch (e: OutOfMemoryError) {
            bmp = null
            bc = null
        }
    }

    private fun releaseBitmap() {
        bmp?.recycle()
        bmp = null
        bc = null
    }

    private fun redrawAll() {
        if (strokes.isEmpty()) {
            releaseBitmap()
            return
        }
        ensureBitmap()
        val b = bmp ?: return
        val c = bc ?: return
        b.eraseColor(0)
        for (s in strokes) drawStrokeLayered(c, s)
    }

    // ====================================================================================
    //  dirty rectangles
    // ====================================================================================

    private fun dirty(s: Stroke) {
        val n = s.pts.size
        if (n == 0) return
        val a = s.pts[n - 1]
        val b = if (n >= 2) s.pts[n - 2] else a
        val c = if (n >= 3) s.pts[n - 3] else b
        val pad = if (s.type == Pen.ANNOT) s.baseW * 2.2f + 8f * d else max(a.w, b.w) + 10f * d
        val l = min(a.x, min(b.x, c.x)) - pad
        val t = min(a.y, min(b.y, c.y)) - pad
        val r = max(a.x, max(b.x, c.x)) + pad
        val bt = max(a.y, max(b.y, c.y)) + pad
        view.invalidate(floor(l).toInt(), floor(t).toInt(), ceil(r).toInt(), ceil(bt).toInt())
    }

    private fun invalidateBounds(s: Stroke) {
        val pad = s.baseW * 2f + 12f * d
        view.invalidate(
            floor(s.l - pad).toInt(), floor(s.t - pad).toInt(),
            ceil(s.r + pad).toInt(), ceil(s.b + pad).toInt(),
        )
    }

    // ====================================================================================
    //  stroke eraser
    // ====================================================================================

    private fun eraseMove(x: Float, y: Float) {
        val r = cfg.eraserPx()
        val dist = hypot(x - ex, y - ey)
        val steps = max(1, ceil(dist / (r * 0.5f)).toInt())
        invalidateRing()
        for (k in 1..steps) {
            val f = k.toFloat() / steps
            eraseAt(ex + (x - ex) * f, ey + (y - ey) * f)
        }
        ex = x
        ey = y
        invalidateRing()
    }

    /** Pen touching a stroke removes the whole stroke. */
    private fun eraseAt(px: Float, py: Float) {
        val r = cfg.eraserPx()
        var removed = false
        var i = strokes.size - 1
        while (i >= 0) {
            val s = strokes[i]
            if (hit(s, px, py, r)) {
                strokes.removeAt(i)
                removed = true
            }
            i--
        }
        if (removed) {
            redrawAll()
            view.invalidate()
        }
    }

    private fun hit(s: Stroke, px: Float, py: Float, r: Float): Boolean {
        val m = r + s.baseW * 1.5f
        if (px < s.l - m || px > s.r + m || py < s.t - m || py > s.b + m) return false
        val pts = s.pts
        if (pts.size == 1) {
            val p = pts[0]
            val rr = r + p.w / 2f
            val dx = px - p.x
            val dy = py - p.y
            return dx * dx + dy * dy <= rr * rr
        }
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val rr = r + max(a.w, b.w) / 2f
            if (distSegSq(px, py, a.x, a.y, b.x, b.y) <= rr * rr) return true
        }
        return false
    }

    private fun distSegSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        var t = if (l2 < 1e-6f) 0f else ((px - ax) * dx + (py - ay) * dy) / l2
        t = clamp(t, 0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    private fun drawRing(c: Canvas) {
        val r = cfg.eraserPx()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * d
        paint.color = 0x66000000
        c.drawCircle(ex, ey, r, paint)
        paint.strokeWidth = 1.5f * d
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(ex, ey, r, paint)
    }

    private fun invalidateRing() {
        val m = cfg.eraserPx() + 4f * d
        view.invalidate(floor(ex - m).toInt(), floor(ey - m).toInt(), ceil(ex + m).toInt(), ceil(ey + m).toInt())
    }

    // ====================================================================================
    //  lasso
    // ====================================================================================

    private fun lassoAdd(x: Float, y: Float) {
        if (ln > 0 && abs(x - lx[ln - 1]) + abs(y - ly[ln - 1]) < 2f * d) return
        if (ln >= 6000) return
        if (ln == lx.size) {
            lx = lx.copyOf(ln * 2)
            ly = ly.copyOf(ln * 2)
        }
        lx[ln] = x
        ly[ln] = y
        ln++
        val px = if (ln >= 2) lx[ln - 2] else x
        val py = if (ln >= 2) ly[ln - 2] else y
        val m = 8f * d
        view.invalidate(
            floor(min(x, px) - m).toInt(), floor(min(y, py) - m).toInt(),
            ceil(max(x, px) + m).toInt(), ceil(max(y, py) + m).toInt(),
        )
    }

    private fun drawLasso(c: Canvas) {
        lassoPath.reset()
        lassoPath.moveTo(lx[0], ly[0])
        for (i in 1 until ln) lassoPath.lineTo(lx[i], ly[i])
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f * d
        paint.color = 0xFFFFB300.toInt()
        val old = paint.pathEffect
        paint.pathEffect = DashPathEffect(floatArrayOf(10f * d, 7f * d), 0f)
        c.drawPath(lassoPath, paint)
        paint.pathEffect = old
        // closing hint: pen position back to the start
        paint.color = 0x66FFB300
        paint.strokeWidth = 1.5f * d
        c.drawLine(lx[ln - 1], ly[ln - 1], lx[0], ly[0], paint)
    }

    private fun inPoly(x: Float, y: Float): Boolean {
        var inside = false
        var j = ln - 1
        for (i in 0 until ln) {
            val xi = lx[i]
            val yi = ly[i]
            val xj = lx[j]
            val yj = ly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Strokes with at least half of their points inside the loop are selected and erased. */
    private fun endLasso() {
        var count = 0
        if (ln >= 3) {
            var i = strokes.size - 1
            while (i >= 0) {
                val s = strokes[i]
                var inside = 0
                for (p in s.pts) if (inPoly(p.x, p.y)) inside++
                if (inside * 2 >= s.pts.size && s.pts.isNotEmpty()) {
                    strokes.removeAt(i)
                    count++
                }
                i--
            }
            if (count > 0) redrawAll()
        }
        ln = 0
        view.invalidate()
        events?.onLassoResult(count)
    }

    companion object {
        private const val MIN_MOVE = 0.12f
        private const val TWO_PI = 6.2831855f
    }
}
