package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val id: String,
    val name: String,
    val poster: String,
    val isTvShow: Boolean,
    val rating: String? = null,
    val year: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "watch_history")
data class HistoryEntity(
    @PrimaryKey val id: String, // format: subjectId or subjectId_S{season}_E{episode}
    val subjectId: String,
    val name: String,
    val poster: String,
    val isTvShow: Boolean,
    val season: Int,
    val episode: Int,
    val seeTime: Long,      // Playback progress position in ms
    val totalTime: Long,    // Total media duration in ms
    val timestamp: Long = System.currentTimeMillis()
)
