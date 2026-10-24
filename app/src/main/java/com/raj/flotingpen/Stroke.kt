package com.raj.flotingpen

/**
 * One pen stroke. Points are stored in a single packed FloatArray (x, y, width) instead of one
 * object per point, which keeps RAM use and GC pressure very low. Coordinates are world
 * coordinates (screen position minus the current canvas pan).
 */
class Stroke(
    val color: Int,
    val penType: Int,
    val annot: Boolean,
    val seed: Int,
) {
    var pts = FloatArray(90)
    var n = 0
    /** Offset added to the point index when generating pencil grain, so grain stays stable after an erase split. */
    var idxBase = 0
    var endedAt = 0L

    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    var maxW = 0f

    fun add(x: Float, y: Float, w: Float) {
        val k = n * 3
        if (k + 3 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[k] = x
        pts[k + 1] = y
        pts[k + 2] = w
        n++
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
        if (w > maxW) maxW = w
    }

    fun x(i: Int): Float = pts[i * 3]
    fun y(i: Int): Float = pts[i * 3 + 1]
    fun w(i: Int): Float = pts[i * 3 + 2]
    fun setW(i: Int, v: Float) {
        pts[i * 3 + 2] = v
    }

    /** Release unused capacity once the stroke is finished. */
    fun trim() {
        if (pts.size > n * 3 && n > 0) pts = pts.copyOf(n * 3)
    }

    /** Bounds padding: half the widest point plus room for pencil grain. */
    fun pad(): Float = maxW * 0.5f + 6f
}
