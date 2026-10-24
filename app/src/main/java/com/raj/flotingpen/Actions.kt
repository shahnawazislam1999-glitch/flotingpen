package com.raj.flotingpen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject

/** Every action that can be put on a pen button / nib-hold gesture. Ids are stored in the settings. */
object Act {
    const val NONE = "none"
    const val WRITE = "write"
    const val ERASER = "eraser"
    const val LASSO = "lasso"
    const val CLEAR = "clear"
    const val UNDO = "undo"
    const val PEN_NIRMAL = "pen_nirmal"
    const val PEN_CLASSIC = "pen_classic"
    const val PEN_PENCIL = "pen_pencil"
    const val PEN_ANNOT = "pen_annot"
    const val PEN_NEXT = "pen_next"
    const val COLOR_RB = "color_rb"
    const val COLOR_NEXT = "color_next"
    const val COLOR_RED = "color_red"
    const val COLOR_BLUE = "color_blue"
    const val COLOR_BLACK = "color_black"
    const val SIZE_UP = "size_up"
    const val SIZE_DOWN = "size_down"
    const val OPACITY_UP = "opacity_up"
    const val OPACITY_DOWN = "opacity_down"
    const val BUBBLE = "bubble_toggle"
    const val PAD_OFF = "pad_off"
    const val G_BACK = "g_back"
    const val G_HOME = "g_home"
    const val G_RECENTS = "g_recents"
    const val G_NOTIFS = "g_notifs"
    const val G_QUICK = "g_quick"
    const val G_SHOT = "g_shot"
    const val CUSTOM_PREFIX = "custom:"

    class Item(@JvmField val id: String, @JvmField val name: String)

    val BUILTIN: List<Item> = listOf(
        Item(NONE, "Do nothing"),
        Item(WRITE, "Back to writing"),
        Item(ERASER, "Stroke eraser (on / off)"),
        Item(LASSO, "Lasso (on / off)"),
        Item(CLEAR, "Clear the whole screen"),
        Item(UNDO, "Undo last stroke"),
        Item(PEN_NIRMAL, "Nirmal Pen (switch on / back)"),
        Item(PEN_CLASSIC, "Classic Pen (switch on / back)"),
        Item(PEN_PENCIL, "Pencil (switch on / back)"),
        Item(PEN_ANNOT, "Annotation Pen (switch on / back)"),
        Item(PEN_NEXT, "Next pen type"),
        Item(COLOR_RB, "Colour: red <-> blue"),
        Item(COLOR_NEXT, "Colour: next in palette"),
        Item(COLOR_RED, "Colour: red"),
        Item(COLOR_BLUE, "Colour: blue"),
        Item(COLOR_BLACK, "Colour: black"),
        Item(SIZE_UP, "Pen size bigger"),
        Item(SIZE_DOWN, "Pen size smaller"),
        Item(OPACITY_UP, "Ink opacity more"),
        Item(OPACITY_DOWN, "Ink opacity less"),
        Item(BUBBLE, "Show / hide the bubble"),
        Item(PAD_OFF, "Turn the writing pad off"),
        Item(G_BACK, "Android: Back"),
        Item(G_HOME, "Android: Home"),
        Item(G_RECENTS, "Android: Recent apps"),
        Item(G_NOTIFS, "Android: Notification panel"),
        Item(G_QUICK, "Android: Quick settings"),
        Item(G_SHOT, "Android: Take screenshot"),
    )

    fun builtinName(id: String): String? {
        for (a in BUILTIN) if (a.id == id) return a.name
        return null
    }

    fun isBuiltin(id: String): Boolean = builtinName(id) != null

    fun nameOf(p: SharedPreferences, id: String): String {
        val b = builtinName(id)
        if (b != null) return b
        if (id.startsWith(CUSTOM_PREFIX)) {
            val c = CustomActions.find(p, id)
            return if (c != null) "* " + c.name else "(deleted custom action)"
        }
        return "Do nothing"
    }
}

/** User-made actions: launch an app, or run a short macro of built-in actions. Stored as JSON in the settings. */
object CustomActions {
    const val APP = "app"
    const val MACRO = "macro"
    const val MAX = 30

    class Custom(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val type: String,
        @JvmField val value: String,
    )

    fun newId(): String = Act.CUSTOM_PREFIX + java.lang.Long.toString(System.currentTimeMillis(), 36)

