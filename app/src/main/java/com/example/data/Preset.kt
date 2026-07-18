package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "presets")
data class Preset(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val soundVolumes: String // Format: "rain:0.5,fireplace:0.8,birds:0.2"
)
