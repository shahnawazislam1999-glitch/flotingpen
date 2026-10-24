package com.raj.flotingpen

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Settings screen. Everything is built in code, so the project needs no extra libraries. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var padSwitch: Switch
    private lateinit var windowSwitch: Switch
    private lateinit var screenOffSwitch: Switch
    private lateinit var colorRed: TextView
    private lateinit var colorBlue: TextView
    private lateinit var a11yStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var modeInfo: TextView
    private lateinit var probe: PenProbe
    private var binding = false

    private val bg = Color.parseColor("#0F1420")
    private val card = Color.parseColor("#1A2133")
    private val accent = Color.parseColor("#FFB300")
    private val textMain = Color.WHITE
    private val textDim = Color.parseColor("#9AA6BF")
    private val ok = Color.parseColor("#4CD08B")
    private val bad = Color.parseColor("#FF6B6B")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ====================================================================================
    //  UI
    // ====================================================================================

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            isFillViewport = true
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        root.addView(text("FloatingPen", 26f, textMain, bold = true))
        root.addView(text("The pen writes on the screen. Your fingers use the apps normally.", 14f, textDim).also { pad(it, 0, 2, 0, 14) })

        // ---- writing pad switch ----
        val padCard = card()
        padSwitch = Switch(this).apply {
            text = "Writing pad"
            textSize = 18f
            setTextColor(textMain)
        }
        padSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (binding) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(Prefs.PAD_ON, on).apply()
            if (on && !a11yEnabled()) {
                Toast.makeText(this, "Turn on the FloatingPen accessibility service first", Toast.LENGTH_LONG).show()
                openAccessibility()
            }
        }
        padCard.addView(padSwitch)
        modeInfo = text("", 12f, textDim).also { pad(it, 0, 6, 0, 0) }
        padCard.addView(modeInfo)
        padCard.addView(
            text(
                "Also in Quick Settings (\"Writing pad\" tile) and in the notification panel (Turn off / Clear). " +
                    "Pen side button = clear the ink. Power button = pad turns off.",
                12f, textDim,
            ).also { pad(it, 0, 6, 0, 0) },
        )
        root.addView(padCard)

        // ---- pen ----
        val penCard = card()
        penCard.addView(heading("Pen"))
        val sizeLabel = text("", 13f, textDim)
        penCard.addView(sizeLabel)
        val sizeBar = SeekBar(this).apply { max = 11 }
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                sizeLabel.text = "Size: ${p + 1} dp"
                if (fromUser && !binding) prefs.edit().putFloat(Prefs.SIZE_DP, (p + 1).toFloat()).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        penCard.addView(sizeBar)
        sizeBar.progress = (prefs.getFloat(Prefs.SIZE_DP, 3f).toInt() - 1).coerceIn(0, 11)

        penCard.addView(text("Colour", 13f, textDim).also { pad(it, 0, 10, 0, 6) })
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorRed = chip("Red")
        colorBlue = chip("Blue")
        colorRed.setOnClickListener { prefs.edit().putInt(Prefs.COLOR, Prefs.RED).apply(); refresh() }
        colorBlue.setOnClickListener { prefs.edit().putInt(Prefs.COLOR, Prefs.BLUE).apply(); refresh() }
        colorRow.addView(colorRed, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.rightMargin = dp(8) })
        colorRow.addView(colorBlue, LinearLayout.LayoutParams(0, dp(44), 1f))
        penCard.addView(colorRow)
        root.addView(penCard)

        // ---- advanced ----
        val advCard = card()
        advCard.addView(heading("Advanced"))
        windowSwitch = Switch(this).apply {
            text = "Window mode (fallback)"
            setTextColor(textMain)
            textSize = 14f
        }
        windowSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.WINDOW_MODE, on).apply()
        }
        advCard.addView(windowSwitch)
        advCard.addView(
            text(
                "Off (Android 14+): the system gives the pen's events to the app, fingers are never touched. " +
                    "On: use only if the pen does not write in the default mode, or on Android 13 and older. " +
                    "The layer then catches touches and replays finger taps / swipes to the app below (scrolling is applied when you lift).",
                12f, textDim,
            ).also { pad(it, 0, 4, 0, 0) },
        )
        screenOffSwitch = Switch(this).apply {
            text = "Turn the pad off when the power button is pressed"
            setTextColor(textMain)
            textSize = 14f
        }
        screenOffSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.OFF_ON_SCREEN_OFF, on).apply()
        }
        advCard.addView(screenOffSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) })
        root.addView(advCard)

        // ---- permissions ----
        val permCard = card()
        permCard.addView(heading("Permissions"))
        a11yStatus = permRow(permCard, "Accessibility service (draws the pen layer)") { openAccessibility() }
        notifStatus = permRow(permCard, "Notifications (Turn off button)") { requestNotifications() }
        permCard.addView(
            text(
                "If Android blocks the accessibility switch for a sideloaded app: App info > ⋮ > Allow restricted settings, then try again.",
                12f, textDim,
            ).also { pad(it, 0, 8, 0, 0) },
        )
        root.addView(permCard)

        // ---- pen test ----
        val testCard = card()
        testCard.addView(heading("Pen test (switch the writing pad off first)"))
        testCard.addView(
            text(
                "Touch or hover this box with the pen and press its side buttons to see what Android reports.",
                12f, textDim,
            ).also { pad(it, 0, 0, 0, 8) },
        )
        probe = PenProbe(this)
        testCard.addView(probe, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(190)))
        root.addView(testCard)
    }

    private fun refresh() {
        binding = true
        padSwitch.isChecked = prefs.getBoolean(Prefs.PAD_ON, false)
        val window = if (prefs.contains(Prefs.WINDOW_MODE)) prefs.getBoolean(Prefs.WINDOW_MODE, false) else Prefs.windowDefault()
        windowSwitch.isChecked = window
        screenOffSwitch.isChecked = prefs.getBoolean(Prefs.OFF_ON_SCREEN_OFF, true)
        modeInfo.text = if (window) "Mode: window (pen writes, finger taps are replayed)" else "Mode: pen capture (fingers are never touched)"
        val blue = prefs.getInt(Prefs.COLOR, Prefs.RED) == Prefs.BLUE
        styleChip(colorRed, !blue, Prefs.RED)
        styleChip(colorBlue, blue, Prefs.BLUE)
        binding = false
        setStatus(a11yStatus, a11yEnabled())
        setStatus(notifStatus, notifGranted())
    }

    /** Pen test: tool type, source, device and buttons, plus the last key (pen side buttons). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && ::probe.isInitialized) {
            probe.setKey(KeyEvent.keyCodeToString(event.keyCode) + " (" + (event.device?.name ?: "?") + ")")
        }
        return super.dispatchKeyEvent(event)
    }

    // ====================================================================================
    //  permissions
    // ====================================================================================

    private fun a11yEnabled(): Boolean {
        if (FloatingPenService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, FloatingPenService::class.java)
        return s.split(':').any { it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true) }
    }

    private fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    // ====================================================================================
    //  small view helpers
    // ====================================================================================

    private fun card(): LinearLayout {
        val l = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(card)
                cornerRadius = dp(16).toFloat()
            }
        }
        l.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.bottomMargin = dp(12) }
        return l
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun heading(s: String) = text(s, 15f, accent, bold = true).also { pad(it, 0, 0, 0, 8) }

    private fun pad(v: View, l: Int, t: Int, r: Int, b: Int) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b))
    }

    private fun chip(label: String) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun styleChip(v: TextView, selected: Boolean, color: Int) {
        v.background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(12).toFloat()
            if (selected) setStroke(dp(3), Color.WHITE) else setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        }
        v.alpha = if (selected) 1f else 0.55f
    }

    private fun permRow(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener { onClick() }
        }
        val name = text(label, 14f, textMain)
        val status = text("", 13f, textDim).also { it.typeface = Typeface.DEFAULT_BOLD }
        row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(status)
        parent.addView(row)
        return status
    }

    private fun setStatus(v: TextView, granted: Boolean) {
        v.text = if (granted) "ON" else "TAP TO SET"
        v.setTextColor(if (granted) ok else bad)
    }

    /** Small diagnostic box: what does Android report for the pen? */
    private inner class PenProbe(ctx: Context) : FrameLayout(ctx) {
        private val tv = TextView(ctx).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextColor(textMain)
            text = "waiting for the pen…"
        }
        private var lastKey = "none yet"

        init {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0F1420"))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), Color.parseColor("#33FFFFFF"))
            }
            setPadding(dp(10), dp(8), dp(10), dp(8))
            addView(tv)
        }

        fun setKey(k: String) {
            lastKey = k
            val t = tv.text.toString()
            val i = t.indexOf("\nlast key:")
            tv.text = (if (i >= 0) t.substring(0, i) else t) + "\nlast key: " + lastKey
        }

        private fun show(e: MotionEvent, what: String) {
            val tool = when (e.getToolType(0)) {
                MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
                MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
                MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
                MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
                else -> "UNKNOWN"
            }
            val dev = InputDevice.getDevice(e.deviceId)?.name ?: "?"
            tv.text = "event: $what\n" +
                "tool type: $tool\n" +
                "source: 0x" + Integer.toHexString(e.source) + "\n" +
                "device: $dev\n" +
                "pressure: " + String.format("%.2f", e.getPressure(0)) + "\n" +
                "buttons: 0x" + Integer.toHexString(e.buttonState) + "\n" +
                "last key: " + lastKey
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            show(e, "touch " + MotionEvent.actionToString(e.actionMasked))
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }

        override fun onHoverEvent(e: MotionEvent): Boolean {
            show(e, "hover " + MotionEvent.actionToString(e.actionMasked))
            return true
        }
    }

    companion object {
        private const val REQ_NOTIF = 12
    }
}
