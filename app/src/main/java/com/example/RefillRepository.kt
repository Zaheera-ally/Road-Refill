package com.example

import kotlinx.coroutines.flow.Flow

class RefillRepository(private val refillLogDao: RefillLogDao) {
    val allLogs: Flow<List<RefillLog>> = refillLogDao.getAllLogs()

    suspend fun insert(log: RefillLog) {
        refillLogDao.insertLog(log)
    }

    suspend fun clearAll() {
        refillLogDao.deleteAllLogs()
    }
}
