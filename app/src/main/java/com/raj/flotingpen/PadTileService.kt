package com.raj.flotingpen

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: the way to switch the writing pad on/off without any icon on the screen. */
class PadTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (FloatingPenService.instance == null) {
            // The accessibility service is not running yet: open the app so it can be enabled.
            openApp()
            return
        }
        val prefs = Prefs.get(this)
        prefs.edit().putBoolean(Prefs.PAD_ON, !prefs.getBoolean(Prefs.PAD_ON, false)).apply()
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val on = Prefs.get(this).getBoolean(Prefs.PAD_ON, false) && FloatingPenService.instance != null
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            startActivityAndCollapse(i)
        }
    }
}
