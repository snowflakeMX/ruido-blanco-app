package ruido.app

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ruido.core.ManualSlot
import ruido.core.NightConfig
import ruido.core.NightConfig.Companion.MINUTE
import ruido.core.NightConfig.Companion.SECOND
import ruido.core.NoiseColor
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Everything the user can configure. */
data class AppSettings(
    /** How many dB above the background counts as a noise. Lower = more sensitive. */
    val thresholdDb: Int = 12,
    val holdMinutes: Int = 15,
    val fadeInSeconds: Int = 5,
    val fadeOutSeconds: Int = 20,
    /** Noise level inside the app, 5..100 %. */
    val volumePercent: Int = 50,
    /** Phone media volume set during the night (the real loudness cap), 5..100 %. */
    val mediaVolumePercent: Int = 40,
    val color: NoiseColor = NoiseColor.PINK,
    val wakeHour: Int = 7,
    val wakeMinute: Int = 0,
    /** Seconds the night screen stays readable after a touch; 0 = goes black right away. */
    val screenOnSeconds: Int = 15,
    /** Screen brightness while the night screen is readable, 5..100 %. */
    val nightBrightnessPercent: Int = 40,
    val maxPlaybackMinutes: Int = 60,
    /** Start softly before the times the neighbors are usually noisy. */
    val preventionEnabled: Boolean = true,
    /** How early prevention starts before a noisy time. */
    val preventionLeadMinutes: Int = 10,
    /** Prevention volume, as a share of [volumePercent]. */
    val preventionVolumePercent: Int = 50,
    /** Times the user knows are noisy, always prevented. */
    val manualSlots: List<ManualSlot> = emptyList(),
    /** Recognize snoring, coughing and crying so they don't start the masking. */
    val classifyEnabled: Boolean = true,
    /** Louder noises get louder masking, up to the volume. */
    val adaptiveVolume: Boolean = false,
    /** Name of the user's own sound file, if one was picked. */
    val ownSoundName: String? = null,
) {
    /** Next occurrence of the wake-up time after [nowMs]. */
    fun wakeAtMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        var wake = now.with(LocalTime.of(wakeHour, wakeMinute))
        if (!wake.isAfter(now)) wake = wake.plusDays(1)
        return wake.toInstant().toEpochMilli()
    }

    /** Engine parameters. [endAtMs] is null for the test mode, which has no end. */
    fun toNightConfig(endAtMs: Long?, preventive: List<LongRange> = emptyList()) = NightConfig(
        thresholdDb = thresholdDb.toDouble(),
        holdMs = holdMinutes * MINUTE,
        fadeInMs = fadeInSeconds * SECOND,
        fadeOutMs = fadeOutSeconds * SECOND,
        volume = volumePercent / 100.0,
        maxPlaybackMs = maxPlaybackMinutes * MINUTE,
        endAtMs = endAtMs,
        preventive = if (preventionEnabled) preventive else emptyList(),
        preventiveVolume = preventionVolumePercent / 100.0,
        adaptiveVolume = adaptiveVolume,
    )

    /** Keeps every value inside its allowed range. */
    fun clamped() = copy(
        thresholdDb = thresholdDb.coerceIn(4, 30),
        holdMinutes = holdMinutes.coerceIn(1, 60),
        fadeInSeconds = fadeInSeconds.coerceIn(1, 30),
        fadeOutSeconds = fadeOutSeconds.coerceIn(5, 120),
        volumePercent = volumePercent.coerceIn(5, 100),
        mediaVolumePercent = mediaVolumePercent.coerceIn(5, 100),
        wakeHour = wakeHour.coerceIn(0, 23),
        wakeMinute = wakeMinute.coerceIn(0, 59),
        screenOnSeconds = screenOnSeconds.coerceIn(0, 300),
        nightBrightnessPercent = nightBrightnessPercent.coerceIn(5, 100),
        maxPlaybackMinutes = maxPlaybackMinutes.coerceIn(15, 240),
        preventionLeadMinutes = preventionLeadMinutes.coerceIn(0, 30),
        preventionVolumePercent = preventionVolumePercent.coerceIn(20, 100),
        manualSlots = manualSlots.take(20),
    )
}

/**
 * Settings saved in private preferences. The [state] updates synchronously on every
 * change, so the screen and a running night see the new value immediately.
 */
object SettingsRepo {
    private var prefs: SharedPreferences? = null
    private val mutable = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = mutable

    val current: AppSettings get() = mutable.value

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs = p
        mutable.value = read(p)
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(mutable.value).clamped()
        mutable.value = next
        prefs?.edit()?.apply {
            putInt("threshold_db", next.thresholdDb)
            putInt("hold_min", next.holdMinutes)
            putInt("fade_in_s", next.fadeInSeconds)
            putInt("fade_out_s", next.fadeOutSeconds)
            putInt("volume_pct", next.volumePercent)
            putInt("media_volume_pct", next.mediaVolumePercent)
            putString("color", next.color.name)
            putInt("wake_hour", next.wakeHour)
            putInt("wake_minute", next.wakeMinute)
            putInt("screen_on_s", next.screenOnSeconds)
            putInt("night_brightness_pct", next.nightBrightnessPercent)
            putInt("max_playback_min", next.maxPlaybackMinutes)
            putBoolean("prevention", next.preventionEnabled)
            putInt("prevention_lead_min", next.preventionLeadMinutes)
            putInt("prevention_volume_pct", next.preventionVolumePercent)
            putString("manual_slots", next.manualSlots.joinToString(";") { it.encode() })
            putBoolean("classify", next.classifyEnabled)
            putBoolean("adaptive_volume", next.adaptiveVolume)
            putString("own_sound_name", next.ownSoundName)
            apply()
        }
    }

    private fun read(p: SharedPreferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            thresholdDb = p.getInt("threshold_db", d.thresholdDb),
            holdMinutes = p.getInt("hold_min", d.holdMinutes),
            fadeInSeconds = p.getInt("fade_in_s", d.fadeInSeconds),
            fadeOutSeconds = p.getInt("fade_out_s", d.fadeOutSeconds),
            volumePercent = p.getInt("volume_pct", d.volumePercent),
            mediaVolumePercent = p.getInt("media_volume_pct", d.mediaVolumePercent),
            color = p.getString("color", null)?.let { name -> NoiseColor.entries.firstOrNull { it.name == name } } ?: d.color,
            wakeHour = p.getInt("wake_hour", d.wakeHour),
            wakeMinute = p.getInt("wake_minute", d.wakeMinute),
            screenOnSeconds = p.getInt("screen_on_s", d.screenOnSeconds),
            nightBrightnessPercent = p.getInt("night_brightness_pct", d.nightBrightnessPercent),
            maxPlaybackMinutes = p.getInt("max_playback_min", d.maxPlaybackMinutes),
            preventionEnabled = p.getBoolean("prevention", d.preventionEnabled),
            preventionLeadMinutes = p.getInt("prevention_lead_min", d.preventionLeadMinutes),
            preventionVolumePercent = p.getInt("prevention_volume_pct", d.preventionVolumePercent),
            manualSlots = p.getString("manual_slots", null).orEmpty().split(';').mapNotNull { ManualSlot.decode(it) },
            classifyEnabled = p.getBoolean("classify", d.classifyEnabled),
            adaptiveVolume = p.getBoolean("adaptive_volume", d.adaptiveVolume),
            ownSoundName = p.getString("own_sound_name", null),
        ).clamped()
    }

    /** For tests: forget the loaded preferences. */
    internal fun reset() {
        prefs = null
        mutable.value = AppSettings()
    }
}
