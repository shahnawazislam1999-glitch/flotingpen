package com.raj.flotingpen

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import kotlin.math.abs

/**
 * Owns the overlay window and decides, moment by moment, whether it catches touches.
 *
 * Touch routing (Smart pen mode):
 *  - Pen idle   -> window is NOT_TOUCHABLE: fingers scroll / tap the apps underneath as normal.
 *  - Pen active -> (hover, touch, or within the gesture window after the pen leaves) the window
 *                  becomes touchable, the pen writes and fingers turn into gesture tools.
 * Finger mode and Compatibility mode keep the window touchable all the time.
 */
class PadController(private val service: AccessibilityService) {

    private val prefs: SharedPreferences = Prefs.get(service)
    private val wm = service.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val density = service.resources.displayMetrics.density
    private val vibrator: Vibrator? = service.getSystemService(Vibrator::class.java)

    private var view: InkView? = null
    private var params: WindowManager.LayoutParams? = null
    private var touchableNow = false
    private var replaying = false
    private var sessionUntil = 0L
    private var expireScheduled = false
    private var lastKeyAt = 0L

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.PAD_ON) applyPad() else updateTouchable()
    }

    private val expire: Runnable = Runnable {
        expireScheduled = false
        val remaining = sessionUntil - SystemClock.uptimeMillis()
        if (remaining > 0L || view?.busy == true) {
            expireScheduled = true
            main.postDelayed(expire, max(remaining, 400L) + 30L)
        } else {
            updateTouchable()
        }
    }

    private fun max(a: Long, b: Long) = if (a > b) a else b

    // ====================================================================================
    //  settings (read live, so the settings screen takes effect immediately)
    // ====================================================================================

    fun padOn() = prefs.getBoolean(Prefs.PAD_ON, false)
    fun fingerMode() = prefs.getBoolean(Prefs.FINGER_MODE, false)
    fun penType() = prefs.getInt(Prefs.PEN_TYPE, PEN_NIRMAL)
    fun sizePx() = prefs.getFloat(Prefs.SIZE_DP, 3f) * density
    fun annotOn() = prefs.getBoolean(Prefs.ANNOT, false)
    private fun sessionMs() = prefs.getInt(Prefs.SESSION_S, 4) * 1000L

    /** Compatibility mode: always catch touches and replay finger gestures to the app below. */
    fun compat(): Boolean =
        if (prefs.contains(Prefs.COMPAT)) prefs.getBoolean(Prefs.COMPAT, true) else Build.VERSION.SDK_INT < 34

    fun color(): Int = if (prefs.getInt(Prefs.COLOR, Prefs.RED) == Prefs.BLUE) Prefs.BLUE else Prefs.RED

    fun flipColor() {
        prefs.edit().putInt(Prefs.COLOR, if (color() == Prefs.RED) Prefs.BLUE else Prefs.RED).apply()
        vibrate(18)
    }

    fun toggleAnnot() {
        prefs.edit().putBoolean(Prefs.ANNOT, !annotOn()).apply()
        vibrate(if (annotOn()) 40L else 25L)
    }

    fun vibrate(ms: Long) {
        val v = vibrator ?: return
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) {
        }
    }

    // ====================================================================================
    //  lifecycle
    // ====================================================================================

    fun start() {
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        applyPad()
    }

    fun stop() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        main.removeCallbacksAndMessages(null)
        hide()
    }

    private fun applyPad() {
        if (padOn()) show() else hide()
    }

    private fun show() {
        if (view != null) return
        val v = InkView(service, this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            BASE_FLAGS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val touchable = wantTouchable()
        if (!touchable) lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        try {
            wm.addView(v, lp)
        } catch (e: Throwable) {
            return
        }
        view = v
        params = lp
        touchableNow = touchable
    }

    private fun hide() {
        val v = view ?: return
        view = null
        params = null
        touchableNow = false
        sessionUntil = 0L
        v.release()
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    // ====================================================================================
    //  touch routing
    // ====================================================================================

    fun isCapturing() = touchableNow

    fun sessionActive(): Boolean = SystemClock.uptimeMillis() < sessionUntil || view?.busy == true

    private fun wantTouchable(): Boolean {
        if (replaying) return false
        return fingerMode() || compat() || sessionActive()
    }

    /** Called on every pen event / finger gesture: keeps the "gesture ready" window open. */
    fun markActivity() {
        sessionUntil = SystemClock.uptimeMillis() + sessionMs()
        if (!touchableNow) updateTouchable()
        if (!expireScheduled) {
            expireScheduled = true
            main.postDelayed(expire, sessionMs() + 30L)
        }
    }

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

    // ====================================================================================
    //  input from the accessibility service
    // ====================================================================================

    /** Stylus events observed system-wide (Android 14+). */
    fun onObservedMotion(e: MotionEvent) {
        val v = view ?: return
        if (!padOn()) return
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> markActivity()
            MotionEvent.ACTION_HOVER_EXIT -> {}
            else -> v.observedStylus(e)
        }
    }

    /**
     * Xiaomi smart pen side buttons arrive as keyboard keys:
     *   lower button  (Page Down / Down / Right) -> lasso erase
     *   upper button  (Page Up / Up / Left)      -> slide the canvas back to normal
     */
    fun onKey(e: KeyEvent): Boolean {
        val v = view ?: return false
        if (!padOn()) return false
        val c = e.keyCode
        val up = c == KeyEvent.KEYCODE_PAGE_UP || c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_LEFT
        val down = c == KeyEvent.KEYCODE_PAGE_DOWN || c == KeyEvent.KEYCODE_DPAD_DOWN || c == KeyEvent.KEYCODE_DPAD_RIGHT
        if (!up && !down) return false
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
            val now = SystemClock.uptimeMillis()
            if (now - lastKeyAt >= 120L) {
                lastKeyAt = now
                markActivity()
                if (down) v.toggleLasso() else v.recenter()
            }
        }
        return true
    }

    /**
     * Compatibility mode: a finger tap / swipe that landed on the overlay while the pen was idle is
     * re-played onto the app underneath. The overlay is made untouchable for the duration so the
     * injected gesture falls through to the app.
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
