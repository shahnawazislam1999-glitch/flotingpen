package com.raj.flotingpen

import android.graphics.Path

class Stroke(
    @JvmField val type: Int,
    @JvmField val color: Int,
    @JvmField val opacity: Float,
    @JvmField val baseW: Float,
) {
    @JvmField val pts = ArrayList<Pt>(64)
    @JvmField var l = Float.MAX_VALUE
    @JvmField var t = Float.MAX_VALUE
    @JvmField var r = -Float.MAX_VALUE
    @JvmField var b = -Float.MAX_VALUE
    @JvmField var path: Path? = null   // annotation strokes only
    @JvmField var alpha = 1f
    @JvmField var fadeAt = 0L

    fun add(p: Pt) {
        pts.add(p)
        if (p.x < l) l = p.x
        if (p.x > r) r = p.x
        if (p.y < t) t = p.y
        if (p.y > b) b = p.y
    }
}
