package com.raj.flotingpen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService

/** Notification-panel control: while the pad is on, "Clear" and "Turn off" buttons are shown. */
object PadNotifier {
    const val ACTION_OFF = "com.raj.flotingpen.action.PAD_OFF"
    const val ACTION_CLEAR = "com.raj.flotingpen.action.PAD_CLEAR"
    const val ACTION_BUBBLE = "com.raj.flotingpen.action.BUBBLE_TOGGLE"
    const val ACTION_BOX = "com.raj.flotingpen.action.BOX_TOGGLE"
    const val ACTION_FIX = "com.raj.flotingpen.action.FIX_PEN"
    private const val ID_TOOLS = 4108
    private const val CHANNEL = "pad_on"
    private const val ID = 4107

    private fun action(ctx: Context, code: Int, act: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code, Intent(ctx, PadActionReceiver::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun show(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CHANNEL, "Writing pad", NotificationManager.IMPORTANCE_LOW)
                ch.setShowBadge(false)
                nm.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                ctx, 2, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val icon = Icon.createWithResource(ctx, R.drawable.ic_tile)
            val bubbleOn = Prefs.get(ctx).getBoolean(Prefs.BUBBLE_ON, true)
            val boxOn = Prefs.get(ctx).getBoolean(Prefs.WHITE_BOX, false)
            // Android shows at most 3 buttons per notification: the bubble toggle moves to a small second one.
            val tools = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(if (bubbleOn) "Bubble is shown" else "Bubble is hidden")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(icon, if (bubbleOn) "Hide bubble" else "Show bubble", action(ctx, 4, ACTION_BUBBLE)).build())
                .addAction(Notification.Action.Builder(icon, "Fix pen", action(ctx, 6, ACTION_FIX)).build())
                .build()
            nm.notify(ID_TOOLS, tools)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("Writing pad is on")
                .setContentText("Write with the pen. Fingers work normally.")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(Notification.Action.Builder(icon, "Clear", action(ctx, 1, ACTION_CLEAR)).build())
                .addAction(Notification.Action.Builder(icon, if (boxOn) "White box: off" else "White box: on", action(ctx, 5, ACTION_BOX)).build())
                .addAction(Notification.Action.Builder(icon, "Turn off", action(ctx, 3, ACTION_OFF)).build())
                .build()
            nm.notify(ID, n)
        } catch (_: Throwable) {
        }
    }

    /** Switch the writing pad off (notification button, tile, app switch) and refresh the tile. */
    fun turnOff(ctx: Context) {
        Prefs.get(ctx).edit().putBoolean(Prefs.PAD_ON, false).apply() // the service hides the pad
        cancel(ctx)
        try {
            TileService.requestListeningState(ctx, ComponentName(ctx, PadTileService::class.java))
        } catch (_: Throwable) {
        }
    }

    fun cancel(ctx: Context) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm?.cancel(ID)
            nm?.cancel(ID_TOOLS)
        } catch (_: Throwable) {
        }
    }
}

/** Handles the buttons of the notification. */
class PadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            PadNotifier.ACTION_OFF -> PadNotifier.turnOff(context)
            PadNotifier.ACTION_CLEAR -> FloatingPenService.instance?.clearInk()
            PadNotifier.ACTION_FIX -> FloatingPenService.instance?.healNow()
            PadNotifier.ACTION_BOX -> {
                val sp = Prefs.get(context)
                sp.edit().putBoolean(Prefs.WHITE_BOX, !sp.getBoolean(Prefs.WHITE_BOX, false)).apply()
            }
            PadNotifier.ACTION_BUBBLE -> {
                val sp = Prefs.get(context)
                sp.edit().putBoolean(Prefs.BUBBLE_ON, !sp.getBoolean(Prefs.BUBBLE_ON, true)).apply()
            }
        }
    }
}
