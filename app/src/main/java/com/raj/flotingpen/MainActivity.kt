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
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/** Main settings screen. Everything is built in code, so the project needs no extra libraries. */
class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences

    private lateinit var padSwitch: Switch
    private lateinit var smartRadio: RadioButton
    private lateinit var fingerRadio: RadioButton
    private lateinit var annotSwitch: Switch
    private lateinit var compatSwitch: Switch
    private lateinit var colorRed: TextView
    private lateinit var colorBlue: TextView
    private lateinit var penRadios: List<RadioButton>
    private lateinit var a11yStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var storageStatus: TextView
    private lateinit var notifStatus: TextView
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
        root.addView(text("Write on top of any app with your smart pen.", 14f, textDim).also { pad(it, 0, 2, 0, 14) })

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
        padCard.addView(text("Also available as the \"Writing pad\" tile in Quick Settings. Nothing is drawn on the screen except your ink.", 12f, textDim).also { pad(it, 0, 6, 0, 0) })
        root.addView(padCard)

        // ---- input mode ----
        val modeCard = card()
        modeCard.addView(heading("Input mode"))
        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        smartRadio = radio("Smart pen mode (default)")
        fingerRadio = radio("Finger mode")
        modeGroup.addView(smartRadio)
        modeGroup.addView(fingerRadio)
        modeCard.addView(modeGroup)
        modeCard.addView(
            text(
                "Smart pen: only the pen writes; fingers use your apps normally and become gesture tools while the pen is active.\n" +
                    "Finger mode: fingers and pen both write (apps below are not touchable while the pad is on).",
                12f, textDim,
            ).also { pad(it, 0, 6, 0, 0) },
        )
        modeGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            prefs.edit().putBoolean(Prefs.FINGER_MODE, id == fingerRadio.id).apply()
        }
        root.addView(modeCard)

        // ---- pen ----
        val penCard = card()
        penCard.addView(heading("Pen"))
        val penGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val nirmal = radio("Nirmal")
        val classic = radio("Classic")
        val pencil = radio("Pencil")
        penRadios = listOf(nirmal, classic, pencil)
        for (r in penRadios) penGroup.addView(r, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        penGroup.setOnCheckedChangeListener { _, id ->
            if (binding) return@setOnCheckedChangeListener
            val t = penRadios.indexOfFirst { it.id == id }
            if (t >= 0) prefs.edit().putInt(Prefs.PEN_TYPE, t).apply()
        }
        penCard.addView(penGroup)

        val sizeLabel = text("", 13f, textDim).also { pad(it, 0, 10, 0, 0) }
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
        sizeBar.tag = "size"

        penCard.addView(text("Colour (a one-finger tap flips it)", 13f, textDim).also { pad(it, 0, 10, 0, 6) })
        val colorRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        colorRed = chip("Red", Prefs.RED)
        colorBlue = chip("Blue", Prefs.BLUE)
        colorRed.setOnClickListener { prefs.edit().putInt(Prefs.COLOR, Prefs.RED).apply(); refresh() }
        colorBlue.setOnClickListener { prefs.edit().putInt(Prefs.COLOR, Prefs.BLUE).apply(); refresh() }
        colorRow.addView(colorRed, LinearLayout.LayoutParams(0, dp(44), 1f).also { it.rightMargin = dp(8) })
        colorRow.addView(colorBlue, LinearLayout.LayoutParams(0, dp(44), 1f))
        penCard.addView(colorRow)

        annotSwitch = Switch(this).apply {
            text = "Annotation pen (glows, fades after 1 s)"
            setTextColor(textMain)
            textSize = 14f
        }
        annotSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.ANNOT, on).apply()
        }
        penCard.addView(annotSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) })
        root.addView(penCard)

        // ---- advanced ----
        val advCard = card()
        advCard.addView(heading("Advanced"))
        compatSwitch = Switch(this).apply {
            text = "Compatibility mode"
            setTextColor(textMain)
            textSize = 14f
        }
        compatSwitch.setOnCheckedChangeListener { _: CompoundButton, on: Boolean ->
            if (!binding) prefs.edit().putBoolean(Prefs.COMPAT, on).apply()
        }
        advCard.addView(compatSwitch)
        advCard.addView(
            text(
                "On: the overlay always catches touches and re-plays your finger taps/swipes to the app underneath (works on every Android version, scrolling is applied when you lift). " +
                    "Off: the overlay only catches touches while the pen is active (needs Android 14+). Default is on for Android 13 and older.",
                12f, textDim,
            ).also { pad(it, 0, 4, 0, 0) },
        )
        val winLabel = text("", 13f, textDim).also { pad(it, 0, 12, 0, 0) }
        advCard.addView(winLabel)
        val winBar = SeekBar(this).apply { max = 8 }
        winBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                winLabel.text = "Gesture window after the pen lifts: ${p + 2} s"
                if (fromUser && !binding) prefs.edit().putInt(Prefs.SESSION_S, p + 2).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        winBar.progress = (prefs.getInt(Prefs.SESSION_S, 4) - 2).coerceIn(0, 8)
        advCard.addView(winBar)
        root.addView(advCard)

        // ---- permissions ----
        val permCard = card()
        permCard.addView(heading("Permissions"))
        a11yStatus = permRow(permCard, "Accessibility service (overlay + pen buttons)") { openAccessibility() }
        overlayStatus = permRow(permCard, "Display over other apps") { requestOverlay() }
        storageStatus = permRow(permCard, "Storage / directory write access") { requestStorage() }
        notifStatus = permRow(permCard, "Notifications") { requestNotifications() }
        val all = Button(this).apply {
            text = "Grant all permissions"
            isAllCaps = false
            setOnClickListener { grantNext() }
        }
        permCard.addView(all, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) })
        permCard.addView(
            text(
                "If Android blocks the accessibility switch for a sideloaded app: App info > ⋮ > Allow restricted settings, then try again.",
                12f, textDim,
            ).also { pad(it, 0, 8, 0, 0) },
        )
        root.addView(permCard)

        // ---- gestures ----
        val gCard = card()
        gCard.addView(heading("Gestures (while the pen is active)"))
        gCard.addView(
            text(
                "• Pen: write\n" +
                    "• Finger scribble: erase\n" +
                    "• 1-finger tap: switch red / blue\n" +
                    "• 1-finger hold 3 s: annotation pen on / off\n" +
                    "• 2-finger tap: clear everything\n" +
                    "• 2 fingers drag: float the canvas anywhere\n" +
                    "• 2 fingers hold 3 s: white writing box (rotate, resize, move with its handles)\n" +
                    "• Pen lower button: lasso erase, then circle the ink\n" +
                    "• Pen upper button: canvas back to normal position",
                13f, textMain,
            ),
        )
        root.addView(gCard)
        root.addView(text("Ink is kept only in memory and is never written to storage.", 12f, textDim).also { pad(it, 4, 12, 4, 0) })
    }

    private fun refresh() {
        binding = true
        padSwitch.isChecked = prefs.getBoolean(Prefs.PAD_ON, false)
        val finger = prefs.getBoolean(Prefs.FINGER_MODE, false)
        smartRadio.isChecked = !finger
        fingerRadio.isChecked = finger
        val pt = prefs.getInt(Prefs.PEN_TYPE, PEN_NIRMAL).coerceIn(0, 2)
        penRadios[pt].isChecked = true
        annotSwitch.isChecked = prefs.getBoolean(Prefs.ANNOT, false)
        compatSwitch.isChecked =
            if (prefs.contains(Prefs.COMPAT)) prefs.getBoolean(Prefs.COMPAT, true) else Build.VERSION.SDK_INT < 34
        val blue = prefs.getInt(Prefs.COLOR, Prefs.RED) == Prefs.BLUE
        styleChip(colorRed, !blue, Prefs.RED)
        styleChip(colorBlue, blue, Prefs.BLUE)
        binding = false

        setStatus(a11yStatus, a11yEnabled())
        setStatus(overlayStatus, Settings.canDrawOverlays(this))
        setStatus(storageStatus, storageGranted())
        setStatus(notifStatus, notifGranted())
    }

    // ====================================================================================
    //  permissions
    // ====================================================================================

    private fun a11yEnabled(): Boolean {
        if (FloatingPenService.instance != null) return true
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val cn = ComponentName(this, FloatingPenService::class.java)
        val full = cn.flattenToString()
        val short = cn.flattenToShortString()
        return s.split(':').any { it.equals(full, true) || it.equals(short, true) }
    }

    private fun storageGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun notifGranted(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openAccessibility() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: Throwable) {
        }
    }

    private fun requestOverlay() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Throwable) {
        }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (_: Throwable) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Throwable) {
                }
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE,
            )
        }
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_MEDIA_IMAGES),
                REQ_NOTIF,
            )
        }
    }

    /** Opens the next missing permission screen; run again after returning until all are granted. */
    private fun grantNext() {
        when {
            !a11yEnabled() -> openAccessibility()
            !Settings.canDrawOverlays(this) -> requestOverlay()
            !storageGranted() -> requestStorage()
            !notifGranted() -> requestNotifications()
            else -> Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        }
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

    private fun radio(label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        setTextColor(textMain)
        textSize = 14f
    }

    private fun chip(label: String, color: Int) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        tag = color
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

    companion object {
        private const val REQ_STORAGE = 11
        private const val REQ_NOTIF = 12
    }
}
