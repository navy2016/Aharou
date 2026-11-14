package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aharou.feature.agent.data.local.entity.SessionExecutionEntity

@Dao
interface SessionExecutionDao {
    @Query("SELECT * FROM session_execution_bindings WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): SessionExecutionEntity?

    @Query("SELECT * FROM session_execution_bindings WHERE sessionId = :sessionId")
    fun observe(sessionId: String): kotlinx.coroutines.flow.Flow<SessionExecutionEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SessionExecutionEntity): Long

    @androidx.room.Update
    suspend fun update(entity: SessionExecutionEntity): Int

    @Query("UPDATE session_execution_bindings SET state = :state WHERE sessionId = :sessionId AND executionId = :executionId")
    suspend fun updateState(sessionId: String, executionId: String, state: String): Int

    @Query("SELECT * FROM session_execution_bindings WHERE executionId = :id AND state = 'ready' LIMIT 1")
    suspend fun getExecution(id: String): SessionExecutionEntity?

    @Query("UPDATE session_execution_bindings SET writePaths = :paths WHERE sessionId = :sessionId")
    suspend fun updateWritePaths(sessionId: String, paths: String): Int

    @Query("DELETE FROM session_execution_bindings WHERE sessionId = :sessionId")
    suspend fun delete(sessionId: String)
}
