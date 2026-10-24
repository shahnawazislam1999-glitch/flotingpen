package com.raj.flotingpen

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Area that erases ink: finger scrub (circle) or pen lasso (polygon). Works in world coordinates. */
abstract class EraseRegion {
    abstract val left: Float
    abstract val top: Float
    abstract val right: Float
    abstract val bottom: Float
    abstract fun hit(x: Float, y: Float): Boolean

    fun mayHit(s: Stroke): Boolean {
        val p = s.pad()
        return !(s.maxX + p < left || s.minX - p > right || s.maxY + p < top || s.minY - p > bottom)
    }
}

class CircleRegion : EraseRegion() {
    var cx = 0f
    var cy = 0f
    var r = 0f
    override val left: Float get() = cx - r
    override val top: Float get() = cy - r
    override val right: Float get() = cx + r
    override val bottom: Float get() = cy + r

    fun set(x: Float, y: Float, radius: Float): CircleRegion {
        cx = x
        cy = y
        r = radius
        return this
    }

    override fun hit(x: Float, y: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= r * r
    }
}

class PolyRegion(private val xs: FloatArray, private val ys: FloatArray, private val n: Int) : EraseRegion() {
    override val left: Float
    override val top: Float
    override val right: Float
    override val bottom: Float

    init {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            if (xs[i] < l) l = xs[i]
            if (xs[i] > r) r = xs[i]
            if (ys[i] < t) t = ys[i]
            if (ys[i] > b) b = ys[i]
        }
        left = l
        top = t
        right = r
        bottom = b
    }

    override fun hit(x: Float, y: Float): Boolean {
        if (x < left || x > right || y < top || y > bottom) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            if ((ys[i] > y) != (ys[j] > y) &&
                x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }
}

/**
 * Holds every stroke and a single screen-sized bitmap cache of the committed ink.
 *
 * RAM design:
 *  - strokes are packed float arrays (no per-point objects)
 *  - the bitmap is only allocated while ink exists; clearing recycles it immediately
 *  - nothing is ever written to storage, so a clear really frees the memory and leaves no trace
 */
class InkEngine(private val density: Float) {

    class Live(val stroke: Stroke, val profile: PenProfile, val baseW: Float) {
        var sx = 0f
        var sy = 0f
        var lx = 0f
        var ly = 0f
        var lt = 0L
        var cw = 0f
        var radius = 1f
    }

    val strokes = ArrayList<Stroke>()
    val hasInk: Boolean get() = strokes.isNotEmpty()

    private var bmp: Bitmap? = null
    private var bc: Canvas? = null
    private var cachePanX = 0f
    private var cachePanY = 0f
    private var w = 0
    private var h = 0
    private var seedCounter = 1

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val dirty = RectF()

    // ---------------------------------------------------------------- cache management

    fun resize(nw: Int, nh: Int, panX: Float, panY: Float) {
        if (nw == w && nh == h) return
        w = nw
        h = nh
        releaseBitmap()
        if (strokes.isNotEmpty()) rebuild(panX, panY)
    }

    private fun ensureCache(panX: Float, panY: Float): Canvas? {
        val existing = bc
        if (existing != null) return existing
        if (w <= 0 || h <= 0) return null
        val b = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val c = Canvas(b)
        c.translate(panX, panY)
        bmp = b
        bc = c
        cachePanX = panX
        cachePanY = panY
        return c
    }

    private fun releaseBitmap() {
        bmp?.recycle()
        bmp = null
        bc = null
    }

    /** Re-render every stroke for a new canvas offset. Called when a pan ends or the size changes. */
    fun rebuild(panX: Float, panY: Float) {
        if (strokes.isEmpty()) {
            releaseBitmap()
            return
        }
        val c = ensureCache(panX, panY) ?: return
        bmp?.eraseColor(0)
        c.setMatrix(null)
        c.translate(panX, panY)
        cachePanX = panX
        cachePanY = panY
        for (s in strokes) drawFull(c, s, 1f, 1f)
    }

