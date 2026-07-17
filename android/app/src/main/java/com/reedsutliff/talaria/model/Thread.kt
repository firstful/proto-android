package com.reedsutliff.talaria.model

import java.util.UUID

data class Thread(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val sessionId: String? = null,
    val lastMessagePreview: String? = null,
    val lastMessageTimestamp: Long = System.currentTimeMillis(),
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false
)
