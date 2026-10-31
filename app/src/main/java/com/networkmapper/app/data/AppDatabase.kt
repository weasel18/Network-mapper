package com.networkmapper.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Asset::class, NetPath::class, Photo::class, SurveySession::class, WifiReading::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun assetDao(): AssetDao
    abstract fun pathDao(): PathDao
    abstract fun photoDao(): PhotoDao
    abstract fun wifiDao(): WifiDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "network_mapper.db")
                .build()
    }
}
