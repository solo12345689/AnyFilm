package com.example.data.network

import android.util.Base64
import com.example.data.model.*
import com.example.data.preferences.PreferencesManager
import com.squareup.moshi.JsonClass
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@JsonClass(generateAdapter = true)
data class GetListRequest(
    val categoryId: Int,
    val page: Int,
    val pageSize: Int
)

interface MovieBoxApi {
    @GET("wefeed-mobile-bff/subject-api/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): SearchResponse

    @POST("wefeed-mobile-bff/subject-api/detail-rec")
    suspend fun getDetailRec(
        @Body request: Map<String, @JvmSuppressWildcards Any>
    ): HomeListResponse

    @GET("wefeed-mobile-bff/subject-api/get")
    suspend fun getDetail(
        @Query("subjectId") subjectId: String,
        @Query("host") host: String = "api6.aoneroom.com"
    ): SubjectDetailResponse

    @GET("wefeed-mobile-bff/subject-api/play-info")
    suspend fun playInfo(
        @Query("subjectId") subjectId: String,
        @Query("se") season: Int,
        @Query("ep") episode: Int,
        @Query("quality") quality: String = "1080p",
        @Query("resourceId") resourceId: String? = null
    ): PlayInfo

    @GET("wefeed-mobile-bff/subject-api/trending/v2")
    suspend fun getTrending(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int
    ): HomeListResponse

    @POST("home/v2/get-list")
    suspend fun getList(
        @Body request: GetListRequest
    ): HomeListResponse

    @POST("login")
    suspend fun login(
        @Body request: LoginApiRequest
    ): AuthResponse

    @POST("register")
    suspend fun register(
        @Body request: RegisterApiRequest
    ): AuthResponse

    @POST("request-otp")
    suspend fun requestOtp(
        @Body request: OtpApiRequest
    ): AuthResponse

    @POST("logout")
    suspend fun logout(): AuthResponse

    @POST("modify")
    suspend fun modifyProfile(
        @Body request: Map<String, String>
    ): CommonResponse
    
    @POST("upload-avatar")
    suspend fun uploadAvatarLocal(
        @Body request: Map<String, String>
    ): Map<String, Any?>

    @GET("wefeed-mobile-bff/upload/sts-token")
    suspend fun getStsToken(): Map<String, Any?>

    @GET("user-info")
    suspend fun getUserInfo(): UserInfoResponse

    @POST("wefeed-mobile-bff/subject-api/want-to-see")
    suspend fun toggleWatchlist(
        @Body request: com.example.data.model.WantToSeeRequest
    ): com.example.data.model.CommonResponse

    @GET("wefeed-mobile-bff/subject-api/see-list-v2")
    suspend fun getWatchlist(
        @Query("page") page: Int,
        @Query("pageSize") pageSize: Int,
        @Query("seeType") seeType: Int = 1
    ): com.example.data.model.WatchlistResponse

    @GET("search-suggestions")
    suspend fun getSearchSuggestions(
        @Query("q") query: String? = null
    ): com.example.data.model.SearchSuggestionsResponse
}

fun md5(input: String): String {
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(input.toByteArray())
    return digest.joinToString("") { String.format("%02x", it) }
}

fun hmacMd5(keyBytes: ByteArray, dataBytes: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacMD5")
    val secretKey = SecretKeySpec(keyBytes, "HmacMD5")
    mac.init(secretKey)
    return mac.doFinal(dataBytes)
}

class MovieBoxSignatureInterceptor(private val prefs: PreferencesManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val mockHost = prefs.mockHost

        if (mockHost.isNotEmpty()) {
            val originalUrl = originalRequest.url
            val pathSegments = originalUrl.pathSegments
            val fullPath = pathSegments.joinToString("/")
            
            val newUrlBuilder = originalUrl.newBuilder()
            mockHost.toHttpUrlOrNull()?.let { mockUrl ->
                newUrlBuilder.scheme(mockUrl.scheme)
                newUrlBuilder.host(mockUrl.host)
                newUrlBuilder.port(mockUrl.port)
            }
            var isPostToGet = false
            var requestBodyString = ""

            if (fullPath.contains("wefeed-mobile-bff/subject-api/search") || fullPath.endsWith("search")) {
                val q = originalUrl.queryParameter("keyword") ?: originalUrl.queryParameter("q") ?: ""
                val page = originalUrl.queryParameter("page") ?: "1"
                newUrlBuilder.encodedPath("/search")
                    .setQueryParameter("q", q)
                    .setQueryParameter("keyword", q)
                    .setQueryParameter("page", page)
                    .removeAllQueryParameters("pageSize")
            } else if (fullPath.contains("wefeed-mobile-bff/subject-api/get") || fullPath.endsWith("get")) {
                val subjectId = originalUrl.queryParameter("subjectId") ?: ""
                newUrlBuilder.encodedPath("/detail/$subjectId")
                    .removeAllQueryParameters("subjectId")
                    .removeAllQueryParameters("host")
            } else if (fullPath.contains("wefeed-mobile-bff/subject-api/play-info") || fullPath.endsWith("play-info")) {
                val subjectId = originalUrl.queryParameter("subjectId") ?: ""
                val se = originalUrl.queryParameter("se") ?: "1"
                val ep = originalUrl.queryParameter("ep") ?: "1"
                val quality = originalUrl.queryParameter("quality") ?: "720p"
                val resourceId = originalUrl.queryParameter("resourceId")
                
                newUrlBuilder.encodedPath("/stream/$subjectId")
                    .setQueryParameter("season", se)
                    .setQueryParameter("episode", ep)
                    .setQueryParameter("quality", quality)
                    .apply {
                        if (resourceId != null) {
                            setQueryParameter("resource_id", resourceId)
                        }
                    }
                    .removeAllQueryParameters("subjectId")
                    .removeAllQueryParameters("se")
                    .removeAllQueryParameters("ep")
                    .removeAllQueryParameters("quality")
                    .removeAllQueryParameters("resourceId")
            } else if (fullPath.contains("wefeed-mobile-bff/subject-api/trending/v2") || fullPath.contains("trending")) {
                newUrlBuilder.encodedPath("/trending")
                    .removeAllQueryParameters("page")
                    .removeAllQueryParameters("pageSize")
            } else if (fullPath.contains("wefeed-mobile-bff/subject-api/detail-rec") || fullPath.endsWith("detail-rec")) {
                val body = originalRequest.body
                if (body != null) {
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    requestBodyString = buffer.readUtf8()
                }
                val subjectId = Regex("\"subjectId\"\\s*:\\s*\"?(\\d+)\"?").find(requestBodyString)?.groupValues?.get(1) ?: ""
                newUrlBuilder.encodedPath("/detail-rec")
                    .setQueryParameter("subjectId", subjectId)
                isPostToGet = true
            } else if (fullPath.contains("home/v2/get-list") || fullPath.endsWith("get-list")) {
                val body = originalRequest.body
                if (body != null) {
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    requestBodyString = buffer.readUtf8()
                }
                
                val categoryId = Regex("\"categoryId\"\\s*:\\s*(\\d+)").find(requestBodyString)?.groupValues?.get(1)?.toIntOrNull() ?: 2
                val page = Regex("\"page\"\\s*:\\s*(\\d+)").find(requestBodyString)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                
                val endpoint = when (categoryId) {
                    2 -> "movies"
                    8 -> "anime"
                    18 -> "asian"
                    5 -> "western"
                    else -> "category/$categoryId"
                }
                
                newUrlBuilder.encodedPath("/$endpoint")
                    .setQueryParameter("page", page.toString())
                
                isPostToGet = true
            } else if (fullPath.endsWith("login") || fullPath.contains("/login")) {
                newUrlBuilder.encodedPath("/login")
            } else if (fullPath.endsWith("register") || fullPath.contains("/register")) {
                newUrlBuilder.encodedPath("/register")
            } else if (fullPath.endsWith("request-otp") || fullPath.contains("/request-otp")) {
                newUrlBuilder.encodedPath("/request-otp")
            } else if (fullPath.endsWith("logout") || fullPath.contains("/logout")) {
                newUrlBuilder.encodedPath("/logout")
            } else if (fullPath.endsWith("user-info") || fullPath.contains("/user-info")) {
                newUrlBuilder.encodedPath("/user-info")
            } else if (fullPath.contains("search-suggestions") || fullPath.endsWith("search-suggestions")) {
                val q = originalUrl.queryParameter("q")
                newUrlBuilder.encodedPath("/search-suggestions")
                if (q != null) {
                    newUrlBuilder.setQueryParameter("q", q)
                }
            }

            val newUrl = newUrlBuilder.build()
            val builder = originalRequest.newBuilder()
                .url(newUrl)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 12; Pixel 6)")
                .header("Accept", "application/json")

            if (isPostToGet) {
                builder.method("GET", null)
            }

            val response = chain.proceed(builder.build())
            if (response.isSuccessful) {
                val responseBody = response.body
                if (responseBody != null) {
                    val rawJson = responseBody.string()
                    val rewrittenJson = try {
                        val path = newUrl.encodedPath
                        if (path.contains("movies") || path.contains("anime") || path.contains("asian") || path.contains("western") || path.contains("category/")) {
                            flattenCategoryJson(rawJson)
                        } else if (path.contains("trending")) {
                            rewriteTrendingJson(rawJson)
                        } else if (path.contains("stream")) {
                            rewriteStreamJson(rawJson)
                        } else if (path.endsWith("search") || path.contains("/search")) {
                            rewriteSearchJson(rawJson)
                        } else if (path.contains("/detail/")) {
                            val subjectId = path.substringAfter("/detail/").substringBefore("?").substringBefore("/")
                            rewriteDetailJson(rawJson, subjectId, mockHost)
                        } else {
                            rawJson
                        }
                    } catch (e: Exception) {
                        rawJson
                    }
                    
                    val contentType = responseBody.contentType()
                    val newBody = okhttp3.ResponseBody.create(contentType, rewrittenJson)
                    return response.newBuilder().body(newBody).build()
                }
            }
            return response
        }