    fun draw(canvas: Canvas, panX: Float, panY: Float) {
        val b = bmp ?: return
        canvas.drawBitmap(b, panX - cachePanX, panY - cachePanY, bmpPaint)
    }

    /** Wipe all ink and give the bitmap memory back right away. */
    fun clearAll() {
        strokes.clear()
        releaseBitmap()
    }

    // ---------------------------------------------------------------- writing

    private fun startTaper(width: Float, idx: Int, p: PenProfile): Float {
        if (p.taperN <= 0 || idx >= p.taperN) return width
        return width * (p.taperFloor + (1f - p.taperFloor) * (idx.toFloat() / p.taperN))
    }

    fun begin(
        x: Float, y: Float, pressure: Float, t: Long,
        color: Int, penType: Int, sizePx: Float, annot: Boolean,
        panX: Float, panY: Float,
    ): Live {
        val prof = PenProfiles.of(penType)
        val base = sizePx * prof.baseScale
        val s = Stroke(color, penType, annot, seedCounter++ * 7919 + 13)
        val live = Live(s, prof, base)
        val p = if (pressure > 0f) pressure else 0.5f
        val w0 = startTaper(max(1f, base * (prof.pressureBase + p * prof.pressureMul)), 0, prof)
        s.add(x, y, w0)
        live.sx = x
        live.sy = y
        live.lx = x
        live.ly = y
        live.lt = t
        live.cw = w0
        live.radius = (base * prof.radiusMul).coerceIn(prof.radiusMin * density, prof.radiusMax * density)
        if (!annot) {
            if (bc != null && (cachePanX != panX || cachePanY != panY)) rebuild(panX, panY)
            strokes.add(s)
            val c = ensureCache(panX, panY)
            if (c != null) drawDot(c, s, 1f, 1f)
        }
        return live
    }

    /** Adds one raw pen sample. Returns true if a point was added. */
    fun move(live: Live, x: Float, y: Float, pressure: Float, t: Long): Boolean {
        val s = live.stroke
        val prof = live.profile
        val dx = x - live.sx
        val dy = y - live.sy
        val d = hypot(dx, dy)
        if (d < MIN_MOVE * density) return false

        val pull = min(1f, d / live.radius) * prof.stab
        live.sx += dx * pull
        live.sy += dy * pull

        val dt = max(1L, t - live.lt).toFloat()
        val speed = hypot(x - live.lx, y - live.ly) / dt / density // dp per ms
        val p = if (pressure > 0f) pressure else 0.5f
        val pf = prof.pressureBase + p * prof.pressureMul
        val sf = (prof.speedCeil - speed * 0.5f).coerceIn(prof.speedFloor, prof.speedCeil)
        val target = max(1f, live.baseW * pf * sf)
        live.cw += (target - live.cw) * prof.widthSmooth
        val wv = startTaper(live.cw, s.n, prof)
        live.lt = t
        live.lx = x
        live.ly = y

        s.add(live.sx, live.sy, wv)
        if (!s.annot) {
            val c = bc
            if (c != null) drawSeg(c, s, s.n - 1, 1f, 1f)
        }
        return true
    }

    /** Pen lifted: taper the tail like the reference pad, then tidy the stroke's memory. */
    fun end(live: Live) {
        val s = live.stroke
        val prof = live.profile
        s.endedAt = SystemClock.uptimeMillis()
        val count = min(prof.endTaperN, s.n)
        var tapered = false
        if (!s.annot && count >= 2) {
            for (k in 0 until count) {
                val idx = s.n - 1 - k
                val frac = k.toFloat() / (count - 1)
                val mult = prof.endTaperFloor + (1f - prof.endTaperFloor) * frac
                s.setW(idx, s.w(idx) * mult)
            }
            tapered = true
        }
        s.trim()
        if (s.annot) return
        if (tapered) {
            val from = max(0, s.n - count - 2)
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (i in from until s.n) {
                l = min(l, s.x(i)); r = max(r, s.x(i))
                t = min(t, s.y(i)); b = max(b, s.y(i))
            }
            val pad = s.pad() + 2f
            dirty.set(l - pad, t - pad, r + pad, b + pad)
            redrawRegion(dirty)
        } else {
            val c = bc
            if (c != null) drawTail(c, s, 1f, 1f)
        }
    }

