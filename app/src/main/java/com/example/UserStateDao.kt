package com.example

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserStateDao {
    @Query("SELECT * FROM user_state WHERE id = :id LIMIT 1")
    fun getUserStateFlow(id: String = "default_user"): Flow<UserState?>

    @Query("SELECT * FROM user_state WHERE id = :id LIMIT 1")
    suspend fun getUserStateDirect(id: String = "default_user"): UserState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserState(userState: UserState)
}
