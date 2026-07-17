package com.reedsutliff.talaria.model

import java.util.UUID

enum class MessageModality { VOICE, TEXT }

enum class MessageStatus { QUEUED, SENDING, SENT, REFINING, DELIVERED, ERROR }

enum class MessageFeedback { NONE, THUMBS_UP, THUMBS_DOWN }

enum class TranscriptionState { NONE, ON_DEVICE, BACKEND_FINAL }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val threadId: String,
    val text: String,
    val isUser: Boolean,
    val modality: MessageModality,
    val status: MessageStatus = MessageStatus.SENT,
    val timestamp: Long = System.currentTimeMillis(),
    val interrupted: Boolean = false,
    val runId: String? = null,
    val feedback: MessageFeedback = MessageFeedback.NONE,
    val transcriptionState: TranscriptionState = TranscriptionState.NONE,
)