    /** Drop a stroke entirely (used when a second finger arrives right after an accidental touch). */
    fun discard(s: Stroke) {
        if (!strokes.remove(s)) return
        if (strokes.isEmpty()) {
            releaseBitmap()
            return
        }
        val p = s.pad()
        dirty.set(s.minX - p, s.minY - p, s.maxX + p, s.maxY + p)
        redrawRegion(dirty)
    }

    // ---------------------------------------------------------------- erasing

    /**
     * Removes every point inside [region]. Strokes are split into the pieces that survive, so a
     * scrub or lasso erases exactly the covered part of a stroke instead of the whole stroke.
     */
    fun erase(region: EraseRegion): Boolean {
        var res: ArrayList<Stroke>? = null
        var any = false
        for (idx in 0 until strokes.size) {
            val s = strokes[idx]
            var hit = false
            if (region.mayHit(s)) {
                for (i in 0 until s.n) {
                    if (region.hit(s.x(i), s.y(i))) {
                        hit = true
                        break
                    }
                }
            }
            if (hit) {
                var out = res
                if (out == null) {
                    out = ArrayList(strokes.size + 4)
                    for (k in 0 until idx) out.add(strokes[k])
                    res = out
                }
                val p = s.pad()
                if (!any) dirty.set(s.minX - p, s.minY - p, s.maxX + p, s.maxY + p)
                else dirty.union(s.minX - p, s.minY - p, s.maxX + p, s.maxY + p)
                any = true
                split(s, region, out)
            } else {
                res?.add(s)
            }
        }
        val out = res ?: return false
        strokes.clear()
        strokes.addAll(out)
        if (strokes.isEmpty()) releaseBitmap() else redrawRegion(dirty)
        return true
    }

    private fun split(s: Stroke, region: EraseRegion, out: ArrayList<Stroke>) {
        var cur: Stroke? = null
        for (i in 0 until s.n) {
            if (region.hit(s.x(i), s.y(i))) {
                cur?.trim()
                cur = null
            } else {
                var c = cur
                if (c == null) {
                    c = Stroke(s.color, s.penType, false, s.seed)
                    c.idxBase = s.idxBase + i
                    out.add(c)
                    cur = c
                }
                c.add(s.x(i), s.y(i), s.w(i))
            }
        }
        cur?.trim()
    }

    private fun redrawRegion(r: RectF) {
        val c = bc ?: return
        c.save()
        c.clipRect(r)
        c.drawColor(0, PorterDuff.Mode.CLEAR)
        for (s in strokes) {
            val p = s.pad()
            if (s.maxX + p < r.left || s.minX - p > r.right || s.maxY + p < r.top || s.minY - p > r.bottom) continue
            drawFull(c, s, 1f, 1f)
        }
        c.restore()
    }

    // ---------------------------------------------------------------- rendering

    fun drawFull(c: Canvas, s: Stroke, alpha: Float, wMul: Float) {
        if (s.n == 0) return
        if (s.n == 1) {
            drawDot(c, s, alpha, wMul)
            return
        }
        for (i in 1 until s.n) drawSeg(c, s, i, alpha, wMul)
        drawTail(c, s, alpha, wMul)
    }

    /** Annotation pen: soft glow underneath, crisp core on top. */
    fun drawAnnot(c: Canvas, s: Stroke, alpha: Float) {
        drawFull(c, s, alpha * 0.22f, 3.0f)
        drawFull(c, s, alpha, 1f)
    }

    private fun drawDot(c: Canvas, s: Stroke, alpha: Float, wMul: Float) {
        fill.color = s.color
        fill.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        c.drawCircle(s.x(0), s.y(0), max(0.5f, s.w(0) * 0.5f * wMul), fill)
    }

