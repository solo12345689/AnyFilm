package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MovieDao {
    @Query("SELECT * FROM favorites ORDER BY addedAt DESC")
    fun getAllFavorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE id = :id")
    suspend fun deleteFavoriteById(id: String)

    @Query("DELETE FROM favorites")
    suspend fun deleteAllFavorites()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorites(favorites: List<FavoriteEntity>)

    @androidx.room.Transaction
    suspend fun syncFavorites(favorites: List<FavoriteEntity>) {
        deleteAllFavorites()
        insertFavorites(favorites)
    }

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id LIMIT 1)")
    fun isFavorite(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE id = :id LIMIT 1)")
    suspend fun isFavoriteDirect(id: String): Boolean

    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM watch_history ORDER BY timestamp DESC")
    suspend fun getAllHistoryDirect(): List<HistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: HistoryEntity)

    @Query("DELETE FROM watch_history WHERE id = :id")
    suspend fun deleteHistoryById(id: String)

    @Query("DELETE FROM watch_history WHERE subjectId = :subjectId OR subjectId = :altSubjectId OR subjectId = :cleanSubjectId")
    suspend fun deleteHistoryBySubject(subjectId: String, altSubjectId: String, cleanSubjectId: String)

    @Query("SELECT * FROM watch_history WHERE id = :id LIMIT 1")
    suspend fun getHistoryById(id: String): HistoryEntity?
}
