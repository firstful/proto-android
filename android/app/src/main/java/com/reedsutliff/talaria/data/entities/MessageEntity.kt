package com.reedsutliff.talaria.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["id"],
            childColumns = ["thread_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["thread_id"])],
)
data class MessageEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "thread_id")
    val threadId: String,

    @ColumnInfo(name = "text")
    val text: String,

    @ColumnInfo(name = "is_user")
    val isUser: Boolean,

    @ColumnInfo(name = "modality")
    val modality: String,

    @ColumnInfo(name = "status")
    val status: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "interrupted")
    val interrupted: Boolean = false,

    @ColumnInfo(name = "run_id")
    val runId: String? = null,
)
