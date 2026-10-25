package com.raj.flotingpen

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
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
        // Remember that the pen service is wanted, and keep the process alive in the background.
        Prefs.get(this).edit().putBoolean(Prefs.A11Y_WANTED, true).apply()
        KeepAliveService.start(this)
        controller?.stop()
        controller = PadController(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // App / window changes: quietly make sure the pen layer and pen capture are still alive.
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) controller?.healSoon()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigChanged()
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean = controller?.onKey(event) ?: false

    override fun onMotionEvent(event: MotionEvent) {
        controller?.onPenMotion(event)
    }

    /**
     * Start / stop receiving the pen's events (Android 14+).
     * [force] switches the capture off and on again: this is what revives a pen that stopped drawing
     * on the screen (the side buttons still worked, the strokes / eraser did not) without restarting the phone.
     */
    fun setPenCapture(on: Boolean, force: Boolean = false) {
        if (Build.VERSION.SDK_INT < 34) return
        try {
            val want = if (on) InputDevice.SOURCE_STYLUS or InputDevice.SOURCE_BLUETOOTH_STYLUS else 0
            var info = serviceInfo ?: return
            if (force && on) {
                info.motionEventSources = 0
                serviceInfo = info
                info = serviceInfo ?: return
            } else if (info.motionEventSources == want) {
                return
            }
            info.motionEventSources = want
            serviceInfo = info
        } catch (_: Throwable) {
        }
    }

    /** True when pen capture is really switched on in Android (it can be reset by the system). */
    fun penCaptureIsOn(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            (serviceInfo?.motionEventSources ?: 0) != 0
        } catch (_: Throwable) {
            true
        }
    }

    fun clearInk() {
        controller?.clearInk()
    }

    /** Fix a pen layer that stopped reacting (called from the notification "Fix pen" button). */
    fun healNow() {
        controller?.heal(true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // You switched the service off in Accessibility settings: do not bring it back, stop the keep-alive.
        Prefs.get(this).edit().putBoolean(Prefs.A11Y_WANTED, false).apply()
        KeepAliveService.stop(this)
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
