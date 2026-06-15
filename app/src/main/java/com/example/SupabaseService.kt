package com.example

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class SupabaseUserStateDto(
    @Json(name = "id") val id: String = "default_user",
    @Json(name = "current_fuel") val currentFuel: Double,
    @Json(name = "total_distance_all_time") val totalDistanceAllTime: Double,
    @Json(name = "total_refills_count") val totalRefillsCount: Int,
    @Json(name = "current_latitude") val currentLatitude: Double,
    @Json(name = "current_longitude") val currentLongitude: Double,
    @Json(name = "last_updated") val lastUpdated: Long
)

interface SupabaseApi {
    @GET("rest/v1/user_state")
    suspend fun getUserState(
        @Query("id") idFilter: String = "eq.default_user",
        @Header("apikey") apiKey: String,
        @Header("Authorization") authHeader: String
    ): List<SupabaseUserStateDto>

    @POST("rest/v1/user_state")
    suspend fun insertUserState(
        @Header("apikey") apiKey: String,
        @Header("Authorization") authHeader: String,
        @Body state: SupabaseUserStateDto
    )

    @PATCH("rest/v1/user_state")
    suspend fun updateUserState(
        @Query("id") idFilter: String = "eq.default_user",
        @Header("apikey") apiKey: String,
        @Header("Authorization") authHeader: String,
        @Body state: Any
    )
}

object SupabaseService {
    private const val TAG = "SupabaseService"

    // Retrieve credential values dynamically from compilation BuildConfig
    private val supabaseUrl: String by lazy {
        try {
            BuildConfig.SUPABASE_URL
        } catch (e: Exception) {
            ""
        }
    }

    private val supabaseAnonKey: String by lazy {
        try {
            BuildConfig.SUPABASE_ANON_KEY
        } catch (e: Exception) {
            ""
        }
    }

    val isConfigured: Boolean by lazy {
        supabaseUrl.isNotEmpty() &&
        supabaseUrl != "MY_SUPABASE_URL_PLACEHOLDER" &&
        supabaseAnonKey.isNotEmpty() &&
        supabaseAnonKey != "MY_SUPABASE_ANON_KEY_PLACEHOLDER"
    }

    private val api: SupabaseApi? by lazy {
        if (!isConfigured) {
            Log.w(TAG, "Supabase credentials are NOT configured yet. Falling back to local database persistence.")
            null
        } else {
            try {
                val formattedUrl = if (supabaseUrl.endsWith("/")) supabaseUrl else "$supabaseUrl/"
                
                val logger = HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .addInterceptor(logger)
                    .build()

                val moshi = Moshi.Builder()
                    .addLast(KotlinJsonAdapterFactory())
                    .build()

                Retrofit.Builder()
                    .baseUrl(formattedUrl)
                    .client(client)
                    .addConverterFactory(MoshiConverterFactory.create(moshi))
                    .build()
                    .create(SupabaseApi::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Retrofit client for Supabase: ${e.message}", e)
                null
            }
        }
    }

    suspend fun loadRemoteState(): SupabaseUserStateDto? {
        val currentApi = api ?: return null
        return try {
            val results = currentApi.getUserState(
                idFilter = "eq.default_user",
                apiKey = supabaseAnonKey,
                authHeader = "Bearer $supabaseAnonKey"
            )
            results.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed calling remote loadUserState on Supabase REST endpoint: ${e.message}")
            null
        }
    }

    suspend fun saveRemoteState(state: SupabaseUserStateDto) {
        val currentApi = api ?: return
        try {
            // First check if user state exists in Supabase
            val results = currentApi.getUserState(
                idFilter = "eq.default_user",
                apiKey = supabaseAnonKey,
                authHeader = "Bearer $supabaseAnonKey"
            )
            
            if (results.isEmpty()) {
                // Not inserted on Supabase database table yet, insert brand new
                currentApi.insertUserState(
                    apiKey = supabaseAnonKey,
                    authHeader = "Bearer $supabaseAnonKey",
                    state = state
                )
                Log.d(TAG, "Successfully inserted brand new UserState into Supabase: $state")
            } else {
                // Exists, perform a dynamic merge patch update
                // We map payload directly to raw structures
                val updateMap = mapOf(
                    "current_fuel" to state.currentFuel,
                    "total_distance_all_time" to state.totalDistanceAllTime,
                    "total_refills_count" to state.totalRefillsCount,
                    "current_latitude" to state.currentLatitude,
                    "current_longitude" to state.currentLongitude,
                    "last_updated" to state.lastUpdated
                )
                currentApi.updateUserState(
                    idFilter = "eq.default_user",
                    apiKey = supabaseAnonKey,
                    authHeader = "Bearer $supabaseAnonKey",
                    state = updateMap
                )
                Log.d(TAG, "Successfully updated existing UserState on Supabase REST endpoint: $state")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist remote UserState coordinates synchronization on Supabase: ${e.message}")
        }
    }
}
