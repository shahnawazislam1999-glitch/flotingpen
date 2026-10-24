package com.raj.flotingpen

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Named presets and JSON import / export. Every value is range-checked on the way in, so a damaged or
 * hand-edited file can never put the app into a broken state. Screen-specific values (bubble position,
 * window mode) are not exported.
 */
object SettingsIO {
    const val MAX_PRESETS = 50
    const val MAX_FILE = 1_000_000

    private val ACTION_KEYS = listOf(
        Prefs.BTN_UP_1, Prefs.BTN_UP_2, Prefs.BTN_DOWN_1, Prefs.BTN_DOWN_2, Prefs.HOLD_ACTION,
    )
    private val BOOL_KEYS = listOf(Prefs.BUBBLE_ON, Prefs.OFF_ON_SCREEN_OFF, Prefs.PEN_TAP_CLICK)

    fun snapshot(ctx: Context): JSONObject {
        val p = Prefs.get(ctx)
        val o = JSONObject()
        o.put(Prefs.PEN_TYPE, p.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3))
        o.put(Prefs.COLOR, p.getInt(Prefs.COLOR, Prefs.RED))
        o.put(Prefs.SIZE_DP, Prefs.size(p).toDouble())
        o.put(Prefs.OPACITY, Prefs.opacity(p))
        o.put(Prefs.BUBBLE_ALPHA, Prefs.bubbleAlpha(p))
        o.put(Prefs.HOLD_SEC, Prefs.holdSec(p))
        o.put(Prefs.DOUBLE_MS, Prefs.doubleMs(p))
        for (k in BOOL_KEYS) o.put(k, p.getBoolean(k, true))
        for (k in ACTION_KEYS) o.put(k, Prefs.action(p, k))
        o.put(Prefs.CUSTOM_ACTIONS, CustomActions.toJson(CustomActions.load(p)))
        return o
    }

    /** Applies the known, valid values of [o]. Returns how many settings were applied. */
    fun apply(ctx: Context, o: JSONObject): Int {
        val e = Prefs.get(ctx).edit()
        var n = 0
        if (o.has(Prefs.PEN_TYPE)) {
            e.putInt(Prefs.PEN_TYPE, o.optInt(Prefs.PEN_TYPE, 0).coerceIn(0, 3))
            n++
        }
        if (o.has(Prefs.COLOR)) {
            e.putInt(Prefs.COLOR, o.optInt(Prefs.COLOR, Prefs.RED))
            n++
        }
        if (o.has(Prefs.SIZE_DP)) {
            e.putFloat(Prefs.SIZE_DP, o.optDouble(Prefs.SIZE_DP, 3.0).toFloat().coerceIn(1f, 30f))
            n++
        }
        if (o.has(Prefs.OPACITY)) {
            e.putInt(Prefs.OPACITY, o.optInt(Prefs.OPACITY, 100).coerceIn(10, 100))
            n++
        }
        if (o.has(Prefs.BUBBLE_ALPHA)) {
            e.putInt(Prefs.BUBBLE_ALPHA, o.optInt(Prefs.BUBBLE_ALPHA, 5).coerceIn(1, 60))
            n++
        }
        if (o.has(Prefs.HOLD_SEC)) {
            e.putInt(Prefs.HOLD_SEC, o.optInt(Prefs.HOLD_SEC, 3).coerceIn(1, 10))
            n++
        }
        if (o.has(Prefs.DOUBLE_MS)) {
            e.putInt(Prefs.DOUBLE_MS, o.optInt(Prefs.DOUBLE_MS, 500).coerceIn(250, 900))
            n++
        }
        for (k in BOOL_KEYS) {
            if (o.has(k)) {
                e.putBoolean(k, o.optBoolean(k, true))
                n++
            }
        }
        val arr = o.optJSONArray(Prefs.CUSTOM_ACTIONS)
        if (arr != null) {
            e.putString(Prefs.CUSTOM_ACTIONS, CustomActions.toJson(CustomActions.fromJson(arr)).toString())
            n++
        }
        for (k in ACTION_KEYS) {
            if (o.has(k)) {
                val v = o.optString(k, "")
                if (Act.isBuiltin(v) || (v.startsWith(Act.CUSTOM_PREFIX) && v.length <= 40)) {
                    e.putString(k, v)
                    n++
                }
            }
        }
        e.apply()
        return n
    }

    // ---------------------------------------------------------------- presets

    private fun presets(p: SharedPreferences): JSONObject =
        try {
            JSONObject(p.getString(Prefs.PRESETS, "{}") ?: "{}")
        } catch (_: Throwable) {
            JSONObject()
        }

    fun presetNames(ctx: Context): List<String> {
        val o = presets(Prefs.get(ctx))
        val out = ArrayList<String>()
        val it = o.keys()
        while (it.hasNext()) out.add(it.next())
        return out.sorted()
    }

    fun savePreset(ctx: Context, name: String): Boolean {
        val clean = name.trim().take(40)
        if (clean.isEmpty()) return false
        val p = Prefs.get(ctx)
        val all = presets(p)
        if (all.length() >= MAX_PRESETS && !all.has(clean)) return false
        all.put(clean, snapshot(ctx))
        p.edit().putString(Prefs.PRESETS, all.toString()).apply()
        return true
    }

    fun loadPreset(ctx: Context, name: String): Boolean {
        val o = presets(Prefs.get(ctx)).optJSONObject(name) ?: return false
        apply(ctx, o)
        return true
    }

    fun deletePreset(ctx: Context, name: String) {
        val p = Prefs.get(ctx)
        val all = presets(p)
        all.remove(name)
        p.edit().putString(Prefs.PRESETS, all.toString()).apply()
    }

    // ---------------------------------------------------------------- JSON file

    fun exportAll(ctx: Context): String {
        val o = JSONObject()
        o.put("app", "FloatingPen")
        o.put("version", 1)
        o.put("settings", snapshot(ctx))
        o.put("presets", presets(Prefs.get(ctx)))
        return o.toString(2)
    }

    /** Returns the number of imported items, or -1 when the text is not a FloatingPen settings file. */
    fun importAll(ctx: Context, text: String): Int {
        val root = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return -1
        }
        var n = 0
        var ok = false
        val st = root.optJSONObject("settings")
            ?: (if (root.has(Prefs.PEN_TYPE) || root.has(Prefs.BTN_UP_1)) root else null)
        if (st != null) {
            n += apply(ctx, st)
            ok = true
        }
        val pr = root.optJSONObject("presets")
        if (pr != null) {
            ok = true
            val p = Prefs.get(ctx)
            val all = presets(p)
            val it = pr.keys()
            while (it.hasNext()) {
                val name = it.next()
                val v = pr.optJSONObject(name) ?: continue
                if (name.isBlank() || name.length > 40) continue
                if (all.length() >= MAX_PRESETS && !all.has(name)) continue
                all.put(name, v)
                n++
            }
            p.edit().putString(Prefs.PRESETS, all.toString()).apply()
        }
        return if (ok) n else -1
    }
}
