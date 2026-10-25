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
    const val ANNOT_SIZE_DP = "annot_size_dp"          // Annotation Pen size in dp (separate from SIZE_DP)
    const val AUTO_SWITCH = "auto_switch"              // eraser / lasso go back to the pen after one use (default on)
    const val WHITE_BOX = "white_box"                  // adjustable white rectangle under the ink (default off)
    const val BOX_X = "box_x"
    const val BOX_Y = "box_y"
    const val BOX_W = "box_w"
    const val BOX_H = "box_h"
    const val OPACITY = "opacity"                      // ink opacity percent 10..100
    const val WINDOW_MODE = "window_mode"              // fallback: overlay catches touches, fingers are replayed
    const val OFF_ON_SCREEN_OFF = "off_on_screen_off"  // power button switches the pad off (default on)
    const val PEN_TAP_CLICK = "pen_tap_click_v3"       // a quick pen tap clicks the app below (default OFF: a tap draws a full stop)
    const val BUBBLE_ON = "bubble_on"                  // floating bubble visible (default on)
    const val BUBBLE_ALPHA = "bubble_alpha"            // bubble opacity percent 1..60 (default 5)
    const val A11Y_WANTED = "a11y_wanted"              // pen service was switched on (so it is brought back if Android kills it)
    const val BUBBLE_X = "bubble_x"
    const val BUBBLE_Y = "bubble_y"

    // smart pen buttons / gestures: each holds an action id (see Act)
    const val BTN_UP_1 = "btn_up_1"                    // up button, 1 tap   (default: lasso)
    const val BTN_UP_2 = "btn_up_2"                    // up button, 2 taps  (default: clear screen)
    const val BTN_DOWN_1 = "btn_down_1"                // down button, 1 tap (default: stroke eraser)
    const val BTN_DOWN_2 = "btn_down_2"                // down button, 2 taps (default: nothing)
    const val HOLD_ACTION = "hold_action"              // nib held still      (default: red <-> blue)
    const val HOLD_SEC = "hold_sec"                    // seconds to hold, 1..10 (default 3)
    const val DOUBLE_MS = "double_ms"                  // 2-tap window in ms, 250..900 (default 500)
    const val CUSTOM_ACTIONS = "custom_actions"        // JSON list of user-made actions
    const val PRESETS = "presets_json"                 // JSON map name -> saved settings
    const val PREV_PEN = "prev_pen"                    // pen used before the last pen switch

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

    fun autoSwitch(p: SharedPreferences): Boolean = p.getBoolean(AUTO_SWITCH, true)

    fun isAnnot(p: SharedPreferences): Boolean = p.getInt(PEN_TYPE, Pen.NIRMAL) == Pen.ANNOT

    /** Settings key of the size that belongs to the pen in use: the Annotation Pen has its own size. */
    fun sizeKey(p: SharedPreferences): String = if (isAnnot(p)) ANNOT_SIZE_DP else SIZE_DP

    /** Size of the pen in use (Annotation Pen: its own size). */
    fun size(p: SharedPreferences): Float = p.getFloat(sizeKey(p), 3f).coerceIn(1f, 30f)

    /** Size shared by Nirmal / Classic / Pencil (also used for the eraser radius). */
    fun penSize(p: SharedPreferences): Float = p.getFloat(SIZE_DP, 3f).coerceIn(1f, 30f)

    fun annotSize(p: SharedPreferences): Float = p.getFloat(ANNOT_SIZE_DP, 3f).coerceIn(1f, 30f)

    /** +1 / -1: 0.5 dp steps while thin, 1 dp steps once thick. Changes only the size of the pen in use. */
    fun stepSize(p: SharedPreferences, dir: Int) {
        val cur = size(p)
        val step = if (dir > 0) (if (cur < 4f) 0.5f else 1f) else (if (cur <= 4f) 0.5f else 1f)
        p.edit().putFloat(sizeKey(p), (cur + dir * step).coerceIn(1f, 30f)).apply()
    }

    fun opacity(p: SharedPreferences): Int = p.getInt(OPACITY, 100).coerceIn(10, 100)

    fun stepOpacity(p: SharedPreferences, dir: Int) {
        p.edit().putInt(OPACITY, (opacity(p) + dir * 10).coerceIn(10, 100)).apply()
    }

    fun bubbleAlpha(p: SharedPreferences): Int = p.getInt(BUBBLE_ALPHA, 5).coerceIn(1, 60)

    fun defaultAction(key: String): String = when (key) {
        BTN_UP_1 -> Act.LASSO
        BTN_UP_2 -> Act.CLEAR
        BTN_DOWN_1 -> Act.ERASER
        HOLD_ACTION -> Act.COLOR_RB
        else -> Act.NONE
    }

    fun action(p: SharedPreferences, key: String): String = p.getString(key, null) ?: defaultAction(key)

    fun holdSec(p: SharedPreferences): Int = p.getInt(HOLD_SEC, 3).coerceIn(1, 10)

    fun doubleMs(p: SharedPreferences): Int = p.getInt(DOUBLE_MS, 500).coerceIn(250, 900)
}

/** Live view of the pen settings for the ink engine (overlay and in-app pad share it). */
class PrefsInkConfig(ctx: Context) : InkConfig {
    private val prefs = Prefs.get(ctx)
    private val density = ctx.resources.displayMetrics.density
    override fun penType(): Int = prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3)
    override fun color(): Int = prefs.getInt(Prefs.COLOR, Prefs.RED)
    override fun sizePx(): Float = Prefs.size(prefs) * density
    override fun opacity(): Float = Prefs.opacity(prefs) / 100f
    override fun eraserPx(): Float = max(Prefs.penSize(prefs) * 2.5f, 10f) * density
}
