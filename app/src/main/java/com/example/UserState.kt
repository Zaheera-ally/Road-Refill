package com.example

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_state")
data class UserState(
    @PrimaryKey val id: String = "default_user",
    val currentFuel: Double,
    val totalDistanceAllTime: Double,
    val totalRefillsCount: Int,
    val currentLatitude: Double,
    val currentLongitude: Double,
    val lastUpdated: Long = System.currentTimeMillis()
)
