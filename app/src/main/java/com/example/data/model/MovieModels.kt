package com.example.data.model

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson

@JsonClass(generateAdapter = true)
data class SeasonInfo(
    val season: Int,
    val episodeList: List<EpisodeInfo> = emptyList()
)

@JsonClass(generateAdapter = true)
data class EpisodeInfo(
    val episode: Int,
    val title: String = "",
    val duration: String = "",
    val stillPath: String = ""
)

@JsonClass(generateAdapter = true)
data class CastMember(
    val name: String,
    val role: String = "",
    val avatar: String = ""
)

@JsonClass(generateAdapter = true)
data class MovieSubject(
    val id: String,
    val name: String,
    val poster: String,
    val description: String = "",
    val rating: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val isTvShow: Boolean = false,
    val totalSeasons: Int = 1,
    val duration: String? = null,
    val cast: List<CastMember> = emptyList(),
    val dubs: List<String> = emptyList(),
    val seasonList: List<SeasonInfo> = emptyList(),
    val resourceDetectors: List<ResourceDetector> = emptyList()
)

@JsonClass(generateAdapter = true)
data class Episode(
    val episodeId: String,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val duration: String = "45 min",
    val stillPath: String = ""
)

@JsonClass(generateAdapter = true)
data class ResourceDetector(
    val resourceId: String,
    val name: String
)

