package com.example.audio

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.R
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class SoundType(val id: String, val label: String, val iconName: String) {
    RAIN("rain", "Yağmur", "umbrella"),
    STORM("storm", "Fırtına", "thunderstorm"),
    WIND("wind", "Rüzgar", "air"),
    WAVES("waves", "Dalgalar", "tsunami"),
    STREAM("stream", "Dere", "waves"),
    FIREPLACE("fireplace", "Şömine", "local_fire_department"),
    BIRDS("birds", "Kuşlar", "flutter_dash"),
    CRICKETS("crickets", "Yaz Gecesi", "nights_stay"),
    TRAIN("train", "Tren", "train"),
    COFFEE_SHOP("coffee_shop", "Kafe", "local_cafe"),
    WHITE_NOISE("white_noise", "Beyaz Gürültü", "blur_on"),
    PINK_NOISE("pink_noise", "Pembe Gürültü", "grain"),
    BOAT("boat", "Tekne", "sailing"),
    CITY("city", "Şehir", "location_city")
}

class AudioEngine(private val context: Context) {
    private val TAG = "AudioEngine"
    
    // Background thread dedicated to running all ExoPlayer instances and updates
    private val audioThread = HandlerThread("AudioEngineThread").apply { start() }
    private val handler = Handler(audioThread.looper)
    private val engineJob = SupervisorJob()
    private val engineDispatcher = handler.asCoroutineDispatcher("AudioEngineDispatcher")
    private val engineScope = CoroutineScope(engineDispatcher + engineJob)

    private var isPlaying = false

    // Store sound volumes (0.0f to 1.0f). Key is soundId.
    private val soundVolumes = ConcurrentHashMap<String, Float>()

    // Map of active ExoPlayer instances. Key is soundId.
    private val players = ConcurrentHashMap<String, ExoPlayer>()

    // Store metadata needed to initialize custom/built-in sounds lazily.
    private val soundMetadata = ConcurrentHashMap<String, SoundMetadata>()

    // Map to hold pending debounce release jobs. Key is soundId.
    private val releaseJobs = ConcurrentHashMap<String, Job>()

    private data class SoundMetadata(
        val isCustom: Boolean,
        val filePath: String?,
        val rawResId: Int?
    )

    init {
        // Initialize default built-in sounds metadata and set their volume to 0f
        SoundType.values().forEach { type ->
            val resId = getRawResourceForSoundType(type)
            soundMetadata[type.id] = SoundMetadata(isCustom = false, filePath = null, rawResId = resId)
            soundVolumes[type.id] = 0f
        }
    }

    fun setVolume(
        soundId: String,
        volume: Float,
        isCustom: Boolean = false,
        filePath: String? = null,
        rawResId: Int? = null
    ) {
        val coercedVolume = volume.coerceIn(0f, 1f)
        soundVolumes[soundId] = coercedVolume

        // Update or register metadata
        soundMetadata[soundId] = SoundMetadata(isCustom, filePath, rawResId ?: getRawResourceForId(soundId))

        Log.d(TAG, "Volume set for $soundId: $coercedVolume")

        // Cancel pending release job for this soundId
        releaseJobs.remove(soundId)?.cancel()

        engineScope.launch {
            if (coercedVolume > 0f) {
                if (isPlaying) {
                    val player = getOrCreatePlayer(soundId)
                    player.volume = coercedVolume
                    if (!player.isPlaying) {
                        player.playWhenReady = true
                    }
                } else {
                    players[soundId]?.volume = coercedVolume
                }
            } else {
                players[soundId]?.playWhenReady = false
                
                // Debounce release ExoPlayer after 2.5 seconds
                val job = engineScope.launch {
                    delay(2500L)
                    if ((soundVolumes[soundId] ?: 0f) == 0f) {
                        players.remove(soundId)?.let { player ->
                            player.stop()
                            player.release()
                            Log.d(TAG, "Released ExoPlayer for $soundId after 2.5s debounce")
                        }
                    }
                }
                releaseJobs[soundId] = job
            }
        }
    }

    fun getVolume(soundId: String): Float {
        return soundVolumes[soundId] ?: 0f
    }

    fun getActiveVolumes(): Map<String, Float> {
        return soundVolumes.toMap()
    }

    @Synchronized
    fun start() {
        if (isPlaying) return
        isPlaying = true
        Log.d(TAG, "AudioEngine starting playing sounds...")

        engineScope.launch {
            soundVolumes.forEach { (soundId, vol) ->
                if (vol > 0f) {
                    releaseJobs.remove(soundId)?.cancel()
                    val player = getOrCreatePlayer(soundId)
                    player.volume = vol
                    if (!player.isPlaying) {
                        player.playWhenReady = true
                    }
                }
            }
        }
    }

    @Synchronized
    fun pause() {
        if (!isPlaying) return
        isPlaying = false
        Log.d(TAG, "AudioEngine pausing playback...")

        engineScope.launch {
            players.values.forEach { player ->
                player.playWhenReady = false
            }
        }
    }

    @Synchronized
    fun stop() {
        pause()
        releaseJobs.values.forEach { it.cancel() }
        releaseJobs.clear()
        engineScope.launch {
            players.values.forEach { player ->
                player.stop()
                player.release()
            }
            players.clear()
            soundVolumes.keys.forEach { soundVolumes[it] = 0f }
            Log.d(TAG, "AudioEngine stopped and resources released.")
            
            engineJob.cancel()
            audioThread.quitSafely()
        }
    }

    internal fun hasPlayer(soundId: String): Boolean {
        return players.containsKey(soundId)
    }

    internal fun getActivePlayerCount(): Int {
        return players.size
    }

    private fun getOrCreatePlayer(soundId: String): ExoPlayer {
        return players.getOrPut(soundId) {
            val meta = soundMetadata[soundId] ?: throw IllegalArgumentException("No metadata registered for sound: $soundId")
            val rawUri = if (meta.isCustom && meta.filePath != null) {
                Uri.fromFile(File(meta.filePath))
            } else {
                Uri.parse("android.resource://${context.packageName}/${meta.rawResId}")
            }
            Log.d(TAG, "Creating ExoPlayer lazily for sound: $soundId")
            ExoPlayer.Builder(context)
                .setPlaybackLooper(audioThread.looper)
                .build().apply {
                    setMediaItem(MediaItem.fromUri(rawUri))
                    repeatMode = Player.REPEAT_MODE_ALL
                    prepare()
                }
        }
    }

    private fun getRawResourceForId(id: String): Int? {
        val type = SoundType.values().find { it.id == id }
        return type?.let { getRawResourceForSoundType(it) }
    }

    private fun getRawResourceForSoundType(soundType: SoundType): Int {
        return when (soundType) {
            SoundType.RAIN -> R.raw.rain
            SoundType.STORM -> R.raw.storm
            SoundType.WIND -> R.raw.wind
            SoundType.WAVES -> R.raw.waves
            SoundType.STREAM -> R.raw.stream
            SoundType.FIREPLACE -> R.raw.fireplace
            SoundType.BIRDS -> R.raw.birds
            SoundType.CRICKETS -> R.raw.summer_night
            SoundType.TRAIN -> R.raw.train
            SoundType.COFFEE_SHOP -> R.raw.coffee_shop
            SoundType.WHITE_NOISE -> R.raw.white_noise
            SoundType.PINK_NOISE -> R.raw.pink_noise
            SoundType.BOAT -> R.raw.boat
            SoundType.CITY -> R.raw.city
        }
    }
}
