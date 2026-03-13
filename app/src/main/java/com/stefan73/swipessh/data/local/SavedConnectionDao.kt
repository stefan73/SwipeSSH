package com.stefan73.swipessh.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedConnectionDao {
    @Query("SELECT * FROM saved_connections ORDER BY lastConnectedAt DESC, updatedAt DESC")
    fun observeSavedConnections(): Flow<List<SavedConnectionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connection: SavedConnectionEntity): Long

    @Update
    suspend fun update(connection: SavedConnectionEntity)

    @Query("DELETE FROM saved_connections WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM saved_connections WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): SavedConnectionEntity?
}

