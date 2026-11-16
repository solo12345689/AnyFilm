package com.example.data.repository

import com.example.data.local.FavoriteEntity
import com.example.data.local.HistoryEntity
import com.example.data.local.MovieDao
import com.example.data.model.*
import com.example.data.network.AniFamApi
import com.example.data.network.AniFamRetrofitClient
import com.example.data.network.GetListRequest
import com.example.data.network.MovieBoxApi
import com.example.data.preferences.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class MovieRepository(
    private val api: MovieBoxApi,
    private val dao: MovieDao,
    private val prefs: PreferencesManager? = null
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

    suspend fun deleteHistoryBySubject(subjectId: String) = withContext(Dispatchers.IO) {
        val clean = subjectId.removePrefix("anifam_").removePrefix("moviebox_")
        dao.deleteHistoryBySubject(subjectId, "anifam_$clean", clean)
    }

    suspend fun getHistoryById(id: String): HistoryEntity? = withContext(Dispatchers.IO) {
        dao.getHistoryById(id)
    }

    suspend fun getAllHistoryDirect(): List<HistoryEntity> = withContext(Dispatchers.IO) {
        dao.getAllHistoryDirect()
    }

    // AniFam API & Helper
    private val aniFamApi: AniFamApi
        get() = AniFamRetrofitClient.getApi(prefs)

    val isAniFam: Boolean
        get() = prefs?.isAniFam == true

    private val detailCache = ConcurrentHashMap<String, MovieSubject>()
    private val aniFamDetailCache = ConcurrentHashMap<Int, AniFamAnimeDetail>()
    private val aniFamEpisodeMap = ConcurrentHashMap<String, Int>() // "subjectId_epNumber" -> episodeId
    private val aniFamEpisodeItemMap = ConcurrentHashMap<Int, AniFamEpisodeItem>()
    private val aniFamGenresCache = CopyOnWriteArrayList<AniFamGenreItem>()
    private val memoryTrendingCache = CopyOnWriteArrayList<MovieSubject>()
    private val memoryCategoryCache = ConcurrentHashMap<Int, List<MovieSubject>>()
    private val memorySectionsCache = ConcurrentHashMap<Int, List<CategorySection>>()

    private val moshi = com.squareup.moshi.Moshi.Builder()
        .add(MovieSubjectAdapter())
        .build()
    private val sectionsListAdapter: com.squareup.moshi.JsonAdapter<List<CategorySection>> = moshi.adapter(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, CategorySection::class.java)
    )
    private val moviesListAdapter: com.squareup.moshi.JsonAdapter<List<MovieSubject>> = moshi.adapter(
        com.squareup.moshi.Types.newParameterizedType(List::class.java, MovieSubject::class.java)
    )

    private fun getCacheFile(filename: String): java.io.File? {
        val dir = prefs?.context?.cacheDir ?: return null
        return java.io.File(dir, filename)
    }

    private fun saveSectionsToDisk(key: String, sections: List<CategorySection>) {
        try {
            val file = getCacheFile("feed_sec_${key}.json") ?: return
            file.writeText(sectionsListAdapter.toJson(sections))
        } catch (_: Exception) {}
    }

    private fun loadSectionsFromDisk(key: String): List<CategorySection>? {
        try {
            val file = getCacheFile("feed_sec_${key}.json") ?: return null
            if (!file.exists()) return null
            val json = file.readText()
            if (json.isBlank()) return null
            return sectionsListAdapter.fromJson(json)
        } catch (_: Exception) {
            return null
        }
    }

    private fun saveMoviesToDisk(key: String, items: List<MovieSubject>) {
        try {
            val file = getCacheFile("feed_mov_${key}.json") ?: return
            file.writeText(moviesListAdapter.toJson(items))
        } catch (_: Exception) {}
    }

    private fun loadMoviesFromDisk(key: String): List<MovieSubject>? {
        try {
            val file = getCacheFile("feed_mov_${key}.json") ?: return null
            if (!file.exists()) return null
            val json = file.readText()
            if (json.isBlank()) return null
            return moviesListAdapter.fromJson(json)
        } catch (_: Exception) {
            return null
        }
    }

    val providerCacheKey: String
        get() {
            if (isAniFam) return "anifam"
            val iso = prefs?.localIso?.lowercase()?.ifEmpty { "in" } ?: "in"
            return "moviebox_$iso"
        }

    fun clearFeedCache() {
        memoryCategoryCache.clear()
        memorySectionsCache.clear()
        memoryTrendingCache.clear()
    }

    fun getCachedCategorySections(categoryId: Int): List<CategorySection> {
        val mem = memorySectionsCache[categoryId]
        if (!mem.isNullOrEmpty()) return mem
        val disk = loadSectionsFromDisk("${providerCacheKey}_$categoryId")
        if (!disk.isNullOrEmpty()) {
            memorySectionsCache[categoryId] = disk
            return disk
        }
        return emptyList()
    }

    fun getCachedCategoryList(categoryId: Int): List<MovieSubject> {
        val mem = memoryCategoryCache[categoryId]
        if (!mem.isNullOrEmpty()) return mem
        val disk = loadMoviesFromDisk("${providerCacheKey}_$categoryId")
        if (!disk.isNullOrEmpty()) {
            memoryCategoryCache[categoryId] = disk
            return disk
        }
        return emptyList()
    }

    fun getCachedTrending(): List<MovieSubject> {
        if (memoryTrendingCache.isNotEmpty()) return memoryTrendingCache.toList()
        val disk = loadMoviesFromDisk("trending_${providerCacheKey}")
        if (!disk.isNullOrEmpty()) {
            memoryTrendingCache.clear()
            memoryTrendingCache.addAll(disk)
            return disk
        }
        return emptyList()
    }

    private suspend fun <T> retryWithBackoff(
        retries: Int = 2,
        initialDelayMs: Long = 400,
        factor: Double = 2.0,
        block: suspend () -> T
    ): T {
        var currentDelay = initialDelayMs
        var lastException: Exception? = null
        repeat(retries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (attempt < retries - 1) {
                    kotlinx.coroutines.delay(currentDelay)
                    currentDelay = (currentDelay * factor).toLong()
                }
            }
        }
        throw lastException ?: java.io.IOException("Request failed after retries")
    }

    val defaultAniFamGenres = listOf(
        AniFamGenreItem(1, "action", "Action"),
        AniFamGenreItem(22, "action-adventure", "Action & Adventure"),
        AniFamGenreItem(2, "adventure", "Adventure"),
        AniFamGenreItem(23, "animation", "Animation"),
        AniFamGenreItem(5, "comedy", "Comedy"),
        AniFamGenreItem(12, "drama", "Drama"),
        AniFamGenreItem(3, "fantasy", "Fantasy"),
        AniFamGenreItem(19, "horror", "Horror"),
        AniFamGenreItem(40, "isekai", "Isekai"),
        AniFamGenreItem(15, "romance", "Romance"),
        AniFamGenreItem(8, "sci-fi", "Sci-Fi"),
        AniFamGenreItem(13, "sports", "Sports"),
        AniFamGenreItem(9, "supernatural", "Supernatural"),
        AniFamGenreItem(20, "suspense", "Suspense"),
        AniFamGenreItem(29, "thriller", "Thriller"),
        AniFamGenreItem(17, "slice-of-life", "Slice of Life")
    )

    suspend fun getAniFamGenres(): List<AniFamGenreItem> = withContext(Dispatchers.IO) {
        if (aniFamGenresCache.isNotEmpty()) return@withContext aniFamGenresCache.toList()
        try {
            val list = retryWithBackoff { aniFamApi.getGenres() }
            if (list.isNotEmpty()) {
                aniFamGenresCache.clear()
                aniFamGenresCache.addAll(list)
                return@withContext list
            }
        } catch (_: Exception) {}
        defaultAniFamGenres
    }

    suspend fun getTrending(page: Int = 1, pageSize: Int = 20): List<MovieSubject> = withContext(Dispatchers.IO) {
        val providerKey = providerCacheKey
        val cached = if (page == 1) getCachedTrending() else emptyList()
        if (isAniFam) {
            try {
                val home = retryWithBackoff { aniFamApi.getHome() }
                val list = (if (home.trending.isNotEmpty()) home.trending else home.spotlight).map { it.toMovieSubject() }
                if (list.isNotEmpty()) {
                    memoryTrendingCache.clear()
                    memoryTrendingCache.addAll(list)
                    if (page == 1) saveMoviesToDisk("trending_anifam", list)
                }
                if (list.isNotEmpty()) list else cached
            } catch (e: Exception) {
                cached.ifEmpty { memoryTrendingCache.toList() }
            }
        } else {
            try {
                val response = retryWithBackoff { api.getTrending(page, pageSize) }
                val list = response.data?.let { data ->
                    if (data.list.isNotEmpty()) data.list else data.items
                } ?: emptyList()
                if ((response.code == 200 || response.code == 0) && list.isNotEmpty()) {
                    if (page == 1) {
                        memoryTrendingCache.clear()
                        memoryTrendingCache.addAll(list)
                        saveMoviesToDisk("trending_${providerKey}", list)
                    }
                    list
                } else {
                    cached.ifEmpty { memoryTrendingCache.toList() }
                }
            } catch (e: Exception) {
                cached.ifEmpty { memoryTrendingCache.toList() }
            }
        }
    }

    suspend fun getRankingList(
        tabId: Int = 2,
        categoryType: String = "all",
        rankingListId: String? = null,
        page: Int = 1,
        perPage: Int = 100
    ): List<MovieSubject> = withContext(Dispatchers.IO) {
        if (isAniFam) return@withContext emptyList()
        try {
            val resp: HomeListResponse = retryWithBackoff {
                api.getRankingList(
                    tabId = tabId,
                    categoryType = categoryType,
                    rankingListId = rankingListId,
                    page = page,
                    perPage = perPage
                )
            }
            val list = resp.data?.list
            if (!list.isNullOrEmpty()) return@withContext list
            val items = resp.data?.items
            if (!items.isNullOrEmpty()) return@withContext items
            emptyList()
        } catch (e: Exception) {
            android.util.Log.e("MovieRepository", "Error fetching ranking list", e)
            emptyList()
        }
    }

    suspend fun getDetailRec(subjectId: String): List<MovieSubject> = withContext(Dispatchers.IO) {
        if (isAniFam) {
            val numId = parseAniFamId(subjectId)
            val cached = if (numId != null) aniFamDetailCache[numId] else null
            if (cached != null && cached.recommended.isNotEmpty()) {
                return@withContext cached.recommended.map { it.toMovieSubject() }
            }
            emptyList()
        } else {
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
    }

    suspend fun getCategorySections(categoryId: Int, page: Int = 1, pageSize: Int = 50): List<CategorySection> = withContext(Dispatchers.IO) {
        val providerKey = providerCacheKey
        val cached = if (page == 1) getCachedCategorySections(categoryId) else (memorySectionsCache[categoryId] ?: emptyList())
        if (isAniFam) {
            try {
                if (categoryId == 0 || categoryId == 2) {
                    val home = retryWithBackoff { aniFamApi.getHome() }
                    val sections = mutableListOf<CategorySection>()
                    if (home.spotlight.isNotEmpty()) {
                        sections.add(CategorySection("Spotlight", "SPOTLIGHT", home.spotlight.map { it.toMovieSubject() }))
                    }
                    if (home.trending.isNotEmpty()) {
                        sections.add(CategorySection("Trending Anime", "TRENDING", home.trending.map { it.toMovieSubject() }, deepLink = "anifam://browse?sort=popular"))
                    }
                    if (home.latest.isNotEmpty()) {
                        sections.add(CategorySection("Latest Episodes", "LATEST", home.latest.map { it.toMovieSubject() }, deepLink = "anifam://browse"))
                    }
                    if (home.new_releases.isNotEmpty()) {
                        sections.add(CategorySection("New Releases", "NEW", home.new_releases.map { it.toMovieSubject() }, deepLink = "anifam://browse?sort=recent"))
                    }
                    if (home.top_airing.isNotEmpty()) {
                        sections.add(CategorySection("Top Airing", "AIRING", home.top_airing.map { it.toMovieSubject() }, deepLink = "anifam://browse?status=airing&sort=popular"))
                    }
                    if (home.most_popular.isNotEmpty()) {
                        sections.add(CategorySection("Most Popular", "POPULAR", home.most_popular.map { it.toMovieSubject() }, deepLink = "anifam://browse?sort=popular"))
                    }
                    if (home.completed.isNotEmpty()) {
                        sections.add(CategorySection("Completed Anime", "COMPLETED", home.completed.map { it.toMovieSubject() }, deepLink = "anifam://browse?status=completed&sort=popular"))
                    }
                    if (home.top_movies.isNotEmpty()) {
                        sections.add(CategorySection("Top Anime Movies", "MOVIES", home.top_movies.map { it.toMovieSubject() }, deepLink = "anifam://browse?type=movie&sort=popular"))
                    }
                    if (sections.isNotEmpty()) {
                        memorySectionsCache[categoryId] = sections
                        if (page == 1) saveSectionsToDisk("${providerKey}_$categoryId", sections)
                    }
                    if (sections.isNotEmpty()) sections else cached
                } else {
                    val genreItem = getAniFamGenres().find { it.id == categoryId }
                    val slug = genreItem?.slug
                    val browse = retryWithBackoff { aniFamApi.browse(genre = slug, page = page) }
                    val items = browse.data.map { it.toMovieSubject() }
                    val sec = listOf(CategorySection(genreItem?.name ?: "Anime", "GENRE", items, deepLink = "anifam://browse?genre=${slug ?: ""}"))
                    if (items.isNotEmpty()) {
                        memorySectionsCache[categoryId] = sec
                        if (page == 1) saveSectionsToDisk("${providerKey}_$categoryId", sec)
                    }
                    if (items.isNotEmpty()) sec else cached
                }
            } catch (e: Exception) {
                cached
            }
        } else {
            try {
                val response = retryWithBackoff { api.getList(GetListRequest(categoryId, page, pageSize)) }
                val sections = response.data?.sections ?: emptyList()
                if ((response.code == 200 || response.code == 0) && sections.isNotEmpty()) {
                    if (page == 1) {
                        memorySectionsCache[categoryId] = sections
                        saveSectionsToDisk("${providerKey}_$categoryId", sections)
                    }
                    sections
                } else {
                    cached
                }
            } catch (e: Exception) {
                cached
            }
        }
    }

    suspend fun getCategoryList(categoryId: Int, page: Int = 1, pageSize: Int = 50): List<MovieSubject> = withContext(Dispatchers.IO) {
        val providerKey = providerCacheKey
        val cached = if (page == 1) getCachedCategoryList(categoryId) else (memoryCategoryCache[categoryId] ?: emptyList())
        if (isAniFam) {
            val sections = getCategorySections(categoryId, page, pageSize)
            val allItems = sections.flatMap { it.items }
            if (allItems.isNotEmpty()) {
                memoryCategoryCache[categoryId] = allItems
                if (page == 1) saveMoviesToDisk("${providerKey}_$categoryId", allItems)
                allItems
            } else {
                cached
            }
        } else {
            try {
                val response = retryWithBackoff { api.getList(GetListRequest(categoryId, page, pageSize)) }
                val sections = response.data?.sections ?: emptyList()
                if (sections.isNotEmpty() && page == 1) {
                    memorySectionsCache[categoryId] = sections
                    saveSectionsToDisk("${providerKey}_$categoryId", sections)
                }
                val list = response.data?.let { data ->
                    if (data.list.isNotEmpty()) data.list else data.items
                } ?: emptyList()
                if ((response.code == 200 || response.code == 0) && list.isNotEmpty()) {
                    if (page == 1) {
                        memoryCategoryCache[categoryId] = list
                        saveMoviesToDisk("${providerKey}_$categoryId", list)
                    }
                    list
                } else {
                    cached
                }
            } catch (e: Exception) {
                cached
            }
        }
    }

    suspend fun getAniFamBrowse(
        genre: String? = null,
        type: String? = null,
        sort: String? = null,
        status: String? = null,
        page: Int = 1
    ): List<MovieSubject> = withContext(Dispatchers.IO) {
        try {
            val resp = retryWithBackoff {
                aniFamApi.browse(
                    genre = genre?.ifBlank { null },
                    type = type?.ifBlank { null },
                    sort = sort?.ifBlank { null },
                    status = status?.ifBlank { null },
                    page = page
                )
            }
            resp.data.map { it.toMovieSubject() }
        } catch (e: Exception) {
            emptyList()
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

    private fun isAniFamSubject(subjectId: String): Boolean {
        return subjectId.startsWith("anifam_") || (isAniFam && !subjectId.startsWith("mb_"))
    }

    private fun parseAniFamId(subjectId: String): Int? {
        return subjectId.removePrefix("anifam_").toIntOrNull()
    }

    suspend fun getDetail(subjectId: String): MovieSubject? = withContext(Dispatchers.IO) {
        val cached = detailCache[subjectId]
        if (cached != null) return@withContext cached

        if (isAniFamSubject(subjectId)) {
            val numId = parseAniFamId(subjectId) ?: return@withContext null
            try {
                val detail = aniFamApi.getAnimeDetail(numId)
                aniFamDetailCache[numId] = detail

                // Pre-fetch episodes so seasonList is properly populated
                val epResponse = try { aniFamApi.getEpisodes(numId) } catch (e: Exception) { null }
                val epList = epResponse?.data ?: emptyList()

                epList.forEach { ep ->
                    aniFamEpisodeMap["${numId}_${ep.episodeNumber}"] = ep.id
                    aniFamEpisodeItemMap[ep.id] = ep
                }

                // Determine sub/dub options from episodes
                val hasAnySub = epList.any { it.isSubAvailable }
                val hasAnyDub = epList.any { it.isDubAvailable }
                val dubOptions = mutableListOf<String>()
                if (hasAnySub) dubOptions.add("Sub")
                if (hasAnyDub) dubOptions.add("Dub")
                if (dubOptions.isEmpty()) dubOptions.add("Sub")

                val resourceDetectors = mutableListOf<ResourceDetector>()
                if (hasAnySub) {
                    resourceDetectors.add(ResourceDetector("sub", "Sub"))
                    resourceDetectors.add(ResourceDetector("sub_server_2", "Sub 1"))
                    resourceDetectors.add(ResourceDetector("sub_server_3", "Sub 2"))
                    resourceDetectors.add(ResourceDetector("sub_server_4", "Sub 3"))
                    resourceDetectors.add(ResourceDetector("sub_server_5", "Sub 4"))
                }
                if (hasAnyDub) {
                    resourceDetectors.add(ResourceDetector("dub", "Dub"))
                    resourceDetectors.add(ResourceDetector("dub_server_2", "Dub 1"))
                    resourceDetectors.add(ResourceDetector("dub_server_3", "Dub 2"))
                    resourceDetectors.add(ResourceDetector("dub_server_4", "Dub 3"))
                    resourceDetectors.add(ResourceDetector("dub_server_5", "Dub 4"))
                }
                if (resourceDetectors.isEmpty()) {
                    resourceDetectors.add(ResourceDetector("sub", "Sub"))
                }

                val episodeInfoList = epList.map { ep ->
                    EpisodeInfo(
                        episode = ep.episodeNumber,
                        title = ep.title ?: "Episode ${ep.episodeNumber}",
                        duration = detail.duration ?: "24 min",
                        stillPath = detail.fullPosterUrl
                    )
                }

                val movieSubject = MovieSubject(
                    id = if (subjectId.startsWith("anifam_")) subjectId else "anifam_$subjectId",
                    name = detail.displayName,
                    poster = detail.fullPosterUrl,
                    backdrop = detail.fullBannerUrl,
                    description = detail.synopsis ?: "",
                    rating = detail.score?.let { String.format("%.1f", it) },
                    year = detail.type ?: "Anime",
                    genres = detail.genres.map { it.name },
                    isTvShow = detail.type?.equals("Movie", ignoreCase = true) != true,
                    totalSeasons = 1,
                    duration = detail.duration,
                    dubs = dubOptions,
                    cast = detail.characters.map { it.toCastMember() },
                    resourceDetectors = resourceDetectors,
                    seasonList = listOf(SeasonInfo(season = 1, episodeList = episodeInfoList))
                )

                detailCache[subjectId] = movieSubject
                detailCache[movieSubject.id] = movieSubject
                movieSubject
            } catch (e: Exception) {
                null
            }
        } else {
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
    }

    suspend fun getEpisodes(subjectId: String, season: Int): List<Episode> = withContext(Dispatchers.IO) {
        if (isAniFamSubject(subjectId)) {
            val numId = parseAniFamId(subjectId) ?: return@withContext emptyList()
            try {
                val epResponse = aniFamApi.getEpisodes(numId)
                val detail = detailCache[subjectId] ?: detailCache["anifam_$numId"] ?: detailCache[numId.toString()]
                val still = detail?.poster ?: ""
                epResponse.data.map { ep ->
                    aniFamEpisodeMap["${numId}_${ep.episodeNumber}"] = ep.id
                    aniFamEpisodeItemMap[ep.id] = ep
                    Episode(
                        episodeId = "anifam_${numId}_${ep.episodeNumber}",
                        title = ep.title ?: "Episode ${ep.episodeNumber}",
                        episodeNumber = ep.episodeNumber,
                        seasonNumber = 1,
                        duration = detail?.duration ?: "24 min",
                        stillPath = still
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
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
    }

    suspend fun getPlayInfo(
        subjectId: String,
        season: Int,
        episode: Int,
        quality: String = "1080p",
        resourceId: String? = null
    ): PlayInfo = withContext(Dispatchers.IO) {
        if (isAniFamSubject(subjectId)) {
            val numId = parseAniFamId(subjectId) ?: return@withContext PlayInfo()
            val epId = aniFamEpisodeMap["${numId}_${episode}"]
                ?: try {
                    val epResponse = aniFamApi.getEpisodes(numId)
                    val found = epResponse.data.find { it.episodeNumber == episode }
                    found?.id
                } catch (e: Exception) { null }

            if (epId == null) return@withContext PlayInfo()

            val track = if (resourceId?.lowercase()?.contains("dub") == true) "dub" else "sub"
            val targetServerId = Regex("_server_(\\d+)").find(resourceId ?: "")?.groupValues?.get(1)?.toIntOrNull()
            try {
                val playResp = aniFamApi.playEpisode(epId, mapOf("track" to track))
                val streams = mutableListOf<StreamItem>()
                val subtitles = mutableListOf<SubtitleItem>()

                var directSource: AniFamPlaySource? = null

                if (targetServerId != null) {
                    try {
                        val resolved = aniFamApi.resolveSource(epId, mapOf("server_id" to targetServerId, "track" to track))
                        if (!resolved.url.isNullOrEmpty()) {
                            directSource = resolved
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MovieRepository", "Failed to resolve AniFam server $targetServerId: ${e.message}")
                    }
                }

                if (directSource == null || directSource.url.isNullOrEmpty()) {
                    directSource = playResp.sources.find { it.kind == "hls" && !it.url.isNullOrEmpty() }
                }
                if (directSource == null || directSource.url.isNullOrEmpty()) {
                    val sId = playResp.sources.firstOrNull { it.server_id != null }?.server_id ?: 2
                    try {
                        val resolved = aniFamApi.resolveSource(epId, mapOf("server_id" to sId, "track" to track))
                        if (!resolved.url.isNullOrEmpty()) {
                            directSource = resolved
                        }
                    } catch (_: Exception) {}
                }
                if (directSource == null) {
                    directSource = playResp.sources.firstOrNull { !it.url.isNullOrEmpty() }
                }

                if (directSource?.url != null) {
                    val mainUrl = directSource.url
                    val cookies = directSource.media_headers?.entries?.joinToString("; ") { "${it.key}=${it.value}" } ?: ""

                    if (directSource.qualities.isNotEmpty()) {
                        directSource.qualities.forEach { q ->
                            val height = q.height ?: 1080
                            val qLabel = "${height}p"
                            streams.add(StreamItem(quality = qLabel, url = mainUrl, cookie = cookies))
                        }
                    } else {
                        streams.add(StreamItem(quality = "1080p", url = mainUrl, cookie = cookies))
                        streams.add(StreamItem(quality = "720p", url = mainUrl, cookie = cookies))
                    }

                    directSource.subtitles.forEach { sub ->
                        if (!sub.url.isNullOrEmpty()) {
                            val label = sub.label?.trim()
                            val cleanLang = when {
                                !label.isNullOrBlank() -> {
                                    // Remove long uploader tags like "(Subtitled by @Shridhuu)" but keep language name
                                    if (label.contains("English", ignoreCase = true) && label.contains("subtitled by", ignoreCase = true)) {
                                        "English"
                                    } else {
                                        label
                                    }
                                }
                                sub.lang?.equals("en", ignoreCase = true) == true -> "English"
                                !sub.lang.isNullOrBlank() -> sub.lang.uppercase()
                                else -> "English"
                            }
                            subtitles.add(SubtitleItem(language = cleanLang, url = sub.url))
                        }
                    }
                }

                PlayInfo(streamList = streams, subTitleList = subtitles)
            } catch (e: Exception) {
                PlayInfo()
            }
        } else {
            try {
                api.playInfo(subjectId, season, episode, quality, resourceId)
            } catch (e: Exception) {
                PlayInfo(emptyList(), emptyList())
            }
        }
    }

    suspend fun search(query: String, page: Int = 1): List<MovieSubject> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()
        if (isAniFam) {
            try {
                val browse = retryWithBackoff { aniFamApi.search(cleanQuery, page) }
                browse.data.map { it.toMovieSubject() }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            try {
                val response = retryWithBackoff { api.search(cleanQuery, page, 30) }
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
    }

    suspend fun getAniFamEpisodeId(animeId: Int, episodeNumber: Int): Int? = withContext(Dispatchers.IO) {
        val cached = aniFamEpisodeMap["${animeId}_${episodeNumber}"]
        if (cached != null) return@withContext cached
        try {
            val epResponse = aniFamApi.getEpisodes(animeId)
            val found = epResponse.data.find { it.episodeNumber == episodeNumber }
            if (found != null) {
                aniFamEpisodeMap["${animeId}_${episodeNumber}"] = found.id
                found.id
            } else null
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getSearchSuggestions(query: String? = null): List<String> = withContext(Dispatchers.IO) {
        if (isAniFam) {
            if (query.isNullOrBlank()) {
                return@withContext listOf(
                    "Solo Leveling",
                    "Demon Slayer: Kimetsu no Yaiba",
                    "Jujutsu Kaisen",
                    "Attack on Titan",
                    "One Piece",
                    "Naruto: Shippuden",
                    "Bleach: Thousand-Year Blood War",
                    "Chainsaw Man",
                    "Spy x Family",
                    "Death Note"
                )
            }
            try {
                val suggestions = aniFamApi.searchSuggest(query)
                suggestions.map { it.displayName }
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            try {
                val response = api.getSearchSuggestions(query)
                if ((response.code == 0 || response.code == 200) && response.data.isNotEmpty()) {
                    response.data
                } else if (query.isNullOrBlank()) {
                    listOf(
                        "Deadpool & Wolverine",
                        "Dune: Part Two",
                        "Loki",
                        "Stranger Things",
                        "House of the Dragon",
                        "Oppenheimer",
                        "The Boys",
                        "Avengers: Endgame",
                        "Breaking Bad",
                        "Spider-Man: Across the Spider-Verse"
                    )
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                if (query.isNullOrBlank()) {
                    listOf(
                        "Deadpool & Wolverine",
                        "Dune: Part Two",
                        "Loki",
                        "Stranger Things",
                        "House of the Dragon",
                        "Oppenheimer",
                        "The Boys",
                        "Avengers: Endgame",
                        "Breaking Bad",
                        "Spider-Man: Across the Spider-Verse"
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    fun clearCaches() {
        detailCache.clear()
        aniFamDetailCache.clear()
        aniFamEpisodeMap.clear()
        aniFamEpisodeItemMap.clear()
        memoryTrendingCache.clear()
        memoryCategoryCache.clear()
        memorySectionsCache.clear()
    }

    // ==================== AniFam Continue Watching & Progress ====================

    suspend fun getAniFamContinueWatching(page: Int = 1): List<AniFamContinueWatchingItem> = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext emptyList()
        try {
            val res = aniFamApi.getContinueWatching(page)
            res.data.forEach { item ->
                val animeId = item.anime?.id
                val epId = item.episode_id
                val epNum = item.epNumber
                if (animeId != null && epId != null) {
                    aniFamEpisodeMap["${animeId}_${epNum}"] = epId
                }
            }
            res.data
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun syncAniFamWatchProgress(
        episodeId: Int,
        positionSeconds: Int,
        durationSeconds: Int,
        track: String = "sub"
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext false
        try {
            val isoNow = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date())
            val req = AniFamProgressRequest(
                items = listOf(
                    AniFamProgressItem(
                        episode_id = episodeId,
                        position = positionSeconds,
                        duration = durationSeconds,
                        track = track,
                        client_ts = isoNow
                    )
                )
            )
            aniFamApi.syncProgress(req)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun syncAniFamWatchProgressBatch(items: List<AniFamProgressItem>): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam || items.isEmpty()) return@withContext false
        try {
            val req = AniFamProgressRequest(items = items)
            aniFamApi.syncProgress(req)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun deleteAniFamContinueWatching(id: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext false
        try {
            val res = aniFamApi.deleteContinueWatching(id)
            res.isSuccessful
        } catch (_: Exception) {
            false
        }
    }

    // ==================== AniFam W2G (Watch Together) ====================

    suspend fun getW2GRooms(tab: String = "on_air", page: Int = 1): List<W2GRoomItem> = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext emptyList()
        try {
            val response = aniFamApi.getW2GRooms(tab = tab, page = page)
            response.data
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getW2GRoomDetail(roomId: String): W2GRoomDetail? = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext null
        try {
            aniFamApi.getW2GRoomDetail(roomId.trim())
        } catch (e: Exception) {
            null
        }
    }

    suspend fun joinW2GRoom(roomId: String): W2GRoomDetail? = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext null
        try {
            aniFamApi.joinW2GRoom(roomId.trim())
        } catch (e: Exception) {
            null
        }
    }

    suspend fun getMineW2GRoom(): W2GRoomItem? = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext null
        try {
            aniFamApi.getMineW2GRoom().room
        } catch (_: Exception) {
            null
        }
    }

    suspend fun createW2GRoom(episodeId: Int, name: String, track: String = "sub"): W2GRoomDetail? = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext null
        try {
            val payload = mapOf(
                "episode_id" to episodeId,
                "name" to name,
                "is_private" to false,
                "track" to track
            )
            aniFamApi.createW2GRoom(payload)
        } catch (e: retrofit2.HttpException) {
            val code = e.code()
            val errorBody = e.response()?.errorBody()?.string() ?: ""
            if (code == 409 || errorBody.contains("active_room_exists")) {
                val mineRoom = getMineW2GRoom()
                throw W2GActiveRoomException(
                    serverMessage = "You already have an active room. End it before creating a new one.",
                    activeRoom = mineRoom
                )
            } else if (code == 401) {
                throw W2GUnauthorizedException("AUTH_REQUIRED")
            } else {
                val msg = try {
                    val json = org.json.JSONObject(errorBody)
                    json.optString("message", e.message())
                } catch (_: Exception) {
                    e.message()
                }
                throw Exception(msg)
            }
        }
    }

    suspend fun startW2GRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext false
        try {
            aniFamApi.startW2GRoom(roomId.trim())
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun syncW2GRoom(roomId: String, action: String, position: Int): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext false
        try {
            aniFamApi.syncW2GRoom(roomId, mapOf("action" to action, "position" to position))
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendW2GMessage(roomId: String, messageText: String): W2GMessage? = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext null
        try {
            aniFamApi.sendW2GMessage(roomId, mapOf("body" to messageText))
        } catch (e: Exception) {
            null
        }
    }

    suspend fun leaveW2GRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext false
        try {
            aniFamApi.leaveW2GRoom(roomId)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun endW2GRoom(roomId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isAniFam) return@withContext false
        try {
            aniFamApi.endW2GRoom(roomId)
            true
        } catch (e: Exception) {
            false
        }
    }
}
