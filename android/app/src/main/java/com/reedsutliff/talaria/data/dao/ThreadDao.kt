package com.reedsutliff.talaria.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.reedsutliff.talaria.data.entities.ThreadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(thread: ThreadEntity)

    @Update
    suspend fun update(thread: ThreadEntity)

    @Delete
    suspend fun delete(thread: ThreadEntity)

    @Query("SELECT * FROM threads ORDER BY last_message_timestamp DESC")
    fun getAll(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE id = :id")
    suspend fun getById(id: String): ThreadEntity?
}
