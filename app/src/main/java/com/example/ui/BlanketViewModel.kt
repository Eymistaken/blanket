package com.example.ui

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.audio.BlanketAudioService
import com.example.audio.SoundType
import com.example.data.Preset
import com.example.data.PresetRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SoundState(
    val volume: Float = 0f,
    val isActive: Boolean = false
)

data class PendingImport(
    val uri: Uri,
    val destFile: File,
    val cleanName: String,
    val newSoundId: String
)

data class SoundItem(
    val id: String,
    val label: String,
    val iconName: String,
    val isCustom: Boolean = false,
    val filePath: String? = null,
    val rawResId: Int? = null
)

private data class SoundVolumeIntent(
    val soundId: String,
    val volume: Float,
    val immediate: Boolean
)

val BuiltInSounds = listOf(
    SoundItem("rain", "Yağmur", "umbrella", rawResId = R.raw.rain),
    SoundItem("storm", "Fırtına", "thunderstorm", rawResId = R.raw.storm),
    SoundItem("wind", "Rüzgar", "air", rawResId = R.raw.wind),
    SoundItem("waves", "Dalgalar", "tsunami", rawResId = R.raw.waves),
    SoundItem("stream", "Dere", "waves", rawResId = R.raw.stream),
    SoundItem("fireplace", "Şömine", "local_fire_department", rawResId = R.raw.fireplace),
    SoundItem("birds", "Kuşlar", "flutter_dash", rawResId = R.raw.birds),
    SoundItem("crickets", "Yaz Gecesi", "nights_stay", rawResId = R.raw.summer_night),
    SoundItem("train", "Tren", "train", rawResId = R.raw.train),
    SoundItem("coffee_shop", "Kafe", "local_cafe", rawResId = R.raw.coffee_shop),
    SoundItem("white_noise", "Beyaz Gürültü", "blur_on", rawResId = R.raw.white_noise),
    SoundItem("pink_noise", "Pembe Gürültü", "grain", rawResId = R.raw.pink_noise),
    SoundItem("boat", "Tekne", "sailing", rawResId = R.raw.boat),
    SoundItem("city", "Şehir", "location_city", rawResId = R.raw.city)
)

