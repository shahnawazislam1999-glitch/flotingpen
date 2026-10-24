package com.raj.flotingpen

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlin.math.max

/** All user settings live in one SharedPreferences file. The overlay service listens to it live. */
object Prefs {
    const val FILE = "floting_prefs"

    const val PAD_ON = "pad_on"                        // writing pad master switch
    const val COLOR = "color"                          // ARGB int (RED / BLUE / palette)
    const val SIZE_DP = "size_dp"                      // pen size in dp (1..30, step 0.5)
    const val PEN_TYPE = "pen_type"                    // Pen.NIRMAL / CLASSIC / PENCIL / ANNOT
    const val OPACITY = "opacity"                      // ink opacity percent 10..100
    const val WINDOW_MODE = "window_mode"              // fallback: overlay catches touches, fingers are replayed
    const val OFF_ON_SCREEN_OFF = "off_on_screen_off"  // power button switches the pad off (default on)
    const val PEN_TAP_CLICK = "pen_tap_click"          // a quick pen tap (no writing) clicks the app below
    const val BUBBLE_ON = "bubble_on"                  // floating bubble visible (default on)
    const val BUBBLE_ALPHA = "bubble_alpha"            // bubble opacity percent 1..60 (default 5)
    const val BUBBLE_X = "bubble_x"
    const val BUBBLE_Y = "bubble_y"

    val RED: Int = 0xFFD92B2B.toInt()
    val BLUE: Int = 0xFF2D6EE0.toInt()
    val BLACK: Int = 0xFF1A1A1A.toInt()
    val GREEN: Int = 0xFF1E9E57.toInt()
    val ORANGE: Int = 0xFFF08A00.toInt()
    val PURPLE: Int = 0xFF8E44AD.toInt()
    val PALETTE: IntArray get() = intArrayOf(RED, BLUE, BLACK, GREEN, ORANGE, PURPLE)

    /**
     * Android 14+ can hand the pen's events to the service while fingers go straight to the apps
     * ("pen capture", the default). Android 13 and older cannot, so they use the window fallback.
     */
    fun windowDefault(): Boolean = Build.VERSION.SDK_INT < 34

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun size(p: SharedPreferences): Float = p.getFloat(SIZE_DP, 3f).coerceIn(1f, 30f)

    /** +1 / -1: 0.5 dp steps while thin, 1 dp steps once thick. */
    fun stepSize(p: SharedPreferences, dir: Int) {
        val cur = size(p)
        val step = if (dir > 0) (if (cur < 4f) 0.5f else 1f) else (if (cur <= 4f) 0.5f else 1f)
        p.edit().putFloat(SIZE_DP, (cur + dir * step).coerceIn(1f, 30f)).apply()
    }

    fun opacity(p: SharedPreferences): Int = p.getInt(OPACITY, 100).coerceIn(10, 100)

    fun stepOpacity(p: SharedPreferences, dir: Int) {
        p.edit().putInt(OPACITY, (opacity(p) + dir * 10).coerceIn(10, 100)).apply()
    }

    fun bubbleAlpha(p: SharedPreferences): Int = p.getInt(BUBBLE_ALPHA, 5).coerceIn(1, 60)
}

/** Live view of the pen settings for the ink engine (overlay and in-app pad share it). */
class PrefsInkConfig(ctx: Context) : InkConfig {
    private val prefs = Prefs.get(ctx)
    private val density = ctx.resources.displayMetrics.density
    override fun penType(): Int = prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3)
    override fun color(): Int = prefs.getInt(Prefs.COLOR, Prefs.RED)
    override fun sizePx(): Float = Prefs.size(prefs) * density
    override fun opacity(): Float = Prefs.opacity(prefs) / 100f
    override fun eraserPx(): Float = max(Prefs.size(prefs) * 2.5f, 10f) * density
}