        val timestamp = System.currentTimeMillis().toString()
        val reversed = timestamp.reversed()
        val clientToken = "$timestamp,${md5(reversed)}"

        // Build sorted query string
        val url = originalRequest.url
        val sortedQueryString = url.queryParameterNames.sorted().joinToString("&") { name ->
            val value = url.queryParameter(name) ?: ""
            "$name=$value"
        }

        // Compute MD5 of request body and body length (exactly matching the python utils.py logic)
        val accept = "application/json"
        val contentType = "application/json;charset=UTF-8"
        var actualBodyLength = ""
        var bodyHash = ""
        val requestBody = originalRequest.body
        if (requestBody != null) {
            try {
                val buffer = okio.Buffer()
                requestBody.writeTo(buffer)
                val bytes = buffer.readByteArray()
                if (bytes.isNotEmpty()) {
                    actualBodyLength = bytes.size.toString()
                    val limit = minOf(bytes.size, 102400)
                    val subBytes = bytes.copyOfRange(0, limit)
                    val md = MessageDigest.getInstance("MD5")
                    val digest = md.digest(subBytes)
                    bodyHash = digest.joinToString("") { String.format("%02x", it) }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val method = originalRequest.method
        val canonicalPathAndQuery = url.encodedPath + if (sortedQueryString.isNotEmpty()) "?$sortedQueryString" else ""

        // Formulate Canonical String
        val canonicalString = "$method\n$accept\n$contentType\n$actualBodyLength\n$timestamp\n$bodyHash\n$canonicalPathAndQuery"

        // Cryptographic App Signature
        var signatureHeader = ""
        try {
            val keyStr = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"
            val keyBytes = try {
                Base64.decode(keyStr, Base64.DEFAULT)
            } catch (e: Exception) {
                keyStr.toByteArray()
            }
            val signatureDigest = hmacMd5(keyBytes, canonicalString.toByteArray())
            val base64Sig = Base64.encodeToString(signatureDigest, Base64.NO_WRAP)
            signatureHeader = "$timestamp|2|$base64Sig"
        } catch (e: Exception) {
            // Ignore
        }

        // Construct request headers
        val builder = originalRequest.newBuilder()
            .header("User-Agent", "MovieBoxPro/16.2.1 (Android 12; Pixel 6)")
            .header("X-M-Version", "16.2.1")
            .header("Accept", accept)
            .header("Content-Type", contentType)
            .header("Referer", "https://api6.aoneroom.com/")
            .header("X-Client-Token", clientToken)
            .header("x-tr-signature", signatureHeader)
            .header("X-Play-Mode", "2")

        // Serialized device metadata (parities)
        val spCode = if (prefs.spCode.isNotEmpty()) prefs.spCode else "40401"
        val region = if (prefs.localIso.isNotEmpty()) prefs.localIso.uppercase() else "IN"
        val timezone = if (region == "US") "America/New_York" else "Asia/Kolkata"
        val clientInfoJson = """
            {
              "package_name": "com.community.oneroom",
              "version_name": "3.0.03.0529.03",
              "version_code": 50020042,
              "os": "android",
              "os_version": "12",
              "install_ch": "ps",
              "device_id": "8c5da15be6ca34e724a27bc102cd8bcf",
              "install_store": "ps",
              "gaid": "4b6c3748-8dfa-4fb6-bda9-163a98e8dfa9",
              "brand": "Google",
              "model": "Pixel 6",
              "system_language": "en",
              "net": "NETWORK_WIFI",
              "region": "$region",
              "timezone": "$timezone",
              "sp_code": "$spCode",
              "X-Play-Mode": "2"
            }
        """.trimIndent().replace("\n", "").replace(" ", "")

        builder.header("X-Client-Info", clientInfoJson)

        return chain.proceed(builder.build())
    }
}

fun flattenCategoryJson(jsonStr: String): String {
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val mapAdapter = moshi.adapter(Map::class.java)
    val parsed = mapAdapter.fromJson(jsonStr) as? Map<String, Any?> ?: return jsonStr
    
    val data = parsed["data"] as? Map<String, Any?> ?: return jsonStr
    val rawSections = (data["sections"] as? List<Map<String, Any?>>)
    val rawList = (data["list"] as? List<Map<String, Any?>>) 
        ?: (data["items"] as? List<Map<String, Any?>>) 
        ?: emptyList()
    
    val allItems = mutableListOf<Map<String, Any?>>()
    val validSections = mutableListOf<Map<String, Any?>>()

    if (rawSections != null && rawSections.isNotEmpty()) {
        validSections.addAll(rawSections)
        rawSections.forEach { section ->
            val items = section["items"] as? List<Map<String, Any?>>
            if (items != null) {
                allItems.addAll(items)
            }
        }
    }

    if (allItems.isEmpty() && rawList.isNotEmpty()) {
        rawList.forEach { entry ->
            val items = entry["items"] as? List<Map<String, Any?>>
            if (items != null) {
                allItems.addAll(items)
                validSections.add(entry)
            } else {
                allItems.add(entry)
            }
        }
    }

    val responseMap = mapOf(
        "code" to 200,
        "message" to "success",
        "data" to mapOf(
            "sections" to validSections,
            "list" to allItems,
            "items" to allItems
        )
    )
    return mapAdapter.toJson(responseMap)
}

fun rewriteTrendingJson(jsonStr: String): String {
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val mapAdapter = moshi.adapter(Map::class.java)
    val parsed = mapAdapter.fromJson(jsonStr) as? Map<String, Any?> ?: return jsonStr
    
    val items = parsed["data"] as? List<Map<String, Any?>> ?: emptyList()
    
    val responseMap = mapOf(
        "code" to 200,
        "message" to "success",
        "data" to mapOf(
            "list" to items,
            "items" to items
        )
    )
    return mapAdapter.toJson(responseMap)
}

fun rewriteStreamJson(jsonStr: String): String {
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val mapAdapter = moshi.adapter(Map::class.java)
    val parsed = mapAdapter.fromJson(jsonStr) as? Map<String, Any?> ?: return jsonStr
    
    val url = parsed["url"] as? String ?: ""
    val cookie = parsed["cookie"] as? String ?: ""
    val resolutions = parsed["resolutions"] as? String ?: ""
    
    val streamList = mutableListOf<Map<String, Any?>>()
    val serverStreamList = (parsed["streamList"] ?: parsed["streams"]) as? List<Map<String, Any?>>
    
    // If serverStreamList has more than 2 items (Auto + multiple resolutions), use it directly
    if (!serverStreamList.isNullOrEmpty() && serverStreamList.size > 2) {
        streamList.addAll(serverStreamList)
    } else if (url.isNotEmpty()) {
        val parsedRes = if (resolutions.isNotEmpty()) {
            resolutions.split(",")
                .mapNotNull { it.trim().filter { c -> c.isDigit() }.toIntOrNull() }
                .distinct()
        } else {
            emptyList()
        }
        val allRes = if (parsedRes.isNotEmpty()) {
            parsedRes.sortedDescending()
        } else {
            listOf(1080, 720, 480)
        }
        
        streamList.add(mapOf(
            "quality" to "Auto",
            "url" to url,
            "size" to "Auto",
            "cookie" to cookie
        ))
        for (res in allRes) {
            val matching = serverStreamList?.find {
                val qDigits = (it["quality"] as? String)?.filter { c -> c.isDigit() }?.toIntOrNull()
                qDigits == res
            }
            val sUrl = matching?.get("url")?.toString() ?: url
            val sCookie = matching?.get("cookie")?.toString() ?: cookie
            streamList.add(mapOf(
                "quality" to "${res}p",
                "url" to sUrl,
                "size" to (matching?.get("size")?.toString() ?: "${res}p HD"),
                "cookie" to sCookie
            ))
        }
    } else if (!serverStreamList.isNullOrEmpty()) {
        streamList.addAll(serverStreamList)
    }
    
    val subtitles = (parsed["subtitles"] ?: parsed["subTitleList"]) as? List<Map<String, Any?>> ?: emptyList()
    val subTitleList = subtitles.map { sub ->
        val lan = sub["lan"] as? String ?: ""
        val lanName = sub["lanName"] as? String ?: ""
        val subUrl = sub["url"] as? String ?: ""
        mapOf(
            "language" to (lanName.ifEmpty { lan.ifEmpty { "English" } }),
            "url" to subUrl,
            "format" to (if (subUrl.endsWith(".vtt", ignoreCase = true)) "vtt" else "srt")
        )
    }
    
    val responseMap = mapOf(
        "streamList" to streamList,
        "subTitleList" to subTitleList
    )
    return mapAdapter.toJson(responseMap)
}

fun rewriteSearchJson(jsonStr: String): String {
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val mapAdapter = moshi.adapter(Map::class.java)
    val parsed = mapAdapter.fromJson(jsonStr) as? Map<String, Any?> ?: return jsonStr
    val mutableParsed = parsed.toMutableMap()
    
    // Change code to 200 to satisfy client checks
    mutableParsed["code"] = 200
    mutableParsed["message"] = "success"
    
    return mapAdapter.toJson(mutableParsed)
}

fun rewriteDetailJson(jsonStr: String, subjectId: String, mockHost: String): String {
    val moshi = com.squareup.moshi.Moshi.Builder().build()
    val mapAdapter = moshi.adapter(Map::class.java)
    val parsed = mapAdapter.fromJson(jsonStr) as? Map<String, Any?> ?: return jsonStr
    val mutableParsed = parsed.toMutableMap()
    
    // Change code to 200
    mutableParsed["code"] = 200
    mutableParsed["message"] = "success"
    
    val data = mutableParsed["data"] as? Map<String, Any?> ?: return mapAdapter.toJson(mutableParsed)
    val mutableData = data.toMutableMap()
    
    // Check if we need to parse and copy resourceDetectors
    val languages = mutableData["languages"] as? List<Map<String, Any?>>
    if (languages != null) {
        val resourceDetectors = mutableListOf<Map<String, Any?>>()
        for (lang in languages) {
            val type = lang["type"] as? String
            if (type == "resource") {
                val resourceId = lang["id"] as? String
                val name = lang["name"] as? String
                if (resourceId != null) {
                    resourceDetectors.add(mapOf<String, Any?>(
                        "resourceId" to resourceId,
                        "name" to (name ?: "Resource")
                    ))
                }
            }
        }
        if (resourceDetectors.isNotEmpty()) {
            mutableData["resourceDetectors"] = resourceDetectors
        }
    }
    
    // Check if it's a TV show and we need to fetch episodes
    val subjectTypeVal = mutableData["subjectType"] ?: mutableData["type"]
    val isTvShow = (subjectTypeVal?.toString()?.toDoubleOrNull()?.toInt() == 2)
    
    if (isTvShow) {
        try {
            // We make a synchronous HTTP request to fetch episodes from the mock server
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            // Build the URL for the /episodes/{series_id} endpoint
            val epUrl = "$mockHost/episodes/$subjectId"
            val request = okhttp3.Request.Builder()
                .url(epUrl)
                .header("User-Agent", "MovieBoxPro/16.2.1 (Android 12; Pixel 6)")
                .build()
            
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val epJson = response.body?.string()
                if (!epJson.isNullOrEmpty()) {
                    val epParsed = mapAdapter.fromJson(epJson) as? Map<String, Any?>
                    val epData = epParsed?.get("data") as? Map<String, Any?>
                    val seasons = epData?.get("seasons") as? List<Map<String, Any?>>
                    if (seasons != null) {
                        val seasonList = mutableListOf<Map<String, Any?>>()
                        for (s in seasons) {
                            val seasonNumberVal = s["seasonNumber"] ?: s["season"] ?: s["se"] ?: 1
                            val seasonNumber = seasonNumberVal.toString().toDoubleOrNull()?.toInt() ?: 1
                            
                            val epsList = s["episodes"] as? List<Map<String, Any?>>
                            if (epsList != null) {
                                val episodeList = mutableListOf<Map<String, Any?>>()
                                for (ep in epsList) {
                                    val episodeNumberVal = ep["episodeNumber"] ?: ep["episode"] ?: ep["ep"] ?: 1
                                    val episodeNumber = episodeNumberVal.toString().toDoubleOrNull()?.toInt() ?: 1
                                    val title = ep["title"] ?: "Episode $episodeNumber"
                                    
                                    episodeList.add(mapOf<String, Any?>(
                                        "episode" to episodeNumber,
                                        "title" to title,
                                        "duration" to (ep["duration"] ?: ""),
                                        "stillPath" to (ep["stillPath"] ?: "")
                                    ))
                                }
                                seasonList.add(mapOf<String, Any?>(
                                    "season" to seasonNumber,
                                    "episodeList" to episodeList
                                ))
                            }
                        }
                        mutableData["seasonList"] = seasonList
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    mutableParsed["data"] = mutableData
    return mapAdapter.toJson(mutableParsed)
}

class CookieInterceptor(private val prefs: PreferencesManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder()
        val savedCookie = prefs.sessionIdCookie
        if (savedCookie.isNotEmpty()) {
            builder.header("Cookie", savedCookie)
        }
        val response = chain.proceed(builder.build())
        val cookies = response.headers("Set-Cookie")
        if (cookies.isNotEmpty()) {
            val sessionCookie = cookies.firstOrNull { it.contains("session_id") }
            if (sessionCookie != null) {
                val cleanCookie = sessionCookie.substringBefore(";")
                prefs.sessionIdCookie = cleanCookie
            }
        }
        return response
    }
}

object MovieBoxRetrofitClient {
    private const val BASE_URL = "https://api6.aoneroom.com/"

    fun getApi(prefs: PreferencesManager): MovieBoxApi {
        val client = OkHttpClient.Builder()
            .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .addInterceptor(CookieInterceptor(prefs))
            .addInterceptor(MovieBoxSignatureInterceptor(prefs))
            .build()

        val moshi = com.squareup.moshi.Moshi.Builder()
            .add(MovieSubjectAdapter())
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()

        return retrofit.create(MovieBoxApi::class.java)
    }
}
