package com.raj.flotingpen

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A tiny, almost invisible bubble (5% opacity by default) that floats above every app.
 * Tap it with a finger to open the quick menu: switch pen, tools, colour, size, erase screen.
 * Drag it to move it. Its own small window is the only part of the screen it touches.
 */
class FloatingBubble(private val service: FloatingPenService, private val ctrl: PadController) {

    private val prefs: SharedPreferences = Prefs.get(service)
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = service.resources.displayMetrics.density
    private val sizePx = (48f * density).toInt()

    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var scrim: FrameLayout? = null
    private val refreshers = ArrayList<() -> Unit>()

    private fun dp(v: Int) = (v * density).toInt()

    @Suppress("DEPRECATION")
    private fun screen(): Point {
        val p = Point()
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            p.set(b.width(), b.height())
        } else {
            wm.defaultDisplay.getRealSize(p)
        }
        return p
    }

    // ====================================================================================
    //  bubble window
    // ====================================================================================

    /** Show / hide / restyle the bubble from the current settings. */
    fun sync() {
        val want = prefs.getBoolean(Prefs.BUBBLE_ON, true)
        if (!want) {
            remove()
            return
        }
        val b = bubble
        if (b == null) {
            add()
        } else {
            b.alphaF = Prefs.bubbleAlpha(prefs) / 100f
            b.invalidate()
        }
    }

    private fun add() {
        val s = screen()
        val savedX = prefs.getInt(Prefs.BUBBLE_X, -1)
        val savedY = prefs.getInt(Prefs.BUBBLE_Y, -1)
        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = if (savedX >= 0) savedX else s.x - sizePx
        lp.y = if (savedY >= 0) savedY else (s.y * 0.35f).toInt()
        lp.x = lp.x.coerceIn(0, max(0, s.x - sizePx))
        lp.y = lp.y.coerceIn(0, max(0, s.y - sizePx))
        val v = BubbleView(service)
        v.alphaF = Prefs.bubbleAlpha(prefs) / 100f
        try {
            wm.addView(v, lp)
        } catch (_: Throwable) {
            return
        }
        bubble = v
        bubbleLp = lp
        // For a moment show the bubble clearly so you know where it is.
        v.boost(2500L)
    }

    fun remove() {
        closeMenu()
        val v = bubble ?: return
        bubble = null
        bubbleLp = null
        try {
            wm.removeView(v)
        } catch (_: Throwable) {
        }
    }

    /** After a rotation: bring the bubble back inside the screen. */
    fun clampToScreen() {
        val v = bubble ?: return
        val lp = bubbleLp ?: return
        val s = screen()
        val nx = lp.x.coerceIn(0, max(0, s.x - sizePx))
        val ny = lp.y.coerceIn(0, max(0, s.y - sizePx))
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            try {
                wm.updateViewLayout(v, lp)
            } catch (_: Throwable) {
            }
        }
    }

    private inner class BubbleView(ctx: Context) : View(ctx) {
        var alphaF = 0.05f
        private var pressed = false
        private var boostUntil = 0L
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * density
        }
        private var sx = 0f
        private var sy = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        fun boost(ms: Long) {
            boostUntil = System.currentTimeMillis() + ms
            invalidate()
            postInvalidateDelayed(ms + 50L)
        }

        override fun onDraw(c: Canvas) {
            val boosted = System.currentTimeMillis() < boostUntil
            val a = when {
                pressed -> 0.65f
                boosted -> 0.55f
                else -> alphaF
            }
            val cx = width / 2f
            val cy = height / 2f
            val r = 15f * density
            fill.color = Color.argb((a * 255f).toInt().coerceIn(0, 255), 120, 130, 150)
            c.drawCircle(cx, cy, r, fill)
            ring.color = Color.argb((a * 255f).toInt().coerceIn(0, 255), 255, 255, 255)
            c.drawCircle(cx, cy, r, ring)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    dragging = false
                    sx = e.rawX
                    sy = e.rawY
                    startX = lp.x
                    startY = lp.y
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx
                    val dy = e.rawY - sy
                    if (!dragging && hypot(dx, dy) > 10f * density) {
                        dragging = true
                        closeMenu()
                    }
                    if (dragging) {
                        val s = screen()
                        lp.x = (startX + dx).toInt().coerceIn(0, max(0, s.x - sizePx))
                        lp.y = (startY + dy).toInt().coerceIn(0, max(0, s.y - sizePx))
                        try {
                            wm.updateViewLayout(this, lp)
                        } catch (_: Throwable) {
                        }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    pressed = false
                    if (dragging) {
                        prefs.edit().putInt(Prefs.BUBBLE_X, lp.x).putInt(Prefs.BUBBLE_Y, lp.y).apply()
                    } else {
                        toggleMenu()
                    }
                    invalidate()
                }
                MotionEvent.ACTION_CANCEL -> {
                    pressed = false
                    invalidate()
                }
            }
            return true
        }
    }

    // ====================================================================================
    //  quick menu
    // ====================================================================================

    private fun toggleMenu() {
        if (scrim != null) closeMenu() else openMenu()
    }

    fun closeMenu() {
        val s = scrim ?: return
        scrim = null
        refreshers.clear()
        try {
            wm.removeView(s)
        } catch (_: Throwable) {
        }
    }

    /** Re-style the open menu after a setting changed (pen button, hold-to-switch colour, app...). */
    fun refreshMenu() {
        if (scrim == null) return
        for (r in refreshers) r()
    }

    private fun round(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
        }

    private fun styleBtn(v: TextView, selected: Boolean, selColor: Int = 0xFFFFB300.toInt()) {
        v.background = if (selected) round(selColor, 12) else round(0xFF2A3350.toInt(), 12)
        v.setTextColor(if (selected) 0xFF1A1A1A.toInt() else Color.WHITE)
    }

    private fun btn(text: String, onClick: () -> Unit): TextView = TextView(service).apply {
        this.text = text
        gravity = Gravity.CENTER
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View): LinearLayout {
        val l = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            l.addView(v, lp)
        }
        return l
    }

    private fun caption(text: String): TextView = TextView(service).apply {
        this.text = text
        textSize = 11f
        setTextColor(0xFF9AA6BF.toInt())
        setPadding(dp(4), dp(6), 0, 0)
    }

    private fun openMenu() {
        if (scrim != null) return
        val lp0 = bubbleLp ?: return
        val s = screen()
        refreshers.clear()

        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
            background = round(0xF2141B2D.toInt(), 18, 0x33FFFFFF, 1)
            isClickable = true
        }

        // ---- header ----
        val title = TextView(service).apply {
            text = "FloatingPen"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFFFB300.toInt())
            setPadding(dp(4), 0, 0, 0)
        }
        val close = btn("✕") { closeMenu() }.also { it.background = round(0xFF2A3350.toInt(), 12) }
        val header = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(46), dp(40)))
        panel.addView(header)

        // ---- pen type ----
        panel.addView(caption("PEN"))
        val penBtns = ArrayList<TextView>()
        for (i in 0..3) {
            val b = btn(Pen.SHORT[i]) { prefs.edit().putInt(Prefs.PEN_TYPE, i).apply() }
            penBtns.add(b)
        }
        panel.addView(row(penBtns[0], penBtns[1]))
        panel.addView(row(penBtns[2], penBtns[3]))
        refreshers.add {
            val cur = prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3)
            for (i in 0..3) styleBtn(penBtns[i], i == cur)
        }

        // ---- tool ----
        panel.addView(caption("TOOL"))
        val toolBtns = ArrayList<TextView>()
        val toolNames = arrayOf("Write", "Erase stroke", "Lasso")
        for (i in 0..2) {
            toolBtns.add(btn(toolNames[i]) {
                ctrl.setTool(i)
                closeMenu()
            })
        }
        panel.addView(row(toolBtns[0], toolBtns[1], toolBtns[2]))
        refreshers.add {
            val cur = ctrl.tool()
            for (i in 0..2) styleBtn(toolBtns[i], i == cur)
        }

        // ---- colour ----
        panel.addView(caption("COLOUR  (hold the nib still 4 s = red / blue)"))
        val swatches = ArrayList<View>()
        for (c in Prefs.PALETTE) {
            val v = View(service)
            v.setOnClickListener { prefs.edit().putInt(Prefs.COLOR, c).apply() }
            swatches.add(v)
        }
        panel.addView(row(*swatches.toTypedArray()))
        refreshers.add {
            val cur = prefs.getInt(Prefs.COLOR, Prefs.RED)
            for (i in swatches.indices) {
                val c = Prefs.PALETTE[i]
                swatches[i].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(if (c == cur) dp(4) else dp(1), if (c == cur) Color.WHITE else 0x55FFFFFF)
                }
            }
        }

        // ---- size ----
        panel.addView(caption("PEN SIZE"))
        val sizeLabel = TextView(service).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val minus = btn("−") { Prefs.stepSize(prefs, -1) }.also { it.textSize = 22f; styleBtn(it, false) }
        val plus = btn("+") { Prefs.stepSize(prefs, +1) }.also { it.textSize = 22f; styleBtn(it, false) }
        panel.addView(row(minus, sizeLabel, plus))
        refreshers.add { sizeLabel.text = String.format("%.1f dp", Prefs.size(prefs)) }

        // ---- opacity ----
        panel.addView(caption("INK OPACITY"))
        val opLabel = TextView(service).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val opMinus = btn("−") { Prefs.stepOpacity(prefs, -1) }.also { it.textSize = 22f; styleBtn(it, false) }
        val opPlus = btn("+") { Prefs.stepOpacity(prefs, +1) }.also { it.textSize = 22f; styleBtn(it, false) }
        panel.addView(row(opMinus, opLabel, opPlus))
        refreshers.add { opLabel.text = Prefs.opacity(prefs).toString() + " %" }

        // ---- actions ----
        panel.addView(caption("ACTIONS"))
        val undo = btn("Undo") { ctrl.undo() }.also { styleBtn(it, false) }
        val erase = btn("Erase screen") {
            ctrl.clearInk()
            closeMenu()
        }.also { styleBtn(it, true, 0xFFD92B2B.toInt()); it.setTextColor(Color.WHITE) }
        val eraseLp = LinearLayout(service).apply { orientation = LinearLayout.HORIZONTAL }
        run {
            val l1 = LinearLayout.LayoutParams(0, dp(52), 1f)
            l1.setMargins(dp(3), dp(3), dp(3), dp(3))
            val l2 = LinearLayout.LayoutParams(0, dp(52), 2f)
            l2.setMargins(dp(3), dp(3), dp(3), dp(3))
            eraseLp.addView(undo, l1)
            eraseLp.addView(erase, l2)
        }
        panel.addView(eraseLp)

        val padBtn = btn("") { ctrl.setPad(!ctrl.padOn()) }
        panel.addView(row(padBtn))
        refreshers.add {
            val on = ctrl.padOn()
            padBtn.text = if (on) "Pen layer: ON  (tap to switch off)" else "Pen layer: OFF  (tap to switch on)"
            styleBtn(padBtn, on, 0xFF2E9E6B.toInt())
            if (on) padBtn.setTextColor(Color.WHITE)
        }

        for (r in refreshers) r()

        // ---- place the panel next to the bubble ----
        val scroll = ScrollView(service).apply {
            isVerticalScrollBarEnabled = false
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val panelW = min(dp(330), s.x - dp(24))
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxH = s.y - dp(40)
        val panelH = min(panel.measuredHeight, maxH)
        val bubbleCx = lp0.x + sizePx / 2
        val left = if (bubbleCx > s.x / 2) lp0.x - panelW - dp(8) else lp0.x + sizePx + dp(8)
        val top = (lp0.y + sizePx / 2 - panelH / 2).coerceIn(dp(20), max(dp(20), s.y - panelH - dp(20)))

        val root = FrameLayout(service)
        val flp = FrameLayout.LayoutParams(panelW, panelH)
        flp.gravity = Gravity.TOP or Gravity.START
        flp.leftMargin = left.coerceIn(dp(8), max(dp(8), s.x - panelW - dp(8)))
        flp.topMargin = top
        root.addView(scroll, flp)
        root.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) closeMenu()
            true
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        lp.gravity = Gravity.TOP or Gravity.START
        try {
            wm.addView(root, lp)
            scrim = root
        } catch (_: Throwable) {
            refreshers.clear()
        }
    }
}