class BlanketViewModel(
    private val applicationContext: Context,
    private val repository: PresetRepository
) : ViewModel() {
    private val TAG = "BlanketViewModel"

    // Service Reference
    private var boundService: BlanketAudioService? = null
    private var isStateRestored = false
    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    // Real-time Playback State
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    // Current volumes from engine (key is soundId)
    private val _currentVolumes = MutableStateFlow<Map<String, Float>>(emptyMap())
    val currentVolumes: StateFlow<Map<String, Float>> = _currentVolumes.asStateFlow()

    // Currently active/toggled sound IDs
    private val _activeSoundIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSoundIds: StateFlow<Set<String>> = _activeSoundIds.asStateFlow()

    // Service Binding Error State
    private val _serviceBindError = MutableStateFlow<String?>(null)
    val serviceBindError: StateFlow<String?> = _serviceBindError.asStateFlow()

    // Pending Import Overwrite State (Option B)
    private val _pendingImport = MutableStateFlow<PendingImport?>(null)
    val pendingImport: StateFlow<PendingImport?> = _pendingImport.asStateFlow()

    // General User Message State (for Snackbar feedback)
    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    fun setServiceBindError(error: String?) {
        _serviceBindError.value = error
    }

    fun clearServiceBindError() {
        _serviceBindError.value = null
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    // Scoped per-sound state flow cache to isolate recompositions
    private val soundStateFlowCache = ConcurrentHashMap<String, StateFlow<SoundState>>()

    fun soundState(id: String): StateFlow<SoundState> {
        return soundStateFlowCache.getOrPut(id) {
            combine(_currentVolumes, _activeSoundIds) { volumes, activeIds ->
                SoundState(
                    volume = volumes[id] ?: 0f,
                    isActive = activeIds.contains(id)
                )
            }
            .distinctUntilChanged()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = SoundState(
                    volume = _currentVolumes.value[id] ?: 0f,
                    isActive = _activeSoundIds.value.contains(id)
                )
            )
        }
    }

    // High-Precision / Fine-Tuning State
    private val _highPrecisionSoundId = MutableStateFlow<String?>(null)
    val highPrecisionSoundId: StateFlow<String?> = _highPrecisionSoundId.asStateFlow()

    // Available sounds list (built-in + scanned custom sounds)
    private val _soundItems = MutableStateFlow<List<SoundItem>>(BuiltInSounds)
    val soundItems: StateFlow<List<SoundItem>> = _soundItems.asStateFlow()

    // Volume intent flow for throttling/sampling volume changes during drag gestures
    private val volumeIntentFlow = MutableSharedFlow<SoundVolumeIntent>(extraBufferCapacity = 128)

    // Store the last non-zero volume for each sound ID
    private val lastVolumes = ConcurrentHashMap<String, Float>().apply {
        BuiltInSounds.forEach { this[it.id] = 0.5f }
    }

    // Sleep Timer States
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()

    private val _sleepTimerTotalMs = MutableStateFlow(0L)
    val sleepTimerTotalMs: StateFlow<Long> = _sleepTimerTotalMs.asStateFlow()

    // Presets from DB
    val presets: StateFlow<List<Preset>> = repository.allPresets
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        loadCustomSounds()
        setupVolumeThrottling()
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun setupVolumeThrottling() {
        viewModelScope.launch(Dispatchers.Default) {
            volumeIntentFlow
                .sample(120L)
                .collect { intent ->
                    applyVolumeInternal(intent.soundId, intent.volume, immediate = false)
                }
        }
    }

    fun loadCustomSounds(onComplete: (() -> Unit)? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val customSoundsDir = File(applicationContext.filesDir, "custom_sounds")
                if (!customSoundsDir.exists()) {
                    customSoundsDir.mkdirs()
                }
                val files = customSoundsDir.listFiles() ?: emptyArray()
                val customItems = files.map { file ->
                    val baseName = file.nameWithoutExtension
                    val cleanLabel = baseName.replace("_", " ")
                        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                    val soundId = "custom_${baseName.lowercase()}"
                    
                    lastVolumes.putIfAbsent(soundId, 0.5f)
                    
                    SoundItem(
                        id = soundId,
                        label = cleanLabel,
                        iconName = "music_note",
                        isCustom = true,
                        filePath = file.absolutePath
                    )
                }
                _soundItems.value = BuiltInSounds + customItems
                
                viewModelScope.launch(Dispatchers.Main) {
                    onComplete?.invoke()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan custom sounds", e)
            }
        }
    }

    // Service Connection Implementation
    val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "onServiceConnected")
            val binder = service as? BlanketAudioService.LocalBinder
            if (binder != null) {
                boundService = binder.getService()
                _isServiceConnected.value = true
                clearServiceBindError()

                // Attach real-time listener
                boundService?.setListener(object : BlanketAudioService.Listener {
                    override fun onStateChanged(isPlaying: Boolean, volumes: Map<String, Float>) {
                        _isPlaying.value = isPlaying
                        
                        // Map volumes of all registered sound items (default 0f if not playing)
                        val fullMap = _soundItems.value.associate { item ->
                            item.id to (volumes[item.id] ?: 0f)
                        }
                        _currentVolumes.value = fullMap
                        
                        // Sync activeSoundIds: exact set of sounds with volume > 0f (symmetrical add and remove)
                        val activeFromVolume = volumes.filter { it.value > 0f }.keys.toSet()
                        _activeSoundIds.value = activeFromVolume

                        // Save playback state whenever it changes
                        savePlaybackState()
                    }

                    override fun onTimerTick(remainingMs: Long, totalMs: Long) {
                        _sleepTimerRemainingMs.value = remainingMs
                        _sleepTimerTotalMs.value = totalMs
                    }
                })

                // Reload custom sounds and then restore state
                loadCustomSounds {
                    restorePlaybackState()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "onServiceDisconnected")
            boundService?.setListener(null)
            boundService = null
            _isServiceConnected.value = false
            isStateRestored = false // Reset for next connection
        }
    }

    private fun savePlaybackState() {
        if (!isStateRestored) {
            Log.d(TAG, "savePlaybackState skipped - state not restored yet")
            return
        }
        val sharedPrefs = applicationContext.getSharedPreferences("blanket_prefs", Context.MODE_PRIVATE)
        val volumes = _currentVolumes.value
        val activeIds = _activeSoundIds.value
        val activeVolumesString = activeIds
            .map { id -> "$id:${volumes[id] ?: 0f}" }
            .joinToString(",")

        sharedPrefs.edit()
            .putString("last_active_volumes", activeVolumesString)
            .putBoolean("last_is_playing", _isPlaying.value)
            .apply()
        Log.d(TAG, "savePlaybackState successful: isPlaying=${_isPlaying.value}, volumes=$activeVolumesString")
    }

    private fun restorePlaybackState() {
        val service = boundService ?: return
        val sharedPrefs = applicationContext.getSharedPreferences("blanket_prefs", Context.MODE_PRIVATE)
        val savedActiveVolumes = sharedPrefs.getString("last_active_volumes", null)
        val savedIsPlaying = sharedPrefs.getBoolean("last_is_playing", false)

        val newActiveIds = mutableSetOf<String>()
        if (!savedActiveVolumes.isNullOrEmpty()) {
            savedActiveVolumes.split(",").forEach { pair ->
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val id = parts[0]
                    val vol = parts[1].toFloatOrNull() ?: 0f
                    newActiveIds.add(id)
                    lastVolumes[id] = vol

                    val sound = _soundItems.value.find { it.id == id }
                    if (sound != null) {
                        service.audioEngine.setVolume(
                            soundId = sound.id,
                            volume = vol,
                            isCustom = sound.isCustom,
                            filePath = sound.filePath,
                            rawResId = sound.rawResId
                        )
                    }
                }
            }
        }

        _activeSoundIds.value = newActiveIds
        isStateRestored = true // State has been successfully loaded into the engine and active lists
        service.updateVolumesAndActiveState(immediate = true)

        if (savedIsPlaying && newActiveIds.isNotEmpty()) {
            service.startPlayback()
        }
    }

    // Playback Controls
    fun togglePlayPause() {
        val service = boundService ?: return
        if (_isPlaying.value) {
            service.pausePlayback()
        } else {
            service.startPlayback()
        }
    }

    fun stopAll() {
        val service = boundService
        val fullZeroMap = _soundItems.value.associate { it.id to 0f }
        _currentVolumes.value = fullZeroMap
        _activeSoundIds.value = emptySet()
        _isPlaying.value = false

        if (service != null) {
            service.stopPlayback()
        }
    }

    private fun applyVolumeInternal(soundId: String, volume: Float, immediate: Boolean) {
        val service = boundService ?: return
        val sound = _soundItems.value.find { it.id == soundId } ?: return

        service.audioEngine.setVolume(
            soundId = sound.id,
            volume = volume,
            isCustom = sound.isCustom,
            filePath = sound.filePath,
            rawResId = sound.rawResId
        )

        val updatedVolumes = _currentVolumes.value.toMutableMap()
        updatedVolumes[soundId] = volume
        _currentVolumes.value = updatedVolumes

        val currentActive = _activeSoundIds.value.toMutableSet()
        if (volume > 0f && !currentActive.contains(soundId)) {
            currentActive.add(soundId)
            _activeSoundIds.value = currentActive
        } else if (volume == 0f && currentActive.contains(soundId)) {
            currentActive.remove(soundId)
            _activeSoundIds.value = currentActive
        }

        service.updateVolumesAndActiveState(immediate)

        if (volume > 0f) {
            lastVolumes[soundId] = volume
        }

        if (!_isPlaying.value && volume > 0f) {
            service.startPlayback()
        }
    }

    fun setVolume(soundId: String, volume: Float, immediate: Boolean = false) {
        if (immediate) {
            viewModelScope.launch(Dispatchers.Default) {
                applyVolumeInternal(soundId, volume, immediate = true)
            }
        } else {
            volumeIntentFlow.tryEmit(SoundVolumeIntent(soundId, volume, immediate = false))
        }
    }

    fun toggleSoundActive(soundId: String) {
        val service = boundService ?: return
        val currentActive = _activeSoundIds.value.toMutableSet()

        if (currentActive.contains(soundId)) {
            // Deactivate
            currentActive.remove(soundId)
            _activeSoundIds.value = currentActive

            // Stop in the engine
            service.audioEngine.setVolume(soundId, 0f)
            
            // Instantly update VM volume state too
            val updatedVolumes = _currentVolumes.value.toMutableMap()
            updatedVolumes[soundId] = 0f
            _currentVolumes.value = updatedVolumes

            service.updateVolumesAndActiveState(immediate = true)
        } else {
            // Activate
            currentActive.add(soundId)
            _activeSoundIds.value = currentActive

            // Restore last volume
            val restoreVolume = lastVolumes[soundId] ?: 0.5f
            val sound = _soundItems.value.find { it.id == soundId } ?: return
            
            service.audioEngine.setVolume(
                soundId = sound.id,
                volume = restoreVolume,
                isCustom = sound.isCustom,
                filePath = sound.filePath,
                rawResId = sound.rawResId
            )

            // Instantly update VM volume state too
            val updatedVolumes = _currentVolumes.value.toMutableMap()
            updatedVolumes[soundId] = restoreVolume
            _currentVolumes.value = updatedVolumes

            service.updateVolumesAndActiveState(immediate = true)

            // Auto-play master if paused
            if (!_isPlaying.value) {
                service.startPlayback()
            }
        }
    }

    fun setHighPrecisionMode(soundId: String, enabled: Boolean) {
        _highPrecisionSoundId.value = if (enabled) soundId else null
    }

    fun importSound(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val originalName = getFileNameFromUri(applicationContext, uri)
                val extension = originalName.substringAfterLast('.', "").lowercase()
                val validExtensions = setOf("ogg", "opus", "mp3", "m4a")
                if (extension !in validExtensions) {
                    _userMessage.value = "Desteklenmeyen dosya biçimi: .$extension (Yalnızca OGG, MP3, M4A, OPUS yüklenebilir)."
                    return@launch
                }

                // 1. File Size Check (20 MB limit)
                val fileSize = try {
                    applicationContext.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
                } catch (e: Exception) {
                    0L
                }
                if (fileSize > 20 * 1024 * 1024L) {
                    _userMessage.value = "Dosya boyutu çok büyük (Maksimum 20 MB yüklenebilir)."
                    return@launch
                }

                // 2. Audio Duration Check via MediaMetadataRetriever (10 Minutes limit)
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(applicationContext, uri)
                    val durationStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                    val durationMs = durationStr?.toLongOrNull() ?: 0L
                    if (durationMs > 10 * 60 * 1000L) {
                        _userMessage.value = "Ses süresi çok uzun (Maksimum 10 dakika yüklenebilir)."
                        return@launch
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read audio duration metadata", e)
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }

                val customSoundsDir = File(applicationContext.filesDir, "custom_sounds")
                if (!customSoundsDir.exists()) {
                    customSoundsDir.mkdirs()
                }

                val cleanBase = originalName.substringBeforeLast('.').replace(Regex("[^a-zA-Z0-9]"), "_")
                val cleanName = "$cleanBase.$extension"
                val destFile = File(customSoundsDir, cleanName)
                val newSoundId = "custom_${cleanBase.lowercase()}"

                val pending = PendingImport(uri, destFile, cleanName, newSoundId)

                if (destFile.exists()) {
                    // Trigger Option B confirmation dialog
                    _pendingImport.value = pending
                } else {
                    executeImport(pending)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import sound", e)
                _userMessage.value = "Ses içe aktarılırken bir hata oluştu."
            }
        }
    }

    fun confirmOverwriteImport() {
        val pending = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch(Dispatchers.IO) {
            executeImport(pending)
        }
    }

    fun cancelImport() {
        _pendingImport.value = null
    }

    private fun executeImport(pending: PendingImport) {
        try {
            applicationContext.contentResolver.openInputStream(pending.uri)?.use { inputStream ->
                pending.destFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            lastVolumes[pending.newSoundId] = 0.5f
            loadCustomSounds()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute import for ${pending.cleanName}", e)
            _userMessage.value = "Dosya kaydedilirken hata oluştu."
        }
    }

    fun deleteCustomSound(soundItem: SoundItem) {
        // 1. Deactivate sound if currently active
        if (_activeSoundIds.value.contains(soundItem.id)) {
            toggleSoundActive(soundItem.id)
        }
        
        // Remove from memory volumes and cache
        lastVolumes.remove(soundItem.id)

        // 2. Delete physical file
        viewModelScope.launch(Dispatchers.IO) {
            try {
                soundItem.filePath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        file.delete()
                    }
                }
                // Reload list to refresh soundboard state
                loadCustomSounds()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete custom sound", e)
            }
        }
    }


    private fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name = ""
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1) {
                    name = it.getString(nameIndex)
                }
            }
        }
        if (name.isEmpty()) {
            name = uri.path?.substringAfterLast('/') ?: "custom_sound_${System.currentTimeMillis()}.mp3"
        }
        return name
    }

    // Sleep Timer Controls
    fun startSleepTimer(minutes: Int) {
        val service = boundService ?: return
        val durationMs = minutes * 60 * 1000L
        service.startSleepTimer(durationMs)
    }

    fun cancelSleepTimer() {
        val service = boundService ?: return
        service.cancelSleepTimer()
    }

    // Preset Controls
    fun savePreset(name: String) {
        viewModelScope.launch {
            val volumes = _currentVolumes.value
            val activeVolumesString = _activeSoundIds.value
                .map { id -> "$id:${volumes[id] ?: 0f}" }
                .joinToString(",")

            if (activeVolumesString.isNotEmpty() && name.isNotBlank()) {
                val newPreset = Preset(name = name, soundVolumes = activeVolumesString)
                repository.insert(newPreset)
            }
        }
    }

    fun loadPreset(preset: Preset) {
        val service = boundService ?: return

        // 1. Reset all sound volumes in the engine and VM local state
        val updatedVolumes = _soundItems.value.associate { it.id to 0f }.toMutableMap()
        _soundItems.value.forEach { service.audioEngine.setVolume(it.id, 0f) }
        val newActiveIds = mutableSetOf<String>()

        // 2. Parse and set new volumes from the preset
        if (preset.soundVolumes.isNotEmpty()) {
            preset.soundVolumes.split(",").forEach { pair ->
                val parts = pair.split(":")
                if (parts.size == 2) {
                    val id = parts[0]
                    val vol = parts[1].toFloatOrNull() ?: 0f
                    if (vol > 0f) {
                        newActiveIds.add(id)
                        lastVolumes[id] = vol
                        updatedVolumes[id] = vol

                        val sound = _soundItems.value.find { it.id == id }
                        if (sound != null) {
                            service.audioEngine.setVolume(
                                soundId = sound.id,
                                volume = vol,
                                isCustom = sound.isCustom,
                                filePath = sound.filePath,
                                rawResId = sound.rawResId
                            )
                        }
                    }
                }
              }
          }

        _currentVolumes.value = updatedVolumes
        _activeSoundIds.value = newActiveIds
        service.updateVolumesAndActiveState(immediate = true)
        service.startPlayback()
    }

    fun deletePreset(preset: Preset) {
        viewModelScope.launch {
            repository.delete(preset)
        }
    }

    override fun onCleared() {
        super.onCleared()
        boundService?.setListener(null)
    }
}

class BlanketViewModelFactory(
    private val applicationContext: Context,
    private val repository: PresetRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BlanketViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return BlanketViewModel(applicationContext, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
