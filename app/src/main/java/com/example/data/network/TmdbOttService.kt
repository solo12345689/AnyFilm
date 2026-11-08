package com.example.data.network

import androidx.compose.ui.graphics.Color
import com.example.R
import com.example.data.model.MovieSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.TimeUnit

data class OttPlatform(
    val id: String,
    val name: String,
    val iconRes: Int,
    val brandColor: Color,
    val bgGradientTop: Color,
    val watchProviderId: Int? = null,
    val networkId: Int? = null,
    val companyId: Int? = null
)

object TmdbOttService {
    private val plainClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val dohDns = object : Dns {
        private val fallbackIps = listOf(
            InetAddress.getByName("3.175.86.50"),
            InetAddress.getByName("3.175.86.37"),
            InetAddress.getByName("3.175.86.103"),
            InetAddress.getByName("3.175.86.67")
        )

        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val sys = Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty()) return sys
            } catch (_: Exception) {}

            if (hostname.contains("themoviedb.org")) {
                try {
                    val req = Request.Builder()
                        .url("https://dns.google/resolve?name=$hostname&type=A")
                        .build()
                    val resp = plainClient.newCall(req).execute().use { r ->
                        if (r.isSuccessful) r.body?.string() else null
                    }
                    if (resp != null) {
                        val obj = JSONObject(resp)
                        val ans = obj.optJSONArray("Answer")
                        val list = mutableListOf<InetAddress>()
                        if (ans != null) {
                            for (i in 0 until ans.length()) {
                                val item = ans.getJSONObject(i)
                                if (item.optInt("type") == 1) {
                                    val ip = item.optString("data")
                                    if (ip.isNotBlank()) {
                                        list.add(InetAddress.getByName(ip))
                                    }
                                }
                            }
                        }
                        if (list.isNotEmpty()) return list
                    }
                } catch (_: Exception) {}

                return fallbackIps
            }

            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val client = OkHttpClient.Builder()
        .dns(dohDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private const val API_KEY_1 = "3e20e76d6d210b6cb128d17d233b64dc"
    private const val API_KEY_2 = "203f18d6bdc16a8e9a1fa73a6c6da6bc"

    val allPlatforms = listOf(
        OttPlatform(
            id = "netflix",
            name = "Netflix",
            iconRes = R.drawable.ic_ott_netflix,
            brandColor = Color(0xFFE50914),
            bgGradientTop = Color(0xFF260608),
            watchProviderId = 8,
            networkId = 213
        ),
        OttPlatform(
            id = "prime",
            name = "Prime Video",
            iconRes = R.drawable.ic_ott_prime,
            brandColor = Color(0xFF00A8E1),
            bgGradientTop = Color(0xFF0C1826),
            watchProviderId = 119,
            networkId = 1024
        ),
        OttPlatform(
            id = "hotstar",
            name = "JioHotstar",
            iconRes = R.drawable.ic_ott_hotstar,
            brandColor = Color(0xFF8338EC),
            bgGradientTop = Color(0xFF1B0E38),
            watchProviderId = 2336,
            networkId = 2739
        ),
        OttPlatform(
            id = "appletv",
            name = "Apple TV+",
            iconRes = R.drawable.ic_ott_appletv,
            brandColor = Color(0xFFFFFFFF),
            bgGradientTop = Color(0xFF1A1A1E),
            watchProviderId = 350,
            networkId = 2552
        ),
        OttPlatform(
            id = "hbo",
            name = "HBO",
            iconRes = R.drawable.ic_ott_hbo,
            brandColor = Color(0xFF5822B4),
            bgGradientTop = Color(0xFF140D2E),
            networkId = 49,
            companyId = 174
        ),
        OttPlatform(
            id = "sonyliv",
            name = "Sony LIV",
            iconRes = R.drawable.ic_ott_sonyliv,
            brandColor = Color(0xFFFF9900),
            bgGradientTop = Color(0xFF1E1408),
            watchProviderId = 237
        ),
        OttPlatform(
            id = "zee5",
            name = "ZEE5",
            iconRes = R.drawable.ic_ott_zee5,
            brandColor = Color(0xFFE81C76),
            bgGradientTop = Color(0xFF1F0815),
            watchProviderId = 232
        )
    )

    private fun makeRequest(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.string() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun queryTmdb(endpoint: String, params: String): List<MovieSubject> {
        val url1 = "https://api.themoviedb.org/3/$endpoint?api_key=$API_KEY_1&$params"
        var json = makeRequest(url1)
        if (json == null) {
            val url2 = "https://api.themoviedb.org/3/$endpoint?api_key=$API_KEY_2&$params"
            json = makeRequest(url2)
        }
        if (json == null) return emptyList()

        return parseTmdbResults(json, isTv = endpoint.contains("/tv"))
    }

    private fun parseTmdbResults(jsonStr: String, isTv: Boolean): List<MovieSubject> {
        val list = mutableListOf<MovieSubject>()
        try {
            val root = JSONObject(jsonStr)
            val results = root.optJSONArray("results") ?: return emptyList()
            for (i in 0 until results.length()) {
                val item = results.getJSONObject(i)
                val id = item.optLong("id")
                val title = if (isTv) item.optString("name") else item.optString("title")
                val origTitle = if (isTv) item.optString("original_name") else item.optString("original_title")
                val name = if (title.isNotBlank()) title else origTitle
                val posterPath = item.optString("poster_path")
                val backdropPath = item.optString("backdrop_path")
                val overview = item.optString("overview")
                val voteAvg = item.optDouble("vote_average", 0.0)
                val dateStr = if (isTv) item.optString("first_air_date") else item.optString("release_date")
                val year = if (dateStr.length >= 4) dateStr.substring(0, 4) else null

                val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                    "https://image.tmdb.org/t/p/w500$posterPath"
                } else if (backdropPath.isNotBlank() && backdropPath != "null") {
                    "https://image.tmdb.org/t/p/w500$backdropPath"
                } else ""

                val backdropUrl = if (backdropPath.isNotBlank() && backdropPath != "null") {
                    "https://image.tmdb.org/t/p/w1280$backdropPath"
                } else null

                if (name.isNotBlank() && posterUrl.isNotBlank()) {
                    list.add(
                        MovieSubject(
                            id = "tmdb_$id",
                            name = name,
                            poster = posterUrl,
                            description = overview,
                            rating = if (voteAvg > 0.0) String.format("%.1f", voteAvg) else null,
                            year = year,
                            isTvShow = isTv,
                            backdrop = backdropUrl
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return list
    }

    suspend fun getTopMovies(platform: OttPlatform): List<MovieSubject> = withContext(Dispatchers.IO) {
        val paramBuilder = StringBuilder("sort_by=popularity.desc&page=1")
        if (platform.watchProviderId != null) {
            paramBuilder.append("&watch_region=IN&with_watch_providers=${platform.watchProviderId}")
        } else if (platform.companyId != null) {
            paramBuilder.append("&with_companies=${platform.companyId}")
        }
        queryTmdb("discover/movie", paramBuilder.toString()).take(10)
    }

    suspend fun getTopShows(platform: OttPlatform): List<MovieSubject> = withContext(Dispatchers.IO) {
        val paramBuilder = StringBuilder("sort_by=popularity.desc&page=1")
        if (platform.networkId != null) {
            paramBuilder.append("&with_networks=${platform.networkId}")
        } else if (platform.watchProviderId != null) {
            paramBuilder.append("&watch_region=IN&with_watch_providers=${platform.watchProviderId}")
        }
        queryTmdb("discover/tv", paramBuilder.toString()).take(10)
    }

    suspend fun getRecentlyAdded(platform: OttPlatform, page: Int = 1): List<MovieSubject> = withContext(Dispatchers.IO) {
        val paramBuilder = StringBuilder("sort_by=popularity.desc&page=$page")
        if (platform.watchProviderId != null) {
            paramBuilder.append("&watch_region=IN&with_watch_providers=${platform.watchProviderId}")
        } else if (platform.networkId != null) {
            paramBuilder.append("&with_networks=${platform.networkId}")
        } else if (platform.companyId != null) {
            paramBuilder.append("&with_companies=${platform.companyId}")
        }
        queryTmdb("discover/movie", paramBuilder.toString())
    }

    suspend fun getPopularShows(platform: OttPlatform): List<MovieSubject> = withContext(Dispatchers.IO) {
        val paramBuilder = StringBuilder("sort_by=popularity.desc&page=2")
        if (platform.networkId != null) {
            paramBuilder.append("&with_networks=${platform.networkId}")
        } else if (platform.watchProviderId != null) {
            paramBuilder.append("&watch_region=IN&with_watch_providers=${platform.watchProviderId}")
        }
        queryTmdb("discover/tv", paramBuilder.toString())
    }

    suspend fun getGenreContent(platform: OttPlatform, genreId: Int, isTv: Boolean = false): List<MovieSubject> = withContext(Dispatchers.IO) {
        val endpoint = if (isTv) "discover/tv" else "discover/movie"
        val paramBuilder = StringBuilder("sort_by=popularity.desc&with_genres=$genreId&page=1")
        if (platform.watchProviderId != null) {
            paramBuilder.append("&watch_region=IN&with_watch_providers=${platform.watchProviderId}")
        } else if (platform.networkId != null && isTv) {
            paramBuilder.append("&with_networks=${platform.networkId}")
        } else if (platform.companyId != null) {
            paramBuilder.append("&with_companies=${platform.companyId}")
        }
        queryTmdb(endpoint, paramBuilder.toString())
    }

    suspend fun searchPlatform(platform: OttPlatform, query: String): List<MovieSubject> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val movies = queryTmdb("search/movie", "query=$encoded&page=1")
        val shows = queryTmdb("search/tv", "query=$encoded&page=1")
        (movies + shows).sortedByDescending { it.rating?.toDoubleOrNull() ?: 0.0 }
    }
}
