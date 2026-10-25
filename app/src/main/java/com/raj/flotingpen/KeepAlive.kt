package com.raj.flotingpen

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * Keeps the app's process alive so Android / Xiaomi's battery manager does not kill the pen service in the
 * background. It shows one tiny silent "FloatingPen is running" notification and stops only when the
 * accessibility service is turned off by you.
 */
class KeepAliveService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            // Accessibility was switched on in Settings but the service is not running (killed): try to bring it back.
            if (FloatingPenService.instance == null) A11yGuard.ensure(this@KeepAliveService)
            main.postDelayed(this, CHECK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        main.removeCallbacks(tick)
        main.postDelayed(tick, CHECK_MS)
        return START_STICKY
    }

    private fun goForeground() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                val ch = NotificationChannel(CHANNEL, "Keep running", NotificationManager.IMPORTANCE_MIN)
                ch.setShowBadge(false)
                nm?.createNotificationChannel(ch)
            }
            val open = PendingIntent.getActivity(
                this, 7, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle("FloatingPen is running")
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ID, n)
            }
        } catch (_: Throwable) {
            // Not allowed to start in the foreground right now: stop quietly (the accessibility service keeps working).
            stopSelf()
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "keep_alive"
        private const val ID = 4109
        private const val CHECK_MS = 20_000L

        fun start(ctx: Context) {
            try {
                val i = Intent(ctx, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
            } catch (_: Throwable) {
            }
        }
    }
}

/** Phone restarted / app updated: start the keep-alive again and (if allowed) turn the accessibility service back on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED || a == "android.intent.action.QUICKBOOT_POWERON") {
            if (Prefs.get(context).getBoolean(Prefs.A11Y_WANTED, false)) {
                A11yGuard.ensure(context)
                KeepAliveService.start(context)
            }
        }
    }
}

/**
 * Helpers for the accessibility service and the "do not kill me" permissions.
 *
 * Re-enabling the accessibility service by itself needs the WRITE_SECURE_SETTINGS permission, which Android only
 * lets you grant once with adb (see README). Without it everything else still works.
 */
object A11yGuard {
    private fun cn(ctx: Context) = ComponentName(ctx, FloatingPenService::class.java).flattenToString()

    fun enabledInSettings(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val c = ComponentName(ctx, FloatingPenService::class.java)
        return s.split(':').any { it.equals(c.flattenToString(), true) || it.equals(c.flattenToShortString(), true) }
    }

    fun canWriteSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Brings the service back after Android killed it. Returns true when the service is (or should soon be) running. */
    fun ensure(ctx: Context): Boolean {
        if (FloatingPenService.instance != null) return true
        if (!Prefs.get(ctx).getBoolean(Prefs.A11Y_WANTED, false)) return false // you turned it off yourself
        if (!canWriteSecure(ctx)) return false
        return try {
            val cr = ctx.contentResolver
            val name = cn(ctx)
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val short = ComponentName(ctx, FloatingPenService::class.java).flattenToShortString()
            val others = cur.split(':').filter { it.isNotEmpty() && !it.equals(name, true) && !it.equals(short, true) }
            if (enabledInSettings(ctx)) {
                // listed but not running: switch it off and on again so Android binds it again
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + name).joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------ battery / auto-start

    fun batteryUnrestricted(ctx: Context): Boolean =
        try {
            ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) ?: false
        } catch (_: Throwable) {
            false
        }

    /** Asks Android to never put the app to sleep (standard "Don't optimise" dialog). */
    fun requestBattery(ctx: Context) {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tryStart(ctx, i)) {
            if (!tryStart(ctx, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) appInfo(ctx)
        }
    }

    /** Xiaomi / HyperOS "Battery saver > No restrictions" page of this app, else the normal app info page. */
    fun openXiaomiBattery(ctx: Context) {
        val a = Intent("miui.intent.action.HIDDEN_APPS_CONFIG_ACTIVITY")
            .putExtra("package_name", ctx.packageName)
            .putExtra("package_label", ctx.getString(R.string.app_name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val b = Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
            .putExtra("package_name", ctx.packageName)
            .putExtra("package_label", ctx.getString(R.string.app_name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!tryStart(ctx, a) && !tryStart(ctx, b)) appInfo(ctx)
    }

    /** Auto-start permission page (Xiaomi, Oppo, Vivo, Huawei, Samsung...) else the app info page. */
    fun openAutoStart(ctx: Context) {
        val targets = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
        for (t in targets) {
            if (tryStart(ctx, Intent().setComponent(t).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) return
        }
        appInfo(ctx)
    }

    fun appInfo(ctx: Context) {
        tryStart(
            ctx,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun tryStart(ctx: Context, i: Intent): Boolean =
        try {
            ctx.startActivity(i)
            true
        } catch (_: Throwable) {
            false
        }
}
