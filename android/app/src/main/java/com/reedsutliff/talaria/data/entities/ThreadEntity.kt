package com.reedsutliff.talaria.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "threads")
data class ThreadEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String? = null,

    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String? = null,

    @ColumnInfo(name = "last_message_timestamp")
    val lastMessageTimestamp: Long,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "is_archived")
    val isArchived: Boolean = false,
)
