package com.example

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "refill_logs")
data class RefillLog(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val stationName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val fuelAmount: Double,
    val latitude: Double,
    val longitude: Double
)
