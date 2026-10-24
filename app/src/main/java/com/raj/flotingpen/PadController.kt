package com.raj.flotingpen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs

/**
 * Owns the overlay window.
 *
 * Pen capture mode (default, Android 14+): the window is NEVER touchable, so fingers always reach the
 * apps. The pen's events are delivered to the service by the system and drawn here.
 *
 * Window mode (fallback, always used on Android 13 and older): the window catches touches, the pen
 * writes, and finger taps / swipes are re-played onto the app underneath.
 */
class PadController(private val service: FloatingPenService) {

    private val prefs: SharedPreferences = Prefs.get(service)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val vibrator: Vibrator? = service.getSystemService(Vibrator::class.java)

    private var view: InkView? = null
    private var box: WhiteBoxView? = null
    private var params: WindowManager.LayoutParams? = null
    private var touchableNow = false
    private var replaying = false
    private var lastKeyAt = 0L
    private var screenOffRegistered = false

    val inkConfig = PrefsInkConfig(service)
    private var tool = Pen.TOOL_WRITE
    private val keys = PenButtons(service, { tool }, { setTool(it) }, { clearInk() }, { undo() }, { vibrate(it) })
    private val bubble = FloatingBubble(service, this)

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.PAD_ON -> {
                applyPad()
                bubble.refreshMenu()
            }
            Prefs.WINDOW_MODE -> applyMode()
            Prefs.WHITE_BOX -> {
                applyBox()
                if (padOn()) PadNotifier.show(service)
            }
            Prefs.BOX_X, Prefs.BOX_Y, Prefs.BOX_W, Prefs.BOX_H -> {}
            Prefs.BUBBLE_ON -> {
                bubble.sync()
                if (padOn()) PadNotifier.show(service) // refresh the Hide / Show bubble button
            }
            Prefs.BUBBLE_ALPHA -> bubble.sync()
            else -> bubble.refreshMenu()
        }
    }

    /** Power button / screen off: switch the writing pad off (can be disabled in the app). */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (padOn() && prefs.getBoolean(Prefs.OFF_ON_SCREEN_OFF, true)) PadNotifier.turnOff(service)
        }
    }

    // ---------------------------------------------------------------- settings (read live)

    fun padOn() = prefs.getBoolean(Prefs.PAD_ON, false)
    fun sizePx() = Prefs.size(prefs) * density
    fun tool(): Int = tool
    fun autoSwitch(): Boolean = Prefs.autoSwitch(prefs)
    fun penTapClicks(): Boolean = prefs.getBoolean(Prefs.PEN_TAP_CLICK, true)
    fun windowMode(): Boolean =
        if (prefs.contains(Prefs.WINDOW_MODE)) prefs.getBoolean(Prefs.WINDOW_MODE, false) else Prefs.windowDefault()

    fun vibrate(ms: Long) {
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- lifecycle

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        try {
            service.registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
            screenOffRegistered = true
        } catch (_: Throwable) {
        }
        applyPad()
        bubble.sync()
    }

    fun onConfigChanged() {
        main.postDelayed({ bubble.clampToScreen() }, 400L)
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (screenOffRegistered) {
            try {
                service.unregisterReceiver(screenOff)
            } catch (_: Throwable) {
            }
            screenOffRegistered = false
        }
        main.removeCallbacksAndMessages(null)
        bubble.remove()
        hide()
        PadNotifier.cancel(service)
    }

    private fun applyPad() {
        if (padOn()) {
            show()
            if (view != null) PadNotifier.show(service)
        } else {
            hide()
            PadNotifier.cancel(service)
        }
    }

    /** Pen capture (Android 14+) is on only while the pad is shown and window mode is off. */
    private fun applyMode() {
        service.setPenCapture(view != null && !windowMode())
        updateTouchable()
        view?.flash(if (windowMode()) "Window mode" else "Pen capture mode")
    }

    private fun show() {
        if (view != null) return
        val v = InkView(service, this)
        val touchable = wantTouchable()
        var flags = BASE_FLAGS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            if (prefs.getBoolean(Prefs.WHITE_BOX, false)) addBox()
            wm.addView(v, lp)
        } catch (e: Throwable) {
            Toast.makeText(service, "Pen layer could not start: " + e.message, Toast.LENGTH_LONG).show()
            return
        }
        view = v
        params = lp
        touchableNow = touchable
        service.setPenCapture(!windowMode())
        v.flash(if (windowMode()) "Pen layer on (window mode)" else "Pen layer on - write with the pen", 3000L)
    }

    private fun hide() {
        tool = Pen.TOOL_WRITE
        service.setPenCapture(false)
        val v = view ?: return
        view = null
        params = null
        touchableNow = false
        v.release()
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        removeBox()
    }

    // ---------------------------------------------------------------- white box

    /** Adds the white box window. It must be added BEFORE the ink window so the ink is drawn above it. */
    private fun addBox() {
        if (box != null) return
        val b = WhiteBoxView(service, wm, prefs)
        try {
            wm.addView(b, b.layoutParams())
            box = b
        } catch (_: Throwable) {
        }
    }

    private fun removeBox() {
        val b = box ?: return
        box = null
        try {
            wm.removeView(b)
        } catch (_: Throwable) {
        }
    }

    /** Switch the white box on / off. When turned on, the ink window is re-added so it stays on top of the box. */
    private fun applyBox() {
        val want = prefs.getBoolean(Prefs.WHITE_BOX, false) && view != null
        if (!want) {
            removeBox()
            return
        }
        if (box != null) return
        val v = view ?: return
        val lp = params ?: return
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
        addBox()
        try {
            wm.addView(v, lp)
        } catch (_: Throwable) {
        }
    }

    fun clearInk() {
        view?.clearInk()
        vibrate(25)
    }

    fun undo() {
        view?.undo()
    }

    fun setTool(t: Int) {
        if (tool == t) return
        tool = t
        vibrate(15)
        view?.onToolChanged(t)
        view?.invalidate()
        bubble.refreshMenu()
    }

    /** Nib held still: how long, whether it is used at all, and what it does (all set in the app). */
    fun holdMs(): Long = Prefs.holdSec(prefs) * 1000L

    fun holdEnabled(): Boolean = Prefs.action(prefs, Prefs.HOLD_ACTION) != Act.NONE

    fun holdAction() {
        keys.runHold()
    }

    fun setPad(on: Boolean) {
        prefs.edit().putBoolean(Prefs.PAD_ON, on).apply()
        if (!on) {
            try {
                TileService.requestListeningState(service, ComponentName(service, PadTileService::class.java))
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- touch routing

    /** Pen capture mode: never touchable. Window mode: always touchable (except while replaying a finger). */
    private fun wantTouchable(): Boolean = windowMode() && !replaying

    private fun updateTouchable() {
        val v = view ?: return
        val lp = params ?: return
        val want = wantTouchable()
        if (want == touchableNow) return
        lp.flags = if (want) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm.updateViewLayout(v, lp)
            touchableNow = want
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- input from the service

    /** Pen events delivered by the system (Android 14+, pen capture mode). */
    fun onPenMotion(e: MotionEvent) {
        val v = view ?: return
        if (!padOn() || windowMode()) return
        v.onPenCaptured(e)
    }

    /**
     * Xiaomi smart pen side buttons arrive as Page Up / Page Down keys.
     * What each button does (1 tap / 2 taps) is chosen in the app.
     */
    fun onKey(e: KeyEvent): Boolean {
        if (view == null || !padOn()) return false
        val c = e.keyCode
        if (c != KeyEvent.KEYCODE_PAGE_UP && c != KeyEvent.KEYCODE_PAGE_DOWN) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeyAt >= 90L) {
                lastKeyAt = now
                if (c == KeyEvent.KEYCODE_PAGE_DOWN) keys.pageDown() else keys.pageUp()
            }
        }
        return true
    }

    /**
     * Window mode: a finger tap / swipe that landed on the overlay is re-played onto the app underneath.
     * The overlay is made untouchable for the duration so the injected gesture falls through.
     */
    fun replay(xs: FloatArray, ys: FloatArray, n: Int, durMs: Long) {
        if (view == null || n <= 0) return
        val p = Path()
        p.moveTo(xs[0], ys[0])
        var lx = xs[0]
        var ly = ys[0]
        for (k in 1 until n) {
            if (abs(xs[k] - lx) + abs(ys[k] - ly) > 2f) {
                p.lineTo(xs[k], ys[k])
                lx = xs[k]
                ly = ys[k]
            }
        }
        val dur = if (durMs < 1L) 1L else if (durMs > 4000L) 4000L else durMs
        val g = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0L, dur))
                .build()
        } catch (_: Throwable) {
            return
        }
        replaying = true
        updateTouchable()
        main.postDelayed({
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = finishReplay()
                override fun onCancelled(gestureDescription: GestureDescription?) = finishReplay()
            }
            val ok = try {
                service.dispatchGesture(g, cb, null)
            } catch (_: Throwable) {
                false
            }
            if (!ok) finishReplay()
        }, 30L)
    }

    private fun finishReplay() {
        main.postDelayed({
            replaying = false
            updateTouchable()
        }, 40L)
    }

    companion object {
        private const val BASE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }
}
