package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Preset::class], version = 1, exportSchema = false)
abstract class BlanketDatabase : RoomDatabase() {
    abstract fun presetDao(): PresetDao

    companion object {
        @Volatile
        private var INSTANCE: BlanketDatabase? = null

        fun getDatabase(context: Context): BlanketDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BlanketDatabase::class.java,
                    "blanket_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
