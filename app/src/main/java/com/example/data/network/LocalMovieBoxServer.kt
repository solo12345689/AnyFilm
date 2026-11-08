package com.example.data.network

import android.content.Context

import android.util.Base64

import android.util.Log

import com.squareup.moshi.Moshi

import kotlinx.coroutines.*

import okhttp3.HttpUrl

import okhttp3.HttpUrl.Companion.toHttpUrl

import okhttp3.MediaType.Companion.toMediaTypeOrNull

import okhttp3.OkHttpClient

import okhttp3.Request

import okhttp3.RequestBody

import java.io.BufferedReader

import java.io.InputStreamReader

import java.io.OutputStream

import java.net.ServerSocket

import java.net.Socket

import java.net.URLDecoder

import java.nio.charset.StandardCharsets

import java.security.MessageDigest

import javax.crypto.Mac

import javax.crypto.spec.SecretKeySpec

class LocalMovieBoxServer(private val context: Context, private val port: Int = 3000) {
    companion object {
        @Volatile

        private var guestToken: String? = null

    }

    private var serverSocket: ServerSocket? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var isRunning = false

    private val moshi = Moshi.Builder().build()

    private val mapAdapter = moshi.adapter(Map::class.java)

    private val okHttpClient = OkHttpClient.Builder()

        .connectionPool(okhttp3.ConnectionPool(10, 5, java.util.concurrent.TimeUnit.MINUTES))
        .connectTimeout(25, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)

        .build()

    private val responseCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()

    private val diskCachePrefs = context.getSharedPreferences("moviebox_api_cache", Context.MODE_PRIVATE)

    fun start() {
        if (isRunning) return

        isRunning = true

        scope.launch {
            try {
                val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

                val userToken = prefs.getString("user_auth_token", null)

                if (isTokenExpired(userToken)) {
                    prefs.edit().remove("user_auth_token").remove("user_info_json").apply()

                }

                val visitorToken = prefs.getString("visitor_token", null)

                if (isTokenExpired(visitorToken)) {
                    guestToken = null

                    prefs.edit().remove("visitor_token").apply()

                    bootstrapGuestToken()

                } else {
                    guestToken = visitorToken

                }

            } catch (e: Exception) {
                Log.e("LocalMovieBoxServer", "Pre-bootstrap token error", e)

            }

        }

        scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true

                    bind(java.net.InetSocketAddress(port))

                }

                Log.i("LocalMovieBoxServer", "Server started on 127.0.0.1:$port")

