package com.example.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import kotlinx.coroutines.*

class BlanketAudioService : Service() {
    private val TAG = "BlanketAudioService"
    private val NOTIFICATION_ID = 101
    private val CHANNEL_ID = "blanket_ambient_channel"

    private val binder = LocalBinder()
    val audioEngine by lazy { AudioEngine(this) }
    private var mediaSession: MediaSession? = null

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pausePlayback()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus regained
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }
    private var isPlaying = false

    // Service Listener to push updates to bound ViewModel/Activity
    interface Listener {
        fun onStateChanged(isPlaying: Boolean, volumes: Map<String, Float>)
        fun onTimerTick(remainingMs: Long, totalMs: Long)
    }
    private var serviceListener: Listener? = null

    // Sleep Timer states
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    private var timerJob: Job? = null
    var sleepTimerTotalMs = 0L
        private set
    var sleepTimerRemainingMs = 0L
        private set

    inner class LocalBinder : Binder() {
        fun getService(): BlanketAudioService = this@BlanketAudioService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")
        createNotificationChannel()
        setupMediaSession()
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        Log.d(TAG, "onStartCommand action: $action")
        when (action) {
            "ACTION_PLAY" -> startPlayback()
            "ACTION_PAUSE" -> pausePlayback()
            "ACTION_TOGGLE" -> {
                if (isPlaying) pausePlayback() else startPlayback()
            }
            "ACTION_STOP" -> stopPlayback()
        }
        return START_NOT_STICKY
    }

    fun setListener(listener: Listener?) {
        this.serviceListener = listener
        // Push initial state
        listener?.onStateChanged(isPlaying, audioEngine.getActiveVolumes())
        listener?.onTimerTick(sleepTimerRemainingMs, sleepTimerTotalMs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Blanket Ortam Sesi Oynatıcı",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ortam sesleri oynatma kontrolleri"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "BlanketMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    startPlayback()
                }

                override fun onPause() {
                    pausePlayback()
                }

                override fun onStop() {
                    stopPlayback()
                }
            })
            isActive = true
        }
    }

    fun startPlayback() {
        if (isPlaying) return
        if (!requestAudioFocus()) {
            Log.d(TAG, "Audio focus request denied")
            return
        }
        isPlaying = true
        audioEngine.start()
        mediaSession?.isActive = true
        startForeground(NOTIFICATION_ID, buildNotification())
        notifyStateChange()
    }

    fun pausePlayback() {
        if (!isPlaying) return
        isPlaying = false
        audioEngine.pause()
        releaseAudioFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            stopForeground(false)
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
        notifyStateChange()
    }

    fun stopPlayback() {
        isPlaying = false
        audioEngine.stop()
        releaseAudioFocus()
        cancelSleepTimer()
        stopForeground(true)
        stopSelf()
        notifyStateChange()
    }

    private var notificationUpdateJob: Job? = null

    fun updateVolumesAndActiveState(immediate: Boolean = false) {
        if (immediate) {
            notificationUpdateJob?.cancel()
            if (isPlaying) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                try {
                    notificationManager.notify(NOTIFICATION_ID, buildNotification())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update notification", e)
                }
            }
            notifyStateChange()
        } else {
            notificationUpdateJob?.cancel()
            notificationUpdateJob = serviceScope.launch {
                delay(250L) // Wait for 250ms of user inactivity before posting notification updates
                if (isPlaying) {
                    val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    try {
                        notificationManager.notify(NOTIFICATION_ID, buildNotification())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update notification", e)
                    }
                }
                notifyStateChange()
            }
        }
    }

    // Sleep Timer management
    fun startSleepTimer(durationMs: Long) {
        timerJob?.cancel()
        sleepTimerTotalMs = durationMs
        sleepTimerRemainingMs = durationMs
        
        if (durationMs <= 0L) {
            notifyTimerTick()
            return
        }

        timerJob = serviceScope.launch {
            while (sleepTimerRemainingMs > 0L) {
                notifyTimerTick()
                delay(1000L)
                sleepTimerRemainingMs -= 1000L
            }
            sleepTimerRemainingMs = 0L
            sleepTimerTotalMs = 0L
            notifyTimerTick()
            
            // Timer finished, pause playback
            pausePlayback()
        }
    }

    fun cancelSleepTimer() {
        timerJob?.cancel()
        sleepTimerRemainingMs = 0L
        sleepTimerTotalMs = 0L
        notifyTimerTick()
    }

    private fun notifyTimerTick() {
        serviceListener?.onTimerTick(sleepTimerRemainingMs, sleepTimerTotalMs)
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, BlanketAudioService::class.java).apply { action = "ACTION_TOGGLE" }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BlanketAudioService::class.java).apply { action = "ACTION_STOP" }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeSoundLabels = audioEngine.getActiveVolumes().entries
            .filter { it.value > 0f }
            .map { (id, _) ->
                val type = SoundType.values().find { it.id == id }
                type?.label ?: id.replace("custom_", "").replace("_", " ")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

        val contentText = if (activeSoundLabels.isEmpty()) {
            "Aktif ses yok"
        } else if (activeSoundLabels.size <= 3) {
            "Çalınıyor: " + activeSoundLabels.joinToString(", ")
        } else {
            "Çalınıyor: " + activeSoundLabels.take(3).joinToString(", ") + " ve ${activeSoundLabels.size - 3} daha"
        }

        val toggleIcon = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val toggleLabel = if (isPlaying) "Duraklat" else "Oynat"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Blanket")
            .setContentText(contentText)
            .setContentIntent(openPendingIntent)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .addAction(toggleIcon, toggleLabel, togglePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Durdur", stopPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        return builder.build()
    }

    private fun notifyStateChange() {
        serviceListener?.onStateChanged(isPlaying, audioEngine.getActiveVolumes())
        
        val intent = Intent("com.example.blanket.STATE_CHANGED").apply {
            putExtra("EXTRA_IS_PLAYING", isPlaying)
            val volumesBundle = android.os.Bundle()
            audioEngine.getActiveVolumes().forEach { (id, vol) ->
                volumesBundle.putFloat(id, vol)
            }
            putExtra("EXTRA_VOLUMES", volumesBundle)
        }
        sendBroadcast(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d(TAG, "onTaskRemoved - swiped away. Stopping audio and service.")
        audioEngine.stop()
        releaseAudioFocus()
        cancelSleepTimer()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")
        serviceJob.cancel()
        audioEngine.stop()
        mediaSession?.release()
        mediaSession = null
    }
}
