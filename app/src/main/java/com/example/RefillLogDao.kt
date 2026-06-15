package com.example

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface RefillLogDao {
    @Query("SELECT * FROM refill_logs ORDER BY timestamp DESC")
    fun getAllLogs(): Flow<List<RefillLog>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: RefillLog)

    @Query("DELETE FROM refill_logs")
    suspend fun deleteAllLogs()
}
