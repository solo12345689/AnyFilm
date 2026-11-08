package com.example.data.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AniFamGenreItem(
    val id: Int,
    val slug: String,
    val name: String,
    val image: String? = null
) {
    val fullImageUrl: String
        get() = if (image.isNullOrEmpty()) "" else if (image.startsWith("http")) image else "https://anifam.net$image"
}

@JsonClass(generateAdapter = true)
data class AniFamAnimeItem(
    val id: Int,
    val slug: String? = null,
    val title: String? = null,
    val name: String? = null,
    val title_jp: String? = null,
    val title_romaji: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val type: String? = null,
    val sub: Int? = null,
    val dub: Int? = null,
    val eps: Int? = null,
    val rating: String? = null,
    val duration: String? = null,
    val status: String? = null,
    val score: Double? = null,
    val synopsis: String? = null
) {
    val displayName: String
        get() = title?.ifBlank { null } ?: name?.ifBlank { null } ?: title_jp ?: "Anime #$id"

    val fullPosterUrl: String
        get() = if (poster.isNullOrEmpty()) "" else if (poster.startsWith("http")) poster else "https://anifam.net$poster"

    val fullBannerUrl: String
        get() = if (banner.isNullOrEmpty()) fullPosterUrl else if (banner.startsWith("http")) banner else "https://anifam.net$banner"

    fun toMovieSubject(): MovieSubject {
        val dubList = mutableListOf<String>()
        if ((sub ?: 0) > 0) dubList.add("Sub")
        if ((dub ?: 0) > 0) dubList.add("Dub")
        if (dubList.isEmpty()) dubList.add("Sub")

        val formattedScore = score?.let { if (it > 0.0) String.format("%.1f", it) else null }

        return MovieSubject(
            id = "anifam_$id",
            name = displayName,
            poster = fullPosterUrl,
            backdrop = fullBannerUrl,
            description = synopsis ?: "",
            rating = formattedScore,
            year = type ?: "Anime",
            genres = listOfNotNull(type, status).filter { it.isNotBlank() },
            isTvShow = type?.equals("Movie", ignoreCase = true) != true,
            totalSeasons = 1,
            duration = duration,
            dubs = dubList
        )
    }
}