@JsonClass(generateAdapter = true)
data class PlayInfo(
    val streamList: List<StreamItem> = emptyList(),
    val subTitleList: List<SubtitleItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class StreamItem(
    val quality: String, // 720p, 1080p, 4K
    val url: String,
    val size: String = "1.2 GB",
    val cookie: String = ""
)

@JsonClass(generateAdapter = true)
data class SubtitleItem(
    val language: String,
    val url: String,
    val format: String = "vtt"
)

@JsonClass(generateAdapter = true)
data class SubjectDetailResponse(
    val code: Int = 200,
    val message: String = "success",
    val data: MovieSubject? = null,
    val resourceDetectors: List<ResourceDetector> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SearchData(
    val list: List<MovieSubject> = emptyList(),
    val items: List<MovieSubject> = emptyList()
)

@JsonClass(generateAdapter = true)
data class SearchResponse(
    val code: Int = 200,
    val data: SearchData? = null
)

@JsonClass(generateAdapter = true)
data class HomeListData(
    val list: List<MovieSubject> = emptyList(),
    val items: List<MovieSubject> = emptyList()
)

@JsonClass(generateAdapter = true)
data class HomeListResponse(
    val code: Int = 200,
    val data: HomeListData? = null
)

@JsonClass(generateAdapter = true)
data class LoginApiRequest(
    val account: String,
    val password: String,
    val authType: Int = 1
)

@JsonClass(generateAdapter = true)
data class RegisterApiRequest(
    val account: String,
    val password: String,
    val otp: String,
    val authType: Int = 1
)

@JsonClass(generateAdapter = true)
data class OtpApiRequest(
    val account: String,
    val authType: Int = 1,
    val type: Int = 1
)

@JsonClass(generateAdapter = true)
data class UserProfile(
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val email: String = "",
    val vip: Int = 0,
    val userId: String = "",
    val vipExpire: String = "",
    val vipDaysLeft: Int = 0,
    val vipPoint: Int = 0,
    val wantToSeeCount: Int = 0,
    val haveSeenCount: Int = 0,
    val favoriteCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class AuthResponse(
    val status: String = "success",
    val message: String = "",
    val token: String? = null,
    val user: UserProfile? = null
)

@JsonClass(generateAdapter = true)
data class UserInfoResponse(
    val logged_in: Boolean = false,
    val mode: String = "Guest Access",
    val user: UserProfile? = null,
    val session_id: String? = null
)

class MovieSubjectAdapter {
    @FromJson
    fun fromJson(map: Map<String, Any?>): MovieSubject {
        val item = (map["subject"] as? Map<String, Any?>) ?: map

        val id = (item["subjectId"] ?: item["id"] ?: "").toString()
        
        val name = (
            item["title"] ?: 
            item["name"] ?: 
            item["subjectName"] ?: 
            item["subject_name"] ?: 
            item["categoryName"] ?: 
            item["content"] ?: 
            item["keyword"] ?: 
            item["keywordName"] ?: 
            item["itemName"] ?: 
            item["show_name"] ?: 
            item["showTitle"] ?: 
            item["titleName"] ?: 
            item["title_en"] ?: 
            item["tag"] ?: 
            item["label"] ?: 
            map["title"] ?:
            map["name"] ?:
            "Unknown"
        ).toString()

        var posterUrl = ""
        val poster = item["poster"]
        if (poster is Map<*, *>) {
            posterUrl = poster["thumbUrl"]?.toString() ?: poster["webpUrl"]?.toString() ?: poster["url"]?.toString() ?: ""
        } else if (poster is String) {
            posterUrl = poster
        }
        if (posterUrl.isEmpty()) {
            val cover = item["cover"]
            if (cover is Map<*, *>) {
                posterUrl = cover["thumbUrl"]?.toString() ?: cover["webpUrl"]?.toString() ?: cover["url"]?.toString() ?: ""
            } else if (cover is String) {
                posterUrl = cover
            }
        }
        if (posterUrl.isEmpty()) {
            val hp = item["horizontalPoster"] ?: item["horizontalCover"]
            if (hp is Map<*, *>) {
                posterUrl = hp["thumbUrl"]?.toString() ?: hp["webpUrl"]?.toString() ?: hp["url"]?.toString() ?: ""
            } else if (hp is String) {
                posterUrl = hp
            }
        }
        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"
        }

        val ratingRaw = (item["imdbRatingValue"] ?: item["imdbRate"] ?: item["starRating"] ?: item["score"] ?: map["rating"])?.toString()?.trim()
        val rating = if (ratingRaw.isNullOrEmpty() || ratingRaw.equals("null", ignoreCase = true) || ratingRaw == "0" || ratingRaw == "0.0") null else ratingRaw
        
        val releaseDateRaw = (item["releaseDate"] ?: item["releaseTime"] ?: item["year"] ?: map["year"])?.toString()?.trim()
        val year = when {
            releaseDateRaw.isNullOrEmpty() || releaseDateRaw.equals("null", ignoreCase = true) -> null
            releaseDateRaw.length >= 4 -> releaseDateRaw.substring(0, 4)
            else -> releaseDateRaw
        }

        val subjectTypeVal = (item["subjectType"] ?: item["type"] ?: item["subject_type"])
        val subjectType = when (subjectTypeVal) {
            is Number -> subjectTypeVal.toInt()
            is String -> subjectTypeVal.toIntOrNull() ?: 1
            else -> 1
        }
        val isTvShow = (subjectType == 2) || (item["episodeCount"] != null) || (item["seasonCount"] != null)

        val seasonListRaw = item["seasonList"] ?: map["seasonList"]
        val totalSeasonsVal = (item["seasonCount"] ?: 1)
        var totalSeasons = when (totalSeasonsVal) {
            is Number -> totalSeasonsVal.toInt()
            is String -> totalSeasonsVal.toIntOrNull() ?: 1
            else -> 1
        }
        if (seasonListRaw is List<*>) {
            if (seasonListRaw.size > totalSeasons) {
                totalSeasons = seasonListRaw.size
            }
        }

        val durationVal = item["duration"] ?: item["runtime"] ?: item["minute"] ?: map["duration"]
        val duration = when {
            durationVal == null || durationVal.toString().isBlank() || durationVal.toString().equals("null", ignoreCase = true) || durationVal == 0 || durationVal == "0" -> null
            durationVal is Number -> if (durationVal.toInt() > 0) "${durationVal.toInt()} min" else null
            else -> {
                val str = durationVal.toString().trim()
                if (str.all { it.isDigit() }) "$str min" else str
            }
        }

        val description = (item["description"] ?: "").toString()

        // Cast mapping
        val castList = mutableListOf<CastMember>()
        val seenNames = mutableSetOf<String>()
        val staffList = item["cast"] ?: item["staffList"] ?: item["actorList"]
        if (staffList is List<*>) {
            for (staff in staffList) {
                if (staff is Map<*, *>) {
                    val staffName = (staff["name"] ?: staff["actorName"] ?: "").toString().trim()
                    if (staffName.isNotEmpty() && !seenNames.contains(staffName)) {
                        seenNames.add(staffName)
                        val role = (staff["character"] ?: staff["role"] ?: "Cast").toString()
                        val avatar = (staff["avatarUrl"] ?: staff["avatar"] ?: staff["photo"] ?: staff["poster"] ?: "").toString()
                        castList.add(CastMember(name = staffName, role = role, avatar = avatar))
                    }
                } else if (staff is String) {
                    val staffName = staff.trim()
                    if (staffName.isNotEmpty() && !seenNames.contains(staffName)) {
                        seenNames.add(staffName)
                        castList.add(CastMember(name = staffName))
                    }
                }
            }
        }

        // Genres mapping
        val genresList = mutableListOf<String>()
        val genresRaw = item["genres"] ?: item["cats"] ?: item["category"]
        if (genresRaw is List<*>) {
            for (g in genresRaw) {
                if (g != null) genresList.add(g.toString())
            }
        } else if (genresRaw is String) {
            genresList.addAll(genresRaw.split(",").map { it.trim() })
        }

        // Resource Detectors mapping
        val detectorsList = mutableListOf<ResourceDetector>()
        val detectorsRaw = item["resourceDetectors"] ?: map["resourceDetectors"]
        if (detectorsRaw is List<*>) {
            for (det in detectorsRaw) {
                if (det is Map<*, *>) {
                    val resourceId = (det["resourceId"] ?: "").toString()
                    val detName = (det["name"] ?: "").toString()
                    if (resourceId.isNotEmpty()) {
                        detectorsList.add(ResourceDetector(resourceId, detName))
                    }
                }
            }
        }

        // Season List mapping
        val seasonList = mutableListOf<SeasonInfo>()
        if (seasonListRaw is List<*>) {
            for (s in seasonListRaw) {
                if (s is Map<*, *>) {
                    val seasonNum = (s["season"] ?: s["seasonNumber"] ?: s["se"] ?: 1).toString().toDoubleOrNull()?.toInt() ?: 1
                    val epsList = mutableListOf<EpisodeInfo>()
                    val episodeListRaw = s["episodeList"] ?: s["episodes"] ?: s["list"] ?: s["items"]
                    if (episodeListRaw is List<*>) {
                        for (ep in episodeListRaw) {
                            if (ep is Map<*, *>) {
                                val epNum = (ep["episode"] ?: ep["episodeNumber"] ?: ep["ep"] ?: 1).toString().toDoubleOrNull()?.toInt() ?: 1
                                val epTitle = (ep["title"] ?: ep["name"] ?: "Episode $epNum").toString()
                                val epDuration = (ep["duration"] ?: ep["runtime"] ?: "").toString()
                                val epStill = (ep["stillPath"] ?: ep["poster"] ?: "").toString()
                                epsList.add(EpisodeInfo(episode = epNum, title = epTitle, duration = epDuration, stillPath = epStill))
                            }
                        }
                    }
                    seasonList.add(SeasonInfo(season = seasonNum, episodeList = epsList))
                }
            }
        }

        // Dubs mapping
        val dubsList = mutableListOf<String>()
        val dubsRaw = item["dubs"] ?: item["audioList"] ?: item["languages"] ?: item["audio"] ?: map["dubs"]
        if (dubsRaw is List<*>) {
            for (d in dubsRaw) {
                if (d is Map<*, *>) {
                    val lanName = (d["lanName"] ?: d["name"] ?: d["language"] ?: "").toString().trim()
                    if (lanName.isNotEmpty()) dubsList.add(lanName)
                } else if (d != null && d.toString().isNotBlank()) {
                    dubsList.add(d.toString().trim())
                }
            }
        } else if (dubsRaw is String && dubsRaw.isNotBlank()) {
            dubsList.addAll(dubsRaw.split(",").map { it.trim() }.filter { it.isNotEmpty() })
        }

        return MovieSubject(
            id = id,
            name = name,
            poster = posterUrl,
            description = description,
            rating = rating,
            year = year,
            genres = genresList,
            isTvShow = isTvShow,
            totalSeasons = totalSeasons,
            duration = duration,
            cast = castList,
            dubs = dubsList,
            seasonList = seasonList,
            resourceDetectors = detectorsList
        )
    }

    @ToJson
    fun toJson(subject: MovieSubject): Map<String, Any?> {
        return mapOf(
            "id" to subject.id,
            "name" to subject.name,
            "poster" to subject.poster,
            "description" to subject.description,
            "rating" to subject.rating,
            "year" to subject.year,
            "genres" to subject.genres,
            "isTvShow" to subject.isTvShow,
            "totalSeasons" to subject.totalSeasons,
            "duration" to subject.duration,
            "cast" to subject.cast,
            "dubs" to subject.dubs,
            "seasonList" to subject.seasonList.map { s ->
                mapOf(
                    "season" to s.season,
                    "episodeList" to s.episodeList.map { ep ->
                        mapOf(
                            "episode" to ep.episode,
                            "title" to ep.title,
                            "duration" to ep.duration,
                            "stillPath" to ep.stillPath
                        )
                    }
                )
            },
            "resourceDetectors" to subject.resourceDetectors.map { det ->
                mapOf(
                    "resourceId" to det.resourceId,
                    "name" to det.name
                )
            }
        )
    }
}

@JsonClass(generateAdapter = true)
data class WatchlistItemSubject(
    val subjectId: String = "",
    val subjectType: Int = 1,
    val title: String = "",
    val cover: Map<String, String>? = null,
    val imdbRatingValue: String = "",
    val releaseDate: String = ""
)

@JsonClass(generateAdapter = true)
data class WatchlistItem(
    val type: Int = 0,
    val subject: WatchlistItemSubject? = null
)

@JsonClass(generateAdapter = true)
data class WatchlistData(
    val items: List<WatchlistItem> = emptyList()
)

@JsonClass(generateAdapter = true)
data class WatchlistResponse(
    val code: Int = 0,
    val message: String = "",
    val data: WatchlistData? = null
)

@JsonClass(generateAdapter = true)
data class WantToSeeRequest(
    val subjectId: String,
    val action: Int,
    val subjectType: Int
)

@JsonClass(generateAdapter = true)
data class CommonResponse(
    val code: Int = 0,
    val message: String = ""
)

@JsonClass(generateAdapter = true)
data class SearchSuggestionsResponse(
    val code: Int = 0,
    val data: List<String> = emptyList()
)