    fun fromJson(arr: JSONArray?): List<Custom> {
        val out = ArrayList<Custom>()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            if (out.size >= MAX) break
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id", "")
            val type = o.optString("type", "")
            if (!id.startsWith(Act.CUSTOM_PREFIX) || id.length > 40) continue
            if (type != APP && type != MACRO) continue
            val name = o.optString("name", "").trim().take(40)
            val value = o.optString("value", "").trim().take(400)
            if (name.isEmpty() || value.isEmpty()) continue
            out.add(Custom(id, name, type, value))
        }
        return out
    }

    fun toJson(list: List<Custom>): JSONArray {
        val a = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("type", c.type)
            o.put("value", c.value)
            a.put(o)
        }
        return a
    }

    fun load(p: SharedPreferences): List<Custom> {
        val s = p.getString(Prefs.CUSTOM_ACTIONS, null) ?: return emptyList()
        return try {
            fromJson(JSONArray(s))
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(p: SharedPreferences, list: List<Custom>) {
        p.edit().putString(Prefs.CUSTOM_ACTIONS, toJson(list).toString()).apply()
    }

    fun find(p: SharedPreferences, id: String): Custom? {
        for (c in load(p)) if (c.id == id) return c
        return null
    }
}

/** Runs one action id. Never throws. */
class ActionRunner(
    private val ctx: Context,
    private val getTool: () -> Int,
    private val setTool: (Int) -> Unit,
    private val clear: () -> Unit,
    private val undo: () -> Unit,
    private val buzz: (Long) -> Unit,
) {
    private val prefs: SharedPreferences by lazy { Prefs.get(ctx) }
    private val main: Handler by lazy { Handler(Looper.getMainLooper()) }

    fun run(id: String) {
        run(id, 0)
    }

    private fun run(id: String, depth: Int) {
        try {
            if (id.isEmpty() || id == Act.NONE) return
            if (id.startsWith(Act.CUSTOM_PREFIX)) runCustom(id, depth) else runBuiltin(id)
        } catch (_: Throwable) {
        }
    }

    private fun runBuiltin(id: String) {
        when (id) {
            Act.WRITE -> setTool(Pen.TOOL_WRITE)
            Act.ERASER -> setTool(if (getTool() == Pen.TOOL_WRITE) Pen.TOOL_ERASE else Pen.TOOL_WRITE)
            Act.LASSO -> setTool(if (getTool() == Pen.TOOL_LASSO) Pen.TOOL_WRITE else Pen.TOOL_LASSO)
            Act.CLEAR -> {
                clear()
                setTool(Pen.TOOL_WRITE)
            }
            Act.UNDO -> {
                undo()
                buzz(20L)
            }
            Act.PEN_NIRMAL -> togglePen(Pen.NIRMAL)
            Act.PEN_CLASSIC -> togglePen(Pen.CLASSIC)
            Act.PEN_PENCIL -> togglePen(Pen.PENCIL)
            Act.PEN_ANNOT -> togglePen(Pen.ANNOT)
            Act.PEN_NEXT -> {
                val cur = prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3)
                prefs.edit().putInt(Prefs.PREV_PEN, cur).putInt(Prefs.PEN_TYPE, (cur + 1) % 4).apply()
                buzz(25L)
            }
            Act.COLOR_RB -> {
                val next = if (prefs.getInt(Prefs.COLOR, Prefs.RED) == Prefs.BLUE) Prefs.RED else Prefs.BLUE
                setColor(next)
            }
            Act.COLOR_NEXT -> {
                val pal = Prefs.PALETTE
                val idx = pal.indexOf(prefs.getInt(Prefs.COLOR, Prefs.RED))
                setColor(pal[(idx + 1) % pal.size])
            }
            Act.COLOR_RED -> setColor(Prefs.RED)
            Act.COLOR_BLUE -> setColor(Prefs.BLUE)
            Act.COLOR_BLACK -> setColor(Prefs.BLACK)
            Act.SIZE_UP -> {
                Prefs.stepSize(prefs, 1)
                buzz(15L)
            }
            Act.SIZE_DOWN -> {
                Prefs.stepSize(prefs, -1)
                buzz(15L)
            }
            Act.OPACITY_UP -> {
                Prefs.stepOpacity(prefs, 1)
                buzz(15L)
            }
            Act.OPACITY_DOWN -> {
                Prefs.stepOpacity(prefs, -1)
                buzz(15L)
            }
            Act.BUBBLE -> {
                prefs.edit().putBoolean(Prefs.BUBBLE_ON, !prefs.getBoolean(Prefs.BUBBLE_ON, true)).apply()
                buzz(25L)
            }
            Act.PAD_OFF -> PadNotifier.turnOff(ctx)
            Act.G_BACK -> global(AccessibilityService.GLOBAL_ACTION_BACK)
            Act.G_HOME -> global(AccessibilityService.GLOBAL_ACTION_HOME)
            Act.G_RECENTS -> global(AccessibilityService.GLOBAL_ACTION_RECENTS)
            Act.G_NOTIFS -> global(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            Act.G_QUICK -> global(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            Act.G_SHOT -> {
                if (Build.VERSION.SDK_INT >= 28) global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            else -> {
            }
        }
    }

    private fun global(code: Int) {
        FloatingPenService.instance?.performGlobalAction(code)
        buzz(20L)
    }

    private fun setColor(c: Int) {
        prefs.edit().putInt(Prefs.COLOR, c).apply()
        buzz(40L)
    }

    /** Switch to a pen; pressing again returns to the pen that was used before. */
    private fun togglePen(target: Int) {
        val cur = prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL).coerceIn(0, 3)
        if (cur == target) {
            var prev = prefs.getInt(Prefs.PREV_PEN, Pen.NIRMAL).coerceIn(0, 3)
            if (prev == target) prev = if (target == Pen.NIRMAL) Pen.CLASSIC else Pen.NIRMAL
            prefs.edit().putInt(Prefs.PEN_TYPE, prev).apply()
        } else {
            prefs.edit().putInt(Prefs.PREV_PEN, cur).putInt(Prefs.PEN_TYPE, target).apply()
        }
        buzz(25L)
    }

    private fun runCustom(id: String, depth: Int) {
        val c = CustomActions.find(prefs, id) ?: return
        if (c.type == CustomActions.APP) {
            val intent = ctx.packageManager.getLaunchIntentForPackage(c.value) ?: return
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            buzz(30L)
        } else if (c.type == CustomActions.MACRO) {
            if (depth > 0) return
            var delay = 0L
            for (raw in c.value.split(',')) {
                val step = raw.trim()
                if (step.isEmpty() || step.startsWith(Act.CUSTOM_PREFIX)) continue
                if (delay == 0L) {
                    run(step, 1)
                } else {
                    main.postDelayed({ run(step, 1) }, delay)
                }
                delay += 250L
            }
        }
    }
}

/**
 * Smart pen side buttons (Page Up / Page Down keys) -> the actions chosen in the app.
 * The 1-tap action fires at once. If a button also has a 2-tap action and it is pressed twice quickly,
 * the pen is put back to how it was before the first tap, and the 2-tap action runs instead.
 * Shared by the overlay and the in-app pad.
 */
class PenButtons(
    private val ctx: Context,
    private val getTool: () -> Int,
    private val setTool: (Int) -> Unit,
    private val clear: () -> Unit,
    private val undo: () -> Unit,
    private val buzz: (Long) -> Unit,
) {
    private val prefs: SharedPreferences by lazy { Prefs.get(ctx) }
    private val runner: ActionRunner by lazy { ActionRunner(ctx, getTool, setTool, clear, undo, buzz) }

    private class Snap(val tool: Int, val pen: Int, val color: Int, val size: Float, val opacity: Int)

    private fun take(): Snap = Snap(
        getTool(),
        prefs.getInt(Prefs.PEN_TYPE, Pen.NIRMAL),
        prefs.getInt(Prefs.COLOR, Prefs.RED),
        Prefs.size(prefs),
        Prefs.opacity(prefs),
    )

    private fun restore(s: Snap) {
        prefs.edit()
            .putInt(Prefs.PEN_TYPE, s.pen)
            .putInt(Prefs.COLOR, s.color)
            .putFloat(Prefs.SIZE_DP, s.size)
            .putInt(Prefs.OPACITY, s.opacity)
            .apply()
        setTool(s.tool)
    }

    private inner class Btn(private val key1: String, private val key2: String) {
        private var lastAt = 0L
        private var snap: Snap? = null

        fun press() {
            val now = SystemClock.uptimeMillis()
            val a1 = Prefs.action(prefs, key1)
            val a2 = Prefs.action(prefs, key2)
            val s = snap
            if (a2 != Act.NONE && s != null && now - lastAt <= Prefs.doubleMs(prefs)) {
                snap = null
                lastAt = 0L
                restore(s)
                runner.run(a2)
                return
            }
            snap = take()
            lastAt = now
            runner.run(a1)
        }
    }

    private val up = Btn(Prefs.BTN_UP_1, Prefs.BTN_UP_2)
    private val down = Btn(Prefs.BTN_DOWN_1, Prefs.BTN_DOWN_2)

    fun pageUp() {
        up.press()
    }

    fun pageDown() {
        down.press()
    }

    /** Nib held still: run the chosen action. */
    fun runHold() {
        runner.run(Prefs.action(prefs, Prefs.HOLD_ACTION))
    }
}
