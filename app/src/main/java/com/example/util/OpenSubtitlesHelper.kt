package com.example.util

import android.util.Log
import android.util.LruCache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * OpenSubtitles v1 REST API Integration & Fallback Helper
 * Reverse-engineered from MovieBox (com.transsion.subtitle.helper.d)
 *
 * Provides a secondary subtitle delivery tier when MovieBox internal
 * CDN subtitles (get-ext-captions / subTitleList) are missing or empty.
 */
object OpenSubtitlesHelper {

    private const val TAG = "OpenSubtitlesHelper"
    private const val BASE_URL = "https://api.opensubtitles.com/api/v1/"
    private const val API_KEY = "ZRN9QpqcTxje5UH3bV1RuugwitxkqB53"
    private const val USER_AGENT = "player2024"

    // In-memory cache for temporary direct .srt download links (prevents consuming the 5-download daily quota repeatedly)
    private val downloadLinkCache = LruCache<String, String>(60)

    data class SubtitleResult(
        val language: String,
        val languageName: String,
        val url: String,
        val format: String = "srt"
    )

    /**
     * Searches OpenSubtitles for the given movie/show and acquires a direct .srt stream link.
     */
    fun fetchSubtitle(
        client: OkHttpClient,
        title: String,
        isMovie: Boolean,
        season: Int? = null,
        episode: Int? = null,
        language: String = "en"
    ): SubtitleResult? {
        if (title.isBlank()) return null

        // Clean up title: strip non-alphanumeric noise, (Hindi Dub), brackets, etc.
        val cleanedTitle = title
            .replace(Regex("""(?i)\[.*?\]|\(.*?\)|hindi\s*dub|dubbed|season\s*\d+"""), "")
            .trim()

        if (cleanedTitle.isBlank()) return null

        val cacheKey = "$cleanedTitle-${season ?: 1}-${episode ?: 1}-$language"
        downloadLinkCache.get(cacheKey)?.let { cachedUrl ->
            Log.d(TAG, "Cache HIT for OpenSubtitles: $cacheKey")
            return SubtitleResult(
                language = language,
                languageName = if (language == "en") "English (OpenSubtitles)" else "$language (OpenSubtitles)",
                url = cachedUrl
            )
        }

        return try {
            // Step 1: Search Subtitles (Strict alphabetical parameter order for Cloudflare canonical edge)
            val encodedQuery = URLEncoder.encode(cleanedTitle, "UTF-8")
            val searchUrl = if (isMovie || season == null || episode == null) {
                // Alphabetical: languages, query
                "${BASE_URL}subtitles?languages=$language&query=$encodedQuery"
            } else {
                // Alphabetical: episode_number, languages, query, season_number
                "${BASE_URL}subtitles?episode_number=$episode&languages=$language&query=$encodedQuery&season_number=$season"
            }

            val searchRequest = Request.Builder()
                .url(searchUrl)
                .header("Api-Key", API_KEY)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val searchResponse = client.newCall(searchRequest).execute()
            if (!searchResponse.isSuccessful) {
                Log.w(TAG, "Search request failed with HTTP ${searchResponse.code}")
                return null
            }

            val searchJsonStr = searchResponse.body?.string() ?: return null
            val searchJson = JSONObject(searchJsonStr)
            val dataArray = searchJson.optJSONArray("data")
            if (dataArray == null || dataArray.length() == 0) {
                Log.d(TAG, "No subtitle matches found on OpenSubtitles for '$cleanedTitle'")
                return null
            }

            // Extract file_id from the best match
            var targetFileId: Long? = null
            var matchedFileName = "$cleanedTitle.srt"

            for (i in 0 until dataArray.length()) {
                val item = dataArray.optJSONObject(i) ?: continue
                val attributes = item.optJSONObject("attributes") ?: continue
                val files = attributes.optJSONArray("files") ?: continue
                if (files.length() > 0) {
                    val fileObj = files.optJSONObject(0) ?: continue
                    val fid = fileObj.optLong("file_id", -1L)
                    if (fid > 0) {
                        targetFileId = fid
                        matchedFileName = fileObj.optString("file_name", matchedFileName)
                        break
                    }
                }
            }

            if (targetFileId == null) {
                Log.d(TAG, "No valid file_id found in subtitle search results")
                return null
            }

            // Step 2: Request Download Ticket
            val downloadUrl = "${BASE_URL}download"
            val jsonBody = JSONObject().apply {
                put("file_id", targetFileId)
            }.toString()

            val downloadRequest = Request.Builder()
                .url(downloadUrl)
                .header("Api-Key", API_KEY)
                .header("User-Agent", USER_AGENT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            val downloadResponse = client.newCall(downloadRequest).execute()
            if (!downloadResponse.isSuccessful) {
                Log.w(TAG, "Download ticket request failed with HTTP ${downloadResponse.code}")
                return null
            }

            val downloadJsonStr = downloadResponse.body?.string() ?: return null
            val downloadJson = JSONObject(downloadJsonStr)
            val directLink = downloadJson.optString("link", "")

            if (directLink.isNotBlank()) {
                val remaining = downloadJson.optInt("remaining", -1)
                Log.i(TAG, "OpenSubtitles Ticket Success! Direct stream link ready. Remaining daily quota: $remaining")
                downloadLinkCache.put(cacheKey, directLink)

                SubtitleResult(
                    language = language,
                    languageName = if (language == "en") "English (OpenSubtitles)" else "$language (OpenSubtitles)",
                    url = directLink
                )
            } else {
                Log.w(TAG, "Download ticket response did not contain a valid stream link")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "OpenSubtitles integration error: ${e.message}")
            null
        }
    }
}

