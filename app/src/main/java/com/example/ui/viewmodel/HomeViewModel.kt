package com.example.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.MovieDatabase
import com.example.data.model.MovieSubject
import com.example.data.network.MovieBoxRetrofitClient
import com.example.data.preferences.PreferencesManager
import com.example.data.repository.MovieRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager(application)
    private val database = MovieDatabase.getDatabase(application)
    private val repository = MovieRepository(
        MovieBoxRetrofitClient.getApi(prefs),
        database.movieDao()
    )

    private val _trendingMovies = MutableStateFlow<List<MovieSubject>>(emptyList())
    val trendingMovies: StateFlow<List<MovieSubject>> = _trendingMovies.asStateFlow()

    private val _categoryMovies = MutableStateFlow<List<MovieSubject>>(emptyList())
    val categoryMovies: StateFlow<List<MovieSubject>> = _categoryMovies.asStateFlow()

    var isDashboardLoading by mutableStateOf(false)
        internal set

    var selectedCategoryTab by mutableStateOf(2) // Defaults to Movies (ID 2)
        internal set

    init {
        loadDashboardContent()
    }

    fun loadDashboardContent() {
        viewModelScope.launch {
            isDashboardLoading = true
            try {
                _trendingMovies.value = repository.getTrending()
                _categoryMovies.value = repository.getCategoryList(selectedCategoryTab)
            } catch (e: Exception) {
                // Keep existing lists on failure
            } finally {
                isDashboardLoading = false
            }
        }
    }

    fun loadCategoryList(categoryId: Int) {
        selectedCategoryTab = categoryId
        viewModelScope.launch {
            isDashboardLoading = true
            try {
                _categoryMovies.value = repository.getCategoryList(categoryId)
            } catch (e: Exception) {
                // Keep existing list on failure
            } finally {
                isDashboardLoading = false
            }
        }
    }
}
