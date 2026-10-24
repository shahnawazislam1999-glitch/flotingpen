package com.raj.flotingpen

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

/**
 * Home-screen cards: pen button / gesture mapping, custom actions, presets and JSON backup.
 * Built in code like the rest of the home screen. Everything is saved the moment it is changed.
 */
class ControlsUi(private val act: Activity) {

    private val prefs = Prefs.get(act)
    private val density = act.resources.displayMetrics.density
    private val cardBg = Color.parseColor("#1A2133")
    private val accent = Color.parseColor("#FFB300")
    private val textMain = Color.WHITE
    private val textDim = Color.parseColor("#9AA6BF")
    private val chipOff = Color.parseColor("#2A3350")
    private val bad = Color.parseColor("#FF6B6B")

    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    private val actionBtns = LinkedHashMap<String, TextView>()
    private lateinit var holdLabel: TextView
    private lateinit var holdBar: SeekBar
    private lateinit var dblLabel: TextView
    private lateinit var dblBar: SeekBar
    private lateinit var customBox: LinearLayout
    private var binding = false

    init {
        build()
    }

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun toast(s: String) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    }

    // ====================================================================================
    //  small view helpers
    // ====================================================================================

    private fun tv(s: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(act).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(title: String): LinearLayout {
        val l = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dp(16).toFloat()
            }
        }
        l.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .also { it.bottomMargin = dp(12) }
        val h = tv(title, 15f, accent, true)
        h.setPadding(0, 0, 0, dp(8))
        l.addView(h)
        view.addView(l)
        return l
    }

    private fun chipBg(): GradientDrawable = GradientDrawable().apply {
        setColor(chipOff)
        cornerRadius = dp(12).toFloat()
    }

    private fun button(parent: LinearLayout, label: String, onClick: () -> Unit): TextView {
        val b = tv(label, 14f, textMain, true)
        b.gravity = Gravity.CENTER
        b.background = chipBg()
        b.setPadding(dp(10), dp(10), dp(10), dp(10))
        b.setOnClickListener { onClick() }
        parent.addView(
            b,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(8) },
        )
        return b
    }

    // ====================================================================================
    //  cards
    // ====================================================================================

    private fun build() {
        // ---- buttons & gestures ----
        val c1 = card("Smart pen buttons & gestures")
        c1.addView(
            tv(
                "Tap a row to choose what it does. The 1-tap action runs at once. If a button also has a " +
                    "2-tap action and you press it twice quickly, the pen goes back to how it was and the 2-tap action runs.",
                12f, textDim,
            ),
        )
        addRow(c1, "Up button - 1 tap", Prefs.BTN_UP_1)
        addRow(c1, "Up button - 2 taps", Prefs.BTN_UP_2)
        addRow(c1, "Down button - 1 tap", Prefs.BTN_DOWN_1)
        addRow(c1, "Down button - 2 taps", Prefs.BTN_DOWN_2)
        addRow(c1, "Pen nib held still (while writing)", Prefs.HOLD_ACTION)

        holdLabel = tv("", 13f, textDim)
        holdLabel.setPadding(0, dp(12), 0, 0)
        c1.addView(holdLabel)
        holdBar = SeekBar(act).apply { max = 9 }
        holdBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                holdLabel.text = "Nib hold time: " + (p + 1) + " s"
                if (fromUser && !binding) prefs.edit().putInt(Prefs.HOLD_SEC, p + 1).apply()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        c1.addView(holdBar)

        dblLabel = tv("", 13f, textDim)
        dblLabel.setPadding(0, dp(8), 0, 0)
        c1.addView(dblLabel)
        dblBar = SeekBar(act).apply { max = 13 }
        dblBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                dblLabel.text = "2-tap speed: within " + (250 + p * 50) + " ms"
                if (fromUser && !binding) prefs.edit().putInt(Prefs.DOUBLE_MS, 250 + p * 50).apply()
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        c1.addView(dblBar)

        button(c1, "Reset buttons to default") {
            prefs.edit()
                .remove(Prefs.BTN_UP_1).remove(Prefs.BTN_UP_2)
                .remove(Prefs.BTN_DOWN_1).remove(Prefs.BTN_DOWN_2)
                .remove(Prefs.HOLD_ACTION).remove(Prefs.HOLD_SEC).remove(Prefs.DOUBLE_MS)
                .apply()
            refresh()
            toast("Buttons reset to default")
        }

        // ---- custom actions ----
        val c2 = card("Custom actions")
        c2.addView(
            tv(
                "Make your own actions, then pick them in the rows above. " +
                    "Launch app: opens any installed app. Macro: runs several actions one after another " +
                    "(for example Pencil + blue colour + bigger size).",
                12f, textDim,
            ),
        )
        customBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        c2.addView(customBox)
        button(c2, "+ Add custom action") { editCustom(null) }

        // ---- presets & backup ----
        val c3 = card("Presets & backup")
        c3.addView(
            tv(
                "All settings are saved automatically. A preset is a named copy of every setting " +
                    "(pen, colour, size, buttons, gestures, custom actions) that you can load again later. " +
                    "Export writes everything to a JSON file you can keep or move to another device.",
                12f, textDim,
            ),
        )
        button(c3, "Save current settings as preset") { savePresetDialog() }
        button(c3, "Load a preset") { loadPresetDialog() }
        button(c3, "Delete a preset") { deletePresetDialog() }
        button(c3, "Export settings to JSON file") { startExport() }
        button(c3, "Import settings from JSON file") { startImport() }
    }

    private fun addRow(parent: LinearLayout, label: String, key: String) {
        val cap = tv(label, 13f, textDim)
        cap.setPadding(0, dp(12), 0, dp(4))
        parent.addView(cap)
        val b = tv("", 14f, textMain, true)
        b.background = chipBg()
        b.setPadding(dp(12), dp(10), dp(12), dp(10))
        b.setOnClickListener { pickAction(label, key) }
        parent.addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        actionBtns[key] = b
    }

    fun refresh() {
        binding = true
        for ((k, b) in actionBtns) b.text = Act.nameOf(prefs, Prefs.action(prefs, k))
        val hs = Prefs.holdSec(prefs)
        holdBar.progress = hs - 1
        holdLabel.text = "Nib hold time: $hs s"
        val dm = Prefs.doubleMs(prefs)
        dblBar.progress = ((dm - 250) / 50).coerceIn(0, 13)
        dblLabel.text = "2-tap speed: within $dm ms"
        rebuildCustom()
        binding = false
    }

    // ====================================================================================
    //  choose an action for a button
    // ====================================================================================

    private fun pickAction(title: String, key: String) {
        val ids = ArrayList<String>()
        val names = ArrayList<String>()
        for (a in Act.BUILTIN) {
            ids.add(a.id)
            names.add(a.name)
        }
        for (c in CustomActions.load(prefs)) {
            ids.add(c.id)
            names.add("* " + c.name)
        }
        AlertDialog.Builder(act)
            .setTitle(title)
            .setItems(names.toTypedArray()) { _, which ->
                prefs.edit().putString(key, ids[which]).apply()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ====================================================================================
    //  custom actions
    // ====================================================================================

    private fun rebuildCustom() {
        customBox.removeAllViews()
        val list = CustomActions.load(prefs)
        if (list.isEmpty()) {
            val none = tv("No custom actions yet.", 13f, textDim)
            none.setPadding(0, dp(8), 0, 0)
            customBox.addView(none)
            return
        }
        for (c in list) {
            val row = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                background = chipBg()
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setOnClickListener { customMenu(c) }
            }
            row.addView(tv("* " + c.name, 14f, textMain, true))
            val summary = if (c.type == CustomActions.APP) {
                "Launch app: " + c.value
            } else {
                "Macro: " + macroText(c.value.split(',').map { it.trim() }.filter { it.isNotEmpty() })
            }
            row.addView(tv(summary, 12f, textDim))
            customBox.addView(
                row,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = dp(8) },
            )
        }
    }

    private fun macroText(steps: List<String>): String {
        if (steps.isEmpty()) return "(no steps yet)"
        val sb = StringBuilder()
        for (i in steps.indices) {
            if (i > 0) sb.append(" > ")
            sb.append(Act.builtinName(steps[i]) ?: steps[i])
        }
        return sb.toString()
    }

    private fun customMenu(c: CustomActions.Custom) {
        AlertDialog.Builder(act)
            .setTitle(c.name)
            .setItems(arrayOf("Edit", "Delete")) { _, which ->
                if (which == 0) {
                    editCustom(c)
                } else {
                    deleteCustom(c)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteCustom(c: CustomActions.Custom) {
        val list = ArrayList<CustomActions.Custom>()
        for (x in CustomActions.load(prefs)) if (x.id != c.id) list.add(x)
        CustomActions.save(prefs, list)
        val e = prefs.edit()
        for (k in actionBtns.keys) {
            if (Prefs.action(prefs, k) == c.id) e.putString(k, Act.NONE)
        }
        e.apply()
        refresh()
        toast("Deleted " + c.name)
    }

    private fun editCustom(existing: CustomActions.Custom?) {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val nameEt = EditText(act).apply {
            hint = "Name"
            setSingleLine()
            setText(existing?.name ?: "")
        }
        box.addView(nameEt)
        AlertDialog.Builder(act)
            .setTitle(if (existing == null) "New custom action" else "Edit custom action")
            .setMessage("Give it a name, then choose what it does.")
            .setView(box)
            .setPositiveButton("Launch app...") { _, _ ->
                pickApp(cleanName(nameEt.text.toString()), existing)
            }
            .setNegativeButton("Macro...") { _, _ ->
                val steps = ArrayList<String>()
                if (existing != null && existing.type == CustomActions.MACRO) {
                    for (s in existing.value.split(',')) {
                        val t = s.trim()
                        if (t.isNotEmpty()) steps.add(t)
                    }
                }
                buildMacro(cleanName(nameEt.text.toString()), existing, steps)
            }
            .setNeutralButton("Cancel", null)
            .show()
    }

    private fun cleanName(s: String): String {
        val t = s.trim().take(40)
        return if (t.isEmpty()) "Custom action" else t
    }

    private fun saveCustom(name: String, type: String, value: String, existing: CustomActions.Custom?) {
        val list = ArrayList<CustomActions.Custom>(CustomActions.load(prefs))
        if (existing == null && list.size >= CustomActions.MAX) {
            toast("Too many custom actions (max " + CustomActions.MAX + ")")
            return
        }
        val c = CustomActions.Custom(existing?.id ?: CustomActions.newId(), name, type, value)
        var idx = -1
        for (i in list.indices) if (list[i].id == c.id) idx = i
        if (idx >= 0) list[idx] = c else list.add(c)
        CustomActions.save(prefs, list)
        refresh()
        toast("Saved " + name)
    }

    private fun pickApp(name: String, existing: CustomActions.Custom?) {
        val pm = act.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = try {
            pm.queryIntentActivities(launcher, 0)
        } catch (_: Throwable) {
            emptyList()
        }
        val labels = ArrayList<String>()
        val pkgs = ArrayList<String>()
        val seen = HashSet<String>()
        val rows = ArrayList<Pair<String, String>>()
        for (ri in found) {
            val pkg = ri.activityInfo.packageName
            if (seen.add(pkg)) rows.add(Pair(ri.loadLabel(pm).toString(), pkg))
        }
        rows.sortBy { it.first.lowercase() }
        for (r in rows) {
            labels.add(r.first)
            pkgs.add(r.second)
        }
        if (labels.isEmpty()) {
            toast("No apps found")
            return
        }
        AlertDialog.Builder(act)
            .setTitle("Launch which app?")
            .setItems(labels.toTypedArray()) { _, which ->
                saveCustom(name, CustomActions.APP, pkgs[which], existing)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun buildMacro(name: String, existing: CustomActions.Custom?, steps: ArrayList<String>) {
        val items = ArrayList<String>()
        items.add("Save macro")
        items.add("Remove last step")
        val ids = ArrayList<String>()
        for (a in Act.BUILTIN) {
            if (a.id != Act.NONE) {
                items.add("+ " + a.name)
                ids.add(a.id)
            }
        }
        AlertDialog.Builder(act)
            .setTitle("Steps: " + macroText(steps))
            .setItems(items.toTypedArray()) { _, which ->
                if (which == 0) {
                    if (steps.isEmpty()) {
                        toast("Add at least one step first")
                        buildMacro(name, existing, steps)
                    } else {
                        saveCustom(name, CustomActions.MACRO, steps.joinToString(","), existing)
                    }
                } else if (which == 1) {
                    if (steps.isNotEmpty()) steps.removeAt(steps.size - 1)
                    buildMacro(name, existing, steps)
                } else {
                    if (steps.size < 8) {
                        steps.add(ids[which - 2])
                    } else {
                        toast("A macro can have 8 steps at most")
                    }
                    buildMacro(name, existing, steps)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ====================================================================================
    //  presets
    // ====================================================================================

    private fun savePresetDialog() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val et = EditText(act).apply {
            hint = "Preset name"
            setSingleLine()
        }
        box.addView(et)
        AlertDialog.Builder(act)
            .setTitle("Save preset")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    toast("Type a name first")
                } else if (SettingsIO.savePreset(act, name)) {
                    toast("Preset saved: " + name.take(40))
                } else {
                    toast("Too many presets (max " + SettingsIO.MAX_PRESETS + ")")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadPresetDialog() {
        val names = SettingsIO.presetNames(act)
        if (names.isEmpty()) {
            toast("No presets saved yet")
            return
        }
        AlertDialog.Builder(act)
            .setTitle("Load which preset?")
            .setItems(names.toTypedArray()) { _, which ->
                if (SettingsIO.loadPreset(act, names[which])) {
                    refresh()
                    toast("Loaded " + names[which])
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deletePresetDialog() {
        val names = SettingsIO.presetNames(act)
        if (names.isEmpty()) {
            toast("No presets saved yet")
            return
        }
        AlertDialog.Builder(act)
            .setTitle("Delete which preset?")
            .setItems(names.toTypedArray()) { _, which ->
                SettingsIO.deletePreset(act, names[which])
                toast("Deleted " + names[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ====================================================================================
    //  JSON export / import (system file picker, no storage permission needed)
    // ====================================================================================

    private fun startExport() {
        try {
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "application/json"
            i.putExtra(Intent.EXTRA_TITLE, "floatingpen-settings.json")
            act.startActivityForResult(i, REQ_EXPORT)
        } catch (_: Throwable) {
            toast("No file manager available")
        }
    }

    private fun startImport() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            act.startActivityForResult(i, REQ_IMPORT)
        } catch (_: Throwable) {
            toast("No file manager available")
        }
    }

    /** Called from the activity. Returns true when the result belonged to this screen. */
    fun onResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != REQ_EXPORT && requestCode != REQ_IMPORT) return false
        if (resultCode != Activity.RESULT_OK) return true
        val uri = data?.data ?: return true
        try {
            if (requestCode == REQ_EXPORT) {
                val text = SettingsIO.exportAll(act)
                val out = act.contentResolver.openOutputStream(uri, "wt")
                if (out == null) {
                    toast("Could not write the file")
                } else {
                    out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    toast("Settings exported")
                }
            } else {
                val input = act.contentResolver.openInputStream(uri)
                if (input == null) {
                    toast("Could not read the file")
                } else {
                    val bytes = input.use { it.readBytes() }
                    if (bytes.size > SettingsIO.MAX_FILE) {
                        toast("File is too big to be a settings file")
                    } else {
                        val n = SettingsIO.importAll(act, String(bytes, Charsets.UTF_8))
                        if (n < 0) {
                            toast("This is not a FloatingPen settings file")
                        } else {
                            refresh()
                            toast("Imported $n items")
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            toast("Failed: " + (e.message ?: "unknown error"))
        }
        return true
    }

    companion object {
        const val REQ_EXPORT = 4101
        const val REQ_IMPORT = 4102
    }
}
