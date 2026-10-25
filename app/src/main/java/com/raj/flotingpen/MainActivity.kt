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
import kotlin.math.min

/** Home screen: every pen mode, a practice pad, the bubble and the settings. Built in code, no extra libraries. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var padSwitch: Switch
    private lateinit var windowSwitch: Switch
    private lateinit var tapSwitch: Switch
    private lateinit var bubbleSwitch: Switch
    private lateinit var autoSwitch: Switch
    private lateinit var boxSwitch: Switch
    private lateinit var a11yStatus: TextView
    private lateinit var notifStatus: TextView
    private lateinit var battStatus: TextView
    private lateinit var modeInfo: TextView
    private lateinit var probe: PenProbe
    private lateinit var canvas: InkCanvasView
    private lateinit var sizeLabel: TextView
    private lateinit var sizeBar: SeekBar
    private lateinit var opLabel: TextView
    private lateinit var opBar: SeekBar
    private lateinit var bubbleLabel: TextView
    private lateinit var bubbleBar: SeekBar
    private lateinit var fingerChip: TextView
    private lateinit var paperChip: TextView
    private val penChips = ArrayList<TextView>()
    private val toolChips = ArrayList<TextView>()
    private val swatches = ArrayList<View>()
    private var binding = false

    private val keys by lazy {
        PenButtons(
            this,
            { if (::canvas.isInitialized) canvas.tool else Pen.TOOL_WRITE },
            { t ->
                if (::canvas.isInitialized) {
                    canvas.tool = t
                    refresh()
                }
            },
            { if (::canvas.isInitialized) canvas.engine.clearAll() },
            {
                if (::canvas.isInitialized) {
                    canvas.engine.undo()
                    canvas.invalidate()
                }
            },
            { },
        )
    }

    private lateinit var controls: ControlsUi

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        runOnUiThread { if (::canvas.isInitialized) refresh() }
    }

    private val bg = Color.parseColor("#0F1420")
    private val card = Color.parseColor("#1A2133")
    private val accent = Color.parseColor("#FFB300")
    private val textMain = Color.WHITE
    private val textDim = Color.parseColor("#9AA6BF")
    private val ok = Color.parseColor("#4CD08B")
    private val bad = Color.parseColor("#FF6B6B")
    private val chipOff = Color.parseColor("#2A3350")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        A11yGuard.ensure(this)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
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
        }
        val maxW = min(resources.displayMetrics.widthPixels, dp(780))
        scroll.addView(root, FrameLayout.LayoutParams(maxW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        setContentView(scroll)

        root.addView(text("FloatingPen  v3", 26f, textMain, bold = true))
        root.addView(text("The pen writes on the screen. Your fingers use the apps normally.", 14f, textDim).also { pad(it, 0, 2, 0, 14) })

        // ---- writing pad switch ----
        val padCard = card()
        padSwitch = Switch(this).apply {
            text = "Writing pad (pen layer over all apps)"
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
                "Smart pen buttons (pad on): choose what the Up / Down buttons (1 tap or 2 taps) and a held nib do in " +
                    "\"Smart pen buttons & gestures\" below. Default: Down = erase mode, Up = lasso, Up twice = clear screen, " +
                    "nib held 3 s = red / blue.\n" +
                    "• Quick pen tap without writing = click in the app below\n" +
                    "Also: Quick Settings tile, notification buttons, and the tiny floating bubble.",
                12f, textDim,
            ).also { pad(it, 0, 8, 0, 0) },
        )
        root.addView(padCard)

        // ---- pen modes ----
        val penCard = card()
        penCard.addView(heading("Pen modes"))
        val modeNote = arrayOf(
            "Nirmal Pen - smooth, fast, no-lag handwriting",
            "Classic Pen - bolder, heavier smoothing",
            "Pencil - grainy graphite, for tiny handwriting",
            "Annotation Pen - glowing mark that fades after 1 second",
        )
        val r1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        penChips.clear()
        for (i in 0..3) {
            val c = chip(Pen.NAMES[i])
            c.setOnClickListener { prefs.edit().putInt(Prefs.PEN_TYPE, i).apply(); refresh() }
            penChips.add(c)
            val lp = LinearLayout.LayoutParams(0, dp(46), 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            (if (i < 2) r1 else r2).addView(c, lp)
        }
        penCard.addView(r1)
        penCard.addView(r2)
        penCard.addView(text(modeNote.joinToString("\n"), 12f, textDim).also { pad(it, 0, 6, 0, 0) })

        sizeLabel = text("", 13f, textDim).also { pad(it, 0, 12, 0, 0) }
        penCard.addView(sizeLabel)
        sizeBar = SeekBar(this).apply { max = 58 }
        sizeBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = 1f + p * 0.5f
                sizeLabel.text = sizeTitle() + ": " + String.format("%.1f", v) + " dp"
                if (fromUser && !binding) prefs.edit().putFloat(Prefs.sizeKey(prefs), v).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        penCard.addView(sizeBar)

        opLabel = text("", 13f, textDim).also { pad(it, 0, 8, 0, 0) }
        penCard.addView(opLabel)
        opBar = SeekBar(this).apply { max = 90 }
        opBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                opLabel.text = "Ink opacity: " + (10 + p) + " %"
                if (fromUser && !binding) prefs.edit().putInt(Prefs.OPACITY, 10 + p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        penCard.addView(opBar)

        penCard.addView(text("Colour", 13f, textDim).also { pad(it, 0, 12, 0, 6) })
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        swatches.clear()
        for (c in Prefs.PALETTE) {
            val v = View(this)
            v.setOnClickListener { prefs.edit().putInt(Prefs.COLOR, c).apply(); refresh() }
            swatches.add(v)
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            lp.setMargins(dp(4), 0, dp(4), 0)
            colorRow.addView(v, lp)
        }
        penCard.addView(colorRow)
        root.addView(penCard)

        // ---- practice pad ----
        val padCard2 = card()
        padCard2.addView(heading("Practice pad - try every pen here"))
        val tr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        toolChips.clear()
        val toolNames = arrayOf("Write", "Erase stroke", "Lasso")
        for (i in 0..2) {
            val c = chip(toolNames[i])
            c.setOnClickListener { canvas.tool = i; refresh() }
            toolChips.add(c)
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            tr.addView(c, lp)
        }
        padCard2.addView(tr)

        canvas = InkCanvasView(this)
        canvas.onToolAuto = { refresh() }
        val ar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, onClick: () -> Unit): TextView {
            val c = chip(label)
            c.background = rounded(chipOff, 12)
            c.setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(0, dp(44), 1f)
            lp.setMargins(dp(3), dp(3), dp(3), dp(3))
            ar.addView(c, lp)
            return c
        }
        action("Undo") { canvas.engine.undo(); canvas.invalidate() }
        action("Clear") { canvas.engine.clearAll(); canvas.invalidate() }
        paperChip = action("Paper") { canvas.paper = !canvas.paper; refresh() }
        fingerChip = action("Finger") { canvas.fingerWrites = !canvas.fingerWrites; refresh() }
        padCard2.addView(ar)

        padCard2.addView(canvas, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)).also { it.topMargin = dp(8) })
        padCard2.addView(
            text(
                "Pen writes, finger erases whole strokes (tap \"Finger\" to let the finger write). " +
                    "The smart pen buttons work here too when the writing pad is off.",
                12f, textDim,
            ).also { pad(it, 0, 8, 0, 0) },
        )
        root.addView(padCard2)

        // ---- smart pen buttons, custom actions, presets ----
        controls = ControlsUi(this)
        root.addView(controls.view)

        // ---- floating bubble ----
        val bubCard = card()
        bubCard.addView(heading("Floating bubble"))
        bubbleSwitch = Switch(this).apply {
            text = "Show the tiny bubble"
            setTextColor(textMain)
            textSize = 14f
        }
        bubbleSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.BUBBLE_ON, on).apply()
        }
        bubCard.addView(bubbleSwitch)
        bubbleLabel = text("", 13f, textDim).also { pad(it, 0, 10, 0, 0) }
        bubCard.addView(bubbleLabel)
        bubbleBar = SeekBar(this).apply { max = 59 }
        bubbleBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                bubbleLabel.text = "Bubble visibility: " + (1 + p) + " %" + (if (p == 4) "  (default)" else "")
                if (fromUser && !binding) prefs.edit().putInt(Prefs.BUBBLE_ALPHA, 1 + p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        bubCard.addView(bubbleBar)
        bubCard.addView(
            text(
                "Tap the bubble with a finger for the quick menu: switch pen, erase / lasso, colour, size, undo, " +
                    "erase the whole screen, pen layer on/off. Drag it to move it. At 5% it is almost invisible - " +
                    "raise the visibility if you cannot find it (it also glows for a moment whenever the service starts).",
                12f, textDim,
            ).also { pad(it, 0, 8, 0, 0) },
        )
        root.addView(bubCard)

        // ---- advanced ----
        val advCard = card()
        advCard.addView(heading("Advanced"))
        autoSwitch = Switch(this).apply {
            text = "Auto switch back to pen"
            setTextColor(textMain)
            textSize = 14f
        }
        autoSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.AUTO_SWITCH, on).apply()
        }
        advCard.addView(autoSwitch)
        advCard.addView(
            text("After one erase or one lasso the pen comes back by itself - no need to press the button again.", 12f, textDim).also { pad(it, 0, 2, 0, 8) },
        )
        boxSwitch = Switch(this).apply {
            text = "White box (also in the notification)"
            setTextColor(textMain)
            textSize = 14f
        }
        boxSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.WHITE_BOX, on).apply()
        }
        advCard.addView(boxSwitch)
        advCard.addView(
            text("A white rectangle you can move and resize with a finger (drag the corner). Write on it with the pen.", 12f, textDim).also { pad(it, 0, 2, 0, 8) },
        )
        tapSwitch = Switch(this).apply {
            text = "A quick pen tap (no writing) clicks the app below"
            setTextColor(textMain)
            textSize = 14f
        }
        tapSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.PEN_TAP_CLICK, on).apply()
        }
        advCard.addView(tapSwitch)
        advCard.addView(
            text("Turn this off if you want dots (i, full stop) with a quick tap of the pen.", 12f, textDim).also { pad(it, 0, 2, 0, 8) },
        )
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
        root.addView(advCard)

        // ---- permissions ----
        val permCard = card()
        permCard.addView(heading("Permissions"))
        a11yStatus = permRow(permCard, "Accessibility service (draws the pen layer)") { openAccessibility() }
        notifStatus = permRow(permCard, "Notifications (Turn off button)") { requestNotifications() }
        battStatus = permRow(permCard, "Battery: never put the app to sleep") { A11yGuard.requestBattery(this) }
        permRow(permCard, "Battery saver > No restrictions (Xiaomi / HyperOS)") { A11yGuard.openXiaomiBattery(this) }.also { it.text = "OPEN"; it.setTextColor(accent) }
        permRow(permCard, "Auto-start (start again after restart)") { A11yGuard.openAutoStart(this) }.also { it.text = "OPEN"; it.setTextColor(accent) }
        permRow(permCard, "App info (also lock the app in Recents)") { A11yGuard.appInfo(this) }.also { it.text = "OPEN"; it.setTextColor(accent) }
        permCard.addView(
            text(
                "To stop Android killing the pen in the background: set Battery to ON, choose \"No restrictions\" and allow Auto-start. " +
                    "The pad now stays on when you press the power button; it only turns off when YOU turn it off. " +
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

    private fun sizeTitle(): String = if (Prefs.isAnnot(prefs)) "Annotation pen size (separate)" else "Pen size"

    private fun refresh() {
        binding = true
        padSwitch.isChecked = prefs.getBoolean(Prefs.PAD_ON, false)
        val window = if (prefs.contains(Prefs.WINDOW_MODE)) prefs.getBoolean(Prefs.WINDOW_MODE, false) else Prefs.windowDefault()
        windowSwitch.isChecked = window
        tapSwitch.isChecked = prefs.getBoolean(Prefs.PEN_TAP_CLICK, false)
        bubbleSwitch.isChecked = prefs.getBoolean(Prefs.BUBBLE_ON, true)
        autoSwitch.isChecked = Prefs.autoSwitch(prefs)
        boxSwitch.isChecked = prefs.getBoolean(Prefs.WHITE_BOX, false)
        modeInfo.text = if (window) "Mode: window (pen writes, finger taps are replayed)" else "Mode: pen capture (fingers are never touched)"

        val pt = prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3)
        for (i in penChips.indices) selChip(penChips[i], i == pt)
        for (i in toolChips.indices) selChip(toolChips[i], i == canvas.tool)
        paperChip.text = if (canvas.paper) "Paper" else "Black"
        fingerChip.text = if (canvas.fingerWrites) "Finger: writes" else "Finger: erases"

        val size = Prefs.size(prefs)
        sizeBar.progress = ((size - 1f) / 0.5f).toInt().coerceIn(0, 58)
        sizeLabel.text = sizeTitle() + ": " + String.format("%.1f", size) + " dp"
        val op = Prefs.opacity(prefs)
        opBar.progress = (op - 10).coerceIn(0, 90)
        opLabel.text = "Ink opacity: $op %"
        val ba = Prefs.bubbleAlpha(prefs)
        bubbleBar.progress = (ba - 1).coerceIn(0, 59)
        bubbleLabel.text = "Bubble visibility: $ba %" + (if (ba == 5) "  (default)" else "")

        val cur = prefs.getInt(Prefs.COLOR, Prefs.RED)
        val pal = Prefs.PALETTE
        for (i in swatches.indices) {
            val c = pal[i]
            swatches[i].background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                setStroke(if (c == cur) dp(4) else dp(1), if (c == cur) Color.WHITE else Color.parseColor("#55FFFFFF"))
            }
        }
        binding = false
        if (::controls.isInitialized) controls.refresh()
        setStatus(a11yStatus, a11yEnabled())
        if (FloatingPenService.instance == null && A11yGuard.enabledInSettings(this)) {
            // Switched on in Settings but Android killed it: turning it off and on again fixes it.
            a11yStatus.text = "STOPPED - tap, switch off & on"
            a11yStatus.setTextColor(bad)
        }
        setStatus(notifStatus, notifGranted())
        setStatus(battStatus, A11yGuard.batteryUnrestricted(this))
    }

    /** Pen test + in-app smart pen buttons (when the writing pad is off the service does not consume them). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && ::probe.isInitialized) {
            probe.setKey(KeyEvent.keyCodeToString(event.keyCode) + " (" + (event.device?.name ?: "?") + ")")
        }
        if (::canvas.isInitialized && (event.keyCode == KeyEvent.KEYCODE_PAGE_UP || event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN) keys.pageDown() else keys.pageUp()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (::controls.isInitialized && controls.onResult(requestCode, resultCode, data)) return
        super.onActivityResult(requestCode, resultCode, data)
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

    private fun rounded(color: Int, r: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(r).toFloat()
        }

    private fun selChip(v: TextView, selected: Boolean) {
        v.background = rounded(if (selected) accent else chipOff, 12)
        v.setTextColor(if (selected) Color.parseColor("#1A1A1A") else Color.WHITE)
    }

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
