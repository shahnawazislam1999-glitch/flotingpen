package com.raj.flotingpen

import android.util.SparseIntArray
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Decides whether a pointer is the pen.
 *
 * The Xiaomi Smart Pen (2nd gen) is a Bluetooth active pen and Xiaomi's input stack does not always
 * label it TOOL_TYPE_STYLUS inside a third-party window (it can come through as UNKNOWN, or as a
 * separate input device named "...pen" / "...stylus"). Checking only the tool type made the app
 * treat the pen as a finger, so nothing was written. This checks every signal Android gives us.
 */
object PenDetect {
    private const val SRC_STYLUS = 0x00004000
    private const val SRC_BT_STYLUS = 0x00008000
    private const val PEN_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

    private val nameCache = SparseIntArray() // deviceId -> 1 = pen-like name, 2 = not
    private val penName = Regex("(^|[^a-z])(pen|stylus)([^a-z]|$)")

    fun isPenPointer(e: MotionEvent, i: Int): Boolean {
        if ((e.buttonState and PEN_BUTTONS) != 0) return true // only a pen has these buttons
        return when (e.getToolType(i)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> true
            MotionEvent.TOOL_TYPE_MOUSE -> false
            MotionEvent.TOOL_TYPE_UNKNOWN ->
                (e.source and (SRC_STYLUS or SRC_BT_STYLUS)) != 0 || deviceLooksLikePen(e.deviceId)
            else -> deviceLooksLikePen(e.deviceId) // reported as finger: trust only a pen-named device
        }
    }

    private fun deviceLooksLikePen(id: Int): Boolean {
        val c = nameCache.get(id, 0)
        if (c != 0) return c == 1
        val dev = InputDevice.getDevice(id) ?: return false
        val pen = penName.containsMatchIn(dev.name.lowercase())
        nameCache.put(id, if (pen) 1 else 2)
        return pen
    }
}
