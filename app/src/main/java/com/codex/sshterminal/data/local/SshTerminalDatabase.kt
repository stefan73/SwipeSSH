package com.codex.sshterminal.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SavedConnectionEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class SshTerminalDatabase : RoomDatabase() {
    abstract fun savedConnectionDao(): SavedConnectionDao

    companion object {
        @Volatile
        private var instance: SshTerminalDatabase? = null

        fun getInstance(context: Context): SshTerminalDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SshTerminalDatabase::class.java,
                    "ssh-terminal.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { database ->
                        instance = database
                    }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE saved_connections ADD COLUMN authType TEXT NOT NULL DEFAULT 'PASSWORD'",
                )
            }
        }
    }
}
