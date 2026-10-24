package com.raj.flotingpen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts the pen overlay (an accessibility overlay window), reads the smart pen's side buttons and
 * can replay finger gestures. It does not read any screen content.
 */
class FloatingPenService : AccessibilityService() {

    private var controller: PadController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val info = serviceInfo
                // Android 14+: observe stylus events system-wide without consuming them.
                info.setMotionEventSources(InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_BLUETOOTH_STYLUS)
                serviceInfo = info
            } catch (_: Throwable) {
            }
        }
        controller = PadController(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean = controller?.onKey(event) ?: false

    override fun onMotionEvent(event: MotionEvent) {
        controller?.onObservedMotion(event)
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
