package com.networkmapper.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        Site::class, Asset::class, NetPath::class, Photo::class, SurveySession::class, WifiReading::class,
        ApName::class, SyncTombstone::class,
    ],
    version = 5,
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
    abstract fun syncDao(): SyncDao

    companion object {
        const val DEFAULT_SITE_NAME = "My site"

        private fun insertDefaultSite(db: SupportSQLiteDatabase) {
            val columns = HashSet<String>()
            db.query("PRAGMA table_info(`sites`)").use { c ->
                while (c.moveToNext()) columns += c.getString(c.getColumnIndexOrThrow("name"))
            }
            val now = System.currentTimeMillis()
            if ("uid" in columns) {
                // Fresh v4+ database: every column must be given.
                db.execSQL(
                    "INSERT OR IGNORE INTO sites (id, name, notes, createdAt, updatedAt, uid, dirty) VALUES (?, ?, '', ?, ?, ?, 1)",
                    arrayOf<Any>(DEFAULT_SITE_ID, DEFAULT_SITE_NAME, now, now, newUid()),
                )
            } else {
                // Migrating an older schema; later migrations add the remaining columns.
                db.execSQL(
                    "INSERT OR IGNORE INTO sites (id, name, notes, createdAt) VALUES (?, ?, '', ?)",
                    arrayOf<Any>(DEFAULT_SITE_ID, DEFAULT_SITE_NAME, now),
                )
            }
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

        /**
         * v3 -> v4: cloud sync. Every row gets a permanent shared id (uid) and a "not yet pushed" flag;
         * sites, photos and surveys get an updatedAt; local deletes are remembered in sync_tombstones.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val now = System.currentTimeMillis()
                for (table in listOf("sites", "assets", "paths", "photos", "survey_sessions", "wifi_readings")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `uid` TEXT NOT NULL DEFAULT ''")
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
                    // Random 128-bit ids, one per existing row.
                    db.execSQL("UPDATE `$table` SET `uid` = lower(hex(randomblob(16))) WHERE `uid` = ''")
                    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_${table}_uid` ON `$table` (`uid`)")
                }
                for (table in listOf("sites", "photos", "survey_sessions")) {
                    db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT $now")
                }
                db.execSQL("ALTER TABLE `ap_names` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_tombstones` (`collection` TEXT NOT NULL, `uid` TEXT NOT NULL, " +
                        "`siteUid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL, PRIMARY KEY(`collection`, `uid`))",
                )
            }
        }

        /** v4 -> v5: devices can share a location pin (closet / rack). */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `assets` ADD COLUMN `groupUid` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val createCallback = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertDefaultSite(db)
            }
        }

        /** Shared configuration, also used by tests with an in-memory builder. */
        fun configure(builder: RoomDatabase.Builder<AppDatabase>): RoomDatabase.Builder<AppDatabase> =
            builder.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).addCallback(createCallback)

        fun build(context: Context): AppDatabase =
            configure(Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "network_mapper.db"))
                .build()
    }
}