                while (isActive && isRunning) {
                    val socket = serverSocket?.accept() ?: break

                    socket.tcpNoDelay = true // Disable Nagle algorithm: send data packets immediately without delay

                    socket.sendBufferSize = 64 * 1024

                    socket.receiveBufferSize = 64 * 1024

                    launch {
                        handleClient(socket)

                    }

                }

            } catch (e: Exception) {
                Log.e("LocalMovieBoxServer", "Error in server socket loop", e)

            }

        }

    }

    fun stop() {
        isRunning = false

        try {
            serverSocket?.close()

        } catch (e: Exception) {
            // Ignore

        }

        scope.cancel()

    }

    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.IO) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.inputStream))

                val line = reader.readLine() ?: return@withContext

                val parts = line.split(" ")

                if (parts.size < 2) return@withContext

                val method = parts[0]

                val pathAndQuery = parts[1]

                // Read headers

                var contentLength = 0

                val headersMap = mutableMapOf<String, String>()

                var lineHeader = reader.readLine()

                while (lineHeader != null && lineHeader.isNotEmpty()) {
                    val colonIdx = lineHeader.indexOf(':')

                    if (colonIdx != -1) {
                        val headerName = lineHeader.substring(0, colonIdx).trim()

                        val headerVal = lineHeader.substring(colonIdx + 1).trim()

                        headersMap[headerName.lowercase()] = headerVal

                        if (headerName.equals("Content-Length", ignoreCase = true)) {
                            contentLength = headerVal.toIntOrNull() ?: 0

                        }

                    }

                    lineHeader = reader.readLine()

                }

                // Read body if POST

                val body = if (contentLength > 0) {
                    val buffer = CharArray(contentLength)

                    var totalRead = 0

                    while (totalRead < contentLength) {
                        val read = reader.read(buffer, totalRead, contentLength - totalRead)

                        if (read == -1) break

                        totalRead += read

                    }

                    String(buffer, 0, totalRead)

                } else {
                    ""

                }

                val questionMarkIndex = pathAndQuery.indexOf('?')

                val path = if (questionMarkIndex != -1) pathAndQuery.substring(0, questionMarkIndex) else pathAndQuery

                val query = if (questionMarkIndex != -1) pathAndQuery.substring(questionMarkIndex + 1) else ""

                if (path == "/cast-proxy") {
                    handleCastProxy(socket, method, query, headersMap)

                    return@withContext

                }

                Log.i("LocalMovieBoxServer", "Incoming: $method $pathAndQuery")

                val responsePair = routeRequest(method, path, query, body)

                val contentType = when {
                    path == "/docs" || path == "/docs/" -> "text/html; charset=utf-8"

                    path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"

                    path.endsWith(".png") -> "image/png"

                    else -> "application/json; charset=utf-8"

                }

                sendResponse(socket, responsePair.first, responsePair.second, contentType)

            } catch (e: Exception) {
                Log.e("LocalMovieBoxServer", "Error handling client", e)

                try {
                    sendResponse(socket, 500, "{\"code\": 500, \"msg\": \"Internal Server Error\"}")

                } catch (ex: Exception) {}

            } finally {
                try {
                    socket.close()

                } catch (e: Exception) {}

            }

        }

    }

    private fun handleCastProxy(

        socket: Socket,

        method: String,

        query: String,

        clientHeaders: Map<String, String>

    ) {
        val qParams = parseQueryParams(query)

        val targetUrl = qParams["url"] ?: run {
            sendResponse(socket, 400, "{\"error\": \"Missing url parameter\"}")

            return

        }

        val cookie = qParams["cookie"] ?: ""

        val requestBuilder = Request.Builder().url(targetUrl)

        requestBuilder.header("User-Agent", "MovieBox/4.0.02 (Android 14; Pixel 6)")

        if (cookie.isNotBlank()) {
            requestBuilder.header("Cookie", cookie)

        }

        val rangeHeader = clientHeaders["range"]

        if (!rangeHeader.isNullOrBlank()) {
            requestBuilder.header("Range", rangeHeader)

        }

        if (method.equals("HEAD", ignoreCase = true)) {
            requestBuilder.head()

        }

        try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()

            val output: OutputStream = socket.getOutputStream()

            val code = response.code

            val contentType = response.header("Content-Type") ?: if (targetUrl.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"

            val body = response.body

            val contentLength = body?.contentLength() ?: -1L

            val sb = StringBuilder()

            sb.append("HTTP/1.1 ").append(code).append(" ").append(response.message.ifBlank { "OK" }).append("\r\n")

            sb.append("Content-Type: ").append(contentType).append("\r\n")

            if (contentLength >= 0) {
                sb.append("Content-Length: ").append(contentLength).append("\r\n")

            }

            sb.append("Accept-Ranges: bytes\r\n")

            response.header("Content-Range")?.let {
                sb.append("Content-Range: ").append(it).append("\r\n")

            }

            sb.append("Access-Control-Allow-Origin: *\r\n")

            sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")

            sb.append("Access-Control-Allow-Headers: Range, Origin, Accept, Content-Type\r\n")

            sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")

            sb.append("Connection: close\r\n\r\n")

            output.write(sb.toString().toByteArray(StandardCharsets.UTF_8))

            output.flush()

            if (!method.equals("HEAD", ignoreCase = true) && body != null) {
                val inputStream = body.byteStream()

                val buffer = ByteArray(64 * 1024)

                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)

                }

                output.flush()

            }

            response.close()

        } catch (e: Exception) {
            Log.e("LocalMovieBoxServer", "Cast proxy stream error: ${e.message}")

        }

    }

     private fun sendResponse(socket: Socket, statusCode: Int, body: String, contentType: String = "application/json; charset=utf-8") {
         val output: OutputStream = socket.getOutputStream()

         val bodyBytes = if (contentType.startsWith("image/")) {
             body.toByteArray(Charsets.ISO_8859_1)

         } else {
             body.toByteArray(StandardCharsets.UTF_8)

         }

         val responseHeaders = "HTTP/1.1 $statusCode OK\r\n" +

                 "Content-Type: $contentType\r\n" +

                 "Content-Length: ${bodyBytes.size}\r\n" +

                 "Connection: close\r\n" +

                 "Access-Control-Allow-Origin: *\r\n" +

                 "\r\n"

         output.write(responseHeaders.toByteArray(StandardCharsets.UTF_8))

         output.write(bodyBytes)

         output.flush()

     }

    private fun routeRequest(method: String, path: String, query: String, body: String): Pair<Int, String> {
        return try {
            val qParams = parseQueryParams(query)

            when {
                path == "/clear-cache" -> {
                    responseCache.clear()

                    diskCachePrefs.edit().clear().apply()

                    // Keep guestToken and auth_token untouched

                    Pair(200, "{\"code\": 0, \"message\": \"Cache cleared\"}")

                }

                path == "/docs" || path == "/docs/" -> {
                    Pair(200, DocsHtml.getHtml(port))

                }

                path == "/user-info" -> {
                    val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

                    val userToken = prefs.getString("user_auth_token", null)

                    if (userToken.isNullOrEmpty() || isTokenExpired(userToken)) {
                        Pair(200, "{\"logged_in\": false, \"mode\": \"Guest Access\", \"session_id\": \"local_session\"}")

                    } else {
                        val rawProfile = try {
                            makeOfficialRequest("GET", "/wefeed-mobile-bff/user-api/profile/v2")

                        } catch(e: Exception) { "" }

                        val responseMap = try {
                            parseJsonToMap(rawProfile)

                        } catch(e: Exception) { emptyMap() }

                        val userMap = responseMap["data"] as? Map<String, Any?> ?: run {
                            val userInfoJson = prefs.getString("user_info_json", null)

                            if (!userInfoJson.isNullOrEmpty()) {
                                try {
                                    parseJsonToMap(userInfoJson)

                                } catch (e: Exception) { null }

                            } else null

                        }

                        val userInfo = userMap?.get("userInfo") as? Map<String, Any?>

                        val rawNickname = (userInfo?.get("nickname") ?: userMap?.get("nickname") ?: "").toString()

                        val rawUsername = (userInfo?.get("username") ?: userMap?.get("userName") ?: "").toString()

                        if (userMap == null || rawNickname.startsWith("Tourist ") || rawUsername.startsWith("Tourist ")) {
                            prefs.edit().remove("user_auth_token").remove("user_info_json").apply()

                            Pair(200, "{\"logged_in\": false, \"mode\": \"Guest Access\", \"session_id\": \"local_session\"}")

                        } else {
                            val vipInfo = userMap?.get("vipInfo") as? Map<String, Any?>

                            val mySubject = userMap?.get("mySubject") as? Map<String, Any?>

                            val favoriteInfo = userMap?.get("favoriteInfo") as? Map<String, Any?>

                            val targetUser = mapOf(

                                "username" to (userInfo?.get("username") ?: userMap?.get("userName") ?: rawNickname.ifEmpty { "User" }),

                                "nickname" to rawNickname.ifEmpty { "User" },

                                "avatar" to (userInfo?.get("avatar") ?: userMap?.get("avatarUrl") ?: ""),

                                "email" to (userInfo?.get("mail") ?: userMap?.get("email") ?: ""),

                                "vip" to (if (vipInfo?.get("isActive") == true || userMap?.get("vip")?.toString()?.toDoubleOrNull()?.toInt() == 1) 1 else 0),

                                "userId" to (userInfo?.get("userId") ?: userMap?.get("userId") ?: ""),

                                "vipExpire" to (vipInfo?.get("expiryDate") ?: ""),

                                "vipDaysLeft" to (vipInfo?.get("daysLeft")?.toString()?.toDoubleOrNull()?.toInt() ?: 0),

                                "vipPoint" to (vipInfo?.get("point")?.toString()?.toDoubleOrNull()?.toInt() ?: 0),

                                "wantToSeeCount" to (mySubject?.get("wantToSeeCount")?.toString()?.toDoubleOrNull()?.toInt() ?: 0),

                                "haveSeenCount" to (mySubject?.get("haveSeenCount")?.toString()?.toDoubleOrNull()?.toInt() ?: 0),

                                "favoriteCount" to (favoriteInfo?.get("favoriteCount")?.toString()?.toDoubleOrNull()?.toInt() ?: 0)

                            )

                            val responseObj = mapOf(

                                "logged_in" to true,

                                "mode" to "Official Account",

                                "user" to targetUser,

                                "session_id" to "local_session"

                            )

                            Pair(200, toJsonString(responseObj))

                        }

                    }

                }

                path == "/request-otp" -> {
                    val requestMap = parseJsonToMap(body)

                    val account = requestMap["account"]?.toString() ?: ""

                    val type = requestMap["type"]?.toString()?.toDoubleOrNull()?.toInt() ?: 1

                    val payload = mutableMapOf<String, Any>(

                        "package_name" to "com.community.mbox.in",

                        "type" to type,

                        "mail" to account,

                        "authType" to 1

                    )

                    val payloadString = toJsonString(payload)

                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/user-api/get-sms-code", emptyMap(), payloadString)

                    val responseMap = parseJsonToMap(rawResponse)

                    val code = responseMap["code"]?.toString()?.toDoubleOrNull()?.toInt() ?: -1

                    if (code == 0 || code == 200) {
                        Pair(200, "{\"status\": \"success\", \"msg\": \"OTP Sent\"}")

                    } else {
                        val msg = responseMap["msg"]?.toString() ?: responseMap["message"]?.toString() ?: "Failed to send OTP"

                        Pair(400, "{\"status\": \"error\", \"message\": \"$msg\"}")

                    }

                }

                path == "/login" -> {
                    val requestMap = parseJsonToMap(body)

                    val account = requestMap["account"]?.toString() ?: ""

                    val password = requestMap["password"]?.toString() ?: ""

                    val authType = requestMap["authType"]?.toString()?.toDoubleOrNull()?.toInt() ?: 1

                    val hashedPwd = md5(password)

                    val payload = mutableMapOf<String, Any>(

                        "password" to hashedPwd,

                        "package_name" to "com.community.mbox.in",

                        "authType" to 1,

                        "type" to 0,

                        "mail" to account

                    )

                    val payloadString = toJsonString(payload)

                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/user-api/login", emptyMap(), payloadString)

                    val responseMap = parseJsonToMap(rawResponse)

                    val code = responseMap["code"]?.toString()?.toDoubleOrNull()?.toInt() ?: -1

                    val data = responseMap["data"] as? Map<String, Any?>

                    val token = data?.get("token")?.toString()

                    if ((code == 0 || code == 200) && !token.isNullOrEmpty()) {
                        val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

                        prefs.edit()

                            .putString("user_auth_token", token)

                            .putString("auth_token", token)

                            .putString("user_info_json", toJsonString(data))

                            .apply()

                        val avatar = (data["avatarUrl"] ?: data["avatar"] ?: "").toString()

                        val targetResponse = mapOf(

                            "status" to "success",

                            "user" to mapOf(

                                "username" to (data["userName"] ?: data["nickname"] ?: account),

                                "nickname" to (data["nickname"] ?: account),

                                "avatar" to avatar,

                                "email" to (data["email"] ?: account),

                                "vip" to (data["vip"] ?: 0),

                                "userId" to (data["userId"] ?: "")

                            )

                        )

                        Pair(200, toJsonString(targetResponse))

                    } else {
                        val msg = responseMap["msg"]?.toString() ?: responseMap["message"]?.toString() ?: "Login failed"

                        Pair(400, "{\"status\": \"error\", \"message\": \"$msg\"}")

                    }

                }

                path == "/register" -> {
                    val requestMap = parseJsonToMap(body)

                    val account = requestMap["account"]?.toString() ?: ""

                    val password = requestMap["password"]?.toString() ?: ""

                    val otp = requestMap["otp"]?.toString() ?: ""

                    val authType = requestMap["authType"]?.toString()?.toDoubleOrNull()?.toInt() ?: 1

                    val hashedPwd = md5(password)

                    val payload = mutableMapOf<String, Any>(

                        "password" to hashedPwd,

                        "verificationCode" to otp,

                        "package_name" to "com.community.mbox.in",

                        "authType" to 1,

                        "type" to 1,

                        "mail" to account

                    )

                    val payloadString = toJsonString(payload)

                    android.util.Log.d("LocalMovieBoxServer", "Register payload: $payloadString")

                    val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/user-api/register", emptyMap(), payloadString)

                    android.util.Log.d("LocalMovieBoxServer", "Register rawResponse: $rawResponse")

                    val responseMap = parseJsonToMap(rawResponse)

                    val code = responseMap["code"]?.toString()?.toDoubleOrNull()?.toInt() ?: -1

                    val data = responseMap["data"] as? Map<String, Any?>

                    if (code == 0 || code == 200) {
                        val token = data?.get("token")?.toString()

                        if (!token.isNullOrEmpty()) {
                            val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

                            prefs.edit()

                                .putString("user_auth_token", token)

                                .putString("auth_token", token)

                                .putString("user_info_json", toJsonString(data))

                                .apply()

                        }

                        val avatar = (data?.get("avatarUrl") ?: data?.get("avatar") ?: "").toString()

                        val targetResponse = mapOf(

                            "status" to "success",

                            "user" to mapOf(

                                "username" to (data?.get("userName") ?: account),

                                "nickname" to (data?.get("nickname") ?: account),

                                "avatar" to avatar,

                                "email" to (data?.get("email") ?: account),

                                "vip" to (data?.get("vip") ?: 0),

                                "userId" to (data?.get("userId") ?: "")

                            )

                        )

                        Pair(200, toJsonString(targetResponse))

                    } else {
                        val msg = responseMap["msg"]?.toString() ?: responseMap["message"]?.toString() ?: "Registration failed"

                        Pair(400, "{\"status\": \"error\", \"message\": \"$msg\"}")

                    }

                }

                path == "/logout" -> {
                    val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

                    prefs.edit().remove("user_auth_token").remove("auth_token").remove("user_info_json").apply()

                    Pair(200, "{\"status\": \"success\"}")

                }

                path == "/modify" -> {
                    try {
                        val requestMap = parseJsonToMap(body)

                        val nickname = requestMap["nickname"]?.toString() ?: ""

                        val avatar = requestMap["avatar"]?.toString() ?: ""

                        val payload = mutableMapOf<String, Any>(

                            "nickname" to nickname

                        )

                        if (avatar.isNotEmpty()) {
                            payload["avatar"] = avatar

                        }

                        val payloadString = toJsonString(payload)

                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/user-api/modify", emptyMap(), payloadString)

                        Pair(200, rawResponse)

                    } catch (e: Exception) {
                        Pair(500, "{\"code\": 500, \"message\": \"${e.message}\"}")

                    }

                }

                path == "/wefeed-mobile-bff/upload/sts-token" || path == "/wefeed-mobile-bff/upload/sts-token/v2" -> {
                    try {
                        val rawResponse = makeOfficialRequest("GET", path)

                        Pair(200, rawResponse)

                    } catch (e: Exception) {
                        Pair(500, "{\"code\": 500, \"message\": \"${e.message}\"}")

                    }

                }

                path == "/upload-avatar" -> {
                    try {
                        val requestMap = parseJsonToMap(body)

                        val base64Str = requestMap["image"]?.toString() ?: ""

                        if (base64Str.isNotEmpty()) {
                            val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP)

                            val file = java.io.File(context.filesDir, "avatar.jpg")

                            file.outputStream().use { out ->

                                out.write(decodedBytes)

                            }

                            Pair(200, "{\"status\": \"success\", \"url\": \"file://${context.filesDir.absolutePath}/avatar.jpg\"}")

                        } else {
                            Pair(400, "{\"status\": \"error\", \"message\": \"Image data is empty\"}")

                        }

                    } catch (e: Exception) {
                        Pair(500, "{\"status\": \"error\", \"message\": \"${e.message}\"}")

                    }

                }

                path == "/uploads/avatar.jpg" -> {
                    try {
                        val file = java.io.File(context.filesDir, "avatar.jpg")

                        if (file.exists()) {
                            val bytes = file.readBytes()

                            val bodyString = String(bytes, Charsets.ISO_8859_1)

                            Pair(200, bodyString)

                        } else {
                            Pair(404, "Not Found")

                        }

                    } catch (e: Exception) {
                        Pair(500, e.message ?: "Error")

                    }

                }

                path == "/wefeed-mobile-bff/subject-api/want-to-see" -> {
                    try {
                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/want-to-see", emptyMap(), body)

                        Pair(200, rawResponse)

                    } catch (e: Exception) {
                        Pair(500, "{\"code\": 500, \"message\": \"${e.message}\"}")

                    }

                }

                path == "/wefeed-mobile-bff/subject-api/see-list-v2" -> {
                    try {
                        val page = qParams["page"] ?: "1"

                        val pageSize = qParams["pageSize"] ?: "20"

                        val seeType = qParams["seeType"] ?: "1"

                        val params = mapOf(

                            "page" to page,

                            "pageSize" to pageSize,

                            "seeType" to seeType

                        )

                        val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/see-list-v2", params)

                        Pair(200, rawResponse)

                    } catch (e: Exception) {
                        Pair(500, "{\"code\": 500, \"message\": \"${e.message}\"}")

                    }

                }

                path == "/detail-rec" -> {
                    try {
                        val subjectId = qParams["subjectId"] ?: ""

                        val bodyJson = "{\"subjectId\":\"$subjectId\",\"page\":1,\"pageSize\":10}"

                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/detail-rec", emptyMap(), bodyJson)

                        Pair(200, rawResponse)

                    } catch (e: Exception) {
                        Pair(500, "{\"code\":-1,\"message\":\"${e.message}\"}")

                    }

                }

                path == "/trending" -> {
                    try {
                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/trending/v2", emptyMap(), "{}")

                        val responseMap = parseJsonToMap(rawResponse)

                        val dataMap = responseMap["data"] as? Map<String, Any?>

                        val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>> 

                            ?: responseMap["data"] as? List<Map<String, Any?>> 

                            ?: emptyList()

                        val mapped = list.mapNotNull { mapItem(it) }

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to mapped

                        )

                        Pair(200, toJsonString(responseBody))

                    } catch (e: Exception) {
                        Log.e("LocalMovieBoxServer", "Trending error: ${e.message}")

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to emptyList<Map<String, Any?>>()

                        )

                        Pair(200, toJsonString(responseBody))

                    }

                }

                path == "/discovery" -> {
                    try {
                        val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/top-rec", emptyMap(), "{}")

                        val responseMap = parseJsonToMap(rawResponse)

                        val dataMap = responseMap["data"] as? Map<String, Any?>

                        val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>> 

                            ?: responseMap["data"] as? List<Map<String, Any?>> 

                            ?: emptyList()

                        val mapped = list.mapNotNull { mapItem(it) }

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to mapped

                        )

                        Pair(200, toJsonString(responseBody))

                    } catch (e: Exception) {
                        Log.e("LocalMovieBoxServer", "Discovery error: ${e.message}")

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to emptyList<Map<String, Any?>>()

                        )

                        Pair(200, toJsonString(responseBody))

                    }

                }

                path == "/search" || path == "/wefeed-mobile-bff/subject-api/search" -> {
                    val q = qParams["keyword"] ?: qParams["q"] ?: ""
                    val page = qParams["page"]?.toIntOrNull() ?: 1
                    try {
                        val allRawItems = mutableListOf<Map<String, Any?>>()
                        val startSubPage = (page - 1) * 4 + 1
                        val endSubPage = startSubPage + 3

                        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
                        val futures = (startSubPage..endSubPage).map { p ->
                            executor.submit(java.util.concurrent.Callable {
                                try {
                                    val payload = mapOf("keyword" to q, "page" to p, "pageSize" to 20)
                                    val raw = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/search", emptyMap(), toJsonString(payload))
                                    val respMap = parseJsonToMap(raw)
                                    val dMap = respMap["data"] as? Map<String, Any?>
                                    ((dMap?.get("items") ?: dMap?.get("list") ?: dMap?.get("subjects")) as? List<Map<String, Any?>>) ?: emptyList()
                                } catch (_: Exception) {
                                    emptyList<Map<String, Any?>>()
                                }
                            })
                        }
                        futures.forEach { fut ->
                            try {
                                allRawItems.addAll(fut.get())
                            } catch (_: Exception) {}
                        }
                        executor.shutdown()

                        val mapped = allRawItems.mapNotNull { mapItem(it) }
                        val distinctMapped = mapped.distinctBy { it["id"] ?: it["subjectId"] }
                        val responseBody = mapOf(
                            "code" to 0,
                            "data" to mapOf(
                                "items" to distinctMapped,
                                "list" to distinctMapped
                            )
                        )
                        Pair(200, toJsonString(responseBody))
                    } catch (e: Exception) {
                        Log.e("LocalMovieBoxServer", "Search error: ${e.message}")
                        val responseBody = mapOf(
                            "code" to 0,
                            "data" to mapOf("items" to emptyList<Map<String, Any?>>(), "list" to emptyList<Map<String, Any?>>())
                        )
                        Pair(200, toJsonString(responseBody))
                    }
                }

                path == "/wefeed-mobile-bff/subject-api/trending/v2" || path == "/search-suggestions" -> {
                    val q = qParams["q"] ?: ""

                    try {
                        if (q.isNotEmpty()) {
                            val payload = mapOf(

                                "keyword" to q,

                                "page" to 1,

                                "pageSize" to 20

                            )

                            val payloadString = toJsonString(payload)

                            val rawResponse = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/search", emptyMap(), payloadString)

                            val responseMap = parseJsonToMap(rawResponse)

                            val dataMap = responseMap["data"] as? Map<String, Any?>

                            val list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>> 

                                ?: responseMap["data"] as? List<Map<String, Any?>> 

                                ?: emptyList()

                            val suggestions = list.mapNotNull { i ->

                                i["title"]?.toString() ?: i["name"]?.toString() ?: i["keyword"]?.toString()

                            }

                            val responseBody = mapOf(

                                "code" to 0,

                                "data" to suggestions

                            )

                            Pair(200, toJsonString(responseBody))

                        } else {
                            val trendRaw = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/trending/v2", emptyMap(), "{}")

                            val trendMap = parseJsonToMap(trendRaw)

                            val trendDataMap = trendMap["data"] as? Map<String, Any?>

                            val trendList = (trendDataMap?.get("list") ?: trendDataMap?.get("items") ?: trendDataMap?.get("subjects")) as? List<Map<String, Any?>> 

                                ?: trendMap["data"] as? List<Map<String, Any?>> 

                                ?: emptyList()

                            var suggestions = trendList.mapNotNull { itMap ->

                                val subj = itMap["subject"] as? Map<String, Any?> ?: itMap

                                (subj["title"] ?: subj["name"] ?: itMap["title"] ?: itMap["name"])?.toString()

                            }.take(10)

                            if (suggestions.isEmpty()) {
                                val rawRank = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/search-rank/v2", mapOf("page" to "1", "pageSize" to "10"))

                                val rankMap = parseJsonToMap(rawRank)

                                val rankData = rankMap["data"] as? Map<String, Any?>

                                val hotArray = rankData?.get("hot") as? List<Map<String, Any?>> ?: emptyList()

                                val firstHotObj = hotArray.firstOrNull() ?: emptyMap()

                                val rankItems = (firstHotObj["items"] ?: firstHotObj["list"]) as? List<Map<String, Any?>> ?: emptyList()

                                suggestions = rankItems.mapNotNull { i ->

                                    (i["keyword"] ?: i["title"] ?: i["name"])?.toString()

                                }.take(10)

                            }

                            if (suggestions.isEmpty()) {
                                suggestions = listOf(
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
                            }

                            val responseBody = mapOf(

                                "code" to 0,

                                "data" to suggestions

                            )

                            Pair(200, toJsonString(responseBody))

                        }

                    } catch (e: Exception) {
                        Log.w("LocalMovieBoxServer", "Search-suggestions fallback triggered: ${e.message}")

                        var fallbackSuggestions = listOf(
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

                        try {
                            val trendRaw = makeOfficialRequest("POST", "/wefeed-mobile-bff/subject-api/trending/v2", emptyMap(), "{}")

                            val trendMap = parseJsonToMap(trendRaw)

                            val trendDataMap = trendMap["data"] as? Map<String, Any?>

                            val trendList = (trendDataMap?.get("list") ?: trendDataMap?.get("items") ?: trendDataMap?.get("subjects")) as? List<Map<String, Any?>> 

                                ?: trendMap["data"] as? List<Map<String, Any?>> 

                                ?: emptyList()

                            val trendTitles = trendList.mapNotNull { itMap ->

                                val subj = itMap["subject"] as? Map<String, Any?> ?: itMap

                                (subj["title"] ?: subj["name"] ?: itMap["title"] ?: itMap["name"])?.toString()

                            }.take(10)

                            if (trendTitles.isNotEmpty()) {
                                fallbackSuggestions = trendTitles

                            }

                        } catch (ex: Exception) {}

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to fallbackSuggestions

                        )

                        Pair(200, toJsonString(responseBody))

                    }

                }

                path.startsWith("/detail/") -> {
                    val subjectId = path.substringAfter("/detail/").substringBefore("?").substringBefore("/")

                    try {
                        val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))

                        val responseMap = parseJsonToMap(rawResponse)

                        var data = responseMap["data"] as? Map<String, Any?> ?: return Pair(404, "{\"code\": 1, \"msg\": \"Not found\"}")

                        // Resolve original series metadata if this is a dub entry

                        val dubsList = data["dubs"] as? List<Map<String, Any?>> ?: emptyList()

                        val originalDub = dubsList.find { it["original"] == true || it["original"]?.toString()?.toBoolean() == true }

                        val mainSubjectId = originalDub?.get("subjectId")?.toString()

                        if (!mainSubjectId.isNullOrEmpty() && mainSubjectId != subjectId) {
                            try {
                                val mainResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to mainSubjectId))

                                val mainResponseMap = parseJsonToMap(mainResponseRaw)

                                val mainData = mainResponseMap["data"] as? Map<String, Any?>

                                if (mainData != null) {
                                    val mutableData = data.toMutableMap()

                                    listOf("description", "duration", "year", "imdbRating", "genres", "staffList", "actorList", "totalSeasons", "totalEpisode").forEach { key ->

                                        val mainVal = mainData[key]

                                        val currVal = data[key]

                                        if (mainVal != null && (currVal == null || currVal.toString().isEmpty() || currVal.toString() == "0" || currVal.toString() == "0 min")) {
                                            mutableData[key] = mainVal

                                        }

                                    }

                                    data = mutableData

                                }

                            } catch (e: Exception) {
                                Log.w("LocalMovieBoxServer", "Failed to fetch main detail for dub: ${e.message}")

                            }

                        }

                        val mapped = mapItem(data) ?: data

                        val mutableMapped = mapped.toMutableMap()

                        // Cast mapping

                        val rawCast = data["staffList"] as? List<Map<String, Any?>> ?: data["actorList"] as? List<Map<String, Any?>> ?: emptyList()

                        val cast = rawCast.mapNotNull { castMember ->

                            val name = castMember["name"] ?: castMember["actorName"] ?: "Unknown"

                            val role = castMember["character"] ?: castMember["role"] ?: "Cast"

                            val rawAvatar = (castMember["avatarUrl"] ?: castMember["avatar"] ?: castMember["photo"] ?: castMember["poster"] ?: "").toString()
                            val avatar = com.example.util.ImageOptimizer.getOptimalUrl(rawAvatar, 180)

                            mapOf("name" to name, "role" to role, "avatar" to avatar)

                        }

                        mutableMapped["cast"] = cast

                        // Available Languages / Dubs (Consolidated into resourceDetectors)

                        val consolidatedDetectors = mutableListOf<Map<String, Any?>>()

                        val rawDubs = data["dubs"] as? List<Map<String, Any?>> ?: emptyList()

                        for (dub in rawDubs) {
                            val dubSubId = dub["subjectId"]?.toString() ?: continue

                            consolidatedDetectors.add(mapOf(

                                "resourceId" to "dub_$dubSubId",

                                "name" to (dub["lanName"] ?: "Custom Dub"),

                                "totalEpisode" to 0

                            ))

                        }

                        val detectors = data["resourceDetectors"] as? List<Map<String, Any?>> ?: emptyList()

                        for (d in detectors) {
                            val resId = d["resourceId"]?.toString() ?: continue

                            consolidatedDetectors.add(mapOf(

                                "resourceId" to "res_$resId",

                                "name" to (d["name"] ?: d["uploadBy"] ?: "Resource"),

                                "totalEpisode" to (d["totalEpisode"] ?: 0)

                            ))

                        }

                        mutableMapped["resourceDetectors"] = consolidatedDetectors

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to mutableMapped

                        )

                        Pair(200, toJsonString(responseBody))

                    } catch (e: Exception) {
                        Log.w("LocalMovieBoxServer", "Detail fallback triggered: ${e.message}")

                        val mockMovie = getMockTrending().find { it["subjectId"] == subjectId } ?: getMockTrending()[0]

                        val mutableMapped = mockMovie.toMutableMap()

                        mutableMapped["cast"] = listOf(

                            mapOf("name" to "Keanu Reeves", "role" to "Neo", "avatar" to "https://image.tmdb.org/t/p/w500/b9v69EMT2g7uY78MKg6Y67688v6.jpg"),

                            mapOf("name" to "Carrie-Anne Moss", "role" to "Trinity", "avatar" to "https://image.tmdb.org/t/p/w500/8gRE3687bcu6797hY0A1vGZ6P6V.jpg")

                        )

                        mutableMapped["languages"] = listOf(

                            mapOf("id" to "sub_en", "subjectId" to subjectId, "name" to "English Dub", "type" to "dub"),

                            mapOf("id" to "res_hd", "subjectId" to subjectId, "name" to "Google Resource (1080p)", "type" to "resource")

                        )

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to mutableMapped

                        )

                        Pair(200, toJsonString(responseBody))

                    }

                }

                path.startsWith("/episodes/") -> {
                    val seriesId = path.substringAfter("/episodes/").substringBefore("?").substringBefore("/")

                    try {
                        val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/season-info/v2", mapOf("subjectId" to seriesId))

                        val responseMap = parseJsonToMap(rawResponse)

                        val data = responseMap["data"] as? Map<String, Any?> ?: responseMap

                        val rawSeasons = data["seasons"] as? List<Map<String, Any?>> ?: data["seasonList"] as? List<Map<String, Any?>> ?: data["list"] as? List<Map<String, Any?>> ?: emptyList()

                        val mappedSeasons = mutableListOf<Map<String, Any?>>()

                        for (sRaw in rawSeasons) {
                            val num = sRaw["se"] ?: sRaw["seasonNumber"] ?: 1

                            val eps = mutableListOf<Map<String, Any?>>()

                            val pool = when {
                                sRaw["allEp"] != null -> sRaw["allEp"]

                                sRaw["epList"] != null -> sRaw["epList"]

                                sRaw["episodeList"] != null -> sRaw["episodeList"]

                                sRaw["episodes"] != null -> sRaw["episodes"]

                                sRaw["list"] != null -> sRaw["list"]

                                sRaw["items"] != null -> sRaw["items"]

                                else -> null

                            }

                            if (pool is String) {
                                pool.split(",").forEach { eNum ->

                                    if (eNum.isNotEmpty()) {
                                        eps.add(mapOf("episodeNumber" to eNum, "title" to "Episode $eNum", "id" to "${seriesId}_${num}_${eNum}"))

                                    }

                                }

                            } else if (pool is List<*>) {
                                for (item in pool) {
                                    if (item is Map<*, *>) {
                                        val en = item["ep"] ?: item["episodeNumber"] ?: item["episode_number"]

                                        if (en != null) {
                                            eps.add(mapOf("episodeNumber" to en.toString(), "title" to (item["title"] ?: "Episode $en"), "id" to "${seriesId}_${num}_${en}"))

                                        }

                                    } else if (item != null) {
                                        eps.add(mapOf("episodeNumber" to item.toString(), "title" to "Episode $item", "id" to "${seriesId}_${num}_${item}"))

                                    }

                                }

                            }

                            if (eps.isEmpty()) {
                                val maxEpVal = sRaw["maxEp"] ?: sRaw["max_ep"] ?: 0

                                val maxEp = maxEpVal.toString().toDoubleOrNull()?.toInt() ?: 0

                                if (maxEp > 0) {
                                    for (i in 1..maxEp) {
                                        eps.add(mapOf("episodeNumber" to i.toString(), "title" to "Episode $i", "id" to "${seriesId}_${num}_${i}"))

                                    }

                                }

                            }

                            if (eps.isNotEmpty()) {
                                val resEpCounts = mutableMapOf<String, Int>()
                                val resRaw = (sRaw["resolutions"] as? List<*>)?.mapNotNull { r ->
                                    if (r is Map<*, *>) {
                                        val resVal = (r["resolution"] as? Number)?.toInt() ?: r["resolution"]?.toString()?.toIntOrNull()
                                        val epVal = (r["epNum"] as? Number)?.toInt() ?: r["epNum"]?.toString()?.toIntOrNull() ?: 0
                                        if (resVal != null) {
                                            resEpCounts[resVal.toString()] = epVal
                                            resVal
                                        } else null
                                    } else if (r is Number) {
                                        r.toInt()
                                    } else {
                                        r?.toString()?.toIntOrNull()
                                    }
                                } ?: emptyList()
                                mappedSeasons.add(mapOf(
                                    "seasonNumber" to num,
                                    "episodes" to eps,
                                    "resolutions" to resRaw,
                                    "resolutionEpCounts" to resEpCounts
                                ))

                            }

                        }

                        if (mappedSeasons.isEmpty()) {
                            throw Exception("No seasons from official API")

                        }

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to mapOf("seasons" to mappedSeasons)

                        )

                        Pair(200, toJsonString(responseBody))

                    } catch (e: Exception) {
                        Log.w("LocalMovieBoxServer", "Episodes fallback triggered: ${e.message}")

                        val mappedSeasons = listOf(

                            mapOf(

                                "seasonNumber" to 1,

                                "episodes" to listOf(

                                    mapOf("episodeNumber" to "1", "title" to "Pilot", "id" to "${seriesId}_1_1"),

                                    mapOf("episodeNumber" to "2", "title" to "The Beginning", "id" to "${seriesId}_1_2"),

                                    mapOf("episodeNumber" to "3", "title" to "Into the Dark", "id" to "${seriesId}_1_3")

                                )

                            )

                        )

                        val responseBody = mapOf(

                            "code" to 0,

                            "data" to mapOf("seasons" to mappedSeasons)

                        )

                        Pair(200, toJsonString(responseBody))

                    }

                }

                path.startsWith("/stream/") -> {
                    val subjectId = path.substringAfter("/stream/").substringBefore("?").substringBefore("/")

                    val season = qParams["season"] ?: "1"

                    val episode = qParams["episode"] ?: "1"

                    val quality = qParams["quality"] ?: "720p"

                    val resourceId = qParams["resource_id"]

                    try {
                        var isMovie = false

                        var subResourceId = subjectId

                        var allDetectors: List<Map<String, Any?>> = emptyList()

                        var allDubs: List<Map<String, Any?>> = emptyList()

                        var mainSubjectId: String = subjectId

                        var movieTitle: String = ""

                        try {
                            val detailResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to subjectId))

                            val detailResponse = parseJsonToMap(detailResponseRaw)

                            val detailData = detailResponse["data"] as? Map<String, Any?>

                            movieTitle = (detailData?.get("title") ?: detailData?.get("name") ?: detailData?.get("subjectName") ?: detailData?.get("title_en") ?: "").toString()

                            val type = detailData?.get("subjectType") ?: detailData?.get("type") ?: 1

                            val typeNum = type.toString().toDoubleOrNull()?.toInt() ?: 1

                            val hasTvIndicators = (detailData?.get("seNum") != null && (detailData?.get("seNum").toString().toIntOrNull() ?: 0) > 0) ||
                                detailData?.get("episodeCount") != null ||
                                detailData?.get("seasonCount") != null ||
                                (detailData?.get("seasons") as? List<*>)?.isNotEmpty() == true

                            isMovie = (typeNum == 1) && !hasTvIndicators

                            val detectors = detailData?.get("resourceDetectors") as? List<Map<String, Any?>> ?: emptyList()

                            allDetectors = detectors

                            allDubs = detailData?.get("dubs") as? List<Map<String, Any?>> ?: emptyList()

                            val origDub = allDubs.find { 
                                it["original"] == true || 

                                it["original"]?.toString()?.toBoolean() == true || 

                                (it["lanName"] ?: "").toString().contains("orig", ignoreCase = true) 

                            }

                            mainSubjectId = origDub?.get("subjectId")?.toString() ?: subjectId

                            if (detectors.isNotEmpty()) {
                                subResourceId = detectors[0]["resourceId"]?.toString() ?: subjectId

                            }

                        } catch (e: Exception) {}

                        var finalSubjectId = subjectId

                        var finalResourceId: String? = null

                        if (resourceId != null) {
                            if (resourceId.startsWith("dub_")) {
                                finalSubjectId = resourceId.substringAfter("dub_")

                            } else if (resourceId.startsWith("res_")) {
                                finalResourceId = resourceId.substringAfter("res_")

                            } else {
                                finalResourceId = resourceId

                            }

                        }

                        val resSe = if (isMovie) null else season

                        val resEp = if (isMovie) null else episode

                        val params = mutableMapOf<String, String>(

                            "subjectId" to finalSubjectId,

                            "host" to "api6.aoneroom.com"

                        )

                        if (resSe != null) params["se"] = resSe

                        if (resEp != null) params["ep"] = resEp

                        if (finalResourceId != null) params["resourceId"] = finalResourceId

                        val rawResponse = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/play-info/v2", params)

                        val responseMap = parseJsonToMap(rawResponse)

                        val data = responseMap["data"] as? Map<String, Any?> ?: emptyMap<String, Any?>()

                        var streams = data["streamList"] as? List<Map<String, Any?>> ?: data["streams"] as? List<Map<String, Any?>> ?: emptyList()

                        val globalCookie = responseMap["signCookie"] ?: data["signCookie"] ?: ""

                        // UGC BYPASS

                        var allDummy = true

                        for (st in streams) {
                            val u = st["url"]?.toString() ?: ""

                            if (!u.contains("macdn.aoneroom.com") && !u.contains("please_update") && !u.contains("update_app") && !u.contains("b164fbfb43")) {
                                allDummy = false

                                break

                            }

                        }

                        if (streams.isEmpty()) allDummy = true

                        if (allDummy) {
                            try {
                                val targetEpNum = resEp?.toIntOrNull() ?: 1
                                val targetSeNum = resSe?.toIntOrNull() ?: 1

                                val ugcStreams = mutableListOf<Map<String, Any?>>()
                                var foundTargetInCollection = false

                                // Candidate subject IDs: First try finalSubjectId, then try mainSubjectId (Original Anime) if different
                                val candidateSubjectIds = mutableListOf(finalSubjectId)
                                if (mainSubjectId != finalSubjectId && !candidateSubjectIds.contains(mainSubjectId)) {
                                    candidateSubjectIds.add(mainSubjectId)
                                }

                                val epExactRegex = Regex("""(?i)(?:s0*${targetSeNum}\s*)?(?:ep|episode|e|#|\s|^)0*${targetEpNum}(?:[^\d]|$)""")

                                for (candSubId in candidateSubjectIds) {
                                    if (foundTargetInCollection) break

                                    val ugcFirstRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/ugc/video/first-by-subject", mapOf("subjectId" to candSubId))
                                    val ugcFirstMap = parseJsonToMap(ugcFirstRaw)
                                    val ugcFirstData = ugcFirstMap["data"] as? Map<String, Any?>
                                    val ugcVidId = ugcFirstData?.get("ugcVideoId")?.toString()
                                    val belongToCollection = ugcFirstData?.get("belongToCollection") as? Map<String, Any?>
                                    val collectionId = belongToCollection?.get("collectionId")?.toString()
                                    val totalVideos = (belongToCollection?.get("videoNum") ?: 0).toString().toIntOrNull() ?: 0
                                    val totalPages = maxOf(1, ((totalVideos - 1) / 50) + 1)

                                    if (!collectionId.isNullOrEmpty()) {
                                        val primaryPage = maxOf(1, minOf(totalPages, ((targetEpNum - 1) / 50) + 1))
                                        val pagesToTry = mutableListOf(primaryPage)
                                        for (p in listOf(primaryPage - 1, primaryPage + 1, primaryPage - 2, primaryPage + 2, primaryPage - 3, primaryPage + 3, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)) {
                                            if (p in 1..totalPages && !pagesToTry.contains(p)) {
                                                pagesToTry.add(p)
                                            }
                                        }

                                        var targetItem: Map<String, Any?>? = null

                                        for (p in pagesToTry) {
                                            val ugcDlRaw = makeOfficialRequest(
                                                "GET",
                                                "/wefeed-mobile-bff/ugc/collection/download-list",
                                                mapOf("collectionId" to collectionId, "page" to p.toString(), "perPage" to "50")
                                            )
                                            val ugcDlMap = parseJsonToMap(ugcDlRaw)
                                            val ugcDlData = ugcDlMap["data"] as? Map<String, Any?>
                                            val ugcItems = ugcDlData?.get("items") as? List<Map<String, Any?>> ?: emptyList()
                                            if (ugcItems.isEmpty() && p > primaryPage) {
                                                break
                                            }

                                            for (item in ugcItems) {
                                                val itemEp = item["episode"] ?: item["ep"] ?: item["episodeNumber"] ?: item["order"] ?: item["seq"]
                                                if (itemEp != null && itemEp.toString().toIntOrNull() == targetEpNum) {
                                                    targetItem = item
                                                    break
                                                }

                                                val epTitle = item["epTitle"]?.toString() ?: ""
                                                val title = item["title"]?.toString() ?: ""

                                                if (epExactRegex.containsMatchIn(epTitle) || epExactRegex.containsMatchIn(title)) {
                                                    targetItem = item
                                                    break
                                                }
                                            }

                                            if (targetItem == null && targetEpNum == 1 && p == 1 && ugcItems.isNotEmpty()) {
                                                targetItem = ugcItems[0]
                                            }

                                            if (targetItem != null) {
                                                break
                                            }
                                        }

                                        if (targetItem != null) {
                                            foundTargetInCollection = true
                                            val resources = targetItem["resources"] as? List<Map<String, Any?>> ?: emptyList()
                                            for (res in resources) {
                                                val rUrl = res["url"]?.toString() ?: ""
                                                if (rUrl.isNotEmpty() && !rUrl.contains("macdn.aoneroom.com") && !rUrl.contains("b164fbfb43")) {
                                                    val mutableRes = res.toMutableMap()
                                                    val uLower = rUrl.lowercase()
                                                    val rawResVal = res["resolutions"] ?: res["resolution"] ?: res["quality"] ?: res["definition"]
                                                    val parsedResNum = rawResVal?.toString()?.filter { it.isDigit() }?.toIntOrNull()
                                                    if (parsedResNum != null && parsedResNum > 0) {
                                                        mutableRes["resolution"] = parsedResNum
                                                        mutableRes["quality"] = "${parsedResNum}p"
                                                    } else {
                                                        if (uLower.contains("1080") || uLower.contains("nyaa")) {
                                                            mutableRes["resolution"] = 1080
                                                            mutableRes["quality"] = "1080p"
                                                        } else if (uLower.contains("720")) {
                                                            mutableRes["resolution"] = 720
                                                            mutableRes["quality"] = "720p"
                                                        } else if (uLower.contains("480")) {
                                                            mutableRes["resolution"] = 480
                                                            mutableRes["quality"] = "480p"
                                                        } else {
                                                            mutableRes["resolution"] = 720
                                                            mutableRes["quality"] = "720p"
                                                        }
                                                    }
                                                    ugcStreams.add(mutableRes)
                                                }
                                            }
                                        }
                                    }

                                    // Single video fallback: ONLY for Movies or Ep 1 if collection didn't yield anything
                                    if (!foundTargetInCollection && !ugcVidId.isNullOrEmpty() && (isMovie || targetEpNum == 1)) {
                                        val ugcDlRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/ugc/video/download", mapOf("ugcVideoId" to ugcVidId))
                                        val ugcDlMap = parseJsonToMap(ugcDlRaw)
                                        val ugcDlData = ugcDlMap["data"] as? Map<String, Any?>
                                        val ugcItems = ugcDlData?.get("items") as? List<Map<String, Any?>> ?: emptyList()
                                        for (item in ugcItems) {
                                            val resources = item["resources"] as? List<Map<String, Any?>> ?: emptyList()
                                            for (res in resources) {
                                                val rUrl = res["url"]?.toString() ?: ""
                                                if (rUrl.isNotEmpty() && !rUrl.contains("macdn.aoneroom.com") && !rUrl.contains("b164fbfb43")) {
                                                    val mutableRes = res.toMutableMap()
                                                    val uLower = rUrl.lowercase()
                                                    val rawResVal = res["resolutions"] ?: res["resolution"] ?: res["quality"] ?: res["definition"]
                                                    val parsedResNum = rawResVal?.toString()?.filter { it.isDigit() }?.toIntOrNull()
                                                    if (parsedResNum != null && parsedResNum > 0) {
                                                        mutableRes["resolution"] = parsedResNum
                                                        mutableRes["quality"] = "${parsedResNum}p"
                                                    } else {
                                                        if (uLower.contains("1080") || uLower.contains("nyaa")) {
                                                            mutableRes["resolution"] = 1080
                                                            mutableRes["quality"] = "1080p"
                                                        } else if (uLower.contains("720")) {
                                                            mutableRes["resolution"] = 720
                                                            mutableRes["quality"] = "720p"
                                                        } else if (uLower.contains("480")) {
                                                            mutableRes["resolution"] = 480
                                                            mutableRes["quality"] = "480p"
                                                        } else {
                                                            mutableRes["resolution"] = 720
                                                            mutableRes["quality"] = "720p"
                                                        }
                                                    }
                                                    ugcStreams.add(mutableRes)
                                                }
                                            }
                                        }
                                    }

                                    // Engine 2: Cloud Sniffer Engine (/sniff/config) via /subject-api/resource
                                    try {
                                        val page = maxOf(0, (targetEpNum - 1) / 20)
                                        val pagesToSniff = mutableListOf(page)
                                        for (p in listOf(page - 1, page + 1, page + 2)) {
                                            if (p >= 0 && !pagesToSniff.contains(p)) {
                                                pagesToSniff.add(p)
                                            }
                                        }

                                        for (p in pagesToSniff) {
                                            val resRaw = makeOfficialRequest(
                                                "GET",
                                                "/wefeed-mobile-bff/subject-api/resource",
                                                mapOf("subjectId" to candSubId, "page" to p.toString(), "perPage" to "20", "pagerMode" to "0")
                                            )
                                            val resMap = parseJsonToMap(resRaw)
                                            val resData = resMap["data"] as? Map<String, Any?>
                                            val resItems = (resData?.get("list") ?: resData?.get("items")) as? List<Map<String, Any?>> ?: emptyList()
                                            if (resItems.isEmpty() && p > page) break

                                            for (it in resItems) {
                                                val itEp = it["ep"] ?: it["episode"]
                                                if (itEp != null && itEp.toString().toIntOrNull() == targetEpNum) {
                                                    val sourceUrl = (it["sourceUrl"] ?: it["resourceLink"])?.toString() ?: ""
                                                    if (sourceUrl.isNotEmpty() && !sourceUrl.contains("macdn.aoneroom.com") && !sourceUrl.contains("b164fbfb43")) {
                                                        val sniffRaw = makeOfficialRequest(
                                                            "GET",
                                                            "/wefeed-mobile-bff/sniff/config",
                                                            mapOf("linkUrl" to sourceUrl, "subjectId" to candSubId)
                                                        )
                                                        val sniffMap = parseJsonToMap(sniffRaw)
                                                        val sniffData = sniffMap["data"] as? Map<String, Any?>
                                                        val dlUrl = sniffData?.get("downloadUrl")?.toString() ?: ""
                                                        if (dlUrl.isNotEmpty() && !dlUrl.contains("macdn.aoneroom.com") && !dlUrl.contains("b164fbfb43") && !ugcStreams.any { it["url"] == dlUrl }) {
                                                            val snRes = (it["resolution"] ?: it["resolutions"] ?: 720).toString().filter { c -> c.isDigit() }.toIntOrNull() ?: 720
                                                            val snCodec = it["codecName"]?.toString() ?: "hevc"
                                                            val snSize = it["size"]?.toString() ?: "${snRes}p HD"
                                                            ugcStreams.add(mutableMapOf(
                                                                "url" to dlUrl,
                                                                "resolution" to snRes,
                                                                "quality" to "${snRes}p",
                                                                "codecName" to snCodec,
                                                                "size" to snSize,
                                                                "id" to (it["resourceId"]?.toString() ?: System.currentTimeMillis().toString()),
                                                                "format" to "MP4"
                                                            ))
                                                            Log.i("LocalMovieBoxServer", "SNIFFER SUCCESS: Recovered stream for ep $targetEpNum (${snRes}p): $dlUrl")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    } catch (e: Exception) {
                                        Log.w("LocalMovieBoxServer", "Sniffer episode resolution failed: ${e.message}")
                                    }

                                    // Engine 3: Movie detector sniffer
                                    if (isMovie) {
                                        for (d in allDetectors) {
                                            val directDl = d["downloadUrl"]?.toString() ?: ""
                                            val rLink = (d["resourceLink"] ?: d["sourceUrl"])?.toString() ?: ""
                                            if (directDl.isNotEmpty() && !directDl.contains("macdn.aoneroom.com") && !directDl.contains("b164fbfb43") && !ugcStreams.any { it["url"] == directDl }) {
                                                val mutableD = d.toMutableMap()
                                                mutableD["url"] = directDl
                                                val dRes = (d["resolutions"] ?: d["resolution"] ?: 1080).toString().filter { c -> c.isDigit() }.toIntOrNull() ?: 1080
                                                mutableD["resolution"] = dRes
                                                mutableD["quality"] = "${dRes}p"
                                                ugcStreams.add(mutableD)
                                            } else if (rLink.isNotEmpty() && !rLink.contains("macdn.aoneroom.com") && !rLink.contains("b164fbfb43")) {
                                                try {
                                                    val sniffRaw = makeOfficialRequest(
                                                        "GET",
                                                        "/wefeed-mobile-bff/sniff/config",
                                                        mapOf("linkUrl" to rLink, "subjectId" to candSubId)
                                                    )
                                                    val sniffMap = parseJsonToMap(sniffRaw)
                                                    val sniffData = sniffMap["data"] as? Map<String, Any?>
                                                    val dlUrl = sniffData?.get("downloadUrl")?.toString() ?: ""
                                                    if (dlUrl.isNotEmpty() && !dlUrl.contains("macdn.aoneroom.com") && !dlUrl.contains("b164fbfb43") && !ugcStreams.any { it["url"] == dlUrl }) {
                                                        val mutableD = d.toMutableMap()
                                                        mutableD["url"] = dlUrl
                                                        val dRes = (d["resolutions"] ?: d["resolution"] ?: 1080).toString().filter { c -> c.isDigit() }.toIntOrNull() ?: 1080
                                                        mutableD["resolution"] = dRes
                                                        mutableD["quality"] = "${dRes}p"
                                                        ugcStreams.add(mutableD)
                                                        Log.i("LocalMovieBoxServer", "SNIFFER MOVIE SUCCESS: $dlUrl")
                                                    }
                                                } catch (_: Exception) {}
                                            }
                                        }
                                    }
                                }

                                if (ugcStreams.isNotEmpty()) {
                                    streams = ugcStreams
                                    Log.i("LocalMovieBoxServer", "UGC BYPASS SUCCESS: Recovered ${ugcStreams.size} REAL streams for episode $targetEpNum!")
                                }

                            } catch (e: Exception) {
                                Log.w("LocalMovieBoxServer", "UGC bypass failed: ${e.message}")
                            }
                        }

                        val prioritizedStreams = streams.map { it }

                        var workingUrl = ""

                        var workingCookie = globalCookie.toString()

                        if (prioritizedStreams.isNotEmpty()) {
                            val sortedDescending = prioritizedStreams.sortedWith { s1, s2 ->

                                val u1 = (s1["url"] ?: "").toString().lowercase()

                                val u2 = (s2["url"] ?: "").toString().lowercase()

                                fun getScore(u: String): Int {
                                    val isH265 = u.contains("h265") || u.contains("x265") || u.contains("hev1")

                                    if (u.contains(".m3u8")) return if (isH265) 3 else 10

                                    if (u.contains(".mpd")) return if (isH265) 2 else 9

                                    if (u.contains(".mp4")) return if (isH265) 1 else 8

                                    return if (isH265) 0 else 5

                                }

                                getScore(u2).compareTo(getScore(u1))

                            }

                            val bestStream = prioritizedStreams.maxWithOrNull { s1, s2 ->
                                val r1 = (s1["resolutions"] ?: s1["resolution"] ?: s1["quality"] ?: "").toString().filter { it.isDigit() }.toIntOrNull() ?: 0
                                val r2 = (s2["resolutions"] ?: s2["resolution"] ?: s2["quality"] ?: "").toString().filter { it.isDigit() }.toIntOrNull() ?: 0
                                if (r1 != r2) {
                                    r1.compareTo(r2)
                                } else {
                                    val size1 = (s1["size"] ?: "").toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                                    val size2 = (s2["size"] ?: "").toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                                    size1.compareTo(size2)
                                }
                            } ?: sortedDescending[0]

                            var url = bestStream["url"]?.toString() ?: ""

                            val bestCookie = bestStream["signCookie"]?.toString() ?: ""

                            var isDummy = url.contains("macdn.aoneroom.com") || url.contains("please_update") || url.contains("update_app")

                            // Anti-Piracy Dummy Video Bypass

                            if (bestCookie.contains("urlprefix=")) {
                                try {
                                    val prefixStart = bestCookie.indexOf("urlprefix=") + "urlprefix=".length

                                    var prefixEnd = bestCookie.indexOf(":", prefixStart)

                                    if (prefixEnd == -1) prefixEnd = bestCookie.indexOf(";", prefixStart)

                                    if (prefixEnd == -1) prefixEnd = bestCookie.length

                                    var base64Str = bestCookie.substring(prefixStart, prefixEnd)

                                    val padding = (4 - base64Str.length % 4) % 4

                                    base64Str += "=".repeat(padding)

                                    val decodedPathBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)

                                    val decodedPath = String(decodedPathBytes, StandardCharsets.UTF_8)

                                    url = if (decodedPath.endsWith("/")) "${decodedPath}index.m3u8" else "${decodedPath}/index.m3u8"

                                    isDummy = false // Bypass successful, no longer a dummy!

                                    Log.i("LocalMovieBoxServer", "Dummy video bypassed. Real URL: $url")

                                } catch (e: Exception) {
                                    Log.w("LocalMovieBoxServer", "Failed to bypass dummy video: ${e.message}")

                                }

                            }

                            if (isDummy) {
                                Log.w("LocalMovieBoxServer", "Skipping un-bypassed dummy video trap: $url")

                                workingUrl = ""

                            } else {
                                workingUrl = url

                            }

                            if (bestCookie.isNotEmpty()) {
                                workingCookie = bestCookie

                            }

                        }

                        if (workingUrl.isEmpty() && prioritizedStreams.isNotEmpty()) {
                            // Try to find any fallback stream that isn't a dummy

                            for (st in prioritizedStreams) {
                                val fallbackUrl = st["url"]?.toString() ?: ""

                                val fallbackIsDummy = fallbackUrl.contains("macdn.aoneroom.com") || fallbackUrl.contains("please_update") || fallbackUrl.contains("update_app")

                                if (!fallbackIsDummy && fallbackUrl.isNotEmpty()) {
                                    workingUrl = fallbackUrl

                                    workingCookie = st["signCookie"]?.toString() ?: ""

                                    break

                                }

                            }

                        }

                        val subtitlesMapped = mutableListOf<Map<String, Any?>>()

                        // Helper to fetch and extract captions for a given subjectId + resourceId

                        fun fetchCaptions(targetSubId: String, targetResId: String, extraLan: String? = null) {
                            try {
                                val subParams = mutableMapOf(

                                    "subjectId" to targetSubId,

                                    "resourceId" to targetResId

                                )

                                if (!isMovie) {
                                    subParams["se"] = season

                                    subParams["ep"] = episode

                                }

                                if (!extraLan.isNullOrEmpty()) {
                                    subParams["lan"] = extraLan

                                }

                                val subResponseRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get-ext-captions", subParams)

                                val subResponseMap = parseJsonToMap(subResponseRaw)

                                val subData = subResponseMap["data"] as? Map<String, Any?> ?: emptyMap()

                                val extList = (subData["extCaptions"] ?: subData["list"] ?: emptyList<Any?>()) as? List<Map<String, Any?>> ?: emptyList()

                                for (sub in extList) {
                                    val lan = sub["lan"]?.toString() ?: ""

                                    val lanName = sub["lanName"]?.toString() ?: ""

                                    val subUrl = sub["url"]?.toString() ?: ""

                                    if (subUrl.isNotEmpty() && !subtitlesMapped.any { it["url"] == subUrl }) {
                                        subtitlesMapped.add(mapOf(

                                            "lan" to lan,

                                            "lanName" to lanName,

                                            "url" to subUrl

                                        ))

                                    }

                                }

                            } catch (e: Exception) {
                                Log.w("LocalMovieBoxServer", "Failed to fetch external subtitles for ($targetSubId, $targetResId): ${e.message}")

                            }

                        }

                        // 1. Fetch external subtitles (get-ext-captions)

                        val targetResources = mutableListOf<Pair<String, String>>() // Pair(subjectId, resourceId)

                        // (A) Always fetch from the original/main subject (where the full multi-language subtitle pack is located)

                        var mainDetectors = allDetectors

                        if (mainSubjectId != subjectId) {
                            try {
                                val mainDetailRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to mainSubjectId))

                                val mainDetailMap = parseJsonToMap(mainDetailRaw)

                                val mainDetailData = mainDetailMap["data"] as? Map<String, Any?>

                                val dList = mainDetailData?.get("resourceDetectors") as? List<Map<String, Any?>> ?: emptyList()

                                if (dList.isNotEmpty()) {
                                    mainDetectors = dList

                                }

                            } catch (_: Exception) {}

                        }

                        // Add all detectors of main/original subject

                        for (d in mainDetectors) {
                            val rId = d["resourceId"]?.toString()

                            if (!rId.isNullOrEmpty()) {
                                targetResources.add(Pair(mainSubjectId, rId))

                            }

                        }

                        // (B) Also add all detectors of the current subject (if different from main)

                        if (mainSubjectId != subjectId) {
                            for (d in allDetectors) {
                                val rId = d["resourceId"]?.toString()

                                if (!rId.isNullOrEmpty()) {
                                targetResources.add(Pair(subjectId, rId))

                                }

                            }

                        }

                        // (C) Also include the English dub subject if available and different

                        val engDub = allDubs.find { dub ->

                            val lan = (dub["lanName"] ?: dub["lan"] ?: "").toString().lowercase()

                            lan.contains("eng")

                        }

                        val engSubId = engDub?.get("subjectId")?.toString()

                        if (!engSubId.isNullOrEmpty() && engSubId != mainSubjectId && engSubId != subjectId) {
                            try {
                                val engDetailRaw = makeOfficialRequest("GET", "/wefeed-mobile-bff/subject-api/get", mapOf("subjectId" to engSubId))

                                val engDetailMap = parseJsonToMap(engDetailRaw)

                                val engDetailData = engDetailMap["data"] as? Map<String, Any?>

                                val engDList = engDetailData?.get("resourceDetectors") as? List<Map<String, Any?>> ?: emptyList()

                                for (d in engDList) {
                                    val rId = d["resourceId"]?.toString()

                                    if (!rId.isNullOrEmpty()) {
                                        targetResources.add(Pair(engSubId, rId))

                                    }

                                }

                            } catch (_: Exception) {}

                        }

                        // (D) Include the currently active resourceId if provided

                        if (!finalResourceId.isNullOrEmpty()) {
                            targetResources.add(Pair(finalSubjectId, finalResourceId))

                        }

                        targetResources.add(Pair(finalSubjectId, subResourceId))

                        targetResources.add(Pair(subjectId, subResourceId))

                        val uniqueTargets = targetResources.distinct()

                        for (target in uniqueTargets) {
                            fetchCaptions(target.first, target.second)

                        }

                        // 2. Fetch stream-internal subtitles and merge

                        val subtitleList = data["subTitleList"] as? List<Map<String, Any?>> ?: emptyList()

                        for (sub in subtitleList) {
                            val lan = sub["lan"]?.toString() ?: ""

                            val lanName = sub["lanName"]?.toString() ?: ""

                            val subUrl = sub["url"]?.toString() ?: ""

                            if (subUrl.isNotEmpty() && !subtitlesMapped.any { it["url"] == subUrl }) {
                                subtitlesMapped.add(mapOf(

                                    "lan" to lan,

                                    "lanName" to lanName,

                                    "url" to subUrl

                                ))

                            }

                        }

                        // 3. Tier 2: OpenSubtitles v1 Fallback (Reverse-engineered from MovieBox com.transsion.subtitle.helper.d)
                        if (subtitlesMapped.isEmpty() && movieTitle.isNotBlank()) {
                            try {
                                val openSub = com.example.util.OpenSubtitlesHelper.fetchSubtitle(
                                    client = okHttpClient,
                                    title = movieTitle,
                                    isMovie = isMovie,
                                    season = resSe?.toIntOrNull(),
                                    episode = resEp?.toIntOrNull(),
                                    language = "en"
                                )
                                if (openSub != null) {
                                    subtitlesMapped.add(mapOf(
                                        "lan" to openSub.language,
                                        "lanName" to openSub.languageName,
                                        "url" to openSub.url
                                    ))
                                }
                            } catch (e: Exception) {
                                Log.w("LocalMovieBoxServer", "OpenSubtitles fallback exception: ${e.message}")
                            }
                        }

                        // Sort subtitles so English and Original appear first in the UI

                        val sortedSubtitles = subtitlesMapped.sortedWith { a, b ->

                            val aLan = (a["lanName"] ?: a["lan"] ?: "").toString().lowercase()

                            val bLan = (b["lanName"] ?: b["lan"] ?: "").toString().lowercase()

                            val aScore = if (aLan.contains("eng")) 2 else if (aLan.contains("orig")) 1 else 0

                            val bScore = if (bLan.contains("eng")) 2 else if (bLan.contains("orig")) 1 else 0

                            bScore.compareTo(aScore)

                        }

                        // Gather all qualities from all available streams
                        val discoveredResolutions = linkedSetOf<Int>()
                        val distinctStreams = mutableListOf<Map<String, Any?>>()
                        val seenStreamUrls = mutableSetOf<String>()

                        // Official displayResolutions from /subject-api/play-info/v2 (Section 3.A)
                        val displayResolutionsStr = (data["displayResolutions"] ?: "").toString()
                        if (displayResolutionsStr.isNotEmpty()) {
                            displayResolutionsStr.split(",").forEach { r ->
                                val num = r.filter { it.isDigit() }.toIntOrNull()
                                if (num != null && num > 0) discoveredResolutions.add(num)
                            }
                        }

                        for (st in prioritizedStreams) {
                            var sUrl = st["url"]?.toString() ?: ""
                            val sCookie = st["signCookie"]?.toString() ?: workingCookie
                            if (sCookie.contains("urlprefix=")) {
                                try {
                                    val prefixStart = sCookie.indexOf("urlprefix=") + "urlprefix=".length
                                    var prefixEnd = sCookie.indexOf(":", prefixStart)
                                    if (prefixEnd == -1) prefixEnd = sCookie.indexOf(";", prefixStart)
                                    if (prefixEnd == -1) prefixEnd = sCookie.length
                                    var base64Str = sCookie.substring(prefixStart, prefixEnd)
                                    val padding = (4 - base64Str.length % 4) % 4
                                    base64Str += "=".repeat(padding)
                                    val decodedPathBytes = android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
                                    val decodedPath = String(decodedPathBytes, StandardCharsets.UTF_8)
                                    sUrl = if (decodedPath.endsWith("/")) "${decodedPath}index.m3u8" else "${decodedPath}/index.m3u8"
                                } catch (_: Exception) {}
                            }
                            val isDummy = sUrl.contains("macdn.aoneroom.com") || sUrl.contains("please_update") || sUrl.contains("update_app") || sUrl.contains("b164fbfb43")

                            val resStr = st["resolutions"]?.toString() ?: ""
                            if (resStr.isNotEmpty()) {
                                resStr.split(",").forEach { r ->
                                    val num = r.filter { it.isDigit() }.toIntOrNull()
                                    if (num != null && num > 0) discoveredResolutions.add(num)
                                }
                            }

                            val singleRes = (st["resolution"] ?: st["quality"] ?: st["definition"] ?: "").toString()
                            val num = singleRes.filter { it.isDigit() }.toIntOrNull()
                            if (num != null && num > 0) discoveredResolutions.add(num)

                            if (sUrl.isNotEmpty() && !isDummy && !seenStreamUrls.contains(sUrl)) {
                                seenStreamUrls.add(sUrl)
                                val qLabel = if (num != null && num > 0) "${num}p" else singleRes.ifEmpty { "Auto" }
                                distinctStreams.add(mapOf(
                                    "quality" to qLabel,
                                    "url" to sUrl,
                                    "size" to (st["size"]?.toString() ?: if (num != null) "${num}p HD" else "Dynamic"),
                                    "cookie" to sCookie
                                ))
                            }
                        }

                        // Inspect master playlist if HLS
                        if (workingUrl.contains(".m3u8")) {
                            try {
                                val m3u8Req = Request.Builder().url(workingUrl)
                                if (workingCookie.isNotEmpty()) m3u8Req.header("Cookie", workingCookie)
                                m3u8Req.header("User-Agent", "MovieBox/4.0.02 (Android 14; Pixel 6)")
                                val m3u8Resp = okHttpClient.newCall(m3u8Req.build()).execute()
                                if (m3u8Resp.isSuccessful) {
                                    val m3u8Body = m3u8Resp.body?.string() ?: ""
                                    val resMatches = Regex("""RESOLUTION=\d+x(\d+)""").findAll(m3u8Body)
                                    for (m in resMatches) {
                                        val h = m.groupValues[1].toIntOrNull()
                                        if (h != null && h > 0) discoveredResolutions.add(h)
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        // Only provide fallback resolutions if none were detected
                        if (discoveredResolutions.isEmpty()) {
                            discoveredResolutions.addAll(listOf(1080, 720, 480))
                        }

                        val sortedResolutionsList = discoveredResolutions.sortedDescending()
                        val resolutionsStr = sortedResolutionsList.joinToString(",")

                        val finalStreamList = mutableListOf<Map<String, Any?>>()
                        // First item is always Auto
                        finalStreamList.add(mapOf(
                            "quality" to "Auto",
                            "url" to workingUrl,
                            "size" to "Auto",
                            "cookie" to workingCookie
                        ))

                        // Add all qualities (1080p, 720p, 480p, 360p...)
                        for (res in sortedResolutionsList) {
                            val matchingDistinct = distinctStreams.filter { 
                                val qDigits = (it["quality"] as? String)?.filter { c -> c.isDigit() }?.toIntOrNull()
                                qDigits == res
                            }.maxByOrNull { 
                                (it["size"]?.toString() ?: "").filter { c -> c.isDigit() }.toLongOrNull() ?: 0L 
                            }
                            val streamUrl = matchingDistinct?.get("url")?.toString() ?: workingUrl
                            val streamCookie = matchingDistinct?.get("cookie")?.toString() ?: workingCookie
                            finalStreamList.add(mapOf(
                                "quality" to "${res}p",
                                "url" to streamUrl,
                                "size" to (matchingDistinct?.get("size")?.toString() ?: "${res}p HD"),
                                "cookie" to streamCookie
                            ))
                        }

                        val responseBody = mapOf(
                            "code" to 0,
                            "url" to workingUrl,
                            "quality" to quality,
                            "cookie" to workingCookie,
                            "resolutions" to resolutionsStr,
                            "streamList" to finalStreamList,
                            "subtitles" to sortedSubtitles
                        )

                        Pair(200, toJsonString(responseBody))

                    } catch (e: Exception) {
                        Log.w("LocalMovieBoxServer", "Stream fallback triggered: ${e.message}")

                        val responseBody = mapOf(

                            "code" to 0,

                            "url" to "",

                            "quality" to quality,

                            "cookie" to "",

                            "subtitles" to listOf(

                                mapOf("lan" to "en", "lanName" to "English", "url" to "https://raw.githubusercontent.com/andreyvit/subtitle-tools/master/test.srt")

                            )

                        )

                        Pair(200, toJsonString(responseBody))

                    }

                }

                else -> {
                    // Category list handlers

                    var categoryId: Int? = null

                    var page = "1"

                    if (path == "/home/v2/get-list") {
                        try {
                            val requestMap = parseJsonToMap(body)

                            categoryId = requestMap["categoryId"]?.toString()?.toDoubleOrNull()?.toInt()

                            page = requestMap["page"]?.toString()?.toDoubleOrNull()?.toInt()?.toString() ?: "1"

                        } catch (e: Exception) {
                            Log.e("LocalMovieBoxServer", "Error parsing get-list body", e)

                        }

                    } else {
                        categoryId = when {
                            path == "/movies" -> 1

                            path == "/series" -> 5

                            path == "/anime" -> 8

                            path == "/asian" -> 18

                            path == "/western" -> 19

                            path == "/short-tv" -> 13

                            path == "/kids" -> 23

                            path == "/education" -> 3

                            path == "/music" -> 4

                            path == "/nollywood" -> 28

                            path == "/game" -> 11

                            path.startsWith("/category/") -> path.removePrefix("/category/").toIntOrNull()

                            else -> null

                        }

                        if (qParams.containsKey("page")) {
                            page = qParams["page"] ?: "1"

                        }

                    }

                    if (categoryId != null) {
                        try {
                            var rawResponse = makeOfficialRequest(

                                "GET",

                                "/wefeed-mobile-bff/tab-operating",

                                mapOf("tabId" to categoryId.toString(), "page" to page, "pageSize" to "50")

                            )

                            var responseMap = parseJsonToMap(rawResponse)

                            var dataMap = responseMap["data"] as? Map<String, Any?>

                            var list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>> 

                                ?: responseMap["data"] as? List<Map<String, Any?>> 

                                ?: emptyList()

                            var formatted = formatTabSections(list)

                            // If the requested tabId (e.g., Anime=8) returns empty from the official API, 

                            // fallback to tabId=1 (Home/Movies) so we at least show content!

                            if (formatted.isEmpty()) {
                                Log.w("LocalMovieBoxServer", "Empty list for tabId $categoryId, falling back to tabId=1")

                                rawResponse = makeOfficialRequest(

                                    "GET",

                                    "/wefeed-mobile-bff/tab-operating",

                                    mapOf("tabId" to "1", "page" to page, "pageSize" to "50")

                                )

                                responseMap = parseJsonToMap(rawResponse)

                                dataMap = responseMap["data"] as? Map<String, Any?>

                                list = (dataMap?.get("list") ?: dataMap?.get("items") ?: dataMap?.get("subjects")) as? List<Map<String, Any?>> 

                                    ?: responseMap["data"] as? List<Map<String, Any?>> 

                                    ?: emptyList()

                                formatted = formatTabSections(list)

                            }

                            if (formatted.isEmpty()) {
                                throw Exception("Empty list from official API")

                            }

                            val allMovies = mutableListOf<Map<String, Any?>>()

                            formatted.forEach { sec ->

                                (sec["items"] as? List<Map<String, Any?>>)?.let { allMovies.addAll(it) }

                            }

                            val responseBody = mapOf(

                                "code" to 0,

                                "data" to mapOf(

                                    "sections" to formatted,

                                    "list" to allMovies,

                                    "items" to allMovies

                                )

                            )

                            Pair(200, toJsonString(responseBody))

                        } catch (e: Exception) {
                            Log.w("LocalMovieBoxServer", "Category fallback triggered: ${e.message}")

                            val responseBody = mapOf(

                                "code" to 0,

                                "data" to mapOf(

                                    "sections" to emptyList<Map<String, Any?>>(),

                                    "list" to emptyList<Map<String, Any?>>(),

                                    "items" to emptyList<Map<String, Any?>>()

                                )

                            )

                            Pair(200, toJsonString(responseBody))

                        }

                    } else {
                        Pair(404, "{\"code\": 404, \"msg\": \"Not Found\"}")

                    }

                }

            }

        } catch (e: Exception) {
            Log.e("LocalMovieBoxServer", "Error routing request $path", e)

            Pair(500, "{\"code\": 500, \"msg\": \"${e.localizedMessage}\"}")

        }

    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()

        if (query.isEmpty()) return result

        query.split("&").forEach { param ->

            val parts = param.split("=", limit = 2)

            val key = URLDecoder.decode(parts[0], "UTF-8")

            val value = if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else ""

            result[key] = value

        }

        return result

    }

    private fun formatTabSections(items: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val sections = mutableListOf<Map<String, Any?>>()

        var isDirectMovies = true

        for (row in items) {
            if (row.containsKey("list") || row.containsKey("items") || row.containsKey("subjects") || row.containsKey("movieList") || row.containsKey("customData") || row.containsKey("banner") || row.containsKey("rankingListData")) {
                isDirectMovies = false

                break

            }

        }

        if (isDirectMovies && items.isNotEmpty()) {
            val mapped = items.mapNotNull { mapItem(it) }

            return listOf(mapOf(

                "title" to "Movies",

                "type" to "SUBJECTS_MOVIE",

                "items" to mapped

            ))

        }

        for (row in items) {
            val title = row["title"] ?: row["name"] ?: "Section"

                        val titleStr = (row["title"] ?: row["name"] ?: "Section").toString()

            val rowType = (row["type"] ?: row["subjectType"] ?: "").toString()

            if (titleStr.contains("Join Us", ignoreCase = true) ||

                titleStr.contains("Star", ignoreCase = true) ||

                titleStr.contains("Actor", ignoreCase = true) ||

                titleStr.contains("Short", ignoreCase = true) ||

                titleStr.contains("Watch on TV", ignoreCase = true) ||

                titleStr.contains("Get Free Premium", ignoreCase = true) ||

                titleStr.contains("Diwali", ignoreCase = true) ||

                titleStr.contains("Offer", ignoreCase = true) ||

                titleStr.equals("Categories", ignoreCase = true) ||

                rowType.contains("FILTER", ignoreCase = true) ||

                rowType.contains("SINGLE_IMAGE", ignoreCase = true) ||

                rowType.contains("ACTOR", ignoreCase = true) ||

                rowType.contains("STAR", ignoreCase = true)

            ) continue

            val realMovies = mutableListOf<Map<String, Any?>>()

            val inner = when {
                (row["customData"] as? Map<*, *>)?.get("items").let { it is List<*> && it.isNotEmpty() } -> {
                    val customData = row["customData"] as Map<String, Any?>

                    customData["items"] as List<Map<String, Any?>>

                }

                (row["banner"] as? Map<*, *>)?.get("banners").let { it is List<*> && it.isNotEmpty() } -> {
                    val banner = row["banner"] as Map<String, Any?>

                    val bannerList = banner["banners"] as List<Map<String, Any?>>
                    bannerList.filter { b ->
                        val hasRes = b["hasResource"] as? Boolean ?: false
                        val sub = b["subject"] as? Map<*, *>
                        val subId = (b["subjectId"] ?: "").toString()
                        val content = (b["content"] ?: "").toString()
                        hasRes && sub != null && subId != "0" && subId.isNotBlank() &&
                            !content.contains("Diwali", ignoreCase = true) &&
                            !content.contains("Offer", ignoreCase = true) &&
                            !content.contains("Premium", ignoreCase = true) &&
                            !content.contains("TV", ignoreCase = true) &&
                            !content.contains("Join", ignoreCase = true)
                    }

                }

                row["rankingListData"].let { it is Map<*, *> && it.isNotEmpty() } -> {
                    val ranking = row["rankingListData"] as Map<String, Any?>

                    ranking["items"] as? List<Map<String, Any?>> ?: emptyList()

                }

                row["list"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["list"] as List<Map<String, Any?>>

                }

                row["items"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["items"] as List<Map<String, Any?>>

                }

                row["subjects"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["subjects"] as List<Map<String, Any?>>

                }

                row["movieList"].let { it is List<*> && it.isNotEmpty() } -> {
                    row["movieList"] as List<Map<String, Any?>>

                }

                else -> emptyList()

            }

            for (i in inner) {
                val subject = i["subject"] as? Map<String, Any?>

                if (subject != null) {
                    realMovies.add(subject)

                } else if (i.containsKey("subjectId") || i.containsKey("id")) {
                    realMovies.add(i)

                }

            }

            if (realMovies.isNotEmpty()) {
                val mapped = realMovies.mapNotNull { mapItem(it) }

                if (mapped.isNotEmpty()) {
                    sections.add(mapOf(

                        "title" to title,

                        "type" to (row["subjectType"] ?: row["type"] ?: "SUBJECTS_MOVIE"),

                        "items" to mapped

                    ))

                }

            }

        }

        return sections

    }

    private fun mapItem(item: Map<String, Any?>): Map<String, Any?>? {
        val itemData = (item["subject"] as? Map<String, Any?>) ?: item

        val sid = (itemData["subjectId"] ?: itemData["id"] ?: "").toString()

        if (sid.isEmpty() || sid == "0") return null

        val title = (

            itemData["title"] ?: 

            itemData["name"] ?: 

            itemData["subjectName"] ?: 

            itemData["subject_name"] ?: 

            itemData["categoryName"] ?: 

            itemData["content"] ?: 

            itemData["keyword"] ?: 

            itemData["keywordName"] ?: 

            itemData["itemName"] ?: 

            itemData["show_name"] ?: 

            itemData["showTitle"] ?: 

            itemData["titleName"] ?: 

            itemData["title_en"] ?: 

            itemData["tag"] ?: 

            itemData["label"] ?: 

            item["title"] ?:

            item["name"] ?:

            "Unknown"

        ).toString()

        if (title.contains("Diwali Dhamaka", ignoreCase = true) ||
            title.contains("Special Offer", ignoreCase = true) ||
            title.contains("Free Premium", ignoreCase = true) ||
            title.contains("Join Communities", ignoreCase = true) ||
            title.contains("MovieBoxTV", ignoreCase = true)
        ) return null

        var posterUrl = ""

        val poster = itemData["poster"] ?: itemData["image"] ?: itemData["img"] ?: itemData["banner"] ?: item["poster"] ?: item["image"]

        if (poster is Map<*, *>) {
            posterUrl = poster["url"]?.toString() ?: ""

        } else if (poster is String) {
            posterUrl = poster

        }

        if (posterUrl.isEmpty()) {
            val cover = itemData["cover"]

            if (cover is Map<*, *>) {
                posterUrl = cover["url"]?.toString() ?: ""

            } else if (cover is String) {
                posterUrl = cover

            }

        }

        if (posterUrl.isEmpty()) {
            val hp = itemData["horizontalPoster"] ?: itemData["horizontalCover"]

            if (hp is Map<*, *>) {
                posterUrl = hp["url"]?.toString() ?: ""

            } else if (hp is String) {
                posterUrl = hp

            }

        }

        if (posterUrl.startsWith("//")) {
            posterUrl = "https:$posterUrl"

        }

        val score = (itemData["imdbRatingValue"] ?: itemData["imdbRate"] ?: itemData["starRating"] ?: itemData["score"] ?: "8.5").toString()

        val releaseDate = (itemData["releaseDate"] ?: itemData["releaseTime"] ?: itemData["year"] ?: "2026").toString()

        val year = if (releaseDate.length >= 4) releaseDate.substring(0, 4) else "2026"

        val runtime = itemData["duration"] ?: itemData["runtime"] ?: itemData["minute"] ?: ""

        if (title.isEmpty() || title == "Unknown" || title.isBlank() || posterUrl.isEmpty() || posterUrl.isBlank()) {
            return null

        }

        var blurHash = ""
        var averageHue = ""
        val coverObj = (itemData["cover"] as? Map<*, *>) ?: (itemData["poster"] as? Map<*, *>) ?: (itemData["horizontalPoster"] as? Map<*, *>)
        if (coverObj != null) {
            blurHash = (coverObj["thumbnail"] ?: coverObj["blurHash"])?.toString() ?: ""
            averageHue = (coverObj["averageHueDark"] ?: coverObj["averageHueLight"] ?: coverObj["averageHue"])?.toString() ?: ""
        }
        if (blurHash.isEmpty()) {
            blurHash = (itemData["blurHash"] ?: itemData["thumbnail"])?.toString() ?: ""
        }
        if (averageHue.isEmpty()) {
            averageHue = (itemData["averageHueDark"] ?: itemData["averageHueLight"] ?: itemData["averageHue"])?.toString() ?: ""
        }

        val optimalPoster = com.example.util.ImageOptimizer.getOptimalUrl(posterUrl, 360)

        return mapOf(

            "subjectId" to sid,

            "id" to sid,

            "title" to title,

            "name" to title,

            "poster" to optimalPoster,

            "cover" to optimalPoster,

            "originalPoster" to posterUrl,

            "blurHash" to blurHash,

            "averageHue" to averageHue,

            "rating" to score,

            "score" to score,

            "year" to year,

            "releaseTime" to year,

            "subjectType" to (itemData["subjectType"] ?: itemData["type"] ?: 1),

            "runtime" to runtime,

            "description" to (itemData["description"] ?: "")

        )

    }

    private fun makeOfficialRequest(

        method: String,

        endpoint: String,

        queryParams: Map<String, String> = emptyMap(),

        bodyString: String = ""

    ): String {
        val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

        val spCode = prefs.getString("sp_code", "")?.takeIf { it.isNotEmpty() } ?: "404"

        val region = prefs.getString("custom_local_iso", "")?.takeIf { it.isNotEmpty() }?.uppercase() ?: "IN"

        val timezone = if (region == "US") "America/New_York" else if (region == "BR") "America/Sao_Paulo" else if (region == "RU") "Europe/Moscow" else "Asia/Kolkata"

        val isCacheable = (method == "GET" && !endpoint.contains("profile") && !endpoint.contains("see-list-v2") && !endpoint.contains("tab-operating")) || endpoint.contains("/subject-api/get") || endpoint.contains("/subject-api/trending") || endpoint.contains("/subject-api/top-rec") || endpoint.contains("/subject-api/search") || endpoint.contains("/subject-api/search-rank") || endpoint.contains("/subject-api/season-info") || endpoint.contains("/subject-api/play-info") || endpoint.contains("/subject-api/get-ext-captions")

        val cacheKey = "$method|$endpoint|${queryParams.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}|$bodyString|$region|$spCode"

        if (isCacheable) {
            val now = System.currentTimeMillis()

            val cached = responseCache[cacheKey]

            if (cached != null && (now - cached.first) < 300000) {
                return cached.second

            }

            val cachedDiskStr = diskCachePrefs.getString(cacheKey, null)

            if (cachedDiskStr != null) {
                val pipeIndex = cachedDiskStr.indexOf('|')

                if (pipeIndex != -1) {
                    val timeStr = cachedDiskStr.substring(0, pipeIndex)

                    val body = cachedDiskStr.substring(pipeIndex + 1)

                    val timeVal = timeStr.toLongOrNull() ?: 0L

                    if ((now - timeVal) < 300000) {
                        responseCache[cacheKey] = Pair(timeVal, body)

                        return body

                    }

                }

            }

        }

        val timestamp = System.currentTimeMillis().toString()

        val reversed = timestamp.reversed()

        val clientToken = "$timestamp,${md5(reversed)}"

        val baseUrl = "https://api.inmoviebox.com"

        val fullUrlWithoutQuery = "$baseUrl$endpoint"

        val urlBuilder = fullUrlWithoutQuery.toHttpUrl().newBuilder()

        if (!queryParams.containsKey("host")) {
            urlBuilder.addQueryParameter("host", "api.inmoviebox.com")

        }

        queryParams.forEach { (k, v) ->

            urlBuilder.addQueryParameter(k, v)

        }

        val httpUrl = urlBuilder.build()

        val sortedQueryString = httpUrl.queryParameterNames.sorted().joinToString("&") { name ->

            val value = httpUrl.queryParameter(name) ?: ""

            "$name=$value"

        }

        var actualBodyLength = ""

        var bodyHash = ""

        if (bodyString.isNotEmpty()) {
            val bytes = bodyString.toByteArray(Charsets.UTF_8)

            actualBodyLength = bytes.size.toString()

            val limit = minOf(bytes.size, 102400)

            val subBytes = bytes.copyOfRange(0, limit)

            bodyHash = md5Bytes(subBytes)

        }

        val canonicalPathAndQuery = httpUrl.encodedPath + if (sortedQueryString.isNotEmpty()) "?$sortedQueryString" else ""

        val accept = "application/json"

        val contentType = "application/json;charset=UTF-8"

        val canonicalString = "$method\n$accept\n$contentType\n$actualBodyLength\n$timestamp\n$bodyHash\n$canonicalPathAndQuery"

        var signatureHeader = ""

        try {
            val keyStr = "76iRl07s0xSN9jqmEWAt79EBJZulIQIsV64FZr2O"

            val keyBytes = try {
                Base64.decode(keyStr, Base64.DEFAULT)

            } catch (e: Exception) {
                keyStr.toByteArray()

            }

            val signatureDigest = hmacMd5(keyBytes, canonicalString.toByteArray(Charsets.UTF_8))

            val base64Sig = Base64.encodeToString(signatureDigest, Base64.NO_WRAP)

            signatureHeader = "$timestamp|2|$base64Sig"

        } catch (e: Exception) {
            Log.e("LocalMovieBoxServer", "Error generating signature", e)

        }

        val mediaType = "application/json;charset=UTF-8".toMediaTypeOrNull()

        val requestBody = if (method == "POST" || method == "PUT") {
            RequestBody.create(mediaType, bodyString)

        } else null

        val clientInfoMap = mapOf(

            "package_name" to "com.community.oneroom",

            "version_name" to "4.0.02",

            "version_code" to 50020126,

            "os" to "android",

            "os_version" to "14",

            "install_ch" to "ps",

            "device_id" to getDeviceId(),

            "install_store" to "ps",

            "gaid" to "",

            "brand" to "Google",

            "model" to "Pixel 6",

            "system_language" to "en",

            "net" to "wifi",

            "region" to region,

            "timezone" to timezone,

            "sp_code" to spCode

        )

        val clientInfoJson = toJsonString(clientInfoMap)

        val acceptLanguage = when (region) {
            "IN" -> "en-IN,hi-IN;q=0.9,en;q=0.8"

            "US" -> "en-US,en;q=0.9"

            "BR" -> "pt-BR,pt;q=0.9,en;q=0.8"

            "RU" -> "ru-RU,ru;q=0.9,en;q=0.8"

            else -> "en-US,en;q=0.9"

        }

        val reqBuilder = Request.Builder()

            .url(httpUrl)

            .header("User-Agent", "MovieBox/4.0.02 (Android 14; Pixel 6)")

            .header("Accept", accept)

            .header("Accept-Language", acceptLanguage)

            .header("Content-Type", contentType)

            .header("X-Sign-Version", "2.0")

            .header("appid", "4U01pxRu278GqCZKY9")

            .header("region", region)

            .header("lang", if (region == "BR") "pt" else if (region == "RU") "ru" else "en")

            .header("os", "android")

            .header("X-Timestamp", timestamp)

            .header("Referer", "https://api.inmoviebox.com/")

            .header("X-Client-Token", clientToken)

            .header("x-tr-signature", signatureHeader)

            .header("X-Play-Mode", "2")

            .header("X-Client-Info", clientInfoJson)

        val userToken = prefs.getString("user_auth_token", null)

        val isUserLoggedIn = !userToken.isNullOrEmpty() && !isTokenExpired(userToken)

        var activeToken = if (isUserLoggedIn) userToken else (guestToken ?: prefs.getString("visitor_token", null))

        if (activeToken.isNullOrEmpty() || isTokenExpired(activeToken)) {
            if (endpoint != "/wefeed-mobile-bff/user-api/visitor-login") {
                guestToken = null

                bootstrapGuestToken()

                activeToken = guestToken ?: prefs.getString("visitor_token", null)

            } else {
                activeToken = null

            }

        }

        if (!activeToken.isNullOrEmpty()) {
            reqBuilder.header("Authorization", "Bearer $activeToken")

            reqBuilder.header("X-Client-Status", "0")

        } else {
            reqBuilder.header("X-Client-Status", "1")

        }

        reqBuilder.method(method, requestBody)

        var builtRequest = reqBuilder.build()

        var response = okHttpClient.newCall(builtRequest).execute()

        var resBody = response.body?.string() ?: ""

        // Dynamic Token Refresh via x-user response header

        val xUser = response.header("x-user") ?: response.header("X-User")

        if (!xUser.isNullOrEmpty()) {
            try {
                val uData = parseJsonToMap(xUser)

                val t = uData["token"]?.toString()

                if (!t.isNullOrEmpty()) {
                    if (isUserLoggedIn) {
                        prefs.edit().putString("user_auth_token", t).putString("auth_token", t).apply()

                    } else {
                        guestToken = t

                        prefs.edit().putString("visitor_token", t).putString("auth_token", t).apply()

                    }

                }

            } catch (_: Exception) {}

        }

        // Auto-recover from 401 JWT Token Expired by generating a fresh guest token and retrying immediately

        if (response.code == 401 || resBody.contains("JWT token has expired") || resBody.contains("\"code\":401") || resBody.contains("\"code\": 401")) {
            Log.w("LocalMovieBoxServer", "401 Expired Token encountered: bootstrapping a fresh guest token...")

            if (isUserLoggedIn) {
                prefs.edit().remove("user_auth_token").remove("user_info_json").apply()

            }

            guestToken = null

            prefs.edit().remove("visitor_token").remove("auth_token").apply()

            bootstrapGuestToken()

            val freshToken = guestToken ?: prefs.getString("visitor_token", null)

            if (!freshToken.isNullOrEmpty()) {
                val retryReq = builtRequest.newBuilder()

                    .header("Authorization", "Bearer $freshToken")

                    .header("X-Client-Status", "0")

                    .build()

                response.close()

                response = okHttpClient.newCall(retryReq).execute()

                resBody = response.body?.string() ?: ""

            }

        }

        if (isCacheable && resBody.isNotEmpty() && !resBody.contains("Internal Server Error") && !resBody.contains("JWT token has expired")) {
            val now = System.currentTimeMillis()

            responseCache[cacheKey] = Pair(now, resBody)

            diskCachePrefs.edit()

                .putString(cacheKey, "$now|$resBody")

                .apply()

        }

        return resBody

    }

    private fun isTokenExpired(jwt: String?): Boolean {
        if (jwt.isNullOrEmpty()) return true

        if (jwt == "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJ1aWQiOjcwNjU5NDg0MTAyMTM4MTYyMzIsInV0cCI6MSwiZXhwIjoxNzkxNzMyMjMzLCJpYXQiOjE3ODM5NTU5MzN9.7iyEzTj4vWAbOF0oXwNnZ0p3Nc1QaO6K9eMiGFyVfGs") return true

        return try {
            val parts = jwt.split(".")

            if (parts.size < 2) return true

            val payloadBytes = android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

            val payload = String(payloadBytes, Charsets.UTF_8)

            val expIdx = payload.indexOf("\"exp\":")

            if (expIdx != -1) {
                val start = expIdx + 6

                val end = payload.indexOfAny(charArrayOf(',', '}'), start)

                val expSec = payload.substring(start, if (end != -1) end else payload.length).trim().toLongOrNull()

                if (expSec != null) {
                    (expSec * 1000) <= (System.currentTimeMillis() + 60000)

                } else false

            } else false

        } catch (_: Exception) {
            true

        }

    }

    private fun bootstrapGuestToken() {
        try {
            android.util.Log.i("LocalMovieBoxServer", "Bootstrapping dynamic guest token via visitor-login...")

            val devId = getDeviceId()
            val visitorPayload = toJsonString(mapOf("device_id" to devId, "deviceId" to devId))

            val rawResponse = makeOfficialRequest(
                method = "POST",
                endpoint = "/wefeed-mobile-bff/user-api/visitor-login",
                queryParams = emptyMap(),
                bodyString = visitorPayload
            )

            val map = parseJsonToMap(rawResponse)

            val data = map["data"] as? Map<String, Any?>

            val token = data?.get("token")?.toString()

            if (!token.isNullOrEmpty()) {
                guestToken = token

                val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

                prefs.edit().putString("visitor_token", token).apply()

                if (prefs.getString("user_auth_token", null).isNullOrEmpty()) {
                    prefs.edit().putString("auth_token", token).apply()

                }

                android.util.Log.i("LocalMovieBoxServer", "Successfully bootstrapped dynamic visitor token: $token")

            } else {
                android.util.Log.e("LocalMovieBoxServer", "visitor-login did not return a token: $rawResponse")

            }

        } catch (e: Exception) {
            android.util.Log.e("LocalMovieBoxServer", "Error bootstrapping visitor token", e)

        }

    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("moviebox_prefs", Context.MODE_PRIVATE)

        var deviceId = prefs.getString("device_id", null)

        if (deviceId.isNullOrEmpty()) {
            deviceId = java.util.UUID.randomUUID().toString().replace("-", "")

            prefs.edit().putString("device_id", deviceId).apply()

        }

        return deviceId

    }

    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")

        val digest = md.digest(input.toByteArray(Charsets.UTF_8))

        return digest.joinToString("") { String.format("%02x", it) }

    }

    private fun md5Bytes(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")

        val digest = md.digest(bytes)

        return digest.joinToString("") { String.format("%02x", it) }

    }

    private fun hmacMd5(keyBytes: ByteArray, dataBytes: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacMD5")

        val secretKey = SecretKeySpec(keyBytes, "HmacMD5")

        mac.init(secretKey)

        return mac.doFinal(dataBytes)

    }

    private fun getMockTrending(): List<Map<String, Any?>> {
        return listOf(

            mapOf(

                "subjectId" to "11059",

                "id" to "11059",

                "title" to "The Matrix Resurrections",

                "cover" to "https://image.tmdb.org/t/p/w500/8cZ1777vX6Y1RLuSgEpY66i9d6y.jpg",

                "poster" to "https://image.tmdb.org/t/p/w500/8cZ1777vX6Y1RLuSgEpY66i9d6y.jpg",

                "score" to "7.2",

                "releaseTime" to "2021",

                "subjectType" to 1,

                "runtime" to "148 mins",

                "description" to "Return to a world of two realities: one, everyday life; the other, what lies behind it. To find out if his reality is a physical or mental construct, to truly know himself, Mr. Anderson will have to choose to follow the white rabbit once more."

            ),

            mapOf(

                "subjectId" to "10543",

                "id" to "10543",

                "title" to "Spider-Man: No Way Home",

                "cover" to "https://image.tmdb.org/t/p/w500/1g0dhvJI6H9Ygjr6YvA1vGZ6P6V.jpg",

                "poster" to "https://image.tmdb.org/t/p/w500/1g0dhvJI6H9Ygjr6YvA1vGZ6P6V.jpg",

                "score" to "8.7",

                "releaseTime" to "2021",

                "subjectType" to 1,

                "runtime" to "148 mins",

                "description" to "Peter Parker is unmasked and no longer able to separate his normal life from the high-stakes of being a super-hero. When he asks for help from Doctor Strange the stakes become even more dangerous, forcing him to discover what it truly means to be Spider-Man."

            ),

            mapOf(

                "subjectId" to "11382",

                "id" to "11382",

                "title" to "Dune: Part Two",

                "cover" to "https://image.tmdb.org/t/p/w500/cz062Sg88Vv6RAsmY8g8b0GgX6n.jpg",

                "poster" to "https://image.tmdb.org/t/p/w500/cz062Sg88Vv6RAsmY8g8b0GgX6n.jpg",

                "score" to "8.9",

                "releaseTime" to "2024",

                "subjectType" to 1,

                "runtime" to "166 mins",

                "description" to "Follow the mythic journey of Paul Atreides as he unites with Chani and the Fremen while on a path of revenge against the conspirators who destroyed his family."

            ),

            mapOf(

                "subjectId" to "11500",

                "id" to "11500",

                "title" to "Oppenheimer",

                "cover" to "https://image.tmdb.org/t/p/w500/8Gxv2gSjdhY7WbgSg1f07st662B.jpg",

                "poster" to "https://image.tmdb.org/t/p/w500/8Gxv2gSjdhY7WbgSg1f07st662B.jpg",

                "score" to "8.6",

                "releaseTime" to "2023",

                "subjectType" to 1,

                "runtime" to "180 mins",

                "description" to "The story of American scientist J. Robert Oppenheimer and his role in the development of the atomic bomb."

            ),

            mapOf(

                "subjectId" to "11650",

                "id" to "11650",

                "title" to "Interstellar",

                "cover" to "https://image.tmdb.org/t/p/w500/gEU2Qv6Xg77DJ9v3vG6mI80m2fN.jpg",

                "poster" to "https://image.tmdb.org/t/p/w500/gEU2Qv6Xg77DJ9v3vG6mI80m2fN.jpg",

                "score" to "8.7",

                "releaseTime" to "2014",

                "subjectType" to 1,

                "runtime" to "169 mins",

                "description" to "The adventures of a group of explorers who make use of a newly discovered wormhole to surpass the limitations on human space travel and conquer the vast distances involved in an interstellar voyage."

            )

        )

    }

    private fun toJsonString(obj: Any?): String {
        return when (obj) {
            is Map<*, *> -> {
                val json = org.json.JSONObject()

                obj.forEach { (key, value) ->

                    if (key != null) {
                        json.put(key.toString(), toJsonElement(value))

                    }

                }

                json.toString()

            }

            is List<*> -> {
                val array = org.json.JSONArray()

                obj.forEach { item ->

                    array.put(toJsonElement(item))

                }

                array.toString()

            }

            else -> org.json.JSONObject.wrap(obj)?.toString() ?: "null"

        }

    }

    private fun toJsonElement(obj: Any?): Any {
        return when (obj) {
            is Map<*, *> -> {
                val json = org.json.JSONObject()

                obj.forEach { (key, value) ->

                    if (key != null) {
                        json.put(key.toString(), toJsonElement(value))

                    }

                }

                json

            }

            is List<*> -> {
                val array = org.json.JSONArray()

                obj.forEach { item ->

                    array.put(toJsonElement(item))

                }

                array

            }

            null -> org.json.JSONObject.NULL

            else -> obj

        }

    }

    private fun parseJsonToMap(jsonStr: String): Map<String, Any?> {
        if (jsonStr.trim().isEmpty()) return emptyMap()

        return try {
            val json = org.json.JSONObject(jsonStr)

            jsonToMap(json)

        } catch (e: Exception) {
            emptyMap()

        }

    }

    private fun jsonToMap(json: org.json.JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()

        val keys = json.keys()

        while (keys.hasNext()) {
            val key = keys.next()

            val value = json.get(key)

            map[key] = when (value) {
                is org.json.JSONObject -> jsonToMap(value)

                is org.json.JSONArray -> jsonToList(value)

                org.json.JSONObject.NULL -> null

                else -> value

            }

        }

        return map

    }

    private fun jsonToList(array: org.json.JSONArray): List<Any?> {
        val list = mutableListOf<Any?>()

        for (i in 0 until array.length()) {
            val value = array.get(i)

            list.add(when (value) {
                is org.json.JSONObject -> jsonToMap(value)

                is org.json.JSONArray -> jsonToList(value)

                org.json.JSONObject.NULL -> null

                else -> value

            })

        }

        return list

    }

}
