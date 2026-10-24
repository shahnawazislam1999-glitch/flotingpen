package com.raj.flotingpen

import android.content.Context
import android.content.SharedPreferences
import android.os.Build

/** All user settings live in one SharedPreferences file. The overlay service listens to it live. */
object Prefs {
    const val FILE = "floting_prefs"

    const val PAD_ON = "pad_on"                        // writing pad master switch
    const val COLOR = "color"                          // RED or BLUE
    const val SIZE_DP = "size_dp"                      // pen size in dp
    const val WINDOW_MODE = "window_mode"              // fallback: overlay catches touches, fingers are replayed
    const val OFF_ON_SCREEN_OFF = "off_on_screen_off"  // power button switches the pad off (default on)

    val RED: Int = 0xFFD92B2B.toInt()
    val BLUE: Int = 0xFF2D6EE0.toInt()

    /**
     * Android 14+ can hand the pen's events to the service while fingers go straight to the apps
     * ("pen capture", the default). Android 13 and older cannot, so they use the window fallback.
     */
    fun windowDefault(): Boolean = Build.VERSION.SDK_INT < 34

    fun get(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
