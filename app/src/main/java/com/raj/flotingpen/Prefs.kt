package com.raj.flotingpen

import android.content.Context
import android.content.SharedPreferences

/** All user settings live in one SharedPreferences file. The overlay service listens to it live. */
object Prefs {
    const val FILE = "floting_prefs"

    const val PAD_ON = "pad_on"            // writing pad master switch
    const val FINGER_MODE = "finger_mode"  // false = Smart pen mode (default), true = Finger mode
    const val PEN_TYPE = "pen_type"        // 0 Nirmal, 1 Classic, 2 Pencil
    const val SIZE_DP = "size_dp"          // pen size in dp
    const val COLOR = "color"              // RED or BLUE
    const val ANNOT = "annot"              // annotation pen (glow, fades away)
    const val COMPAT = "compat"            // always capture touches and replay finger gestures
    const val SESSION_S = "session_s"      // seconds fingers stay "gesture ready" after the pen leaves

    val RED: Int = 0xFFD92B2B.toInt()
    val BLUE: Int = 0xFF2D6EE0.toInt()

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
