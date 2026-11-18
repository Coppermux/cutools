package com.coppermux.cutools.data.local

import androidx.room.TypeConverter
import com.coppermux.cutools.data.model.ConnectionStatus
import com.coppermux.cutools.data.model.WorkspaceCategory

class ConnectionStatusConverter {

    @TypeConverter
    fun fromConnectionStatus(status: ConnectionStatus): String = status.name

    @TypeConverter
    fun toConnectionStatus(value: String): ConnectionStatus = ConnectionStatus.valueOf(value)
}

class WorkspaceCategoryConverter {

    @TypeConverter
    fun fromCategory(category: WorkspaceCategory): String = category.name

    @TypeConverter
    fun toCategory(value: String): WorkspaceCategory = try {
        WorkspaceCategory.valueOf(value)
    } catch (_: Exception) {
        WorkspaceCategory.HOME
    }
}