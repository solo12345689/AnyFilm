package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.HistoryEntity
import com.example.data.local.MovieDatabase
import com.example.data.model.Episode
import com.example.data.model.MovieSubject
import com.example.data.model.PlayInfo
import com.example.data.model.StreamItem
import com.example.data.model.SubtitleItem
import com.example.data.network.MovieBoxRetrofitClient
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.MovieRepository
import kotlinx.coroutines.launch

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val database = MovieDatabase.getDatabase(application)
    private val repository = MovieRepository(
        MovieBoxRetrofitClient.getApi(prefs),
        database.movieDao()
    )

    var currentEpisode by mutableStateOf<Episode?>(null)
    var playInfo by mutableStateOf<PlayInfo?>(null)
    var selectedStream by mutableStateOf<StreamItem?>(null)
    var selectedSubtitle by mutableStateOf<SubtitleItem?>(null)
    var isPlaying by mutableStateOf(false)
    var playbackProgressMs by mutableStateOf(0L)
    var isPlaybackLoading by mutableStateOf(false)
    var mediaDurationMs by mutableStateOf(0L)

    fun updatePlaybackProgress(subject: MovieSubject, episode: Episode?, positionMs: Long, durationMs: Long) {
        playbackProgressMs = positionMs
        mediaDurationMs = durationMs
        val historyId = if (subject.isTvShow) {
            val ep = episode ?: return
            "${subject.id}_S${ep.seasonNumber}_E${ep.episodeNumber}"
        } else {
            subject.id
        }

        viewModelScope.launch {
            try {
                repository.insertHistory(
                    HistoryEntity(
                        id = historyId,
                        subjectId = subject.id,
                        name = subject.name,
                        poster = subject.poster,
                        isTvShow = subject.isTvShow,
                        season = if (subject.isTvShow) episode?.seasonNumber ?: 1 else 0,
                        episode = if (subject.isTvShow) episode?.episodeNumber ?: 1 else 0,
                        seeTime = positionMs,
                        totalTime = durationMs
                    )
                )
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }
}
