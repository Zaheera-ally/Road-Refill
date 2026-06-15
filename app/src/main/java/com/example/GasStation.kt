package com.example

data class GasStation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val address: String,
    val brand: String = "Shell" // Shell, BP, Engen, Sasol, Total
)
