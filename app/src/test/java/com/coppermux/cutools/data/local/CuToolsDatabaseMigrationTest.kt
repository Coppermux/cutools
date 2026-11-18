package com.coppermux.cutools.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import com.coppermux.cutools.data.model.WorkspaceCategory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class CuToolsDatabaseMigrationTest {

    @Test
    fun migration1To2_executesExpectedAlterTableSql() {
        val db: SupportSQLiteDatabase = mock()
        CuToolsDatabase.MIGRATION_1_2.migrate(db)

        verify(db).execSQL(
            "ALTER TABLE workspace_sessions ADD COLUMN category TEXT NOT NULL DEFAULT 'HOME'"
        )
    }

    @Test
    fun workspaceCategoryConverter_serializesAndDeserializesCorrectly() {
        val converter = WorkspaceCategoryConverter()

        assertEquals("WORK", converter.fromCategory(WorkspaceCategory.WORK))
        assertEquals("HOME", converter.fromCategory(WorkspaceCategory.HOME))

        assertEquals(WorkspaceCategory.WORK, converter.toCategory("WORK"))
        assertEquals(WorkspaceCategory.HOME, converter.toCategory("HOME"))
    }

    @Test
    fun workspaceCategoryConverter_fallsBackToHomeForUnknownOrMalformedValues() {
        val converter = WorkspaceCategoryConverter()

        assertEquals(WorkspaceCategory.HOME, converter.toCategory("INVALID"))
        assertEquals(WorkspaceCategory.HOME, converter.toCategory(""))
        assertEquals(WorkspaceCategory.HOME, converter.toCategory("work"))
    }
}
