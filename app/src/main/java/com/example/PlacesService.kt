package com.example

import android.util.Log
import com.squareup.moshi.JsonClass
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

@JsonClass(generateAdapter = true)
data class PlacesResponse(
    val results: List<PlaceResult>?,
    val status: String?
)

@JsonClass(generateAdapter = true)
data class PlaceResult(
    val name: String,
    val formatted_address: String?,
    val geometry: PlaceGeometry?
)

@JsonClass(generateAdapter = true)
data class PlaceGeometry(
    val location: PlaceLatLng?
)

@JsonClass(generateAdapter = true)
data class PlaceLatLng(
    val lat: Double,
    val lng: Double
)

interface PlacesApi {
    @GET("maps/api/place/textsearch/json")
    suspend fun textSearch(
        @Query("query") query: String,
        @Query("location") location: String? = null,
        @Query("radius") radius: Int? = null,
        @Query("key") apiKey: String
    ): PlacesResponse
}

object PlacesClient {
    private const val BASE_URL = "https://maps.googleapis.com/"

    private val httpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    val retrofitService: PlacesApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(httpClient)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(PlacesApi::class.java)
    }
}

class PlacesRepository {

    // Prestigious presets in Durban, South Africa
    val durbanPresets = listOf(
        GasStation("Shell Umhlanga Rocks", -29.7258, 31.0858, "Umhlanga Rocks, Durban", "Shell"),
        GasStation("BP Crest Umhlanga", -29.7310, 31.0800, "Ridge Road, Umhlanga, Durban", "BP"),
        GasStation("Engen Marine Drive", -29.7500, 31.0650, "Marine Drive, Durban North", "Engen"),
        GasStation("Caltex Lagoon Drive", -29.7150, 31.0900, "Lagoon Drive, Umhlanga, Durban", "Caltex"),
        GasStation("Total Durban North", -29.7800, 31.0400, "Broadway, Durban North", "Total"),
        GasStation("Sasol Point Waterfront", -29.8650, 31.0480, "Point Waterfront, Durban", "Sasol"),
        GasStation("Shell Durban Beachfront", -29.8520, 31.0370, "O.R. Tambo Parade, Durban", "Shell")
    )

    suspend fun searchGasStations(
        query: String,
        currentLat: Double,
        currentLng: Double
    ): List<GasStation> {
        val apiKey = BuildConfig.PLACES_API_KEY

        // Check if API key is blank or equal to standard placeholder
        if (apiKey.isBlank() || apiKey == "MY_PLACES_API_KEY") {
            Log.d("PlacesRepository", "Places API Key is missing or default. Falling back to local data.")
            return filterLocalPresets(query)
        }

        return try {
            val response = PlacesClient.retrofitService.textSearch(
                query = if (query.isBlank()) "gas station" else query,
                location = "$currentLat,$currentLng",
                radius = 10000,
                apiKey = apiKey
            )

            if (response.status == "OK" && !response.results.isNullOrEmpty()) {
                response.results.map { result ->
                    val brand = determineBrand(result.name)
                    GasStation(
                        name = result.name,
                        latitude = result.geometry?.location?.lat ?: currentLat,
                        longitude = result.geometry?.location?.lng ?: currentLng,
                        address = result.formatted_address ?: "Address unavailable",
                        brand = brand
                    )
                }
            } else {
                Log.d("PlacesRepository", "Api returned status: ${response.status}. Using presets.")
                filterLocalPresets(query)
            }
        } catch (e: Exception) {
            Log.e("PlacesRepository", "Error searching places: ${e.message}", e)
            filterLocalPresets(query)
        }
    }

    private fun filterLocalPresets(query: String): List<GasStation> {
        if (query.isBlank()) return durbanPresets
        return durbanPresets.filter {
            it.name.contains(query, ignoreCase = true) || 
            it.address.contains(query, ignoreCase = true) ||
            it.brand.contains(query, ignoreCase = true)
        }
    }

    private fun determineBrand(name: String): String {
        val uppercaseName = name.uppercase()
        return when {
            uppercaseName.contains("SHELL") -> "Shell"
            uppercaseName.contains("BP") -> "BP"
            uppercaseName.contains("ENGEN") -> "Engen"
            uppercaseName.contains("CALTEX") -> "Caltex"
            uppercaseName.contains("TOTAL") -> "Total"
            uppercaseName.contains("SASOL") -> "Sasol"
            else -> "Shell"
        }
    }
}