    private fun drawTail(c: Canvas, s: Stroke, alpha: Float, wMul: Float) {
        if (s.n < 2) return
        val a = s.n - 2
        val b = s.n - 1
        path.reset()
        path.moveTo((s.x(a) + s.x(b)) * 0.5f, (s.y(a) + s.y(b)) * 0.5f)
        path.lineTo(s.x(b), s.y(b))
        paint.color = s.color
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        paint.strokeWidth = max(0.6f, s.w(b) * wMul)
        c.drawPath(path, paint)
    }

    private fun drawSeg(c: Canvas, s: Stroke, i: Int, alpha: Float, wMul: Float) {
        val x1 = s.x(i - 1)
        val y1 = s.y(i - 1)
        val x2 = s.x(i)
        val y2 = s.y(i)
        val sx = if (i >= 2) (s.x(i - 2) + x1) * 0.5f else x1
        val sy = if (i >= 2) (s.y(i - 2) + y1) * 0.5f else y1
        path.reset()
        path.moveTo(sx, sy)
        path.quadTo(x1, y1, (x1 + x2) * 0.5f, (y1 + y2) * 0.5f)
        val w = (s.w(i - 1) + s.w(i)) * 0.5f * wMul

        if (s.penType == PEN_PENCIL && !s.annot) {
            drawPencil(c, s, i, w, alpha, x2, y2)
            return
        }
        paint.color = s.color
        paint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        paint.strokeWidth = max(0.6f, w)
        c.drawPath(path, paint)
    }

    /** Textured graphite: main pass, two mis-registered grain passes and a few speckles. Grain is hash-based, so it never shimmers. */
    private fun drawPencil(c: Canvas, s: Stroke, i: Int, w: Float, alpha: Float, px: Float, py: Float) {
        val gi = s.idxBase + i
        paint.color = s.color
        paint.alpha = (alpha * 0.9f * 255f).toInt().coerceIn(0, 255)
        paint.strokeWidth = max(0.6f, w * 0.85f)
        c.drawPath(path, paint)

        val mag = (w * 0.24f + 0.22f * density).coerceIn(0.24f * density, 1.15f * density)
        for (k in 0..1) {
            val ang = rnd(s.seed, gi, k) * TWO_PI
            val a = alpha * (0.3f + 0.3f * rnd(s.seed, gi, k + 5))
            c.save()
            c.translate(cos(ang) * mag * (1f - 0.3f * k), sin(ang) * mag * (1f - 0.3f * k))
            paint.alpha = (a * 255f).toInt().coerceIn(0, 255)
            paint.strokeWidth = max(0.5f, w * 0.5f)
            c.drawPath(path, paint)
            c.restore()
        }

        fill.color = s.color
        val spread = w * 0.85f + 0.6f * density
        for (k in 0 until 4) {
            val ang = rnd(s.seed, gi, 10 + k) * TWO_PI
            val dist = rnd(s.seed, gi, 20 + k) * spread
            fill.alpha = (alpha * (0.1f + 0.32f * rnd(s.seed, gi, 30 + k)) * 255f).toInt().coerceIn(0, 255)
            c.drawCircle(
                px + cos(ang) * dist, py + sin(ang) * dist,
                (0.14f + 0.32f * rnd(s.seed, gi, 40 + k)) * density + 0.3f, fill,
            )
        }
    }

    private fun rnd(a: Int, b: Int, c: Int): Float {
        var h = a * K1 + b * K2 + c * K3
        h = h xor (h ushr 15)
        h *= K4
        h = h xor (h ushr 12)
        return (h and 0xFFFFFF) / 16777216f
    }

    companion object {
        private const val MIN_MOVE = 0.12f
        private val TWO_PI = (2.0 * PI).toFloat()
        private const val K1 = 0x27d4eb2d
        private const val K2 = 0x165667b1
        private val K3 = 0x85ebca6b.toInt()
        private const val K4 = 0x2c1b3c6d
    }
}
