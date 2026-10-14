package ruido.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.runBlocking
import ruido.core.Levels
import ruido.core.NightEngine
import ruido.core.NightRecord
import ruido.core.NoiseGenerator
import kotlin.math.roundToInt

/**
 * Runs the whole night: listens to the microphone, asks [NightEngine] what to do every
 * 100 ms and plays the masking noise. A foreground service, so Android keeps it alive.
 * It has to be started while the app is on screen (Android does not allow starting the
 * microphone from the background).
 */
class NightService : Service() {

    @Volatile private var running = false
    @Volatile private var gain = 0.0

    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedMediaVolume: Int? = null

    private lateinit var storage: NightStorage
    private var engine: NightEngine? = null
    private var startMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNight()
            return START_NOT_STICKY
        }
        if (!running) startNight()
        return START_NOT_STICKY
    }

    private fun startNight() {
        storage = NightStorage(this)
        val settings = runBlocking { SettingsStore(this@NightService).current() }
        startMs = System.currentTimeMillis()
        val config = settings.toNightConfig(startMs)

        startForeground(
            NOTIFICATION_ID,
            notification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruido:night")
            .apply { acquire(16 * 60 * 60 * 1000L) }

        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        savedMediaVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * settings.mediaVolumePercent / 100.0).roundToInt(), 0)

        val engine = NightEngine(config, startMs).also { engine = it }
        storage.save(NightRecord(startMs, null, emptyList(), emptyList()))
        running = true
        gain = 0.0
        Live.update { LiveState(running = true, startMs = startMs, endAtMs = config.endAtMs) }

        playbackThread = Thread({ playbackLoop(settings) }, "ruido-playback").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        captureThread = Thread({ captureLoop(engine, config.windowMs) }, "ruido-capture").apply { start() }
    }

    @SuppressLint("MissingPermission") // Checked by the screen before starting the night.
    private fun captureLoop(engine: NightEngine, windowMs: Long) {
        val audio = getSystemService(AUDIO_SERVICE) as AudioManager
        // UNPROCESSED skips automatic gain control, which would flatten the very peaks we detect.
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val samplesPerWindow = (SAMPLE_RATE_IN * windowMs / 1000).toInt()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(source, SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, samplesPerWindow * 2 * 4))
        } catch (e: Exception) {
            fail("No se pudo abrir el micrófono: ${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            fail("No se pudo abrir el micrófono")
            return
        }
        Live.update { it.copy(audioSource = if (unprocessed) "Sin procesar" else "Reconocimiento de voz") }

        val buffer = ShortArray(samplesPerWindow)
        var lastSave = System.currentTimeMillis()
        record.startRecording()
        try {
            while (running) {
                var filled = 0
                while (filled < samplesPerWindow && running) {
                    val n = record.read(buffer, filled, samplesPerWindow - filled)
                    if (n < 0) throw IllegalStateException("Error del micrófono $n")
                    filled += n
                }
                if (!running) break
                val now = System.currentTimeMillis()
                val tick = engine.tick(now, Levels.rmsDbfs(buffer, filled))
                gain = tick.gain
                Live.update {
                    it.copy(
                        phase = tick.phase, levelDb = tick.levelDb, baselineDb = tick.baselineDb,
                        expectedDb = tick.expectedDb, leakDb = engine.leakDb, gain = tick.gain,
                        noisy = tick.noisy, events = engine.events.size, playbackStarts = engine.spans.size +
                            if (tick.phase == NightEngine.Phase.PLAYING) 1 else 0,
                    )
                }
                if (tick.finishedEvent != null || tick.finishedSpan != null || now - lastSave > SAVE_EVERY_MS) {
                    save(engine, null)
                    lastSave = now
                }
                if (tick.phase == NightEngine.Phase.ENDED && tick.gain == 0.0) {
                    android.os.Handler(mainLooper).post { stopNight() }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture", e)
            fail("El micrófono se detuvo: ${e.message}")
        } finally {
            record.stop()
            record.release()
        }
    }

    private fun playbackLoop(settings: AppSettings) {
        val generator = NoiseGenerator(settings.color, seed = System.nanoTime())
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK * 4 * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val chunk = FloatArray(CHUNK)
        var current = 0.0
        var playing = false
        try {
            while (running) {
                val next = gain
                if (next == 0.0 && current == 0.0) {
                    // Nothing to play: pause the track so the speaker is idle, and wait.
                    if (playing) {
                        track.pause(); track.flush(); playing = false
                    }
                    Thread.sleep(50)
                    continue
                }
                if (!playing) {
                    track.play(); playing = true
                }
                generator.fill(chunk, current, next)
                current = next
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.e(TAG, "playback", e)
            fail("El reproductor se detuvo: ${e.message}")
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun save(engine: NightEngine, endMs: Long?) {
        runCatching {
            storage.save(NightRecord(startMs, endMs, engine.events.toList(), engine.spans.toList()))
        }.onFailure { Log.e(TAG, "save", it) }
    }

    private fun fail(message: String) {
        Live.update { it.copy(error = message) }
        android.os.Handler(mainLooper).post { stopNight() }
    }

    private fun stopNight() {
        if (running) {
            running = false
            captureThread?.join(2000)
            playbackThread?.interrupt()
            playbackThread?.join(2000)
            val now = System.currentTimeMillis()
            engine?.let {
                it.finish(now)
                save(it, now)
            }
            savedMediaVolume?.let {
                (getSystemService(AUDIO_SERVICE) as AudioManager).setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
            }
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        Live.update { it.copy(running = false, gain = 0.0) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopNight()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Noche en curso", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NightService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Escuchando la noche")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Terminar noche", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "NightService"
        private const val CHANNEL = "night"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "ruido.STOP"
        private const val SAMPLE_RATE_IN = 16_000
        private const val SAMPLE_RATE_OUT = 48_000
        /** 20 ms of output per write. */
        private const val CHUNK = 960
        private const val SAVE_EVERY_MS = 5 * 60 * 1000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NightService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NightService::class.java).setAction(ACTION_STOP))
        }
    }
}
