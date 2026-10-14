package ruido.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import ruido.core.Levels
import ruido.core.NightEngine
import ruido.core.NightRecord
import ruido.core.SoundKind
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Listens to the microphone, asks [NightEngine] what to do every 100 ms and plays the
 * masking noise. Runs as a foreground service so Android keeps it alive all night. It
 * must be started while the app is on screen: Android does not let a background app
 * start using the microphone.
 *
 * Two modes: a real night (saved, ends at the wake-up time) and the test mode in
 * settings (nothing saved, no end, follows setting changes live).
 */
class NightService : Service() {

    @Volatile private var running = false
    @Volatile private var pendingSettings: AppSettings? = null
    @Volatile private var pendingRecalibrate = false

    private var test = false
    private var captureThread: Thread? = null
    private var player: NoisePlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var mediaVolume: MediaVolume? = null
    private var scope: CoroutineScope? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var storage: NightStorage
    private var engine: NightEngine? = null
    private var startMs = 0L

    /** Sound recognition for one night: the model (null while loading or if it failed) and its thread. */
    private class Recognition {
        @Volatile var classifier: SoundClassifier? = null
        val thread: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "ruido-classifier") }
    }

    @Volatile private var recognition: Recognition? = null
    /** Classifier answers, applied by the capture thread (the engine is not thread-safe). */
    private val kinds = ConcurrentLinkedQueue<Pair<Long, SoundKind>>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when {
            intent?.action == ACTION_STOP -> stopListening()
            !running -> startListening(test = intent?.action == ACTION_TEST)
        }
        return START_NOT_STICKY
    }

    private fun startListening(test: Boolean) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Live.update { it.copy(error = "Falta el permiso del micrófono") }
            stopSelf()
            return
        }
        SettingsRepo.init(this)
        SoundPreview.stop()
        this.test = test
        storage = NightStorage(this)
        val settings = SettingsRepo.current
        startMs = System.currentTimeMillis()
        val endAt = if (test) null else settings.wakeAtMs(startMs)

        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ruido:night")
            .apply { acquire(16 * 60 * 60 * 1000L) }

        mediaVolume = MediaVolume(this).apply { set(settings.mediaVolumePercent) }

        // Tonight's prevention, from past nights and the user's fixed times.
        val windows = if (endAt == null) emptyList() else runCatching {
            Prevention.windows(storage.observations(startMs), settings, startMs, endAt)
        }.onFailure { Log.e(TAG, "prevention", it) }.getOrDefault(emptyList())
        val engine = NightEngine(settings.toNightConfig(endAt, windows), startMs).also { engine = it }
        if (!test) storage.save(NightRecord(startMs, null, emptyList(), emptyList()))
        running = true
        Live.update {
            LiveState(
                running = true, test = test, startMs = startMs, endAtMs = endAt, thresholdDb = settings.thresholdDb.toDouble(),
                windows = windows, classifier = if (settings.classifyEnabled) "Cargando…" else "Apagado",
            )
        }

        kinds.clear()
        if (settings.classifyEnabled) {
            val r = Recognition().also { recognition = it }
            r.thread.apply {
                execute {
                    try {
                        r.classifier = SoundClassifier.load(this@NightService)
                        Live.update { it.copy(classifier = "Activo") }
                    } catch (e: Throwable) {
                        Log.e(TAG, "classifier", e)
                        Live.update { it.copy(classifier = "No disponible: ${e.message ?: e.javaClass.simpleName}") }
                    }
                }
            }
        }

        player = NoisePlayer(settings.color, OwnSound.file(this), ::fail).apply { start() }
        captureThread = Thread({ captureLoop(engine) }, "ruido-capture").apply { start() }

        // Setting changes apply right away (mainly for the test mode).
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).apply {
            launch {
                var last = settings
                SettingsRepo.state.drop(1).collect { s ->
                    player?.color = s.color
                    if (s.mediaVolumePercent != last.mediaVolumePercent) {
                        mediaVolume?.set(s.mediaVolumePercent)
                        // The microphone now hears the app's noise at another level.
                        pendingRecalibrate = true
                    }
                    pendingSettings = s
                    last = s
                }
            }
        }
    }

    private fun captureLoop(engine: NightEngine) {
        val audio = getSystemService(AudioManager::class.java)
        // UNPROCESSED skips automatic gain control, which would flatten the very peaks we detect.
        val unprocessed = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val windowMs = engine.config.windowMs
        val samplesPerWindow = (SAMPLE_RATE * windowMs / 1000).toInt()
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            @Suppress("MissingPermission") // Checked in startListening.
            AudioRecord(source, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, samplesPerWindow * 2 * 4))
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
        // The last two seconds of audio, kept only in memory for the classifier.
        val recent = ShortArray(SAMPLE_RATE * 2)
        var recentPos = 0
        /** Noises waiting for one second of audio before being classified. */
        val pending = ArrayDeque<Long>()
        var lastSave = System.currentTimeMillis()
        var lastSpans = 0
        try {
            record.startRecording()
            while (running) {
                var filled = 0
                while (filled < samplesPerWindow && running) {
                    val n = record.read(buffer, filled, samplesPerWindow - filled)
                    if (n < 0) throw IllegalStateException("código $n")
                    filled += n
                }
                if (!running) break
                val now = System.currentTimeMillis()

                pendingSettings?.let { s ->
                    pendingSettings = null
                    engine.updateConfig(s.toNightConfig(engine.config.endAtMs))
                }
                if (pendingRecalibrate) {
                    pendingRecalibrate = false
                    engine.recalibrate(now)
                }
                while (true) {
                    val (eventMs, kind) = kinds.poll() ?: break
                    engine.classify(eventMs, kind)
                    Live.update { it.copy(lastKind = kind) }
                }

                for (i in 0 until filled) {
                    recent[recentPos] = buffer[i]
                    recentPos = (recentPos + 1) % recent.size
                }
                val tick = engine.tick(now, Levels.rmsDbfs(buffer, filled))
                val recognizer = recognition
                tick.startedEventMs?.let { if (recognizer?.classifier != null) pending.addLast(it) }
                // About a second after a noise began, classify the last 0.975 s.
                while (pending.isNotEmpty() && now - pending.first() >= CLASSIFY_AFTER_MS) {
                    val eventMs = pending.removeFirst()
                    val clip = ShortArray(SoundClassifier.SAMPLES) { i ->
                        recent[(recentPos - SoundClassifier.SAMPLES + i + recent.size) % recent.size]
                    }
                    val r = recognizer ?: continue
                    val c = r.classifier ?: continue
                    runCatching {
                        r.thread.execute {
                            runCatching { kinds.add(eventMs to c.classify(clip)) }.onFailure { Log.e(TAG, "classify", it) }
                        }
                    }
                }
                player?.gain = tick.gain
                val playing = tick.phase == NightEngine.Phase.PLAYING
                Live.update {
                    it.copy(
                        phase = tick.phase, levelDb = tick.levelDb, baselineDb = tick.baselineDb,
                        expectedDb = tick.expectedDb, thresholdDb = engine.config.thresholdDb,
                        leakDb = engine.leakDb, gain = tick.gain, noisy = tick.noisy, preventing = engine.preventing,
                        events = engine.events.size,
                        playbackStarts = engine.spans.size + if (playing) 1 else 0,
                    )
                }
                if (!test && (engine.spans.size != lastSpans || tick.finishedEvent != null || now - lastSave > SAVE_EVERY_MS)) {
                    save(engine, startMs, null)
                    lastSave = now
                    lastSpans = engine.spans.size
                }
                if (tick.phase == NightEngine.Phase.ENDED && tick.gain == 0.0) {
                    main.post { stopListening() }
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "capture", e)
            fail("El micrófono se detuvo: ${e.message}")
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private fun save(engine: NightEngine, startMs: Long, endMs: Long?) {
        runCatching {
            storage.save(NightRecord(startMs, endMs, engine.events.toList(), engine.spans.toList()))
        }.onFailure { Log.e(TAG, "save", it) }
    }

    private fun fail(message: String) {
        Live.update { it.copy(error = message) }
        main.post { stopListening() }
    }

    /**
     * Stops right away for the screen, then releases the microphone and speaker on a
     * background thread: waiting for them on the main thread could freeze the buttons.
     */
    private fun stopListening() {
        if (!running) {
            Live.update { it.stopped() }
            finishService()
            return
        }
        running = false
        scope?.cancel()
        Live.update { it.stopped() }
        val capture = captureThread
        val player = player
        val engine = engine
        val test = test
        val start = startMs
        val volume = mediaVolume
        val lock = wakeLock
        val classifying = recognition
        recognition = null
        Thread({
            capture?.join(2000)
            player?.stop()
            classifying?.thread?.shutdown()
            runCatching { classifying?.thread?.awaitTermination(2, TimeUnit.SECONDS) }
            classifying?.classifier?.close()
            val now = System.currentTimeMillis()
            engine?.let {
                it.finish(now)
                if (!test) save(it, start, now)
            }
            volume?.restore()
            lock?.takeIf { it.isHeld }?.release()
            main.post { if (!running) finishService() }
        }, "ruido-stop").start()
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopListening()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Noche en curso", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fijo mientras la app escucha. No suena ni vibra."
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, NightService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (test) "Probando la detección" else "Escuchando la noche")
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Terminar", stop).build())
            .build()
    }

    companion object {
        private const val TAG = "NightService"
        private const val CHANNEL = "night"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "ruido.STOP"
        private const val ACTION_TEST = "ruido.TEST"
        private const val SAMPLE_RATE = 16_000
        /** The classifier hears 0.975 s: wait that long after a noise starts, plus a little before it. */
        private const val CLASSIFY_AFTER_MS = 900L
        /** Saved often, so a night cut off by Android still shows when it stopped. */
        private const val SAVE_EVERY_MS = 60 * 1000L

        fun startNight(context: Context) {
            context.startForegroundService(Intent(context, NightService::class.java))
        }

        fun startTest(context: Context) {
            context.startForegroundService(Intent(context, NightService::class.java).setAction(ACTION_TEST))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NightService::class.java).setAction(ACTION_STOP))
        }
    }
}
