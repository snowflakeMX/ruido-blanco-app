package ruido.app

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import ruido.core.NightConfig
import ruido.core.NightConfig.Companion.MINUTE
import ruido.core.NightConfig.Companion.SECOND
import ruido.core.NoiseColor
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything the user can configure. */
data class AppSettings(
    val thresholdDb: Float = 12f,
    val holdMinutes: Int = 15,
    val fadeInSeconds: Int = 5,
    val fadeOutSeconds: Int = 20,
    /** Noise gain inside the app, 0.05..1. */
    val volume: Float = 0.5f,
    /** Media volume set at the start of the night (the real loudness cap), 0..100 %. */
    val mediaVolumePercent: Int = 40,
    val color: NoiseColor = NoiseColor.PINK,
    val wakeHour: Int = 7,
    val wakeMinute: Int = 0,
    /** Seconds the dim night screen stays visible; 0 = off right away. */
    val screenOnSeconds: Int = 10,
    val maxPlaybackMinutes: Int = 60,
    /** Turn the screen fully off (needs the accessibility permission). */
    val turnScreenOff: Boolean = true,
) {
    /** Next occurrence of the wake-up time after [nowMs]. */
    fun wakeAtMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
        var wake = now.with(LocalTime.of(wakeHour, wakeMinute)).withSecond(0).withNano(0)
        if (!wake.isAfter(now)) wake = wake.plusDays(1)
        return wake.toInstant().toEpochMilli()
    }

    fun toNightConfig(nowMs: Long) = NightConfig(
        thresholdDb = thresholdDb.toDouble(),
        holdMs = holdMinutes * MINUTE,
        fadeInMs = fadeInSeconds * SECOND,
        fadeOutMs = fadeOutSeconds * SECOND,
        volume = volume.toDouble(),
        maxPlaybackMs = maxPlaybackMinutes * MINUTE,
        endAtMs = wakeAtMs(nowMs),
    )
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsStore(private val context: Context) {
    private object K {
        val threshold = floatPreferencesKey("threshold_db")
        val hold = intPreferencesKey("hold_min")
        val fadeIn = intPreferencesKey("fade_in_s")
        val fadeOut = intPreferencesKey("fade_out_s")
        val volume = floatPreferencesKey("volume")
        val mediaVolume = intPreferencesKey("media_volume_pct")
        val color = stringPreferencesKey("color")
        val wakeHour = intPreferencesKey("wake_hour")
        val wakeMinute = intPreferencesKey("wake_minute")
        val screenOn = intPreferencesKey("screen_on_s")
        val maxPlayback = intPreferencesKey("max_playback_min")
        val turnScreenOff = booleanPreferencesKey("turn_screen_off")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun save(s: AppSettings) {
        context.dataStore.edit {
            it[K.threshold] = s.thresholdDb
            it[K.hold] = s.holdMinutes
            it[K.fadeIn] = s.fadeInSeconds
            it[K.fadeOut] = s.fadeOutSeconds
            it[K.volume] = s.volume
            it[K.mediaVolume] = s.mediaVolumePercent
            it[K.color] = s.color.name
            it[K.wakeHour] = s.wakeHour
            it[K.wakeMinute] = s.wakeMinute
            it[K.screenOn] = s.screenOnSeconds
            it[K.maxPlayback] = s.maxPlaybackMinutes
            it[K.turnScreenOff] = s.turnScreenOff
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            thresholdDb = this[K.threshold] ?: d.thresholdDb,
            holdMinutes = this[K.hold] ?: d.holdMinutes,
            fadeInSeconds = this[K.fadeIn] ?: d.fadeInSeconds,
            fadeOutSeconds = this[K.fadeOut] ?: d.fadeOutSeconds,
            volume = this[K.volume] ?: d.volume,
            mediaVolumePercent = this[K.mediaVolume] ?: d.mediaVolumePercent,
            color = this[K.color]?.let { runCatching { NoiseColor.valueOf(it) }.getOrNull() } ?: d.color,
            wakeHour = this[K.wakeHour] ?: d.wakeHour,
            wakeMinute = this[K.wakeMinute] ?: d.wakeMinute,
            screenOnSeconds = this[K.screenOn] ?: d.screenOnSeconds,
            maxPlaybackMinutes = this[K.maxPlayback] ?: d.maxPlaybackMinutes,
            turnScreenOff = this[K.turnScreenOff] ?: d.turnScreenOff,
        )
    }
}
