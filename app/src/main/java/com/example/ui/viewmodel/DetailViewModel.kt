package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.FavoriteEntity
import com.example.data.local.MovieDatabase
import com.example.data.model.Episode
import com.example.data.model.MovieSubject
import com.example.data.model.ResourceDetector
import com.example.data.network.MovieBoxRetrofitClient
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.MovieRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class DetailViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val database = MovieDatabase.getDatabase(application)
    private val repository = MovieRepository(
        MovieBoxRetrofitClient.getApi(prefs),
        database.movieDao()
    )

    var selectedSubject by mutableStateOf<MovieSubject?>(null)
    var originalSubjectDetail by mutableStateOf<MovieSubject?>(null)
    var maxSeasonLimitConstraint by mutableStateOf<Int?>(null)
    var selectedResource by mutableStateOf<ResourceDetector?>(null)
    var seasonsList by mutableStateOf<List<Int>>(emptyList())
    var selectedSeason by mutableStateOf(1)
    var episodesList by mutableStateOf<List<Episode>>(emptyList())
    var detailRecommendations by mutableStateOf<List<MovieSubject>>(emptyList())

    fun selectSubject(subject: MovieSubject) {
        selectedSubject = subject
        originalSubjectDetail = subject
        selectedResource = null
        selectedSeason = 1
        episodesList = emptyList()
        detailRecommendations = emptyList()

        if (subject.isTvShow) {
            val total = if (subject.totalSeasons > 0) subject.totalSeasons else 1
            seasonsList = (1..total).toList()
            loadEpisodesForSeason(1)
        }

        viewModelScope.launch {
            try {
                val fullDetail = repository.getDetail(subject.id)
                if (fullDetail != null) {
                    selectedSubject = fullDetail
                    originalSubjectDetail = fullDetail
                    if (fullDetail.isTvShow) {
                        val total = if (fullDetail.totalSeasons > 0) fullDetail.totalSeasons else 1
                        seasonsList = (1..total).toList()
                    }
                }
                detailRecommendations = repository.getDetailRec(subject.id)
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    fun loadEpisodesForSeason(season: Int) {
        val subject = selectedSubject ?: return
        selectedSeason = season
        viewModelScope.launch {
            try {
                episodesList = repository.getEpisodes(subject.id, season)
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    fun toggleFavorite(subject: MovieSubject) {
        viewModelScope.launch {
            try {
                val isFav = repository.isFavoriteDirect(subject.id)
                if (isFav) {
                    repository.deleteFavorite(subject.id)
                } else {
                    repository.insertFavorite(
                        FavoriteEntity(
                            id = subject.id,
                            name = subject.name,
                            poster = subject.poster,
                            isTvShow = subject.isTvShow,
                            rating = subject.rating,
                            year = subject.year
                        )
                    )
                }
            } catch (e: Exception) {
                // Ignore failure
            }
        }
    }

    fun isFavoriteFlow(id: String): Flow<Boolean> = repository.isFavorite(id)
}
