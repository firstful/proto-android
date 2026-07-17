package com.reedsutliff.talaria.data.repository

import com.reedsutliff.talaria.data.dao.MessageDao
import com.reedsutliff.talaria.data.dao.ThreadDao
import com.reedsutliff.talaria.data.entities.MessageEntity
import com.reedsutliff.talaria.data.entities.ThreadEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ThreadRepository(
    private val threadDao: ThreadDao,
    private val messageDao: MessageDao,
) {

    val allThreads: Flow<List<ThreadEntity>> = threadDao.getAll()

    suspend fun createThread(name: String): ThreadEntity {
        val now = System.currentTimeMillis()
        val thread = ThreadEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            lastMessageTimestamp = now,
            createdAt = now,
        )
        threadDao.insert(thread)
        return thread
    }

    suspend fun deleteThread(id: String) {
        val thread = threadDao.getById(id) ?: return
        messageDao.deleteByThreadId(id)
        threadDao.delete(thread)
    }

    suspend fun switchThread(id: String): ThreadEntity? {
        return threadDao.getById(id)
    }

    fun getMessages(threadId: String): Flow<List<MessageEntity>> {
        return messageDao.getByThreadId(threadId)
    }

    /** One-shot messages query — no reactive Flow. */
    suspend fun getMessagesOnce(threadId: String): List<MessageEntity> {
        return messageDao.getByThreadIdOnce(threadId)
    }

    suspend fun insertMessage(message: MessageEntity) {
        messageDao.insert(message)
    }

    suspend fun updateMessageStatus(runId: String, newStatus: String) {
        messageDao.updateStatus(runId, newStatus)
    }
}
