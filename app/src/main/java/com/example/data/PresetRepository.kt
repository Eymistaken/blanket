package com.example.data

import kotlinx.coroutines.flow.Flow

class PresetRepository(private val presetDao: PresetDao) {
    val allPresets: Flow<List<Preset>> = presetDao.getAllPresets()

    suspend fun insert(preset: Preset) {
        presetDao.insertPreset(preset)
    }

    suspend fun delete(preset: Preset) {
        presetDao.deletePreset(preset)
    }
}
