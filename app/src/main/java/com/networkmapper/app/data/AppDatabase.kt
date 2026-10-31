package com.networkmapper.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Site::class, Asset::class, NetPath::class, Photo::class, SurveySession::class, WifiReading::class, ApName::class],
    version = 3,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteDao(): SiteDao
    abstract fun assetDao(): AssetDao
    abstract fun pathDao(): PathDao
    abstract fun photoDao(): PhotoDao
    abstract fun wifiDao(): WifiDao
    abstract fun apNameDao(): ApNameDao

    companion object {
        const val DEFAULT_SITE_NAME = "My site"

        private fun insertDefaultSite(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO sites (id, name, notes, createdAt) VALUES (?, ?, '', ?)",
                arrayOf<Any>(DEFAULT_SITE_ID, DEFAULT_SITE_NAME, System.currentTimeMillis()),
            )
        }

        /** v1 -> v2: adds sites; all existing pins, paths and surveys move into the default site. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sites` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)",
                )
                insertDefaultSite(db)
                for (table in listOf("assets", "paths", "survey_sessions")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `siteId` INTEGER NOT NULL DEFAULT 1")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_${table}_siteId` ON `$table` (`siteId`)")
                }
            }
        }

        /** v2 -> v3: your own names for access points, keyed by BSSID. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ap_names` (`bssid` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`bssid`))",
                )
            }
        }

        private val createCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertDefaultSite(db)
            }
        }

        /** Shared configuration, also used by tests with an in-memory builder. */
        fun configure(builder: RoomDatabase.Builder<AppDatabase>): RoomDatabase.Builder<AppDatabase> =
            builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3).addCallback(createCallback)

        fun build(context: Context): AppDatabase =
            configure(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "network_mapper.db"))
                .build()
    }
}
