package com.raj.flotingpen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts the pen overlay (an accessibility overlay window). It does not read any screen content.
 *
 * Android 14+: while the pad is on, the service asks the system for the pen's events
 * (setMotionEventSources). The system then delivers every pen event ONLY to onMotionEvent, never to
 * the app underneath, while finger touches are untouched and keep working in the apps.
 */
class FloatingPenService : AccessibilityService() {

    private var controller: PadController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        controller = PadController(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean = controller?.onKey(event) ?: false

    override fun onMotionEvent(event: MotionEvent) {
        controller?.onPenMotion(event)
    }

    /** Start / stop receiving the pen's events (Android 14+). */
    fun setPenCapture(on: Boolean) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val info = serviceInfo ?: return
            info.motionEventSources = if (on) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_BLUETOOTH_STYLUS else 0
            serviceInfo = info
        } catch (_: Throwable) {
        }
    }

    fun clearInk() {
        controller?.clearInk()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        controller?.stop()
        controller = null
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        controller?.stop()
        controller = null
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: FloatingPenService? = null
    }
}
