package ruido.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick settings tile: starts the night without looking for the app, or ends it.
 * Android only lets the microphone start while the app is on screen, so starting
 * opens the app, which starts the night and shows the night screen.
 */
class NightTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val night = Live.state.value.night
        qsTile?.apply {
            state = if (night) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (night) "Escuchando" else null
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (Live.state.value.night) {
            runCatching { NightService.stop(this) }
            qsTile?.apply { state = Tile.STATE_INACTIVE; subtitle = null; updateTile() }
            return
        }
        // The alias is not exported: only this app can ask it to start a night.
        val start = Intent().setComponent(ComponentName(this, START_ALIAS)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        unlockAndRun {
            if (Build.VERSION.SDK_INT >= 34) {
                startActivityAndCollapse(PendingIntent.getActivity(this, 0, start, PendingIntent.FLAG_IMMUTABLE))
            } else {
                @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
                startActivityAndCollapse(start)
            }
        }
    }

    companion object {
        const val START_ALIAS = "ruido.app.StartNight"
    }
}
