package com.coppermux.cutools.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.coppermux.cutools.data.model.WorkspaceSession

@Database(entities = [WorkspaceSession::class], version = 2, exportSchema = true)
@TypeConverters(ConnectionStatusConverter::class, WorkspaceCategoryConverter::class)
abstract class CuToolsDatabase : RoomDatabase() {

    abstract fun workspaceSessionDao(): WorkspaceSessionDao

    companion object {
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workspace_sessions ADD COLUMN category TEXT NOT NULL DEFAULT 'HOME'")
            }
        }
    }
}