@JsonClass(generateAdapter = true)
data class AniFamHomeResponse(
    val spotlight: List<AniFamAnimeItem> = emptyList(),
    val trending: List<AniFamAnimeItem> = emptyList(),
    val latest: List<AniFamAnimeItem> = emptyList(),
    val new_releases: List<AniFamAnimeItem> = emptyList(),
    val top_airing: List<AniFamAnimeItem> = emptyList(),
    val most_popular: List<AniFamAnimeItem> = emptyList(),
    val top_movies: List<AniFamAnimeItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AniFamBrowseResponse(
    val data: List<AniFamAnimeItem> = emptyList(),
    val meta: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class AniFamEpisodesResponse(
    val data: List<AniFamEpisodeItem> = emptyList(),
    val ranges: Any? = null
)

@JsonClass(generateAdapter = true)
data class AniFamEpisodeItem(
    val id: Int,
    val number: Double? = 1.0,
    val title: String? = null,
    val is_filler: Any? = null,
    val has_sub: Any? = null,
    val has_dub: Any? = null
) {
    val episodeNumber: Int
        get() = number?.toInt() ?: 1

    val isSubAvailable: Boolean
        get() = when (has_sub) {
            is Boolean -> has_sub
            is Number -> has_sub.toInt() != 0
            is String -> has_sub.equals("true", ignoreCase = true) || has_sub == "1"
            else -> true
        }

    val isDubAvailable: Boolean
        get() = when (has_dub) {
            is Boolean -> has_dub
            is Number -> has_dub.toInt() != 0
            is String -> has_dub.equals("true", ignoreCase = true) || has_dub == "1"
            else -> false
        }
}

@JsonClass(generateAdapter = true)
data class AniFamAnimeDetail(
    val id: Int,
    val slug: String? = null,
    val title: String? = null,
    val title_jp: String? = null,
    val title_romaji: String? = null,
    val poster: String? = null,
    val banner: String? = null,
    val synopsis: String? = null,
    val type: String? = null,
    val sub: Int? = null,
    val dub: Int? = null,
    val eps: Int? = null,
    val rating: String? = null,
    val duration: String? = null,
    val status: String? = null,
    val score: Double? = null,
    val genres: List<AniFamGenreItem> = emptyList(),
    val recommended: List<AniFamAnimeItem> = emptyList(),
    val related: List<AniFamAnimeItem> = emptyList(),
    val seasons: List<AniFamAnimeItem> = emptyList(),
    val comments_count: Int = 0
) {
    val displayName: String
        get() = title?.ifBlank { null } ?: title_jp ?: "Anime #$id"

    val fullPosterUrl: String
        get() = if (poster.isNullOrEmpty()) "" else if (poster.startsWith("http")) poster else "https://anifam.net$poster"

    val fullBannerUrl: String
        get() = if (banner.isNullOrEmpty()) fullPosterUrl else if (banner.startsWith("http")) banner else "https://anifam.net$banner"

    fun toMovieSubject(): MovieSubject {
        val dubList = mutableListOf<String>()
        if ((sub ?: 0) > 0) dubList.add("Sub")
        if ((dub ?: 0) > 0) dubList.add("Dub")
        if (dubList.isEmpty()) dubList.add("Sub")

        val formattedScore = score?.let { if (it > 0.0) String.format("%.1f", it) else null }

        return MovieSubject(
            id = "anifam_$id",
            name = displayName,
            poster = fullPosterUrl,
            backdrop = fullBannerUrl,
            description = synopsis ?: "",
            rating = formattedScore,
            year = type ?: "Anime",
            genres = genres.map { it.name },
            isTvShow = type?.equals("Movie", ignoreCase = true) != true,
            totalSeasons = 1,
            duration = duration,
            dubs = dubList
        )
    }
}

@JsonClass(generateAdapter = true)
data class AniFamQuality(
    val height: Int? = null,
    val bandwidth: Long? = null
)

@JsonClass(generateAdapter = true)
data class AniFamSubtitle(
    val lang: String? = null,
    val label: String? = null,
    val url: String? = null,
    val default: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class AniFamPlaySource(
    val kind: String? = null,
    val server: String? = null,
    val server_id: Int? = null,
    val url: String? = null,
    val media_headers: Map<String, String>? = null,
    val qualities: List<AniFamQuality> = emptyList(),
    val subtitles: List<AniFamSubtitle> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AniFamPlayResponse(
    val episode_id: Int? = null,
    val track: String? = null,
    val tracks: List<String> = emptyList(),
    val sources: List<AniFamPlaySource> = emptyList()
)

@JsonClass(generateAdapter = true)
data class AniFamUser(
    val id: Int? = null,
    val name: String? = null,
    val username: String? = null,
    val email: String? = null,
    val avatar: String? = null
) {
    val fullAvatarUrl: String
        get() = if (avatar.isNullOrEmpty()) "" else if (avatar.startsWith("http")) avatar else "https://anifam.net$avatar"
}

@JsonClass(generateAdapter = true)
data class AniFamAuthResponse(
    val token: String? = null,
    val user: AniFamUser? = null
)

@JsonClass(generateAdapter = true)
data class AniFamRegisterRequest(
    val name: String,
    val email: String,
    val password: String,
    val password_confirmation: String,
    val device_name: String
)

@JsonClass(generateAdapter = true)
data class AniFamLoginRequest(
    val login: String,
    val password: String,
    val device_name: String
)

@JsonClass(generateAdapter = true)
data class AniFamWatchlistMeta(
    val page: Int? = null,
    val per_page: Int? = null,
    val total: Int? = null,
    val has_more: Boolean? = false
)

@JsonClass(generateAdapter = true)
data class AniFamWatchlistItem(
    val id: Int,
    val slug: String? = null,
    val title: String,
    val title_jp: String? = null,
    val title_romaji: String? = null,
    val poster: String? = null,
    val type: String? = null,
    val sub: Int? = null,
    val dub: Int? = null,
    val eps: Int? = null,
    val rating: String? = null,
    val duration: String? = null,
    val status: String? = null,
    val score: Double? = null,
    val list_status: String? = null
) {
    val fullPosterUrl: String
        get() = if (poster.isNullOrEmpty()) ""
        else if (poster.startsWith("http")) poster
        else "https://anifam.net$poster"
}

@JsonClass(generateAdapter = true)
data class AniFamWatchlistResponse(
    val data: List<AniFamWatchlistItem> = emptyList(),
    val meta: AniFamWatchlistMeta? = null
)

@JsonClass(generateAdapter = true)
data class AniFamWatchlistStatusResponse(
    val status: String? = null,
    val rating: Double? = null
)

@JsonClass(generateAdapter = true)
data class AniFamProfileUpdateRequest(
    val name: String? = null,
    val bio: String? = null
)

