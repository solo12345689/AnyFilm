package com.example.data.repository

import com.example.data.local.FavoriteEntity
import com.example.data.local.HistoryEntity
import com.example.data.local.MovieDao
import com.example.data.model.*
import com.example.data.network.GetListRequest
import com.example.data.network.MovieBoxApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class MovieRepository(
    private val api: MovieBoxApi,
    private val dao: MovieDao
) {
    // Room Databases operations
    val favorites: Flow<List<FavoriteEntity>> = dao.getAllFavorites()
    val history: Flow<List<HistoryEntity>> = dao.getAllHistory()

    suspend fun insertFavorite(favorite: FavoriteEntity) = withContext(Dispatchers.IO) {
        dao.insertFavorite(favorite)
    }

    suspend fun deleteFavorite(id: String) = withContext(Dispatchers.IO) {
        dao.deleteFavoriteById(id)
    }

    suspend fun syncFavorites(favorites: List<FavoriteEntity>) = withContext(Dispatchers.IO) {
        dao.syncFavorites(favorites)
    }

    fun isFavorite(id: String): Flow<Boolean> = dao.isFavorite(id)
    suspend fun isFavoriteDirect(id: String): Boolean = withContext(Dispatchers.IO) {
        dao.isFavoriteDirect(id)
    }

    suspend fun insertHistory(history: HistoryEntity) = withContext(Dispatchers.IO) {
        dao.insertHistory(history)
    }

    suspend fun deleteHistory(id: String) = withContext(Dispatchers.IO) {
        dao.deleteHistoryById(id)
    }

    suspend fun getHistoryById(id: String): HistoryEntity? = withContext(Dispatchers.IO) {
        dao.getHistoryById(id)
    }

    // Empty fallback as requested by the user to remove all fake/mock content
    private val fallbackMovies = emptyList<MovieSubject>()

    private val detailCache = java.util.concurrent.ConcurrentHashMap<String, MovieSubject>()

    private val memoryTrendingCache = java.util.concurrent.CopyOnWriteArrayList<MovieSubject>()
    private val memoryCategoryCache = java.util.concurrent.ConcurrentHashMap<Int, List<MovieSubject>>()

    suspend fun getTrending(page: Int = 1, pageSize: Int = 20): List<MovieSubject> = withContext(Dispatchers.IO) {
        try {
            val response = api.getTrending(page, pageSize)
            val list = response.data?.let { data ->
                if (data.list.isNotEmpty()) data.list else data.items
            } ?: emptyList()
            if ((response.code == 200 || response.code == 0) && list.isNotEmpty()) {
                if (page == 1) {
                    memoryTrendingCache.clear()
                    memoryTrendingCache.addAll(list)
                }
                list
            } else {
                if (page == 1 && memoryTrendingCache.isNotEmpty()) memoryTrendingCache.toList() else emptyList()
            }
        } catch (e: Exception) {
            if (page == 1 && memoryTrendingCache.isNotEmpty()) memoryTrendingCache.toList() else emptyList()
        }
    }

    suspend fun getDetailRec(subjectId: String): List<MovieSubject> = withContext(Dispatchers.IO) {
        try {
            val req = mapOf(
                "subjectId" to subjectId,
                "page" to 1,
                "pageSize" to 15
            )
            val response = api.getDetailRec(req)
            val list = response.data?.let { data ->
                if (data.list.isNotEmpty()) data.list else data.items
            } ?: emptyList()
            if (response.code == 0 || response.code == 200) list else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private val memorySectionsCache = java.util.concurrent.ConcurrentHashMap<Int, List<CategorySection>>()

    suspend fun getCategorySections(categoryId: Int, page: Int = 1, pageSize: Int = 50): List<CategorySection> = withContext(Dispatchers.IO) {
        val cached = memorySectionsCache[categoryId]
        try {
            val response = api.getList(GetListRequest(categoryId, page, pageSize))
            val sections = response.data?.sections ?: emptyList()
            if ((response.code == 200 || response.code == 0) && sections.isNotEmpty()) {
                if (page == 1) {
                    memorySectionsCache[categoryId] = sections
                }
                sections
            } else {
                cached ?: emptyList()
            }
        } catch (e: Exception) {
            cached ?: emptyList()
        }
    }

    suspend fun getCategoryList(categoryId: Int, page: Int = 1, pageSize: Int = 50): List<MovieSubject> = withContext(Dispatchers.IO) {
        val cached = memoryCategoryCache[categoryId]
        try {
            val response = api.getList(GetListRequest(categoryId, page, pageSize))
            val sections = response.data?.sections ?: emptyList()
            if (sections.isNotEmpty() && page == 1) {
                memorySectionsCache[categoryId] = sections
            }
            val list = response.data?.let { data ->
                if (data.list.isNotEmpty()) data.list else data.items
            } ?: emptyList()
            if ((response.code == 200 || response.code == 0) && list.isNotEmpty()) {
                if (page == 1) {
                    memoryCategoryCache[categoryId] = list
                }
                list
            } else {
                cached ?: emptyList()
            }
        } catch (e: Exception) {
            cached ?: emptyList()
        }
    }

    fun getCachedDetail(subjectId: String): MovieSubject? {
        return detailCache[subjectId]
    }

    fun getCachedEpisodes(subjectId: String, season: Int): List<Episode> {
        val detail = detailCache[subjectId] ?: return emptyList()
        val seasonInfo = detail.seasonList.find { it.season == season } ?: return emptyList()
        return seasonInfo.episodeList.map { epInfo ->
            Episode(
                episodeId = "${subjectId}_S${season}_E${epInfo.episode}",
                title = epInfo.title.ifEmpty { "Episode ${epInfo.episode}" },
                episodeNumber = epInfo.episode,
                seasonNumber = season,
                stillPath = epInfo.stillPath
            )
        }
    }

    suspend fun getDetail(subjectId: String): MovieSubject? = withContext(Dispatchers.IO) {
        val cached = detailCache[subjectId]
        if (cached != null) return@withContext cached
        try {
            val response = api.getDetail(subjectId)
            if ((response.code == 200 || response.code == 0) && response.data != null) {
                detailCache[subjectId] = response.data
                response.data
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getEpisodes(subjectId: String, season: Int): List<Episode> = withContext(Dispatchers.IO) {
        val detail = getDetail(subjectId)
        if (detail != null && detail.seasonList.isNotEmpty()) {
            val seasonInfo = detail.seasonList.find { it.season == season }
            if (seasonInfo != null) {
                return@withContext seasonInfo.episodeList.map { epInfo ->
                    Episode(
                        episodeId = "${subjectId}_S${season}_E${epInfo.episode}",
                        title = epInfo.title.ifEmpty { "Episode ${epInfo.episode}" },
                        episodeNumber = epInfo.episode,
                        seasonNumber = season,
                        duration = epInfo.duration.ifEmpty { "45 min" },
                        stillPath = epInfo.stillPath
                    )
                }
            }
        }
        emptyList()
    }

    suspend fun getPlayInfo(
        subjectId: String,
        season: Int,
        episode: Int,
        quality: String = "1080p",
        resourceId: String? = null
    ): PlayInfo = withContext(Dispatchers.IO) {
        try {
            api.playInfo(subjectId, season, episode, quality, resourceId)
        } catch (e: Exception) {
            PlayInfo(emptyList(), emptyList())
        }
    }

    suspend fun search(query: String, page: Int = 1): List<MovieSubject> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val response = api.search(query, page, 30)
            val items = response.data?.let { data ->
                if (data.items.isNotEmpty()) data.items else data.list
            } ?: emptyList()
            if ((response.code == 200 || response.code == 0) && items.isNotEmpty()) {
                items
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getSearchSuggestions(query: String? = null): List<String> = withContext(Dispatchers.IO) {
        try {
            val response = api.getSearchSuggestions(query)
            if (response.code == 0 || response.code == 200) {
                response.data
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
