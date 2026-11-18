package com.coppermux.cutools.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.coppermux.cutools.data.local.ConnectionStatusConverter
import com.coppermux.cutools.data.local.WorkspaceCategoryConverter
import java.util.UUID

@Entity(tableName = "workspace_sessions")
data class WorkspaceSession(
    @PrimaryKey var id: String = UUID.randomUUID().toString(),
    var targetUrl: String = "",
    var domain: String = "",
    var displayName: String = "",
    var isIncognito: Boolean = false,
    var isDesktopMode: Boolean = false,
    var isPinned: Boolean = false,
    var lastActiveAt: Long = 0L,
    var createdAt: Long = 0L,
    @TypeConverters(ConnectionStatusConverter::class)
    var status: ConnectionStatus = ConnectionStatus.UNKNOWN,
    @ColumnInfo(defaultValue = "'HOME'")
    @TypeConverters(WorkspaceCategoryConverter::class)
    var category: WorkspaceCategory = WorkspaceCategory.HOME
)