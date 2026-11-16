package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.local.FavoriteEntity
import com.example.data.local.HistoryEntity
import com.example.data.model.Episode
import com.example.data.model.MovieSubject
import com.example.data.model.StreamItem
import com.example.data.model.SubtitleItem
import com.example.ui.theme.*
import com.example.ui.viewmodel.MovieViewModel
import com.example.ui.viewmodel.ActiveDownload
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import android.net.Uri
import android.content.Intent
import com.example.R
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.widget.Toast
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.roundToInt


object PosterDownloadTracker {
    val downloadingOrDownloadedShows: MutableSet<String> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun shouldDownload(showPrefix: String): Boolean {
        if (showPrefix.isBlank()) return false
        return downloadingOrDownloadedShows.add(showPrefix)
    }

    fun markFailed(showPrefix: String) {
        downloadingOrDownloadedShows.remove(showPrefix)
    }
}

fun downloadVideo(
    context: android.content.Context,
    url: String,
    title: String,
    posterUrl: String = "",
    showToast: Boolean = true,
    safeShowPrefixOverride: String? = null,
    subtitles: List<com.example.data.model.SubtitleItem> = emptyList()
) {
    val postToast: (String) -> Unit = { msg ->
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    if (url.isEmpty()) {
        if (showToast) postToast("Stream URL is empty")
        return
    }
    try {
        val safeTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
        val manager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as android.app.DownloadManager

        val safeShowName = safeShowPrefixOverride?.ifBlank { null }
            ?: Regex("(?i)(.*?)(?:[\\s._-]*S\\d+E\\d+.*|$)").find(title)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
            ?: Regex("(.*?)(_S\\d+E\\d+.*|$)").find(safeTitle)?.groupValues?.get(1)?.replace("_", " ")?.trim()
            ?: safeTitle
        val safeShowPrefix = safeShowName.replace(Regex("[^a-zA-Z0-9.-]"), "_")

        val moviesDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
        val anyFilmDir = java.io.File(moviesDir, "AnyFilm")
        val showDir = java.io.File(anyFilmDir, safeShowPrefix)
        if (!showDir.exists()) showDir.mkdirs()
        val epDir = java.io.File(showDir, safeTitle)
        if (!epDir.exists()) epDir.mkdirs()

        // 1. Download show poster ONLY ONCE into showDir/poster.jpg
        if (posterUrl.isNotEmpty()) {
            val showPoster = java.io.File(showDir, "poster.jpg")
            val namedPoster = java.io.File(showDir, "$safeShowPrefix.jpg")
            if (!showPoster.exists() && PosterDownloadTracker.shouldDownload(safeShowPrefix)) {
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                    try {
                        val referer = when {
                            posterUrl.contains("anifam", ignoreCase = true) -> "https://anifam.net/"
                            posterUrl.contains("tmdb.org", ignoreCase = true) -> "https://www.themoviedb.org/"
                            else -> "https://moviebox.ph/"
                        }
                        val client = okhttp3.OkHttpClient.Builder()
                            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                            .followRedirects(true)
                            .build()
                        val req = okhttp3.Request.Builder()
                            .url(posterUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .header("Referer", referer)
                            .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) {
                                val bytes = resp.body?.bytes()
                                if (bytes != null && bytes.isNotEmpty()) {
                                    if (!showPoster.exists()) showPoster.writeBytes(bytes)
                                    try { if (!namedPoster.exists()) namedPoster.writeBytes(bytes) } catch (_: Throwable) {}
                                }
                            } else {
                                PosterDownloadTracker.markFailed(safeShowPrefix)
                            }
                        }
                    } catch (_: Exception) {
                        PosterDownloadTracker.markFailed(safeShowPrefix)
                    }
                }
            }
        }

        // 2. Download all subtitles into epDir
        if (subtitles.isNotEmpty()) {
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .followRedirects(true)
                    .build()
                val subArray = org.json.JSONArray()
                val usedNames = mutableSetOf<String>()

                for ((index, sub) in subtitles.withIndex()) {
                    try {
                        if (sub.url.isBlank()) continue
                        val ext = if (sub.url.contains(".vtt", ignoreCase = true) || sub.format.equals("vtt", ignoreCase = true)) "vtt" else "srt"
                        val rawLang = sub.language.trim().ifBlank { "Track ${index + 1}" }
                        val safeLang = rawLang.replace(Regex("[/\\\\:*?\"<>|]"), " ").replace(Regex("\\s+"), " ").trim()
                        var fileName = "$safeLang.$ext"
                        var counter = 2
                        while (usedNames.contains(fileName.lowercase())) {
                            fileName = "$safeLang ($counter).$ext"
                            counter++
                        }
                        usedNames.add(fileName.lowercase())
                        val subFile = java.io.File(epDir, fileName)

                        if (!subFile.exists() || subFile.length() == 0L) {
                            val referer = if (sub.url.contains("anifam")) "https://anifam.net/" else "https://moviebox.ph/"
                            val req = okhttp3.Request.Builder()
                                .url(sub.url)
                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                                .header("Referer", referer)
                                .build()
                            client.newCall(req).execute().use { resp ->
                                if (resp.isSuccessful) {
                                    val bytes = resp.body?.bytes()
                                    if (bytes != null && bytes.isNotEmpty()) {
                                        subFile.writeBytes(bytes)
                                    }
                                }
                            }
                        }

                        if (subFile.exists() && subFile.length() > 0) {
                            val item = org.json.JSONObject()
                            item.put("language", rawLang)
                            item.put("fileName", fileName)
                            subArray.put(item)
                        }
                    } catch (_: Throwable) {}
                }
                if (subArray.length() > 0) {
                    try {
                        val jsonFile = java.io.File(epDir, "subtitles.json")
                        jsonFile.writeText(subArray.toString())
                    } catch (_: Exception) {}
                }
            }
        }

        val request = android.app.DownloadManager.Request(android.net.Uri.parse(url))
        val fileName = "$safeTitle.mp4"
        request.setTitle(title)
        request.setDescription("Downloading...")
        request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_MOVIES, "AnyFilm/$safeShowPrefix/$safeTitle/$fileName")
        manager.enqueue(request)
        if (showToast) {
            postToast("Download started...")
        }
    } catch (e: Exception) {
        if (showToast) {
            postToast("Failed to start download: ${e.message}")
        }
    }
}

fun loadMediaOnCast(
    castSession: com.google.android.gms.cast.framework.CastSession?,
    streamUrl: String,
    subject: MovieSubject,
    viewModel: com.example.ui.viewmodel.MovieViewModel,
    context: android.content.Context? = null
) {
    if (castSession == null || !castSession.isConnected || streamUrl.isEmpty()) return
    try {
        val remoteMediaClient = castSession.remoteMediaClient ?: return

        val isAniFam = viewModel.isAniFam ||
                streamUrl.contains("zokoanime", ignoreCase = true) ||
                streamUrl.contains("dramahot", ignoreCase = true) ||
                streamUrl.contains("megaplay", ignoreCase = true) ||
                streamUrl.contains("shiora", ignoreCase = true) ||
                streamUrl.contains("embermeadow", ignoreCase = true) ||
                streamUrl.contains("laggedduringcutscene", ignoreCase = true) ||
                streamUrl.contains("anipixcdn", ignoreCase = true)

        val streamToCast = if (isAniFam) {
            viewModel.selectedStream ?: viewModel.playInfo?.streamList?.firstOrNull()
        } else {
            val availableStreams = viewModel.playInfo?.streamList ?: emptyList()
            availableStreams.find { s ->
                val u = s.url.lowercase()
                u.isNotEmpty() && !u.contains("b164fbfb43477929") && !u.contains("/h265/") && !u.contains("h265") && !u.contains("hevc")
            } ?: viewModel.selectedStream
        }

        val rawTargetUrl = streamToCast?.url?.ifEmpty { streamUrl } ?: streamUrl
        val targetCookie = streamToCast?.cookie ?: viewModel.selectedStream?.cookie ?: ""

        val effectiveIsAniFam = isAniFam ||
                rawTargetUrl.contains("zokoanime", ignoreCase = true) ||
                rawTargetUrl.contains("dramahot", ignoreCase = true) ||
                rawTargetUrl.contains("megaplay", ignoreCase = true) ||
                rawTargetUrl.contains("shiora", ignoreCase = true) ||
                rawTargetUrl.contains("embermeadow", ignoreCase = true) ||
                rawTargetUrl.contains("laggedduringcutscene", ignoreCase = true) ||
                rawTargetUrl.contains("anipixcdn", ignoreCase = true)

        // Resolve phone's LAN IP address for Cast receiver
        val wifiIp = com.example.cast.CastForegroundService.getWifiIpAddress()
        val castUrl = if (wifiIp != "127.0.0.1") {
            val encodedTarget = java.net.URLEncoder.encode(rawTargetUrl, "UTF-8")
            val encodedCookie = java.net.URLEncoder.encode(targetCookie, "UTF-8")
            val aniFamParam = if (effectiveIsAniFam) "&isAniFam=true" else ""
            "http://$wifiIp:3000/cast-proxy?url=$encodedTarget&cookie=$encodedCookie$aniFamParam"
        } else {
            rawTargetUrl
        }

        // If media is already loaded for this url, don't reload
        val currentMedia = remoteMediaClient.mediaInfo
        if (currentMedia?.contentId == castUrl || currentMedia?.contentUrl == castUrl ||
            currentMedia?.contentId == rawTargetUrl || currentMedia?.contentUrl == rawTargetUrl) {
            return
        }

        val movieMetadata = com.google.android.gms.cast.MediaMetadata(
            if (subject.isTvShow) com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_TV_SHOW
            else com.google.android.gms.cast.MediaMetadata.MEDIA_TYPE_MOVIE
        ).apply {
            val title = if (subject.isTvShow) {
                "${subject.name} - S${viewModel.selectedSeason}E${viewModel.currentEpisode?.episodeNumber ?: 1}"
            } else {
                subject.name
            }
            putString(com.google.android.gms.cast.MediaMetadata.KEY_TITLE, title)
            if (subject.isTvShow) {
                putString(com.google.android.gms.cast.MediaMetadata.KEY_SERIES_TITLE, subject.name)
                putInt(com.google.android.gms.cast.MediaMetadata.KEY_SEASON_NUMBER, viewModel.selectedSeason)
                putInt(com.google.android.gms.cast.MediaMetadata.KEY_EPISODE_NUMBER, viewModel.currentEpisode?.episodeNumber ?: 1)
            }
            if (subject.poster.isNotEmpty()) {
                addImage(com.google.android.gms.common.images.WebImage(android.net.Uri.parse(subject.poster)))
            }
        }

        val isM3u8 = rawTargetUrl.contains(".m3u8", ignoreCase = true)
        val isHevc = rawTargetUrl.contains("/h265/", ignoreCase = true) || rawTargetUrl.contains("hevc", ignoreCase = true)
        val mimeType = when {
            isM3u8 -> "application/x-mpegURL"
            isHevc -> "video/mp4; codecs=\"hev1.1.6.L93.B0, mp4a.40.2\""
            else -> "video/mp4"
        }

        val mediaInfo = com.google.android.gms.cast.MediaInfo.Builder(castUrl)
            .setStreamType(com.google.android.gms.cast.MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(mimeType)
            .setMetadata(movieMetadata)
            .build()

        val startPosition = viewModel.playbackProgressMs.coerceAtLeast(0L)
        val loadOptions = com.google.android.gms.cast.MediaLoadOptions.Builder()
            .setAutoplay(true)
            .setPlayPosition(startPosition)
            .build()

        val mediaTitle = if (subject.isTvShow) "${subject.name} S${viewModel.selectedSeason}E${viewModel.currentEpisode?.episodeNumber ?: 1}" else subject.name
        if (context != null) {
            com.example.cast.CastForegroundService.start(context, mediaTitle)
        }

        android.util.Log.i("MovieScreens", "Loading media on Cast: url=$castUrl mimeType=$mimeType pos=$startPosition")
        val pendingResult = remoteMediaClient.load(mediaInfo, loadOptions)
        pendingResult.setResultCallback { result ->
            if (result.status.isSuccess) {
                android.util.Log.i("MovieScreens", "Cast media load command acknowledged successfully")
                if (context != null) {
                    com.example.cast.CastForegroundService.start(context, mediaTitle)
                }
            } else {
                android.util.Log.w("MovieScreens", "Cast media load rejected: ${result.status.statusCode} ${result.status.statusMessage}")
            }
        }

        // Pause local player since TV is now playing
        viewModel.isPlaying = false
    } catch (e: Exception) {
        android.util.Log.e("MovieScreens", "Failed to cast media to TV", e)
    }
}

object VideoCacheManager {
    @Volatile
    private var simpleCache: androidx.media3.datasource.cache.SimpleCache? = null

    @Synchronized
    fun getCache(context: android.content.Context): androidx.media3.datasource.cache.SimpleCache {
        if (simpleCache == null) {
            val cacheDir = java.io.File(context.cacheDir, "media_video_cache")
            val evictor = androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024) // 300MB fast LRU disk cache
            val databaseProvider = androidx.media3.database.StandaloneDatabaseProvider(context)
            simpleCache = androidx.media3.datasource.cache.SimpleCache(cacheDir, evictor, databaseProvider)
        }
        return simpleCache!!
    }

    /**
     * Token-agnostic cache key matching MovieBox's w.h0(url).
     * Extracts only scheme + authority + path, stripping signed token queries (?Expires, ?Signature, ?sign, ?t).
     * Guarantees 100% cache hits even across session token renewals.
     */
    fun getCacheKey(uri: android.net.Uri): String {
        val path = uri.path ?: ""
        return if (uri.scheme != null && uri.authority != null && path.isNotEmpty()) {
            "${uri.scheme}://${uri.authority}$path"
        } else {
            uri.toString()
        }
    }
}

@Composable
fun SplashScreen(
    isReady: Boolean,
    onFinish: () -> Unit
) {
    var startAnimation by remember { mutableStateOf(false) }
    val alphaAnim by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 600),
        label = "splash_fade_in"
    )
    val scaleAnim by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (startAnimation) 1f else 0.85f,
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 700,
            easing = androidx.compose.animation.core.FastOutSlowInEasing
        ),
        label = "splash_scale"
    )

    LaunchedEffect(Unit) {
        startAnimation = true
    }

    LaunchedEffect(isReady) {
        val startTime = System.currentTimeMillis()
        val minDisplayTimeMs = 7000L // Guaranteed minimum 7-8s display
        val maxWaitTimeMs = 8500L    // Maximum safety timeout (8.5 seconds)

        while (isActive) {
            val elapsed = System.currentTimeMillis() - startTime
            if (isReady && elapsed >= minDisplayTimeMs) {
                onFinish()
                break
            }
            if (elapsed >= maxWaitTimeMs) {
                onFinish()
                break
            }
            delay(50L)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF140204),
                        ObsidianDark,
                        Color(0xFF07080D)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .alpha(alphaAnim)
                .scale(scaleAnim)
        ) {
            // Standalone App Logo sitting cleanly on dark background
            androidx.compose.foundation.Image(
                painter = painterResource(id = R.drawable.ic_popup_logo),
                contentDescription = "AnyFilm Logo",
                modifier = Modifier.size(110.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Cinematic App Title
            Text(
                text = buildAnnotatedString {
                    append("ANY")
                    withStyle(SpanStyle(color = Color(0xFFFF2A6D))) {
                        append("FILM")
                    }
                },
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 3.sp,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Stream Endless Cinema",
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = 1.5.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Smooth sleek loading bar
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = CinematicRed,
                strokeWidth = 2.dp
            )
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MovieBoxApp(viewModel: MovieViewModel) {
    val trending by viewModel.trendingMovies.collectAsState()
    val categoryMovies by viewModel.categoryMovies.collectAsState()
    val isContentReady = remember(trending, categoryMovies, viewModel.isDashboardLoading) {
        (trending.isNotEmpty() || categoryMovies.isNotEmpty()) && !viewModel.isDashboardLoading
    }

    var showSplash by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        AnimatedContent(
            targetState = showSplash,
            transitionSpec = {
                fadeIn(animationSpec = tween(400)) with
                fadeOut(animationSpec = tween(400))
            },
            label = "splash_to_main_transition"
        ) { isSplashVisible ->
            if (isSplashVisible) {
                SplashScreen(
                    isReady = isContentReady,
                    onFinish = { showSplash = false }
                )
            } else {
                MainContent(viewModel)
            }
        }
    }
}

@Composable
fun LockoutScreen(viewModel: MovieViewModel) {
    var passwordInput by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF140204),
                        ObsidianDark
                    )
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Earth/Globe Icon that represents geofencing
            Icon(
                imageVector = Icons.Filled.PublicOff,
                contentDescription = "Geofenced",
                tint = CinematicRed,
                modifier = Modifier
                    .size(96.dp)
                    .padding(bottom = 16.dp)
            )

            Text(
                text = "Service Restriction",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Clickable text to unlock developer mode
            Text(
                text = "This application is not available in your current region.",
                fontSize = 15.sp,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 8.dp)
                    .clickable(onClick = {
                        viewModel.passwordAttempts++
                        if (viewModel.passwordAttempts >= 10) {
                            viewModel.showPasswordDialog = true
                        }
                    })
                    .testTag("lockout_message")
            )

            Spacer(modifier = Modifier.height(40.dp))

            // User friendly help block for development environments
            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "STUDIO EMULATOR MODE ACTIVE",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentAmber,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "To bypass geographic carrier restrictions, tap the message above 10 times and enter security access key or use the Auto-Bypass below.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            viewModel.submitDeveloperPassword("or666")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auto_bypass_button")
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = "Bypass", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Auto-Bypass Lockout", fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }

        // Lab password popup dialog
        if (viewModel.showPasswordDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.showPasswordDialog = false },
                title = { Text("Laboratory Security Gateway", color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Text("Enter decryption or bypass developer credentials:", color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
                        OutlinedTextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            label = { Text("Gateway Key") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CinematicRed,
                                unfocusedBorderColor = TextSecondary
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("password_input")
                        )
                        if (loginError) {
                            Text("Invalid security key. Hint: 'or666'", color = CinematicRed, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val success = viewModel.submitDeveloperPassword(passwordInput)
                            if (success) {
                                loginError = false
                            } else {
                                loginError = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CinematicRed)
                    ) {
                        Text("Verify Key")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.showPasswordDialog = false }) {
                        Text("Cancel", color = TextSecondary)
                    }
                },
                containerColor = ObsidianCard
            )
        }
    }
}

@Composable
fun TelegramJoinDialog(
    onDismiss: () -> Unit,
    telegramUrl: String = "https://t.me/AnyFilmOfficial"
) {
    val context = LocalContext.current

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1D22)),
            elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // App Logo sitting cleanly on dark background
                Image(
                    painter = painterResource(id = R.drawable.ic_popup_logo),
                    contentDescription = "AnyFilm Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(76.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Title
                Text(
                    text = buildAnnotatedString {
                        append("Join the Any")
                        withStyle(SpanStyle(color = Color(0xFFFF2A6D))) {
                            append("Film")
                        }
                        append(" Family")
                    },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Subtitle / Description
                Text(
                    text = "Be a special member of our Telegram family \u2014 get early access, request titles, and hear about new features first.",
                    color = Color(0xFFA6A6B0),
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action Button: Join Telegram
                Button(
                    onClick = {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(telegramUrl))
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open Telegram: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                        onDismiss()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                                colors = listOf(Color(0xFF7C3AED), Color(0xFFE130CF))
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    contentPadding = PaddingValues()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Telegram",
                            tint = Color.White,
                            modifier = Modifier
                                .size(18.dp)
                                .rotate(-25f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Join Telegram",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Dismiss: Maybe later
                TextButton(
                    onClick = onDismiss
                ) {
                    Text(
                        text = "Maybe later",
                        color = Color(0xFF92929D),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun ForceUpdateFullScreen(
    updateInfo: com.example.ui.viewmodel.MovieViewModel.AppUpdateInfo,
    isDownloading: Boolean,
    progress: Int,
    statusText: String,
    onUpdateClick: () -> Unit,
    onGoToDownloads: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Standalone App Logo sitting cleanly on dark background
            Image(
                painter = painterResource(id = R.drawable.ic_popup_logo),
                contentDescription = "AnyFilm",
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Update Required to Continue",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Version ${updateInfo.latestVersionName} is now available",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = CinematicRed
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "What's New in this update:",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = updateInfo.changelog.ifEmpty { "Critical improvements, stream playback fixes, and security updates." },
                        color = Color(0xFFB0B3B8),
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            if (isDownloading) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LinearProgressIndicator(
                        progress = { if (progress > 0) progress / 100f else 0f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = CinematicRed,
                        trackColor = Color(0xFF333338)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = if (statusText.isNotEmpty()) "Downloading: $statusText" else "Downloading update...",
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                }
            } else {
                Button(
                    onClick = onUpdateClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CinematicRed)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Download",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Update Now",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = onGoToDownloads,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFF3A3B40))
                ) {
                    Icon(
                        imageVector = Icons.Filled.DownloadDone,
                        contentDescription = "Offline Downloads",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Watch Offline Downloads",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
fun AppUpdateDialog(
    updateInfo: com.example.ui.viewmodel.MovieViewModel.AppUpdateInfo,
    isDownloading: Boolean,
    progress: Int,
    statusText: String,
    onUpdateClick: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = {
            if (!isDownloading) onDismiss()
        },
        properties = androidx.compose.ui.window.DialogProperties(
            dismissOnBackPress = !isDownloading,
            dismissOnClickOutside = !isDownloading,
            usePlatformDefaultWidth = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .wrapContentHeight(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1F24)),
            elevation = CardDefaults.cardElevation(defaultElevation = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Logo / Update Icon
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF0EA2DE).copy(alpha = 0.15f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = "Update",
                        tint = Color(0xFF0EA2DE),
                        modifier = Modifier.size(34.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "New Version Available",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Version ${updateInfo.latestVersionName}",
                    color = CinematicRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )

                if (updateInfo.changelog.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = updateInfo.changelog,
                            color = Color(0xFFB0B3B8),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "A new update is available with improvements and fixes.",
                        color = Color(0xFFB0B3B8),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }

                Spacer(modifier = Modifier.height(22.dp))

                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { if (progress > 0) progress / 100f else 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = CinematicRed,
                            trackColor = Color(0xFF333338)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (statusText.isNotEmpty()) "Downloading: $statusText" else "Downloading update...",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                } else {
                    Button(
                        onClick = onUpdateClick,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA2DE))
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Download,
                            contentDescription = "Download",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Download & Install",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Later",
                            color = Color(0xFF8E8E93),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun MainContent(viewModel: MovieViewModel) {
    var activeTab by remember { mutableStateOf("home") } // home, search, watchlist, history, account
    val updateInfo = viewModel.availableUpdateInfo
    val isForceUpdateActive = updateInfo?.isForceUpdate == true
    var forceUpdateAllowDownloadsOnly by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE) }
    val lastShownMillis = remember { prefs.getLong("last_telegram_prompt_timestamp", 0L) }
    val cooldownPeriodMillis = 168L * 60 * 60 * 1000 // 168 hours (7 days)
    val shouldPromptTelegram = remember { (System.currentTimeMillis() - lastShownMillis) >= cooldownPeriodMillis }
    var showTelegramDialog by rememberSaveable { mutableStateOf(shouldPromptTelegram) }
    var lastBackPressTime by remember { mutableStateOf(0L) }

    // Back navigation across tabs
    BackHandler(enabled = activeTab == "manage_categories" || activeTab == "history") {
        activeTab = "settings"
    }

    BackHandler(enabled = activeTab != "home" && activeTab != "manage_categories" && activeTab != "history") {
        activeTab = "home"
    }

    // Cancel loading stream links on back
    BackHandler(enabled = viewModel.isPlaybackLoading) {
        viewModel.isPlaybackLoading = false
    }

    // Double-tap back on home screen to exit app safely
    BackHandler(
        enabled = activeTab == "home" &&
            viewModel.selectedSubject == null &&
            viewModel.playInfo == null &&
            viewModel.playingDownloadedFile == null &&
            !viewModel.isPlaybackLoading
    ) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000L) {
            (context as? android.app.Activity)?.finish()
        } else {
            lastBackPressTime = currentTime
            android.widget.Toast.makeText(context, "Press back again to exit", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                if (viewModel.playInfo == null && viewModel.playingDownloadedFile == null && !viewModel.isLiveTvPlaying) {
                    NavigationBar(
                        containerColor = ObsidianDark,
                        tonalElevation = 8.dp
                    ) {
                        if (!isForceUpdateActive) {
                            NavigationBarItem(
                                selected = activeTab == "home",
                                onClick = { activeTab = "home" },
                                icon = { Icon(if (activeTab == "home") Icons.Filled.Home else Icons.Outlined.Home, contentDescription = "Home") },
                                label = { Text("Home", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinematicRed,
                                    selectedTextColor = CinematicRed,
                                    indicatorColor = ObsidianCard,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_home")
                            )
                            NavigationBarItem(
                                selected = activeTab == "search",
                                onClick = { activeTab = "search" },
                                icon = { Icon(if (activeTab == "search") Icons.Filled.Search else Icons.Outlined.Search, contentDescription = "Search") },
                                label = { Text("Search", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinematicRed,
                                    selectedTextColor = CinematicRed,
                                    indicatorColor = ObsidianCard,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_search")
                            )
                            NavigationBarItem(
                                selected = activeTab == "livetv",
                                onClick = { activeTab = "livetv" },
                                icon = { Icon(if (activeTab == "livetv") Icons.Filled.LiveTv else Icons.Outlined.LiveTv, contentDescription = "Live TV") },
                                label = { Text("Live TV", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinematicRed,
                                    selectedTextColor = CinematicRed,
                                    indicatorColor = ObsidianCard,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_livetv")
                            )
                        }

                        NavigationBarItem(
                            selected = activeTab == "download",
                            onClick = { activeTab = "download" },
                            icon = { Icon(if (activeTab == "download") Icons.Filled.Download else Icons.Outlined.Download, contentDescription = "Download") },
                            label = { Text("Download", fontSize = 11.sp) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CinematicRed,
                                selectedTextColor = CinematicRed,
                                indicatorColor = ObsidianCard,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            ),
                            modifier = Modifier.testTag("nav_download")
                        )

                        if (!isForceUpdateActive) {
                            NavigationBarItem(
                                selected = activeTab == "settings",
                                onClick = { activeTab = "settings" },
                                icon = { Icon(if (activeTab == "settings") Icons.Filled.Settings else Icons.Outlined.Settings, contentDescription = "Settings") },
                                label = { Text("Settings", fontSize = 11.sp) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = CinematicRed,
                                    selectedTextColor = CinematicRed,
                                    indicatorColor = ObsidianCard,
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                ),
                                modifier = Modifier.testTag("nav_settings")
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (viewModel.isLiveTvPlaying) PaddingValues(0.dp) else innerPadding)
            ) {
                when (activeTab) {
                    "home" -> HomeScreen(viewModel)
                    "search" -> SearchScreen(viewModel)
                    "livetv" -> LiveTvScreen(viewModel)
                    "watchlist" -> WatchlistScreen(
                        viewModel,
                        onBack = { activeTab = "settings" }
                    )
                    "download" -> DownloadScreen(viewModel)
                    "settings" -> SettingsScreen(
                        viewModel, 
                        onNavigateToHistory = { activeTab = "history" },
                        onNavigateToWatchlist = { activeTab = "watchlist" },
                        onNavigateToManageCategories = { activeTab = "manage_categories" }
                    )
                    "manage_categories" -> ManageCategoriesScreen(
                        viewModel,
                        onBack = { activeTab = "settings" }
                    )
                    "history" -> HistoryScreen(
                        viewModel,
                        onBack = { activeTab = "settings" }
                    )
                }
            }
        }



        // Detail screen slide-up overlay
        AnimatedVisibility(
            visible = viewModel.selectedSubject != null,
            enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(300)),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(300))
        ) {
            DetailScreen(viewModel)
        }

        // Simulated Video Player overlay
        AnimatedVisibility(
            visible = viewModel.playInfo != null,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250))
        ) {
            PlayerScreen(viewModel)
        }

        // Offline Download Video Player overlay
        AnimatedVisibility(
            visible = viewModel.playingDownloadedFile != null,
            enter = fadeIn(animationSpec = tween(250)),
            exit = fadeOut(animationSpec = tween(250))
        ) {
            val file = viewModel.playingDownloadedFile
            if (file != null) {
                OfflinePlayerScreen(
                    fileToPlay = file,
                    viewModel = viewModel,
                    onDismiss = { viewModel.playingDownloadedFile = null }
                )
            }
        }

        // Playback loading overlay
        if (viewModel.isPlaybackLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(enabled = false) {}, // block all clicks while loading
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(color = CinematicRed)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Fetching stream links...",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // In-App Smart/Force Update Screen
        val updateInfo = viewModel.availableUpdateInfo
        if (updateInfo != null && viewModel.playInfo == null) {
            if (updateInfo.isForceUpdate) {
                if (activeTab != "download") {
                    ForceUpdateFullScreen(
                        updateInfo = updateInfo,
                        isDownloading = viewModel.isDownloadingUpdate,
                        progress = viewModel.updateDownloadProgress,
                        statusText = viewModel.updateStatusMessage,
                        onUpdateClick = {
                            viewModel.downloadAndInstallUpdate(context, updateInfo.downloadUrl)
                        },
                        onGoToDownloads = {
                            activeTab = "download"
                        }
                    )
                }
            } else {
                AppUpdateDialog(
                    updateInfo = updateInfo,
                    isDownloading = viewModel.isDownloadingUpdate,
                    progress = viewModel.updateDownloadProgress,
                    statusText = viewModel.updateStatusMessage,
                    onUpdateClick = {
                        viewModel.downloadAndInstallUpdate(context, updateInfo.downloadUrl)
                    },
                    onDismiss = {
                        android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                            .edit()
                            .putLong("last_update_dismissed_timestamp", System.currentTimeMillis())
                            .apply()
                        viewModel.availableUpdateInfo = null
                    }
                )
            }
        }

        // Telegram Channel Welcome Popup on App Open (168-hour cooldown)
        if (showTelegramDialog && viewModel.playInfo == null && updateInfo == null) {
            TelegramJoinDialog(
                onDismiss = {
                    prefs.edit().putLong("last_telegram_prompt_timestamp", System.currentTimeMillis()).apply()
                    showTelegramDialog = false
                },
                telegramUrl = "https://t.me/AnyFilmOfficial"
            )
        }
    }
}

fun Modifier.shimmerEffect(): Modifier = composed {
    var size by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val transition = rememberInfiniteTransition(label = "shimmer")
    val startOffsetX by transition.animateFloat(
        initialValue = -2 * size.width.toFloat(),
        targetValue = 2 * size.width.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    background(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFF1E1E22),
                Color(0xFF2E2E32),
                Color(0xFF1E1E22),
            ),
            start = Offset(startOffsetX, 0f),
            end = Offset(startOffsetX + size.width.toFloat(), size.height.toFloat())
        )
    ).onGloballyPositioned {
        size = it.size
    }
}

@Composable
fun DashboardShimmerScreen() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
    ) {
        // Shimmer Hero Banner Placeholder
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(350.dp)
                    .shimmerEffect()
            ) {
                // Bottom shading gradient
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, ObsidianDark)
                            )
                        )
                )
                
                // Text lines placeholders
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .padding(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .width(80.dp)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .shimmerEffect()
                    )
                }
            }
        }

        // Horizontal Row Placeholder 1
        item {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                Box(
                    modifier = Modifier
                        .padding(start = 16.dp, bottom = 12.dp)
                        .width(140.dp)
                        .height(18.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .shimmerEffect()
                )
                
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(6) {
                        Column(modifier = Modifier.width(110.dp)) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(165.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .shimmerEffect()
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .shimmerEffect()
                            )
                        }
                    }
                }
            }
        }

        // Category Filter Chips placeholder
        item {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(4) {
                    Box(
                        modifier = Modifier
                            .width(90.dp)
                            .height(32.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .shimmerEffect()
                    )
                }
            }
        }

        // Grid items placeholder
        items(3) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(3) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .shimmerEffect()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.7f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .shimmerEffect()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MovieGridItem(
    movie: MovieSubject,
    modifier: Modifier = Modifier.width(110.dp),
    showRating: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(8.dp))
        ) {
            val optimalPosterUrl = androidx.compose.runtime.remember(movie.poster) {
                com.example.util.ImageOptimizer.getOptimalUrl(movie.poster, 360)
            }
            val placeholderColor = androidx.compose.runtime.remember(movie.averageHue) {
                com.example.util.ImageOptimizer.parseAverageHue(movie.averageHue)
            }
            val placeholderPainter = androidx.compose.runtime.remember(movie.blurHash, placeholderColor) {
                com.example.util.ImageOptimizer.getBlurHashPainter(movie.blurHash, 20, 20)
                    ?: androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor)
            }

            AsyncImage(
                model = coil.request.ImageRequest.Builder(LocalContext.current)
                    .data(optimalPosterUrl)
                    .memoryCacheKey(optimalPosterUrl)
                    .diskCacheKey(optimalPosterUrl)
                    .size(width = 330, height = 480) // Decode at exact 110dp x 160dp card resolution (~3x density) like Vega
                    .precision(coil.size.Precision.INEXACT) // Fast decoding without full uncompressed bitmap in RAM
                    .crossfade(false)
                    .build(),
                contentDescription = movie.name,
                contentScale = ContentScale.Crop,
                placeholder = placeholderPainter,
                error = placeholderPainter,
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = movie.name,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!movie.year.isNullOrBlank() && movie.year != "Not available") {
            Text(
                text = movie.year,
                fontSize = 11.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
fun SearchScreen(viewModel: MovieViewModel) {
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val results by viewModel.searchResults.collectAsState()
    var searchInput by remember { mutableStateOf(viewModel.searchQuery) }
    var isSearchSubmitted by remember { mutableStateOf(false) }
    val searchListState = rememberLazyListState()

    LaunchedEffect(viewModel.customCountryIso) {
        viewModel.loadSearchSuggestions()
    }

    // Auto-load more results when scrolling near the bottom of search results
    val shouldLoadMore = remember {
        derivedStateOf {
            val totalItems = searchListState.layoutInfo.totalItemsCount
            val lastVisible = searchListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            totalItems > 0 && lastVisible >= totalItems - 4
        }
    }
    LaunchedEffect(shouldLoadMore.value) {
        if (shouldLoadMore.value && viewModel.canSearchLoadMore && !viewModel.isSearchLoadingMore) {
            viewModel.loadMoreSearchResults()
        }
    }

    BackHandler(enabled = searchInput.isNotEmpty()) {
        searchInput = ""
        isSearchSubmitted = false
        viewModel.searchMovies("")
        focusManager.clearFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = searchInput,
            onValueChange = {
                searchInput = it
                isSearchSubmitted = false
                viewModel.searchMovies(it)
            },
            placeholder = { Text("Search", color = TextSecondary) },
            leadingIcon = {
                IconButton(onClick = {
                    if (searchInput.isNotBlank()) {
                        isSearchSubmitted = true
                        viewModel.searchMovies(searchInput, immediate = true)
                        focusManager.clearFocus()
                    }
                }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = if (searchInput.isNotBlank()) CinematicRed else TextSecondary)
                }
            },
            trailingIcon = {
                if (searchInput.isNotEmpty()) {
                    IconButton(onClick = {
                        searchInput = ""
                        isSearchSubmitted = false
                        viewModel.searchMovies("")
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    if (searchInput.isNotBlank()) {
                        isSearchSubmitted = true
                        viewModel.searchMovies(searchInput, immediate = true)
                        focusManager.clearFocus()
                    }
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = ObsidianCard,
                unfocusedContainerColor = ObsidianCard,
                focusedBorderColor = CinematicRed,
                unfocusedBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("search_text_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        val suggestions by viewModel.searchSuggestions.collectAsState()

        if (viewModel.isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CinematicRed)
            }
        } else if (!isSearchSubmitted || searchInput.isEmpty()) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (suggestions.isNotEmpty()) {
                    item {
                        Text(
                            text = if (searchInput.isEmpty()) "Trending Searches" else "Suggestions",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
                
                itemsIndexed(suggestions) { index, suggestion ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                searchInput = suggestion
                                isSearchSubmitted = true
                                viewModel.searchMovies(suggestion, immediate = true)
                                focusManager.clearFocus()
                            }
                            .background(ObsidianCard, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (searchInput.isEmpty()) {
                            Text(
                                text = "${index + 1}",
                                color = if (index < 3) CinematicRed else TextSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                modifier = Modifier.width(32.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Suggestion",
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                        }
                        Text(
                            text = suggestion,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (searchInput.isEmpty()) {
                            Icon(
                                imageVector = Icons.Filled.TrendingUp,
                                contentDescription = "Trending",
                                tint = if (index < 3) CinematicRed else TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        } else if (results.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.SearchOff, contentDescription = "No Results", tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No results found for \"$searchInput\"", color = TextSecondary, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(
                state = searchListState,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(results) { movie ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                focusManager.clearFocus()
                                viewModel.selectSubject(movie)
                            }
                            .background(ObsidianCard, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val searchThumb = androidx.compose.runtime.remember(movie.poster) {
                            com.example.util.ImageOptimizer.getOptimalUrl(movie.poster, 180)
                        }
                        AsyncImage(
                            model = searchThumb,
                            contentDescription = movie.name,
                            contentScale = ContentScale.Crop,
                            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(com.example.util.ImageOptimizer.parseAverageHue(movie.averageHue, androidx.compose.ui.graphics.Color.DarkGray)),
                            error = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.DarkGray),
                            modifier = Modifier
                                .size(width = 60.dp, height = 90.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(movie.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (movie.genres.isNotEmpty()) {
                                Text(movie.genres.joinToString(", "), fontSize = 13.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!movie.rating.isNullOrBlank() && movie.rating != "Not available") {
                                    Icon(Icons.Filled.Star, contentDescription = "Rating", tint = AccentAmber, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(movie.rating, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Text(movie.year ?: "", fontSize = 12.sp, color = TextSecondary)
                                if (movie.isTvShow) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                            .padding(horizontal = 4.dp, vertical = 1.dp)
                                    ) {
                                        Text("TV", fontSize = 10.sp, color = TextSecondary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                if (viewModel.isSearchLoadingMore) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = CinematicRed,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WatchlistScreen(viewModel: MovieViewModel, onBack: () -> Unit = {}) {
    val favorites by viewModel.favoritesList.collectAsState()

    BackHandler {
        onBack()
    }

    LaunchedEffect(Unit) {
        viewModel.refreshWatchlist()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("My Watchlist", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        if (favorites.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = "Watchlist empty", tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Your watchlist is empty", color = TextSecondary, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(favorites) { movie ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                // Maps entities back to subjects
                                val subjectId = if (viewModel.isAniFam) {
                                    if (movie.id.startsWith("anifam_")) movie.id else "anifam_${movie.id}"
                                } else {
                                    movie.id
                                }
                                val subject = MovieSubject(
                                    id = subjectId,
                                    name = movie.name,
                                    poster = movie.poster,
                                    isTvShow = movie.isTvShow,
                                    rating = movie.rating,
                                    year = movie.year
                                )
                                viewModel.selectSubject(subject)
                            }
                            .background(ObsidianCard, RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = movie.poster,
                            contentDescription = movie.name,
                            contentScale = ContentScale.Crop, placeholder = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.DarkGray), error = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.DarkGray),
                            modifier = Modifier
                                .size(width = 50.dp, height = 75.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(movie.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                            Text((movie.year ?: "Not available") + " • " + if (movie.isTvShow) "TV Show" else "Movie", fontSize = 13.sp, color = TextSecondary)
                        }
                        IconButton(onClick = {
                            val subjectId = if (viewModel.isAniFam) {
                                if (movie.id.startsWith("anifam_")) movie.id else "anifam_${movie.id}"
                            } else {
                                movie.id.removePrefix("moviebox_")
                            }
                            val subject = MovieSubject(
                                id = subjectId,
                                name = movie.name,
                                poster = movie.poster,
                                isTvShow = movie.isTvShow,
                                rating = movie.rating,
                                year = movie.year
                            )
                            viewModel.toggleFavorite(subject)
                        }) {
                            Icon(Icons.Filled.BookmarkRemove, contentDescription = "Remove", tint = CinematicRed)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(viewModel: MovieViewModel, onBack: () -> Unit = {}) {
    val history by viewModel.historyList.collectAsState()
    var expandedSubjectId by remember { mutableStateOf<String?>(null) }

    val groupedHistory = remember(history, viewModel.isAniFam) {
        history.groupBy { 
            if (viewModel.isAniFam) it.subjectId.removePrefix("anifam_") else it.subjectId 
        }.map { entry ->
            entry.value.first() to entry.value
        }
    }

    BackHandler {
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 16.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text("Continue Watching", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        }

        if (groupedHistory.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.PlayCircleOutline, contentDescription = "History empty", tint = TextSecondary, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No playback history available", color = TextSecondary, fontSize = 15.sp)
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(groupedHistory) { (item, episodesList) ->
                    val groupKey = if (viewModel.isAniFam) item.subjectId.removePrefix("anifam_") else item.subjectId
                    val isExpanded = expandedSubjectId == groupKey

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(ObsidianCard, RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (item.isTvShow) {
                                        expandedSubjectId = if (isExpanded) null else groupKey
                                    } else {
                                        // Movie: play directly
                                        val subject = MovieSubject(
                                            id = item.subjectId,
                                            name = item.name,
                                            poster = item.poster,
                                            isTvShow = item.isTvShow
                                        )
                                        viewModel.selectSubject(subject)
                                        viewModel.selectMovieDirect()
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = item.poster,
                                contentDescription = item.name,
                                contentScale = ContentScale.Crop, placeholder = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.DarkGray), error = androidx.compose.ui.graphics.painter.ColorPainter(androidx.compose.ui.graphics.Color.DarkGray),
                                modifier = Modifier
                                    .size(width = 60.dp, height = 90.dp)
                                    .clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(modifier = Modifier.height(4.dp))
                                if (item.isTvShow) {
                                    Text(
                                        text = "${episodesList.size} episodes watched • Tap to view",
                                        fontSize = 13.sp,
                                        color = AccentAmber,
                                        fontWeight = FontWeight.Medium
                                    )
                                } else {
                                    val percentage = if (item.totalTime > 0) (item.seeTime.toFloat() / item.totalTime.toFloat()) else 0f
                                    val formattedProgress = formatTime(item.seeTime) + " / " + formatTime(item.totalTime)
                                    Text(formattedProgress, fontSize = 12.sp, color = TextSecondary)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = percentage.coerceIn(0f, 1f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp)),
                                        color = CinematicRed,
                                        trackColor = Color.Gray.copy(alpha = 0.3f)
                                    )
                                }
                            }
                            IconButton(onClick = {
                                viewModel.deleteHistoryForSubject(item.subjectId, episodesList)
                            }) {
                                Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete All", tint = TextSecondary)
                            }
                        }

                        if (item.isTvShow && isExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, start = 16.dp, end = 8.dp)
                                    .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                episodesList.forEach { epItem ->
                                    val percentage = if (epItem.totalTime > 0) (epItem.seeTime.toFloat() / epItem.totalTime.toFloat()) else 0f
                                    val formattedProgress = formatTime(epItem.seeTime) + " / " + formatTime(epItem.totalTime)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                val subject = MovieSubject(
                                                    id = epItem.subjectId,
                                                    name = epItem.name,
                                                    poster = epItem.poster,
                                                    isTvShow = epItem.isTvShow
                                                )
                                                viewModel.selectSubject(subject)
                                                val episode = Episode(
                                                    episodeId = epItem.id,
                                                    title = "Season ${epItem.season} Episode ${epItem.episode}",
                                                    episodeNumber = epItem.episode,
                                                    seasonNumber = epItem.season
                                                )
                                                viewModel.selectEpisode(episode)
                                            }
                                            .padding(vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Season ${epItem.season} Ep ${epItem.episode}", fontSize = 13.sp, color = AccentAmber, fontWeight = FontWeight.Bold)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(formattedProgress, fontSize = 11.sp, color = TextSecondary)
                                            Spacer(modifier = Modifier.height(4.dp))
                                            LinearProgressIndicator(
                                                progress = percentage.coerceIn(0f, 1f),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(3.dp)
                                                    .clip(RoundedCornerShape(1.dp)),
                                                color = CinematicRed,
                                                trackColor = Color.Gray.copy(alpha = 0.3f)
                                            )
                                        }
                                        IconButton(
                                            onClick = { viewModel.deleteHistoryItem(epItem.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Filled.DeleteOutline, contentDescription = "Delete", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LabScreen(viewModel: MovieViewModel) {
    var hostText by remember { mutableStateOf(viewModel.mockHost) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Science, contentDescription = "Laboratory", tint = AccentAmber, modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Text("BFF Laboratory Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
        Text("Sandbox & geofencing simulator config", fontSize = 13.sp, color = TextSecondary)

        Spacer(modifier = Modifier.height(20.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianCard),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Simulate Region Carrier Settings", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Overrides SIM parameters in API handshake headers", fontSize = 12.sp, color = TextSecondary)

                Spacer(modifier = Modifier.height(16.dp))

                // Region choices matching protocol Section 9
                val regions = listOf(
                    Triple("90101", "us", "United States (Global)"),
                    Triple("40401", "in", "India (Hindi Dubs)"),
                    Triple("62101", "ng", "Nigeria (Nollywood)")
                )

                regions.forEach { reg ->
                    val isSelected = viewModel.customMcc == reg.first && viewModel.customCountryIso == reg.second
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.applyCustomSandbox(reg.first, reg.second) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { viewModel.applyCustomSandbox(reg.first, reg.second) },
                            colors = RadioButtonDefaults.colors(selectedColor = CinematicRed)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(reg.third, color = TextPrimary, fontSize = 14.sp)
                            Text("MCC: ${reg.first} | ISO: ${reg.second}", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors = CardDefaults.cardColors(containerColor = ObsidianCard),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("BFF Proxy Host Configuration", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Route API traffic through your custom cloud instance", fontSize = 12.sp, color = TextSecondary)

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = hostText,
                    onValueChange = { hostText = it },
                    label = { Text("Server Host URL") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = CinematicRed,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.5f),
                        focusedLabelColor = CinematicRed,
                        cursorColor = CinematicRed
                    ),
                    modifier = Modifier.fillMaxWidth().testTag("mock_host_input")
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            val defaultHost = "http://127.0.0.1:3000"
                            hostText = defaultHost
                            viewModel.updateMockHost(defaultHost)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.2f)),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("Local Server", color = AccentAmber, fontSize = 12.sp)
                    }

                    Button(
                        onClick = {
                            hostText = "http://127.0.0.1:3000"
                            viewModel.updateMockHost("http://127.0.0.1:3000")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.2f)),
                        modifier = Modifier.weight(0.8f)
                    ) {
                        Text("Default Local", color = Color.White, fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = {
                        viewModel.updateMockHost(hostText.trim())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save & Apply Host", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { viewModel.resetSandbox() },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Gray.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Re-Lock Sandbox & Exit Lab", color = CinematicRed, fontWeight = FontWeight.Bold)
        }
    }
}

fun formatPreviewTime(ms: Long): String {
    val totalSec = maxOf(0L, ms / 1000L)
    val hrs = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hrs > 0) {
        String.format("%d:%02d:%02d", hrs, mins, secs)
    } else {
        String.format("%d:%02d", mins, secs)
    }
}

@Composable
fun SeekPreviewPopup(
    seekMs: Long,
    fraction: Float,
    previewPlayer: androidx.media3.exoplayer.ExoPlayer? = null,
    previewSource: Any? = null,
    modifier: Modifier = Modifier
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val previewWidthDp = 136.dp
    val previewHeightDp = 76.dp
    val previewWidthPx = with(density) { previewWidthDp.toPx() }
    val timeBadgeWidthDp = 64.dp
    val timeBadgeWidthPx = with(density) { timeBadgeWidthDp.toPx() }
    var containerWidthPx by remember { mutableStateOf(0f) }

    // Live real-time seeking for preview player
    var lastSeekTriggerTime by remember { mutableStateOf(0L) }
    LaunchedEffect(seekMs, previewPlayer) {
        if (previewPlayer != null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastSeekTriggerTime > 60L) {
                lastSeekTriggerTime = now
                previewPlayer.seekTo(seekMs)
            } else {
                kotlinx.coroutines.delay(60L)
                lastSeekTriggerTime = android.os.SystemClock.uptimeMillis()
                previewPlayer.seekTo(seekMs)
            }
        }
    }

    // Offline bitmap extraction fallback (for local File)
    val retrieverState = produceState<android.media.MediaMetadataRetriever?>(initialValue = null, key1 = previewSource) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            when (previewSource) {
                is java.io.File -> {
                    if (previewSource.exists()) {
                        try {
                            val r = android.media.MediaMetadataRetriever()
                            r.setDataSource(previewSource.absolutePath)
                            r
                        } catch (_: Throwable) { null }
                    } else null
                }
                else -> null
            }
        }
    }
    val retriever = retrieverState.value

    DisposableEffect(retriever) {
        onDispose {
            try { retriever?.release() } catch (_: Throwable) {}
        }
    }

    val frameCache = remember { android.util.LruCache<Long, android.graphics.Bitmap>(30) }
    var previewBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val roundedBucket = (seekMs / 1000L) * 1000L
    LaunchedEffect(roundedBucket, retriever) {
        if (retriever != null) {
            val cached = frameCache.get(roundedBucket)
            if (cached != null) {
                previewBitmap = cached
            } else {
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val timeUs = maxOf(1000L, seekMs * 1000L)
                        val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                            retriever.getScaledFrameAtTime(
                                timeUs,
                                android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                280,
                                158
                            )
                        } else {
                            retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        }
                        if (bmp != null) {
                            frameCache.put(roundedBucket, bmp)
                            withContext(kotlinx.coroutines.Dispatchers.Main) {
                                previewBitmap = bmp
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .onGloballyPositioned { coordinates ->
                containerWidthPx = coordinates.size.width.toFloat()
            }
    ) {
        val effectiveWidth = if (containerWidthPx > 0f) containerWidthPx else with(density) { 360.dp.toPx() }
        val thumbX = effectiveWidth * fraction.coerceIn(0f, 1f)
        val hasPreview = (previewPlayer != null || previewBitmap != null)
        val popupWidthPx = if (hasPreview) previewWidthPx else timeBadgeWidthPx
        val halfWidth = popupWidthPx / 2f
        val rawX = thumbX - halfWidth
        val clampedX = if (effectiveWidth > popupWidthPx) {
            rawX.coerceIn(0f, effectiveWidth - popupWidthPx)
        } else {
            0f
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(clampedX.roundToInt(), 0) }
        ) {
            // 1. Thumbnail Card (16:9) using TextureView preview_card_player_view
            if (hasPreview) {
                Box(
                    modifier = Modifier
                        .size(previewWidthDp, previewHeightDp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF16161A))
                        .border(1.5.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (previewBitmap != null) {
                        Image(
                            bitmap = previewBitmap!!.asImageBitmap(),
                            contentDescription = "Seek Frame",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    if (previewPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                val view = android.view.LayoutInflater.from(ctx)
                                    .inflate(com.example.R.layout.preview_card_player_view, null) as androidx.media3.ui.PlayerView
                                view.player = previewPlayer
                                view
                            },
                            update = { view ->
                                if (view.player != previewPlayer) {
                                    view.player = previewPlayer
                                }
                            },
                            onRelease = { view ->
                                view.player = null
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 2. Floating Time Badge Pill (YouTube/Netflix style)
            Box(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.90f), RoundedCornerShape(5.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = formatPreviewTime(seekMs),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun VideoProgressBar(
    currentPositionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit,
    onSeekFinished: (Long) -> Unit,
    onSeekingChanged: ((Boolean, Long, Float) -> Unit)? = null,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFFE50914),
    inactiveColor: Color = Color.White.copy(alpha = 0.35f),
    thumbColor: Color = Color(0xFFE50914)
) {
    val totalMs = maxOf(1L, durationMs)
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }

    val currentFraction = if (isDragging) {
        dragFraction
    } else {
        (currentPositionMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    }

    val currentTotalMs by rememberUpdatedState(totalMs)
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)
    val currentOnSeekingChanged by rememberUpdatedState(onSeekingChanged)

    Box(
        modifier = modifier
            .height(32.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isDragging = true
                    var fraction = (down.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                    dragFraction = fraction
                    var targetMs = (fraction * currentTotalMs).toLong()
                    currentOnSeek(targetMs)
                    currentOnSeekingChanged?.invoke(true, targetMs, fraction)

                    val pointerId = down.id
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null || !change.pressed) {
                            break
                        }
                        change.consume()
                        fraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        dragFraction = fraction
                        targetMs = (fraction * currentTotalMs).toLong()
                        currentOnSeek(targetMs)
                        currentOnSeekingChanged?.invoke(true, targetMs, fraction)
                    }

                    isDragging = false
                    currentOnSeekingChanged?.invoke(false, targetMs, fraction)
                    currentOnSeekFinished(targetMs)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(16.dp)
        ) {
            val centerY = size.height / 2f
            val trackHeight = 2.5.dp.toPx()
            val thumbRadius = if (isDragging) 6.5.dp.toPx() else 4.5.dp.toPx()
            val activeWidth = size.width * currentFraction

            // Inactive (background) Track
            drawLine(
                color = inactiveColor,
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = trackHeight,
                cap = StrokeCap.Round
            )

            // Active (played) Track
            if (activeWidth > 0f) {
                drawLine(
                    color = if (enabled) activeColor else Color.LightGray.copy(alpha = 0.6f),
                    start = Offset(0f, centerY),
                    end = Offset(activeWidth.coerceAtMost(size.width), centerY),
                    strokeWidth = trackHeight,
                    cap = StrokeCap.Round
                )
            }

            // Single clean circular thumb dot
            if (enabled) {
                drawCircle(
                    color = thumbColor,
                    radius = thumbRadius,
                    center = Offset(activeWidth.coerceIn(0f, size.width), centerY)
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(viewModel: MovieViewModel) {
    val playInfo = viewModel.playInfo ?: return
    val subject = viewModel.selectedSubject ?: return
    val context = LocalContext.current

    val selectedStream = viewModel.selectedStream
    val rawUrl = selectedStream?.url ?: ""
    val streamUrl = if (rawUrl == "https://macdn.aoneroom.com/other/2026/09/04/b164fbfb4347792950bdfbfb563d39d9.mp4") "" else rawUrl
    val selectedCookie = selectedStream?.cookie ?: ""
    val subtitleUrl = viewModel.selectedSubtitle?.url

    val previewHeaders = remember(rawUrl, selectedCookie, viewModel.isAniFam) {
        val map = mutableMapOf<String, String>()
        map["User-Agent"] = if (viewModel.isAniFam) "okhttp/4.12.0" else "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)"
        if (viewModel.isAniFam || rawUrl.contains("dramahot.top") || rawUrl.contains("zokoanime") || rawUrl.contains("hakunaymatata")) {
            map["Referer"] = "https://zokoanime.video/"
            map["Origin"] = "https://zokoanime.video"
        }
        if (selectedCookie.isNotEmpty()) {
            if (selectedCookie.contains("=") && (selectedCookie.contains("Referer=") || selectedCookie.contains("Origin="))) {
                selectedCookie.split(";").forEach { pair ->
                    val parts = pair.split("=", limit = 2)
                    if (parts.size == 2) map[parts[0].trim()] = parts[1].trim()
                }
            } else {
                map["Cookie"] = selectedCookie
            }
        }
        map
    }

    var activeSheet by remember { mutableStateOf<String?>(null) }
    var isDownloadSelectionMode by remember { mutableStateOf(false) }
    var selectedDownloadQuality by remember { mutableStateOf(viewModel.defaultDownloadQuality.ifEmpty { "1080p" }) }
    val selectedDownloadEpisodes = remember { androidx.compose.runtime.mutableStateListOf<com.example.data.model.Episode>() }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp
    val isFullscreen = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val playerHeight = minOf(screenWidth * (9f / 16f), screenHeight * 0.32f)
    var isControllerVisible by remember { mutableStateOf(true) }

    // Double-tap Gesture Indicator States
    var doubleTapActionText by remember { mutableStateOf<String?>(null) }
    var doubleTapActionSide by remember { mutableStateOf<String?>(null) } // "left", "center", "right"
    var gestureAnimTrigger by remember { mutableStateOf(0L) }

    BackHandler {
        if (activeSheet != null) {
            activeSheet = null
        } else if (isDownloadSelectionMode) {
            isDownloadSelectionMode = false
        } else if (isFullscreen) {
            val activity = context as? android.app.Activity
            if (activity != null) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        } else {
            if (viewModel.activeW2GRoom != null) {
                viewModel.leaveActiveW2GRoom()
                viewModel.selectedSubject = null
            }
            viewModel.playInfo = null
            val activity = context as? android.app.Activity
            if (activity != null) {
                activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // Google Cast Session State & Listener
    var isCastConnected by remember { mutableStateOf(false) }
    val castContext = remember {
        try {
            com.google.android.gms.cast.framework.CastContext.getSharedInstance(context)
        } catch (_: Exception) {
            null
        }
    }

    DisposableEffect(castContext, streamUrl) {
        val sessionManager = castContext?.sessionManager
        val castListener = object : com.google.android.gms.cast.framework.SessionManagerListener<com.google.android.gms.cast.framework.CastSession> {
            override fun onSessionStarted(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {
                isCastConnected = true
                loadMediaOnCast(session, streamUrl, subject, viewModel, context)
            }
            override fun onSessionResumed(session: com.google.android.gms.cast.framework.CastSession, wasSuspended: Boolean) {
                isCastConnected = true
                loadMediaOnCast(session, streamUrl, subject, viewModel, context)
            }
            override fun onSessionEnded(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                isCastConnected = false
                com.example.cast.CastForegroundService.stop(context)
            }
            override fun onSessionSuspended(session: com.google.android.gms.cast.framework.CastSession, reason: Int) {
                isCastConnected = false
                com.example.cast.CastForegroundService.stop(context)
            }
            override fun onSessionStarting(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionStartFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                isCastConnected = false
                com.example.cast.CastForegroundService.stop(context)
            }
            override fun onSessionEnding(session: com.google.android.gms.cast.framework.CastSession) {}
            override fun onSessionResuming(session: com.google.android.gms.cast.framework.CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: com.google.android.gms.cast.framework.CastSession, error: Int) {
                isCastConnected = false
                com.example.cast.CastForegroundService.stop(context)
            }
        }

        // Check current session immediately
        val currentSession = sessionManager?.currentCastSession
        if (currentSession != null && currentSession.isConnected) {
            isCastConnected = true
            loadMediaOnCast(currentSession, streamUrl, subject, viewModel, context)
        }

        sessionManager?.addSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java)
        onDispose {
            sessionManager?.removeSessionManagerListener(castListener, com.google.android.gms.cast.framework.CastSession::class.java)
        }
    }

    LaunchedEffect(viewModel.selectedStream, isCastConnected) {
        if (isCastConnected && castContext != null) {
            val session = castContext.sessionManager?.currentCastSession
            if (session != null && session.isConnected && viewModel.selectedStream != null) {
                loadMediaOnCast(session, viewModel.selectedStream?.url ?: streamUrl, subject, viewModel, context)
            }
        }
    }

    // Initialize Media3 ExoPlayer
    val exoPlayer = remember {
        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(androidx.media3.common.C.USAGE_MEDIA)
            .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
            
        val renderersFactory = androidx.media3.exoplayer.DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setMediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
                val decoders = androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
                if (mimeType.startsWith("video/")) {
                    decoders.sortedWith { d1, d2 ->
                        val n1 = d1.name.lowercase()
                        val n2 = d2.name.lowercase()
                        val sw1 = n1.contains("google") || n1.contains("c2.android") || n1.contains("sw")
                        val sw2 = n2.contains("google") || n2.contains("c2.android") || n2.contains("sw")
                        when {
                            !sw1 && sw2 -> -1 // Priority 1: Hardware decoders (Adreno, Exynos, Mali, Tensor)
                            sw1 && !sw2 -> 1  // Priority 2: Software fallback
                            else -> 0
                        }
                    }
                } else {
                    decoders
                }
            }
        }
        
        // Dynamic Hardware-Based Buffering (Vega-Style)
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val totalRamBytes = memoryInfo.totalMem
        val heapLimitBytes = activityManager.memoryClass * 1024L * 1024L
        
        val safeBufferBytes = minOf(
            (heapLimitBytes * 0.35).toLong(),
            (totalRamBytes / 32)
        ).toInt()
        val targetBufferBytes = safeBufferBytes.coerceAtLeast(32 * 1024 * 1024) // Fallback to 32MB min
        
        val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(true, androidx.media3.common.C.DEFAULT_BUFFER_SEGMENT_SIZE)
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setAllocator(allocator)
            .setBufferDurationsMs(
                25000,   // minBufferMs (25 seconds minimum buffer before idle)
                120000,  // maxBufferMs (2 minutes max buffer limit)
                500,     // bufferForPlaybackMs (Only 0.5s needed to start instantly, matching MovieBox np.d.java!)
                1500     // bufferForPlaybackAfterRebufferMs (Only 1.5 seconds needed to recover)
            )
            .setTargetBufferBytes(targetBufferBytes) // Dynamically calculated safe limit
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30000, true) // Keep 30 seconds of previous video in RAM
            .build()

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, false)
            .build().apply {
                volume = 1.0f
                playWhenReady = viewModel.isPlaying
            }
    }

    // Dedicated lightweight preview player for live scrubbing thumbnail frames
    val previewPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            playWhenReady = false
            setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
        }
    }
    DisposableEffect(previewPlayer) {
        onDispose {
            try { previewPlayer.release() } catch (_: Throwable) {}
        }
    }

    var currentProgressMs by remember { mutableStateOf(viewModel.playbackProgressMs) }
    var durationMs by remember { mutableStateOf(viewModel.mediaDurationMs) }
    var isSeeking by remember { mutableStateOf(false) }
    var seekPositionMs by remember { mutableStateOf(0L) }
    var seekFraction by remember { mutableStateOf(0f) }
    var lastSeekFinishedTime by remember { mutableStateOf(0L) }

    // Sync native playlist next/prev buttons transitions with ViewModel selections & W2G Live Sync
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                viewModel.isPlaying = playWhenReady
                // Live Sync: If host, broadcast play/pause state and current position
                if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                    val posSec = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0)
                    viewModel.syncW2GPlayback(if (playWhenReady) "play" else "pause", posSec)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // Live Sync: When video is loaded and ready, set duration and sync initial position
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    val dur = exoPlayer.duration
                    if (dur > 0) {
                        durationMs = dur
                    }
                    currentProgressMs = exoPlayer.currentPosition

                    if (viewModel.isAniFam && viewModel.activeW2GRoom != null) {
                        val initialPosMs = (viewModel.activeW2GRoom?.state?.positionSeconds ?: 0) * 1000L
                        if (initialPosMs > 0 && exoPlayer.currentPosition < 1000L) {
                            exoPlayer.seekTo(initialPosMs)
                            currentProgressMs = initialPosMs
                        }
                        if (!viewModel.isHostingW2G) {
                            val hostPlaying = viewModel.activeW2GRoom?.state?.playing == true
                            exoPlayer.playWhenReady = hostPlaying
                            viewModel.isPlaying = hostPlaying
                        }
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int
            ) {
                // Live Sync: If host manually seeks, broadcast seek position
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                        val posSec = (newPosition.positionMs / 1000).toInt().coerceAtLeast(0)
                        viewModel.syncW2GPlayback("seek", posSec)
                    }
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (mediaItem != null && mediaItem.localConfiguration?.uri?.toString() == "http://dummy.mp4") {
                    val targetEpisodeId = mediaItem.mediaId
                    val targetEpisode = viewModel.episodesList.find { it.episodeId == targetEpisodeId }
                    if (targetEpisode != null) {
                        viewModel.selectEpisode(targetEpisode)
                    }
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Live Sync: Guest auto-follows host playback state and position drift
    androidx.compose.runtime.LaunchedEffect(viewModel.activeW2GRoom?.state) {
        val state = viewModel.activeW2GRoom?.state ?: return@LaunchedEffect
        if (viewModel.isAniFam && !viewModel.isHostingW2G && !viewModel.isGuestWatchingOnOwn && exoPlayer.playbackState != androidx.media3.common.Player.STATE_IDLE) {
            val hostPlaying = state.playing == true
            val targetPosMs = (state.positionSeconds * 1000L).coerceAtLeast(0L)

            // Match play/pause
            if (exoPlayer.playWhenReady != hostPlaying) {
                exoPlayer.playWhenReady = hostPlaying
                viewModel.isPlaying = hostPlaying
            }

            // Correct position drift if greater than 2.5 seconds (2500ms)
            val currentPos = exoPlayer.currentPosition
            if (kotlin.math.abs(currentPos - targetPosMs) > 2500L) {
                exoPlayer.seekTo(targetPosMs)
            }
        }
    }

    // Update stream and subtitle configuration
    LaunchedEffect(streamUrl, subtitleUrl) {
        if (streamUrl.isNotEmpty()) {
            val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                .setUserAgent(if (viewModel.isAniFam) "okhttp/4.12.0" else "MovieBoxPro/16.2.1 (Android 14; com.community.mbox.in)")
                .setAllowCrossProtocolRedirects(true)
            
            val requestHeaders = mutableMapOf<String, String>()
            if (viewModel.isAniFam || streamUrl.contains("dramahot.top") || streamUrl.contains("zokoanime")) {
                requestHeaders["Referer"] = "https://zokoanime.video/"
                requestHeaders["Origin"] = "https://zokoanime.video"
            }
            if (selectedCookie.isNotEmpty()) {
                if (selectedCookie.contains("=") && (selectedCookie.contains("Referer=") || selectedCookie.contains("Origin="))) {
                    selectedCookie.split(";").forEach { pair ->
                        val parts = pair.split("=", limit = 2)
                        if (parts.size == 2) {
                            requestHeaders[parts[0].trim()] = parts[1].trim()
                        }
                    }
                } else {
                    requestHeaders["Cookie"] = selectedCookie
                }
            }
            if (requestHeaders.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(requestHeaders)
            }

            // Wrap with on-disk LRU media cache for zero-stutter seeks and zero repeated downloads
            val cacheDataSourceFactory = androidx.media3.datasource.cache.CacheDataSource.Factory()
                .setCache(VideoCacheManager.getCache(context))
                .setUpstreamDataSourceFactory(httpDataSourceFactory)
                .setCacheKeyFactory { VideoCacheManager.getCacheKey(it.uri) }
                .setFlags(androidx.media3.datasource.cache.CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

            val mediaItemBuilder = MediaItem.Builder().setUri(Uri.parse(streamUrl))
            if (!subtitleUrl.isNullOrEmpty()) {
                val mimeType = if (subtitleUrl.endsWith(".vtt", ignoreCase = true)) {
                    MimeTypes.TEXT_VTT
                } else {
                    MimeTypes.APPLICATION_SUBRIP
                }
                val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitleUrl))
                    .setMimeType(mimeType)
                    .setLanguage(viewModel.selectedSubtitle?.language ?: "en")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                mediaItemBuilder.setSubtitleConfigurations(listOf(subtitleConfig))
            }

            val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                cacheDataSourceFactory,
                androidx.media3.extractor.DefaultExtractorsFactory()
            )
            val mediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())

            val currentEpisode = viewModel.currentEpisode
            val episodes = viewModel.episodesList
            if (subject.isTvShow && episodes.isNotEmpty() && currentEpisode != null) {
                val currentIndex = episodes.indexOfFirst { it.episodeId == currentEpisode.episodeId }
                if (currentIndex != -1) {
                    val mediaSources = episodes.map { ep ->
                        if (ep.episodeId == currentEpisode.episodeId) {
                            mediaSource
                        } else {
                            mediaSourceFactory.createMediaSource(
                                MediaItem.Builder()
                                    .setMediaId(ep.episodeId)
                                    .setUri(Uri.parse("http://dummy.mp4"))
                                    .build()
                            )
                        }
                    }
                    exoPlayer.setMediaSources(mediaSources, currentIndex, viewModel.playbackProgressMs)
                } else {
                    exoPlayer.setMediaSource(mediaSource)
                }
            } else {
                exoPlayer.setMediaSource(mediaSource)
            }
            exoPlayer.prepare()
            if (viewModel.playbackProgressMs > 0) {
                exoPlayer.seekTo(viewModel.playbackProgressMs)
            }
            if (viewModel.isPlaying) {
                exoPlayer.play()
            }

            // Prepare read-only preview player for live scrubbing
            try {
                val previewMediaSource = mediaSourceFactory.createMediaSource(mediaItemBuilder.build())
                previewPlayer.setMediaSource(previewMediaSource)
                previewPlayer.prepare()
            } catch (e: Throwable) {
                android.util.Log.e("PlayerScreen", "Failed to prepare preview player", e)
            }
        }
    }

    // Play/Pause sync
    LaunchedEffect(viewModel.isPlaying) {
        if (viewModel.isPlaying != exoPlayer.playWhenReady) {
            exoPlayer.playWhenReady = viewModel.isPlaying
        }
    }



    // Buffering listener
    var isBuffering by remember { mutableStateOf(false) }
    DisposableEffect(exoPlayer) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = (state == androidx.media3.common.Player.STATE_BUFFERING)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Auto-hide controller when playing
    LaunchedEffect(isControllerVisible, viewModel.isPlaying, isSeeking) {
        if (isControllerVisible && viewModel.isPlaying && !isSeeking) {
            delay(4000)
            isControllerVisible = false
        }
    }

    // Speed sync
    var selectedSpeed by remember { mutableStateOf(1.0f) }
    LaunchedEffect(selectedSpeed) {
        exoPlayer.setPlaybackSpeed(selectedSpeed)
    }

    // Dynamic resolution constraint selector
    LaunchedEffect(selectedStream) {
        val q = selectedStream?.quality ?: "Auto"
        val (maxWidth, maxHeight) = when {
            q.contains("4k", ignoreCase = true) || q.contains("2160") -> Pair(3840, 2160)
            q.contains("2k", ignoreCase = true) || q.contains("1440") -> Pair(2560, 1440)
            q.contains("1080") -> Pair(1920, 1080)
            q.contains("720") -> Pair(1280, 720)
            q.contains("480") -> Pair(854, 480)
            q.contains("360") -> Pair(640, 360)
            q.contains("240") -> Pair(426, 240)
            else -> Pair(Integer.MAX_VALUE, Integer.MAX_VALUE)
        }
        try {
            val builder = exoPlayer.trackSelectionParameters.buildUpon()
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
            if (viewModel.isAniFam) {
                val isDub = viewModel.selectedResource?.resourceId?.lowercase()?.contains("dub") == true ||
                            viewModel.selectedResource?.name?.lowercase()?.contains("dub") == true
                if (isDub) {
                    builder.setPreferredAudioLanguage("en")
                } else {
                    builder.setPreferredAudioLanguage("ja")
                }
            }
            if (q == "Auto") {
                builder.clearVideoSizeConstraints()
                builder.setForceHighestSupportedBitrate(false)
            } else {
                builder.setMaxVideoSize(maxWidth, maxHeight)
                // Force ExoPlayer to pick the highest possible quality within this cap immediately
                // instead of waiting for adaptive bandwidth estimation to trigger a switch
                builder.setForceHighestSupportedBitrate(true)
            }
            exoPlayer.trackSelectionParameters = builder.build()
        } catch (e: Exception) {
            // Log fallback or safety
        }
    }

    // Dynamic audio track preference sync for AniFam
    LaunchedEffect(viewModel.selectedResource) {
        if (viewModel.isAniFam) {
            try {
                val isDub = viewModel.selectedResource?.resourceId?.lowercase()?.contains("dub") == true ||
                            viewModel.selectedResource?.name?.lowercase()?.contains("dub") == true
                val builder = exoPlayer.trackSelectionParameters.buildUpon()
                builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_AUDIO, false)
                if (isDub) {
                    builder.setPreferredAudioLanguage("en")
                } else {
                    builder.setPreferredAudioLanguage("ja")
                }
                exoPlayer.trackSelectionParameters = builder.build()
            } catch (_: Exception) {}
        }
    }

    // Listen to player progress ticking
    LaunchedEffect(viewModel.isPlaying) {
        if (viewModel.isPlaying) {
            while (true) {
                if (!isSeeking && System.currentTimeMillis() - lastSeekFinishedTime > 1200L) {
                    val pos = exoPlayer.currentPosition
                    val dur = exoPlayer.duration
                    if (dur > 0) {
                        currentProgressMs = pos
                        durationMs = dur
                        viewModel.updatePlaybackProgress(pos, dur)
                    }
                }
                delay(1000)
            }
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {
            exoPlayer.release()
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    val activity = context as? android.app.Activity
    val isInPip = activity?.isInPictureInPictureMode ?: false

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (!isFullscreen) Modifier.statusBarsPadding() else Modifier)
        ) {

            // Top Bar in Portrait W2G Mode (matches AniFam official W2G UI)
            if (!isFullscreen && !isInPip && viewModel.isAniFam && viewModel.activeW2GRoom != null) {
                val room = viewModel.activeW2GRoom!!
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            viewModel.leaveActiveW2GRoom()
                            viewModel.selectedSubject = null
                            viewModel.playInfo = null
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Leave Watch Party", tint = Color.White)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = room.name?.ifEmpty { null } ?: room.host?.name ?: "Watch Party",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val epNumber = room.episode?.episodeNumber ?: viewModel.currentEpisode?.episodeNumber ?: 1
                        val subText = "${room.anime?.title ?: subject.name} - Ep $epNumber"
                        Text(
                            text = subText,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    TextButton(
                        onClick = {
                            viewModel.leaveActiveW2GRoom()
                            viewModel.selectedSubject = null
                            viewModel.playInfo = null
                        }
                    ) {
                        Text(
                            text = if (viewModel.isHostingW2G) "End room" else "Leave room",
                            color = Color(0xFFEF4444),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            // Player Container (Always present in tree at index 0)
        Box(
            modifier = if (isFullscreen || isInPip) {
                Modifier.fillMaxSize()
            } else {
                Modifier
                    .fillMaxWidth()
                    .height(playerHeight)
            }
        ) {
            if (streamUrl.isNotEmpty()) {
                AndroidView(
                    factory = { ctx ->
                        val view = android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.texture_player_view, null) as androidx.media3.ui.PlayerView
                        view.keepScreenOn = true
                        view.isHapticFeedbackEnabled = false
                        view.isSoundEffectsEnabled = false
                        view.layoutParams = android.view.ViewGroup.LayoutParams(
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                            android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        view.apply {
                            subtitleView?.setViewType(androidx.media3.ui.SubtitleView.VIEW_TYPE_CANVAS)
                            val cleanStyle = androidx.media3.ui.CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                                androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                android.graphics.Color.BLACK,
                                null
                            )
                            subtitleView?.setStyle(cleanStyle)
                        }
                    },
                    update = { view ->
                        if (view.player != exoPlayer) {
                            view.player = exoPlayer
                        }
                    },
                    onRelease = { view ->
                        view.player = null
                    },
                    modifier = Modifier.fillMaxSize()
                )



                // Universal Gestures Layer (Single tap toggles UI, Double tap Left = -5s, Center = Pause/Play, Right = +10s)
                val density = androidx.compose.ui.platform.LocalDensity.current
                val coroutineScope = rememberCoroutineScope()

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = {
                                    isControllerVisible = !isControllerVisible
                                },
                                onDoubleTap = { tapOffset: androidx.compose.ui.geometry.Offset ->
                                    val width = size.width.toFloat()
                                    val x = tapOffset.x
                                    val leftBoundary = width * 0.35f
                                    val rightBoundary = width * 0.65f

                                    // If guest in W2G, mark independent playback (watching on own)
                                    if (viewModel.isAniFam && viewModel.activeW2GRoom != null && !viewModel.isHostingW2G) {
                                        viewModel.isGuestWatchingOnOwn = true
                                    }

                                    when {
                                        x < leftBoundary -> {
                                            // Left side: rewind 10 seconds (matches AniFam official player)
                                            val current = exoPlayer.currentPosition
                                            val target = (current - 10000L).coerceAtLeast(0L)
                                            exoPlayer.seekTo(target)
                                            if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                                val posSec = (target / 1000).toInt().coerceAtLeast(0)
                                                viewModel.syncW2GPlayback("seek", posSec)
                                            }
                                            doubleTapActionSide = "left"
                                            doubleTapActionText = "-10s"
                                            gestureAnimTrigger = System.currentTimeMillis()
                                        }
                                        x > rightBoundary -> {
                                            // Right side: forward 10 seconds
                                            val current = exoPlayer.currentPosition
                                            val dur = exoPlayer.duration
                                            val target = if (dur > 0) (current + 10000L).coerceAtMost(dur) else current + 10000L
                                            exoPlayer.seekTo(target)
                                            if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                                val posSec = (target / 1000).toInt().coerceAtLeast(0)
                                                viewModel.syncW2GPlayback("seek", posSec)
                                            }
                                            doubleTapActionSide = "right"
                                            doubleTapActionText = "+10s"
                                            gestureAnimTrigger = System.currentTimeMillis()
                                        }
                                        else -> {
                                            // Center: toggle play / pause (stop video)
                                            if (exoPlayer.isPlaying) {
                                                exoPlayer.pause()
                                                viewModel.isPlaying = false
                                                if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                                    val posSec = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0)
                                                    viewModel.syncW2GPlayback("pause", posSec)
                                                }
                                                doubleTapActionSide = "center"
                                                doubleTapActionText = "PAUSE"
                                            } else {
                                                exoPlayer.play()
                                                viewModel.isPlaying = true
                                                if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                                    val posSec = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0)
                                                    viewModel.syncW2GPlayback("play", posSec)
                                                }
                                                doubleTapActionSide = "center"
                                                doubleTapActionText = "PLAY"
                                            }
                                            gestureAnimTrigger = System.currentTimeMillis()
                                        }
                                    }
                                }
                            )
                        }
                )

                // Double-Tap Animated Feedback Badges
                LaunchedEffect(gestureAnimTrigger) {
                    if (gestureAnimTrigger > 0L) {
                        delay(750)
                        doubleTapActionSide = null
                        doubleTapActionText = null
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = doubleTapActionSide != null,
                    enter = fadeIn(tween(150)) + scaleIn(tween(150)),
                    exit = fadeOut(tween(250)),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when (doubleTapActionSide) {
                            "left" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.38f)
                                        .align(Alignment.CenterStart)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Filled.Replay5,
                                            contentDescription = "Rewind 5s",
                                            tint = Color.White,
                                            modifier = Modifier.size(46.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = doubleTapActionText ?: "-5s",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            "right" -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.38f)
                                        .align(Alignment.CenterEnd)
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(Color.Transparent, Color.White.copy(alpha = 0.22f))
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            Icons.Filled.Forward10,
                                            contentDescription = "Forward 10s",
                                            tint = Color.White,
                                            modifier = Modifier.size(46.dp)
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = doubleTapActionText ?: "+10s",
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                            "center" -> {
                                Box(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .align(Alignment.Center)
                                        .background(Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(20.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            if (viewModel.isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                            contentDescription = "Play/Pause Toggle",
                                            tint = Color.White,
                                            modifier = Modifier.size(40.dp)
                                        )
                                        Text(
                                            text = doubleTapActionText ?: "",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Custom Compose Playback Controller Overlay
                androidx.compose.animation.AnimatedVisibility(
                    visible = isControllerVisible && !isInPip,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Top Bar: Back button on the left, Settings gear on the right
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopStart)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent)
                                    )
                                )
                                .statusBarsPadding()
                                .clickable(enabled = false) {} // block click-through
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            if (viewModel.activeW2GRoom == null || isFullscreen) {
                                IconButton(onClick = {
                                    if (viewModel.activeW2GRoom != null) {
                                        viewModel.leaveActiveW2GRoom()
                                        viewModel.selectedSubject = null
                                    }
                                    viewModel.playInfo = null
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                                        activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                    }
                                }) {
                                    Icon(
                                        Icons.Filled.ArrowBack,
                                        contentDescription = "Close Player",
                                        tint = Color.White
                                    )
                                }
                            } else {
                                Spacer(modifier = Modifier.size(36.dp))
                            }
                            
                            // Top Right Icons: Sync with host (if watching on own), Download (hidden in W2G party mode) and Settings Gear
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (viewModel.isAniFam && viewModel.activeW2GRoom != null && !viewModel.isHostingW2G && viewModel.isGuestWatchingOnOwn) {
                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = Color(0xFFEF4444),
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .clip(RoundedCornerShape(16.dp))
                                            .clickable {
                                                viewModel.isGuestWatchingOnOwn = false
                                                val state = viewModel.activeW2GRoom?.state
                                                val targetPosMs = (state?.positionSeconds ?: 0) * 1000L
                                                exoPlayer.seekTo(targetPosMs)
                                                val hostPlaying = state?.playing == true
                                                exoPlayer.playWhenReady = hostPlaying
                                                viewModel.isPlaying = hostPlaying
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Refresh,
                                                contentDescription = "Sync with host",
                                                tint = Color.White,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "Sync with host",
                                                color = Color.White,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                if (viewModel.activeW2GRoom == null) {
                                    IconButton(onClick = {
                                        val safeTitle = if (subject.isTvShow) {
                                            "${subject.name} S${viewModel.selectedSeason}E${viewModel.currentEpisode?.episodeNumber}"
                                        } else {
                                            subject.name
                                        }
                                        val bestStream = viewModel.playInfo?.streamList?.let { viewModel.selectHighestQualityStream(it) }
                                        val targetStream = if (viewModel.isAniFam) (viewModel.selectedStream ?: bestStream) else (bestStream ?: viewModel.selectedStream)
                                        val dlUrl = targetStream?.url?.takeIf { it.isNotEmpty() && !it.contains("b164fbfb4347792950bdfbfb563d39d9.mp4") } ?: streamUrl
                                        val cookies = targetStream?.cookie ?: viewModel.selectedStream?.cookie ?: ""
                                        viewModel.startDownload(context, subject, safeTitle, dlUrl, cookies, viewModel.playInfo?.subTitleList ?: emptyList())
                                    }) {
                                        Icon(
                                            Icons.Filled.Download,
                                            contentDescription = "Download Video",
                                            tint = Color.White
                                        )
                                    }
                                }
                                
                                if (viewModel.activeW2GRoom == null) {
                                    IconButton(onClick = {
                                        activeSheet = "player_settings"
                                    }) {
                                        Icon(
                                            Icons.Filled.Settings,
                                            contentDescription = "Player Settings",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        // Center Buffering Spinner (if loading)
                        if (isBuffering) {
                            CircularProgressIndicator(
                                color = CinematicRed,
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(48.dp)
                            )
                        }

                        // Bottom Controls: Floating Seek Preview + Controls Row
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomStart)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                                    )
                                )
                                .then(if (isFullscreen) Modifier.navigationBarsPadding() else Modifier)
                                .clickable(enabled = false) {} // block click-through
                        ) {
                            // Floating Seek Preview Popup (above the seekbar, never clipped!)
                            androidx.compose.animation.AnimatedVisibility(
                                visible = isSeeking,
                                enter = fadeIn(tween(120)) + scaleIn(tween(120)),
                                exit = fadeOut(tween(120))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.Bottom
                                ) {
                                    Spacer(modifier = Modifier.size(32.dp))
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = 8.dp)
                                    ) {
                                        SeekPreviewPopup(
                                            seekMs = seekPositionMs,
                                            fraction = seekFraction,
                                            previewPlayer = previewPlayer
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(120.dp))
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Play/Pause Button
                                IconButton(
                                    onClick = {
                                        if (viewModel.isAniFam && viewModel.activeW2GRoom != null && !viewModel.isHostingW2G) {
                                            viewModel.isGuestWatchingOnOwn = true
                                        }
                                        if (exoPlayer.isPlaying) {
                                            exoPlayer.pause()
                                            viewModel.isPlaying = false
                                            if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                                val posSec = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0)
                                                viewModel.syncW2GPlayback("pause", posSec)
                                            }
                                        } else {
                                            exoPlayer.play()
                                            viewModel.isPlaying = true
                                            if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                                val posSec = (exoPlayer.currentPosition / 1000).toInt().coerceAtLeast(0)
                                                viewModel.syncW2GPlayback("play", posSec)
                                            }
                                        }
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        if (viewModel.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        contentDescription = "Play/Pause",
                                        tint = Color.White,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                // Video Progress Bar (Thin, crisp line with a single clean circular thumb dot)
                                VideoProgressBar(
                                    currentPositionMs = if (isSeeking) seekPositionMs else currentProgressMs,
                                    durationMs = durationMs,
                                    onSeek = { seekPositionMs = it },
                                    onSeekFinished = { targetMs ->
                                        lastSeekFinishedTime = System.currentTimeMillis()
                                        currentProgressMs = targetMs
                                        if (viewModel.isAniFam && viewModel.activeW2GRoom != null && !viewModel.isHostingW2G) {
                                            viewModel.isGuestWatchingOnOwn = true
                                        }
                                        exoPlayer.seekTo(targetMs)
                                        if (viewModel.isAniFam && viewModel.activeW2GRoom != null && viewModel.isHostingW2G) {
                                            val posSec = (targetMs / 1000).toInt().coerceAtLeast(0)
                                            viewModel.syncW2GPlayback("seek", posSec)
                                        }
                                    },
                                    onSeekingChanged = { seeking, ms, frac ->
                                        isSeeking = seeking
                                        if (seeking) {
                                            isControllerVisible = true
                                        }
                                        seekPositionMs = ms
                                        seekFraction = frac
                                    },
                                    enabled = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 8.dp)
                                )

                                // Position / Duration text (e.g. 00:03/02:05)
                                Text(
                                    text = "${formatTime(if (isSeeking) seekPositionMs else currentProgressMs)}/${formatTime(durationMs)}",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium
                                )

                            Spacer(modifier = Modifier.width(12.dp))

                            // PIP Button
                            IconButton(
                                onClick = {
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                            activity.enterPictureInPictureMode(
                                                android.app.PictureInPictureParams.Builder().build()
                                            )
                                        }
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Filled.PictureInPicture,
                                    contentDescription = "PiP Mode",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Fullscreen Toggle Button
                            IconButton(
                                onClick = {
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        if (!isFullscreen) {
                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                            activity.window.decorView.systemUiVisibility = (
                                                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                                                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                            )
                                        } else {
                                            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                            activity.window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                        }
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                    contentDescription = "Toggle Fullscreen",
                                    tint = Color.White,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }

                // Transparent click catcher overlay on player view when bottom sheet is active
                if (activeSheet != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable { activeSheet = null }
                    )
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = CinematicRed)
                }
            }
        }

        // Portrait Details panel scroll container at bottom (only in portrait mode)
        if (!isFullscreen && !isInPip) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (activeSheet != null) {
                    // Player Controller Panels (Subtitles, Dubs & Resolutions)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ObsidianDark)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Header row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = when (activeSheet) {
                                        "player_settings" -> "Playback Settings"
                                        "language" -> "Select language"
                                        "season" -> "${viewModel.seasonsList.size} seasons"
                                        "subtitle" -> "Select subtitle"
                                        "quality" -> "Select quality"
                                        "speed" -> "Playback speed"
                                        "episodes" -> "All episodes"
                                        else -> ""
                                    },
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE2E8F0)
                                )
                                IconButton(onClick = { activeSheet = null }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                                }
                            }
                            
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 12.dp),
                                color = Color.Gray.copy(alpha = 0.2f)
                            )
                            
                            if (activeSheet == "player_settings") {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    // 0. Cast to TV
                                    var castButton by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.mediarouter.app.MediaRouteButton?>(null) }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { castButton?.performClick() }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text("Cast to TV", color = Color.White, fontSize = 14.sp)
                                            AndroidView(
                                                factory = { ctx ->
                                                    val themeCtx = android.view.ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat)
                                                    androidx.mediarouter.app.MediaRouteButton(themeCtx).apply {
                                                        com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(themeCtx, this)
                                                        castButton = this
                                                        alpha = 0f
                                                    }
                                                },
                                                modifier = Modifier.size(1.dp)
                                            )
                                        }
                                        Text(
                                            text = if (isCastConnected) "Connected (Playing on TV)" else "Not connected",
                                            color = if (isCastConnected) Color(0xFF10B981) else CinematicRed,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    // 1. Audio Dub Language
                                    val currentDub = viewModel.selectedResource?.name ?: if (viewModel.isAniFam) "Sub" else "Original"
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeSheet = "language" }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text(if (viewModel.isAniFam) "Audio / Track" else "Audio Dub Language", color = Color.White, fontSize = 14.sp)
                                        }
                                        Text(currentDub, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // 2. Subtitles
                                    val currentSub = viewModel.selectedSubtitle?.language ?: "Off"
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeSheet = "subtitle" }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.ClosedCaption, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text("Subtitles", color = Color.White, fontSize = 14.sp)
                                        }
                                        Text(currentSub, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // 3. Playback Speed
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeSheet = "speed" }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text("Playback Speed", color = Color.White, fontSize = 14.sp)
                                        }
                                        Text("${selectedSpeed}x", color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // 4. Video Quality
                                    val currentQuality = viewModel.selectedStream?.quality ?: "Auto"
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeSheet = "quality" }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text("Video Quality", color = Color.White, fontSize = 14.sp)
                                        }
                                        Text(currentQuality, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                    }

                                    // 5. Episodes List
                                    if (subject.isTvShow) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { activeSheet = "episodes" }
                                                .padding(vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.MenuBook, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                                Spacer(modifier = Modifier.width(16.dp))
                                                Text("Episodes List", color = Color.White, fontSize = 14.sp)
                                            }
                                            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                        }
                                    }
                                }
                            } else {
                                val itemsList = when (activeSheet) {
                                    "language" -> subject.resourceDetectors
                                    "season" -> viewModel.seasonsList
                                    "subtitle" -> listOf("None") + playInfo.subTitleList.map { it.language }
                                    "quality" -> playInfo.streamList
                                    "speed" -> listOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
                                    "episodes" -> viewModel.episodesList
                                    else -> emptyList()
                                }
                                
                                val cols = if (activeSheet == "episodes") 4 else 2
                                val chunked = itemsList.chunked(cols)
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    chunked.forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            rowItems.forEach { item ->
                                                val isSelected = when (activeSheet) {
                                                    "language" -> viewModel.selectedResource == item
                                                    "season" -> viewModel.selectedSeason == item
                                                    "subtitle" -> if (item == "None") viewModel.selectedSubtitle == null else viewModel.selectedSubtitle?.language == item
                                                    "quality" -> viewModel.selectedStream?.quality == (item as com.example.data.model.StreamItem).quality
                                                    "speed" -> {
                                                        val currentSpeed = exoPlayer.playbackParameters.speed
                                                        val itemSpeed = when (item as String) {
                                                            "0.5x" -> 0.5f
                                                            "0.75x" -> 0.75f
                                                            "1.0x (Normal)" -> 1.0f
                                                            "1.25x" -> 1.25f
                                                            "1.5x" -> 1.5f
                                                            "2.0x" -> 2.0f
                                                            else -> 1.0f
                                                        }
                                                        Math.abs(currentSpeed - itemSpeed) < 0.05f
                                                    }
                                                    "episodes" -> viewModel.currentEpisode?.episodeId == (item as com.example.data.model.Episode).episodeId
                                                    else -> false
                                                }
                                                
                                                Button(
                                                    onClick = {
                                                        when (activeSheet) {
                                                            "language" -> viewModel.selectLanguageResource(item as com.example.data.model.ResourceDetector)
                                                            "season" -> viewModel.loadEpisodesForSeason(item as Int)
                                                            "subtitle" -> {
                                                                if (item == "None") {
                                                                    viewModel.selectedSubtitle = null
                                                                } else {
                                                                    val found = playInfo.subTitleList.find { it.language == item }
                                                                    if (found != null) viewModel.selectedSubtitle = found
                                                                }
                                                            }
                                                            "quality" -> {
                                                                val prevPos = exoPlayer.currentPosition
                                                                if (prevPos > 0) {
                                                                    viewModel.playbackProgressMs = prevPos
                                                                }
                                                                val streamItem = item as com.example.data.model.StreamItem
                                                                viewModel.selectedStream = streamItem
                                                                viewModel.setDefaultStreamQualityPref(streamItem.quality)
                                                                android.widget.Toast.makeText(context, "Quality: ${streamItem.quality}", android.widget.Toast.LENGTH_SHORT).show()
                                                            }
                                                            "speed" -> {
                                                                val speedVal = when (item as String) {
                                                                    "0.5x" -> 0.5f
                                                                    "0.75x" -> 0.75f
                                                                    "1.0x (Normal)" -> 1.0f
                                                                    "1.25x" -> 1.25f
                                                                    "1.5x" -> 1.5f
                                                                    "2.0x" -> 2.0f
                                                                    else -> 1.0f
                                                                }
                                                                selectedSpeed = speedVal
                                                            }
                                                            "episodes" -> {
                                                                viewModel.selectEpisode(item as com.example.data.model.Episode)
                                                            }
                                                        }
                                                        activeSheet = null
                                                    },
                                                    colors = ButtonDefaults.buttonColors(
                                                        containerColor = if (isSelected) Color(0xFF115E59) else Color(0xFF2D2D30)
                                                    ),
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp)
                                                ) {
                                                    Text(
                                                        text = when (activeSheet) {
                                                            "language" -> (item as com.example.data.model.ResourceDetector).name
                                                            "season" -> "Season ${(item as Int).toString().padStart(2, '0')}"
                                                            "subtitle" -> item as String
                                                            "quality" -> (item as com.example.data.model.StreamItem).quality
                                                            "speed" -> item as String
                                                            "episodes" -> (item as com.example.data.model.Episode).episodeNumber.toString().padStart(2, '0')
                                                            else -> ""
                                                        },
                                                        color = if (isSelected) Color(0xFF2DD4BF) else Color.White,
                                                        fontSize = 13.sp,
                                                        fontWeight = FontWeight.Medium
                                                    )
                                                }
                                            }
                                            if (rowItems.size < cols) {
                                                Spacer(modifier = Modifier.weight((cols - rowItems.size).toFloat()))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (viewModel.isAniFam && viewModel.activeW2GRoom != null) {
                    // Dedicated W2G Watch Party Screen (matches image 2)
                    W2GPartyPlayerBottomView(
                        room = viewModel.activeW2GRoom!!,
                        viewModel = viewModel,
                        context = context,
                        onSyncWithHost = {
                            viewModel.isGuestWatchingOnOwn = false
                            val state = viewModel.activeW2GRoom?.state
                            val targetPosMs = (state?.positionSeconds ?: 0) * 1000L
                            exoPlayer.seekTo(targetPosMs)
                            val hostPlaying = state?.playing == true
                            exoPlayer.playWhenReady = hostPlaying
                            viewModel.isPlaying = hostPlaying
                        }
                    )
                } else {
                    // Normal Portrait Details & Episodes Section
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(ObsidianDark)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Resource, Season and Inline Download Trigger
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 16.dp)
                        ) {
                            // Resource trigger button
                            if (subject.resourceDetectors.isNotEmpty()) {
                                Button(
                                    onClick = { activeSheet = "language" },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D30)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    val currentDub = viewModel.selectedResource?.name ?: if (viewModel.isAniFam) "Sub" else "Original Audio"
                                    Text(
                                        text = "$currentDub ▾",
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                }
                            }

                            // Season trigger button (only for TV Shows, hidden for AniFam)
                            if (subject.isTvShow && viewModel.seasonsList.isNotEmpty() && !viewModel.isAniFam) {
                                Button(
                                    onClick = { activeSheet = "season" },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D30)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Season ${viewModel.selectedSeason.toString().padStart(2, '0')} ▾",
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                }
                            }


                            // Download Icon / Cancel button
                            if (subject.isTvShow && viewModel.episodesList.isNotEmpty()) {
                                if (isDownloadSelectionMode) {
                                    Button(
                                        onClick = {
                                            isDownloadSelectionMode = false
                                            selectedDownloadEpisodes.clear()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B1818)),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Text("Cancel", color = Color(0xFFEF4444), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            isDownloadSelectionMode = true
                                            selectedDownloadEpisodes.clear()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D30)),
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Download,
                                            contentDescription = "Download multiple episodes",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            // W2G Watch Party Button (AniFam only)
                            if (viewModel.isAniFam) {
                                var showCreateW2GDialog by remember { mutableStateOf(false) }
                                var showAuthSheet by remember { mutableStateOf(false) }
                                var createdRoomCode by remember { mutableStateOf<String?>(null) }
                                var isCreatingW2G by remember { mutableStateOf(false) }

                                androidx.compose.runtime.LaunchedEffect(viewModel.isLoggedIn) {
                                    if (viewModel.isLoggedIn) {
                                        showAuthSheet = false
                                    }
                                }

                                Button(
                                    onClick = {
                                        if (!viewModel.isLoggedIn) {
                                            showAuthSheet = true
                                            return@Button
                                        }
                                        if (viewModel.activeW2GRoom != null) {
                                            Toast.makeText(context, "Already in Watch Party (${viewModel.activeW2GRoom?.public_id})", Toast.LENGTH_SHORT).show()
                                        } else {
                                            isCreatingW2G = true
                                            viewModel.createW2GRoomForCurrentEpisode(
                                                onSuccess = { code ->
                                                    isCreatingW2G = false
                                                    createdRoomCode = code
                                                    showCreateW2GDialog = true
                                                },
                                                onError = { err ->
                                                    isCreatingW2G = false
                                                    if (err == "AUTH_REQUIRED") {
                                                        showAuthSheet = true
                                                    } else if (err != "ACTIVE_ROOM_EXISTS") {
                                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (viewModel.activeW2GRoom != null) CinematicRed else Color(0xFF2D2D30)
                                    ),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    if (isCreatingW2G) {
                                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                                    } else {
                                        Icon(
                                            imageVector = Icons.Filled.Groups,
                                            contentDescription = "Watch Together",
                                            tint = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (viewModel.activeW2GRoom != null) "In Party" else "W2G",
                                            color = Color.White,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (showAuthSheet) {
                                    androidx.compose.ui.window.Dialog(
                                        onDismissRequest = { showAuthSheet = false },
                                        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                                    ) {
                                        Surface(
                                            modifier = Modifier.fillMaxSize(),
                                            color = ObsidianDark
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .statusBarsPadding()
                                                    .navigationBarsPadding()
                                                    .padding(16.dp)
                                            ) {
                                                AuthSheet(
                                                    viewModel = viewModel,
                                                    onDismiss = { showAuthSheet = false }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (showCreateW2GDialog && createdRoomCode != null) {
                                    AlertDialog(
                                        onDismissRequest = { showCreateW2GDialog = false },
                                        containerColor = ObsidianCard,
                                        title = {
                                            Text(
                                                text = "Watch Party Created!",
                                                color = TextPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp
                                            )
                                        },
                                        text = {
                                            Column {
                                                Text(
                                                    text = "Share this Room Code with your friends:",
                                                    color = TextSecondary,
                                                    fontSize = 13.sp
                                                )
                                                Spacer(modifier = Modifier.height(12.dp))
                                                Surface(
                                                    color = ObsidianDark,
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = createdRoomCode!!,
                                                            color = CinematicRed,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 18.sp,
                                                            letterSpacing = 2.sp
                                                        )
                                                        IconButton(
                                                            onClick = {
                                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                                                                val clip = android.content.ClipData.newPlainText("Room Code", createdRoomCode)
                                                                clipboard?.setPrimaryClip(clip)
                                                                Toast.makeText(context, "Room Code copied!", Toast.LENGTH_SHORT).show()
                                                            }
                                                        ) {
                                                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy", tint = TextPrimary)
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = { showCreateW2GDialog = false },
                                                colors = ButtonDefaults.buttonColors(containerColor = CinematicRed)
                                            ) {
                                                Text("Start Watching", fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    )
                                }

                                if (viewModel.showActiveRoomDialog && viewModel.activeExistingW2GRoom != null) {
                                    val existing = viewModel.activeExistingW2GRoom!!
                                    AlertDialog(
                                        onDismissRequest = { viewModel.showActiveRoomDialog = false },
                                        containerColor = ObsidianCard,
                                        title = {
                                            Text(
                                                text = "Active Watch Party Found",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp
                                            )
                                        },
                                        text = {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text(
                                                    text = "You already have an active room on the server. AniFam requires ending your previous room before creating a new one.",
                                                    color = Color.White.copy(alpha = 0.8f),
                                                    fontSize = 13.sp
                                                )
                                                Surface(
                                                    color = Color(0xFF1E1E24),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Column(modifier = Modifier.padding(12.dp)) {
                                                        Text(
                                                            text = existing.anime?.title ?: existing.name ?: "My Room",
                                                            color = CinematicRed,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp
                                                        )
                                                        Text(
                                                            text = "Episode ${existing.episode?.episodeNumber ?: 1} • Code: ${existing.public_id}",
                                                            color = Color.White.copy(alpha = 0.6f),
                                                            fontSize = 12.sp
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    viewModel.showActiveRoomDialog = false
                                                    isCreatingW2G = true
                                                    viewModel.endExistingAndCreateNewW2GRoom(
                                                        existingRoomId = existing.public_id,
                                                        onSuccess = { code ->
                                                            isCreatingW2G = false
                                                            createdRoomCode = code
                                                            showCreateW2GDialog = true
                                                        },
                                                        onError = { err ->
                                                            isCreatingW2G = false
                                                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                        }
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = CinematicRed)
                                            ) {
                                                Text("End Old & Create New")
                                            }
                                        },
                                        dismissButton = {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                TextButton(
                                                    onClick = { viewModel.showActiveRoomDialog = false }
                                                ) {
                                                    Text("Cancel", color = Color.White.copy(alpha = 0.6f))
                                                }
                                                Button(
                                                    onClick = {
                                                        viewModel.showActiveRoomDialog = false
                                                        viewModel.rejoinExistingW2GRoom(
                                                            roomId = existing.public_id,
                                                            onSuccess = {
                                                                Toast.makeText(context, "Rejoined party as host!", Toast.LENGTH_SHORT).show()
                                                            },
                                                            onError = { err ->
                                                                Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                                            }
                                                        )
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2D30))
                                                ) {
                                                    Text("Rejoin Room", color = Color.White)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        // Quality Option Selector (Only visible in Download Selection Mode)
                        if (isDownloadSelectionMode && subject.isTvShow) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val activeSeasonInfo = subject.seasonList.find { it.season == viewModel.selectedSeason }
                                val seasonResolutions = activeSeasonInfo?.resolutions?.takeIf { it.isNotEmpty() }?.sorted()?.map { "${it}p" }
                                val downloadQualities = if (viewModel.isAniFam) listOf("360p", "720p", "1080p") else (seasonResolutions ?: listOf("480p", "720p", "1080p"))

                                androidx.compose.runtime.LaunchedEffect(downloadQualities) {
                                    if (downloadQualities.isNotEmpty() && !downloadQualities.any { it.equals(selectedDownloadQuality, ignoreCase = true) }) {
                                        selectedDownloadQuality = downloadQualities.last()
                                    }
                                }

                                downloadQualities.forEach { q ->
                                    val isSelected = selectedDownloadQuality.equals(q, ignoreCase = true)
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) Color(0xFF115E59) else Color(0xFF26262A))
                                            .then(
                                                if (isSelected) Modifier.border(1.dp, Color(0xFF2DD4BF), RoundedCornerShape(8.dp)) else Modifier
                                            )
                                            .clickable {
                                                if (!selectedDownloadQuality.equals(q, ignoreCase = true)) {
                                                    selectedDownloadQuality = q
                                                    selectedDownloadEpisodes.clear()
                                                }
                                            }
                                            .padding(horizontal = 16.dp, vertical = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = q.uppercase(),
                                            color = if (isSelected) Color(0xFF2DD4BF) else Color(0xFF9CA3AF),
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 13.sp
                                        )
                                    }
                                }
                            }
                        }

                        if (subject.isTvShow) {
                            if (isDownloadSelectionMode) {
                                val activeSeasonInfo = subject.seasonList.find { it.season == viewModel.selectedSeason }
                                val currentResDigits = selectedDownloadQuality.filter { it.isDigit() }
                                val maxEpForQuality = activeSeasonInfo?.resolutionEpCounts?.get(currentResDigits) ?: activeSeasonInfo?.resolutionEpCounts?.get(selectedDownloadQuality.lowercase())
                                val availableEpisodesForQuality = androidx.compose.runtime.remember(viewModel.episodesList, selectedDownloadQuality, activeSeasonInfo) {
                                    if (maxEpForQuality != null && maxEpForQuality > 0) {
                                        viewModel.episodesList.filter { it.episodeNumber <= maxEpForQuality }
                                    } else {
                                        viewModel.episodesList
                                    }
                                }

                                // Multi-select download list (Filtered by selected video quality)
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 120.dp) // generous space for sticky bottom bar + nav insets
                                ) {
                                    availableEpisodesForQuality.forEach { ep ->
                                        val isSelected = selectedDownloadEpisodes.any { it.episodeId == ep.episodeId }
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (isSelected) {
                                                        selectedDownloadEpisodes.removeAll { it.episodeId == ep.episodeId }
                                                    } else {
                                                        selectedDownloadEpisodes.add(ep)
                                                    }
                                                },
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isSelected) Color(0xFF271B1B) else Color(0xFF1E1E22)
                                            ),
                                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, CinematicRed) else null
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Left Side: Episode Name only (Clean, no length, no size)
                                                Text(
                                                    text = "Episode ${ep.episodeNumber.toString().padStart(2, '0')}",
                                                    color = Color.White,
                                                    fontSize = 15.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                                )

                                                // Right Side: Circle indicator (Turns Red when clicked)
                                                Box(
                                                    modifier = Modifier
                                                        .size(24.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isSelected) CinematicRed else Color.Transparent)
                                                        .then(
                                                            if (!isSelected) {
                                                                Modifier.border(2.dp, Color(0xFF6B7280), CircleShape)
                                                            } else {
                                                                Modifier
                                                            }
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Filled.Check,
                                                            contentDescription = "Selected",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(16.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                // Normal 4-Column Watch Grid
                                val chunkedEp = viewModel.episodesList.chunked(4)
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                                ) {
                                    chunkedEp.forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            rowItems.forEach { ep ->
                                                val isCurrentEp = viewModel.currentEpisode?.episodeId == ep.episodeId
                                                Card(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(48.dp)
                                                        .clickable { viewModel.selectEpisode(ep) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    colors = CardDefaults.cardColors(
                                                        containerColor = if (isCurrentEp) Color(0xFF115E59) else Color(0xFF2E2E30)
                                                    )
                                                ) {
                                                    Box(
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = ep.episodeNumber.toString().padStart(2, '0'),
                                                            color = if (isCurrentEp) Color(0xFF2DD4BF) else Color.White,
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 14.sp
                                                        )
                                                    }
                                                }
                                            }
                                            // Handle odd items padding
                                            if (rowItems.size < 4) {
                                                Spacer(modifier = Modifier.weight((4 - rowItems.size).toFloat()))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
            }

            // Sticky Bottom Action Bar for Download All / Selected (Always visible on screen, no need to scroll to bottom!)
            if (activeSheet == null && viewModel.activeW2GRoom == null && isDownloadSelectionMode && subject.isTvShow) {
                Card(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF232328)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Select All toggle
                        val activeSeasonInfoBottom = subject.seasonList.find { it.season == viewModel.selectedSeason }
                        val currentResDigitsBottom = selectedDownloadQuality.filter { it.isDigit() }
                        val maxEpForQualityBottom = activeSeasonInfoBottom?.resolutionEpCounts?.get(currentResDigitsBottom) ?: activeSeasonInfoBottom?.resolutionEpCounts?.get(selectedDownloadQuality.lowercase())
                        val availableEpisodesBottom = if (maxEpForQualityBottom != null && maxEpForQualityBottom > 0) {
                            viewModel.episodesList.filter { it.episodeNumber <= maxEpForQualityBottom }
                        } else {
                            viewModel.episodesList
                        }

                        val allSelected = selectedDownloadEpisodes.size == availableEpisodesBottom.size && availableEpisodesBottom.isNotEmpty()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (allSelected) {
                                        selectedDownloadEpisodes.clear()
                                    } else {
                                        selectedDownloadEpisodes.clear()
                                        selectedDownloadEpisodes.addAll(availableEpisodesBottom)
                                    }
                                }
                                .padding(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(if (allSelected) CinematicRed else Color.Transparent)
                                    .then(
                                        if (!allSelected) Modifier.border(2.dp, Color(0xFF9CA3AF), CircleShape) else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (allSelected) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = "All Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Select All",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        // Prominent Download Button
                        Button(
                            onClick = {
                                if (selectedDownloadEpisodes.isNotEmpty()) {
                                    val listToDownload = selectedDownloadEpisodes.toList()
                                    viewModel.downloadEpisodesBatch(context, listToDownload, targetQuality = selectedDownloadQuality)
                                    isDownloadSelectionMode = false
                                    selectedDownloadEpisodes.clear()
                                } else {
                                    Toast.makeText(context, "Please select at least 1 episode", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedDownloadEpisodes.isNotEmpty()) CinematicRed else Color(0xFF3F3F46)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Download,
                                contentDescription = "Download",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedDownloadEpisodes.isNotEmpty()) "Download (${selectedDownloadEpisodes.size})" else "Download",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

        } // Close screen-wrapping Column


        // Custom Slide-Up 2-Column Grid Bottom Sheet Overlay (Root Level)
        if (activeSheet != null && isFullscreen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { activeSheet = null }
            ) {
                val isPlayerSheet = isFullscreen && (activeSheet == "subtitle" || activeSheet == "quality" || activeSheet == "speed" || activeSheet == "language" || activeSheet == "episodes" || activeSheet == "season" || activeSheet == "player_settings")
                Card(
                    modifier = Modifier
                        .align(if (isPlayerSheet) Alignment.Center else Alignment.BottomCenter)
                        .padding(if (isPlayerSheet) 24.dp else 0.dp)
                        .fillMaxWidth(if (isPlayerSheet) 0.6f else 1f)
                        .clickable(enabled = false) {}, // prevent click-through
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E20)),
                    shape = if (isPlayerSheet) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // Header row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (activeSheet) {
                                    "player_settings" -> "Playback Settings"
                                    "language" -> "Select language"
                                    "season" -> "${viewModel.seasonsList.size} seasons"
                                    "subtitle" -> "Select subtitle"
                                    "quality" -> "Select quality"
                                    "speed" -> "Playback speed"
                                    "episodes" -> "All episodes"
                                    else -> ""
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E8F0)
                            )
                            IconButton(onClick = { activeSheet = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                            }
                        }
                        
                        if (activeSheet == "player_settings") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 0. Cast to TV
                                var castButton2 by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<androidx.mediarouter.app.MediaRouteButton?>(null) }
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { castButton2?.performClick() }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Tv, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Cast to TV", color = Color.White, fontSize = 14.sp)
                                        AndroidView(
                                            factory = { ctx ->
                                                val themeCtx = android.view.ContextThemeWrapper(ctx, androidx.appcompat.R.style.Theme_AppCompat)
                                                androidx.mediarouter.app.MediaRouteButton(themeCtx).apply {
                                                    com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(themeCtx, this)
                                                    castButton2 = this
                                                    alpha = 0f
                                                }
                                            },
                                            modifier = Modifier.size(1.dp)
                                        )
                                    }
                                    Text(
                                        text = if (isCastConnected) "Connected (Playing on TV)" else "Not connected",
                                        color = if (isCastConnected) Color(0xFF10B981) else CinematicRed,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // 1. Audio Dub Language
                                val currentDub = viewModel.selectedResource?.name ?: if (viewModel.isAniFam) "Sub" else "Original"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeSheet = "language" }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text(if (viewModel.isAniFam) "Audio / Track" else "Audio Dub Language", color = Color.White, fontSize = 14.sp)
                                    }
                                    Text(currentDub, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                // 2. Subtitles
                                val currentSub = viewModel.selectedSubtitle?.language ?: "Off"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeSheet = "subtitle" }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ClosedCaption, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Subtitles", color = Color.White, fontSize = 14.sp)
                                    }
                                    Text(currentSub, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                // 3. Playback Speed
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeSheet = "speed" }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Playback Speed", color = Color.White, fontSize = 14.sp)
                                    }
                                    Text("${selectedSpeed}x", color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                // 4. Quality Resolution
                                val currentQuality = viewModel.selectedStream?.quality ?: "Auto"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeSheet = "quality" }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Settings, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Video Quality", color = Color.White, fontSize = 14.sp)
                                    }
                                    Text(currentQuality, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                // 5. Episodes List (if TV Show)
                                if (subject.isTvShow) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { activeSheet = "episodes" }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Filled.MenuBook, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(16.dp))
                                            Text("Episodes List", color = Color.White, fontSize = 14.sp)
                                        }
                                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        } else {
                            // Scrollable Option Grid list (2-column or 4-column depending on sheet type)
                            val itemsList = when (activeSheet) {
                                        "language" -> subject.resourceDetectors
                                        "season" -> viewModel.seasonsList
                                        "subtitle" -> listOf("None") + playInfo.subTitleList.map { it.language }
                                        "quality" -> playInfo.streamList
                                        "speed" -> listOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
                                        "episodes" -> viewModel.episodesList
                                        else -> emptyList()
                                    }
                            
                            val cols = if (activeSheet == "episodes") 4 else 2
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val chunked = itemsList.chunked(cols)
                            items(chunked.size) { index ->
                                val rowItems = chunked[index]
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    rowItems.forEach { item ->
                                        val isSelected = when (activeSheet) {
                                            "language" -> viewModel.selectedResource == item
                                            "season" -> viewModel.selectedSeason == item
                                            "subtitle" -> if (item == "None") viewModel.selectedSubtitle == null else viewModel.selectedSubtitle?.language == item
                                            "quality" -> viewModel.selectedStream?.quality == (item as com.example.data.model.StreamItem).quality
                                            "speed" -> {
                                                val currentSpeed = exoPlayer.playbackParameters.speed
                                                val itemSpeed = when (item as String) {
                                                    "0.5x" -> 0.5f
                                                    "0.75x" -> 0.75f
                                                    "1.0x (Normal)" -> 1.0f
                                                    "1.25x" -> 1.25f
                                                    "1.5x" -> 1.5f
                                                    "2.0x" -> 2.0f
                                                    else -> 1.0f
                                                }
                                                Math.abs(currentSpeed - itemSpeed) < 0.05f
                                            }
                                            "episodes" -> viewModel.currentEpisode?.episodeId == (item as com.example.data.model.Episode).episodeId
                                            else -> false
                                        }
                                        
                                        Button(
                                            onClick = {
                                                when (activeSheet) {
                                                    "language" -> viewModel.selectLanguageResource(item as com.example.data.model.ResourceDetector)
                                                    "season" -> viewModel.loadEpisodesForSeason(item as Int)
                                                    "subtitle" -> {
                                                        if (item == "None") {
                                                            viewModel.selectedSubtitle = null
                                                        } else {
                                                            val found = playInfo.subTitleList.find { it.language == item }
                                                            if (found != null) viewModel.selectedSubtitle = found
                                                        }
                                                    }
                                                    "quality" -> {
                                                        val prevPos = exoPlayer.currentPosition
                                                        if (prevPos > 0) {
                                                            viewModel.playbackProgressMs = prevPos
                                                        }
                                                        val streamItem = item as com.example.data.model.StreamItem
                                                        viewModel.selectedStream = streamItem
                                                        viewModel.setDefaultStreamQualityPref(streamItem.quality)
                                                        android.widget.Toast.makeText(context, "Quality: ${streamItem.quality}", android.widget.Toast.LENGTH_SHORT).show()
                                                    }
                                                    "speed" -> {
                                                        val speedVal = when (item as String) {
                                                            "0.5x" -> 0.5f
                                                            "0.75x" -> 0.75f
                                                            "1.0x (Normal)" -> 1.0f
                                                            "1.25x" -> 1.25f
                                                            "1.5x" -> 1.5f
                                                            "2.0x" -> 2.0f
                                                            else -> 1.0f
                                                        }
                                                        selectedSpeed = speedVal
                                                    }
                                                    "episodes" -> {
                                                        viewModel.selectEpisode(item as com.example.data.model.Episode)
                                                    }
                                                }
                                                activeSheet = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isSelected) Color(0xFF115E59) else Color(0xFF2D2D30)
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                        ) {
                                            Text(
                                                text = when (activeSheet) {
                                                    "language" -> (item as com.example.data.model.ResourceDetector).name
                                                    "season" -> "Season ${(item as Int).toString().padStart(2, '0')}"
                                                    "subtitle" -> item as String
                                                    "quality" -> (item as com.example.data.model.StreamItem).quality
                                                    "speed" -> item as String
                                                    "episodes" -> (item as com.example.data.model.Episode).episodeNumber.toString().padStart(2, '0')
                                                    else -> ""
                                                },
                                                color = if (isSelected) Color(0xFF2DD4BF) else Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                    // Handle odd elements padding
                                    if (rowItems.size < cols) {
                                        Spacer(modifier = Modifier.weight((cols - rowItems.size).toFloat()))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
}

// Human readable time formatter helper (mm:ss)
fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

data class VideoPlayerOption(
    val name: String,
    val packageName: String,
    val activityName: String?,
    val icon: android.graphics.drawable.Drawable?
)

fun getInstalledVideoPlayers(context: android.content.Context): List<VideoPlayerOption> {
    val pm = context.packageManager
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
        setDataAndType(android.net.Uri.parse("content://dummy/video.mp4"), "video/*")
    }
    val resolveInfos = try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }
    } catch (e: Exception) {
        emptyList()
    }
    
    return resolveInfos
        .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
        .map {
            VideoPlayerOption(
                name = it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName,
                packageName = it.activityInfo.packageName,
                activityName = it.activityInfo.name,
                icon = try { it.loadIcon(pm) } catch (e: Exception) { null }
            )
        }
}

fun openInExternalPlayer(context: android.content.Context, file: java.io.File, option: VideoPlayerOption?) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (option != null && option.activityName != null) {
                setClassName(option.packageName, option.activityName)
            }
        }
        if (option != null) {
            context.startActivity(intent)
        } else {
            val chooser = android.content.Intent.createChooser(intent, "Play with...")
            context.startActivity(chooser)
        }
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Unable to play: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun shareVideoFile(context: android.content.Context, file: java.io.File) {
    try {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = android.content.Intent.createChooser(shareIntent, "Share Video")
        chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    } catch (e: Exception) {
        android.widget.Toast.makeText(context, "Could not share video: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

fun saveVideoToCameraRoll(context: android.content.Context, file: java.io.File) {
    if (!file.exists()) {
        android.widget.Toast.makeText(context, "File does not exist", android.widget.Toast.LENGTH_SHORT).show()
        return
    }

    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
        try {
            val displayName = file.name
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val contentResolver = context.contentResolver
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                }

                val collection = android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = contentResolver.insert(collection, values) ?: throw Exception("Could not create MediaStore entry in DCIM/Camera")

                contentResolver.openOutputStream(itemUri)?.use { outStream ->
                    file.inputStream().use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }

                values.clear()
                values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
                contentResolver.update(itemUri, values, null, null)
            } else {
                val dcimDir = java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DCIM), "Camera")
                if (!dcimDir.exists()) dcimDir.mkdirs()
                val targetFile = java.io.File(dcimDir, displayName)
                file.copyTo(targetFile, overwrite = true)
                android.media.MediaScannerConnection.scanFile(
                    context,
                    arrayOf(targetFile.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
            }

            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Saved!", android.widget.Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(context, "Failed to save: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
fun DownloadItemOptionsMenu(
    file: java.io.File,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp,
    onDelete: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }

    Box {
        IconButton(
            onClick = { menuExpanded = true },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = "Options",
                tint = TextSecondary,
                modifier = Modifier.size(iconSize)
            )
        }

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            shape = RoundedCornerShape(12.dp),
            containerColor = Color(0xFF222226),
            tonalElevation = 0.dp,
            shadowElevation = 8.dp
        ) {
            DropdownMenuItem(
                text = { Text("Save", color = Color.White, fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Save,
                        contentDescription = "Save",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    saveVideoToCameraRoll(context, file)
                }
            )

            DropdownMenuItem(
                text = { Text("Share", color = Color.White, fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    shareVideoFile(context, file)
                }
            )

            DropdownMenuItem(
                text = { Text("Delete", color = Color(0xFFEF4444), fontSize = 14.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFEF4444),
                        modifier = Modifier.size(18.dp)
                    )
                },
                onClick = {
                    menuExpanded = false
                    onDelete()
                }
            )
        }
    }
}

fun toggleDownloadPauseResume(
    context: android.content.Context,
    active: com.example.ui.viewmodel.ActiveDownload,
    isPaused: Boolean
) {
    val manager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager ?: return
    var succeeded = false

    if (isPaused) {
        // --- RESUME ---
        // 1. Try AOSP hidden method: resumeDownload(long... ids)
        try {
            val method = manager.javaClass.getMethod("resumeDownload", LongArray::class.java)
            method.invoke(manager, longArrayOf(active.id))
            succeeded = true
        } catch (_: Throwable) {
            // 2. Try single-primitive method: resumeDownload(long id)
            try {
                val method = manager.javaClass.getMethod("resumeDownload", Long::class.javaPrimitiveType)
                method.invoke(manager, active.id)
                succeeded = true
            } catch (_: Throwable) {}
        }

        // 3. Try direct ContentResolver update (Downloads.Impl CONTROL_RUN = 0)
        if (!succeeded) {
            try {
                val baseUri = android.net.Uri.parse("content://downloads/my_downloads")
                val values = android.content.ContentValues().apply {
                    put("control", 0) // CONTROL_RUN
                    put("status", 190) // STATUS_PENDING
                }
                val updated = context.contentResolver.update(
                    baseUri,
                    values,
                    "_id = ?",
                    arrayOf(active.id.toString())
                )
                if (updated > 0) succeeded = true
            } catch (_: Throwable) {}
        }

        // 4. Try restartDownload(long... ids)
        if (!succeeded) {
            try {
                val method = manager.javaClass.getMethod("restartDownload", LongArray::class.java)
                method.invoke(manager, longArrayOf(active.id))
                succeeded = true
            } catch (_: Throwable) {}
        }

        // 5. Fallback for vendor-locked ROMs (Realme / ColorOS): Re-enqueue download if stalled/paused
        if (!succeeded && active.url.isNotBlank()) {
            try {
                manager.remove(active.id)
                downloadVideo(context, active.url, active.title, showToast = false)
                succeeded = true
            } catch (_: Throwable) {}
        }

        if (succeeded) {
            android.widget.Toast.makeText(context, "Resuming download...", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Could not resume download on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    } else {
        // --- PAUSE ---
        // 1. Try AOSP hidden method: pauseDownload(long... ids)
        try {
            val method = manager.javaClass.getMethod("pauseDownload", LongArray::class.java)
            method.invoke(manager, longArrayOf(active.id))
            succeeded = true
        } catch (_: Throwable) {
            // 2. Try single-primitive method: pauseDownload(long id)
            try {
                val method = manager.javaClass.getMethod("pauseDownload", Long::class.javaPrimitiveType)
                method.invoke(manager, active.id)
                succeeded = true
            } catch (_: Throwable) {}
        }

        // 3. Try direct ContentResolver update (Downloads.Impl CONTROL_PAUSED = 1)
        if (!succeeded) {
            try {
                val baseUri = android.net.Uri.parse("content://downloads/my_downloads")
                val values = android.content.ContentValues().apply {
                    put("control", 1) // CONTROL_PAUSED
                    put("status", 193) // STATUS_PAUSED_BY_APP
                }
                val updated = context.contentResolver.update(
                    baseUri,
                    values,
                    "_id = ?",
                    arrayOf(active.id.toString())
                )
                if (updated > 0) succeeded = true
            } catch (_: Throwable) {}
        }

        if (succeeded) {
            android.widget.Toast.makeText(context, "Download paused", android.widget.Toast.LENGTH_SHORT).show()
        } else {
            android.widget.Toast.makeText(context, "Pause is not supported on this device", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(viewModel: MovieViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var hasPermission by remember { 
        mutableStateOf(
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R || 
            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) 
    }

    val downloadedFiles = viewModel.downloadedFiles
    val activeDownloads = viewModel.activeDownloads
    val isScanLoaded = viewModel.isDownloadScanLoaded
    val historyEntities by viewModel.historyList.collectAsState()
    val favoriteEntities by viewModel.favoritesList.collectAsState()
    var expandedShows by remember { mutableStateOf(setOf<String>()) }
    var playerPickerFile by remember { mutableStateOf<java.io.File?>(null) }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) {
            val activity = context as? android.app.Activity
            if (activity != null && android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) {
                androidx.core.app.ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 101)
            }
        }
        
        // Background polling loop on Dispatchers.IO - keeps UI 100% fluid with zero lag
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val manager = context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager
            while (isActive) {
                val currentActive = mutableListOf<ActiveDownload>()
                if (manager != null) {
                    try {
                        val query = android.app.DownloadManager.Query().setFilterByStatus(
                            android.app.DownloadManager.STATUS_RUNNING or 
                            android.app.DownloadManager.STATUS_PENDING or 
                            android.app.DownloadManager.STATUS_PAUSED
                        )
                        manager.query(query)?.use { cursor ->
                            val idIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
                            val titleIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
                            val soFarIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            val totalIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            val statusIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
                            val reasonIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_REASON)
                            val uriIdx = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_URI)
                            while (cursor.moveToNext()) {
                                val id = if (idIdx >= 0) cursor.getLong(idIdx) else 0L
                                val title = if (titleIdx >= 0) cursor.getString(titleIdx) ?: "Downloading..." else "Downloading..."
                                val bytesDownloaded = if (soFarIdx >= 0) cursor.getLong(soFarIdx) else 0L
                                val bytesTotal = if (totalIdx >= 0) cursor.getLong(totalIdx) else 0L
                                val status = if (statusIdx >= 0) cursor.getInt(statusIdx) else android.app.DownloadManager.STATUS_RUNNING
                                val reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0
                                val uri = if (uriIdx >= 0) cursor.getString(uriIdx) ?: "" else ""
                                val progress = if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt() else 0
                                currentActive.add(ActiveDownload(id, title, progress, bytesDownloaded, bytesTotal, status, reason, uri))
                            }
                        }
                    } catch (e: Exception) { }
                }

                val candidateDirs = listOfNotNull(
                    java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "AnyFilm"),
                    context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { java.io.File(it, "AnyFilm") },
                    java.io.File(context.filesDir, "AnyFilm"),
                    java.io.File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES), "Genga World")
                )
                val scanned = candidateDirs
                    .filter { it.exists() && it.isDirectory }
                    .flatMap { dir ->
                        dir.walkTopDown()
                            .maxDepth(4)
                            .filter { it.isFile && it.extension.equals("mp4", ignoreCase = true) }
                            .toList()
                    }
                    .distinctBy { it.absolutePath }
                    .sortedBy { it.name }

                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    val combined = (viewModel.inAppDownloads + currentActive).distinctBy { it.id }
                    viewModel.activeDownloads = combined
                    if (viewModel.downloadedFiles.size != scanned.size || viewModel.downloadedFiles.map { it.name } != scanned.map { it.name }) {
                        viewModel.downloadedFiles = scanned
                    }
                    viewModel.isDownloadScanLoaded = true
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
    ) {
        if (!isScanLoaded && downloadedFiles.isEmpty() && activeDownloads.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CinematicRed, modifier = Modifier.size(36.dp))
            }
        } else if (downloadedFiles.isEmpty() && activeDownloads.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Downloads",
                    tint = TextSecondary,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = if (hasPermission) "No Downloads Yet" else "Storage Permission Required",
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Downloaded media will appear here.",
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "My Downloads",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                androidx.compose.foundation.lazy.LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (activeDownloads.isNotEmpty()) {
                        item {
                            Text("Downloading...", color = CinematicRed, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
                        }
                        items(activeDownloads) { active ->
                            val anyFilmDir = remember {
                                val base = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
                                val primary = java.io.File(base, "AnyFilm")
                                if (primary.exists()) primary else java.io.File(base, "Genga World")
                            }
                            val safeActiveTitle = remember(active.title) { active.title.replace(Regex("[^a-zA-Z0-9.-]"), "_") }
                            val safeShowPrefix = remember(active.title) {
                                val match = Regex("(.*?)(_S\\d+E\\d+.*|$)").find(safeActiveTitle)
                                match?.groupValues?.get(1)?.trim() ?: safeActiveTitle
                            }
                            val activePosterFile = remember(active.title) {
                                val searchDirs = mutableListOf<java.io.File>()
                                searchDirs.add(anyFilmDir)
                                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { searchDirs.add(java.io.File(it, "AnyFilm")) }
                                searchDirs.add(java.io.File(context.filesDir, "AnyFilm"))

                                val extensions = listOf("jpg", "webp", "png", "jpeg")
                                for (d in searchDirs.distinct()) {
                                    for (ext in extensions) {
                                        val tFile = java.io.File(d, "$safeActiveTitle.$ext")
                                        if (tFile.exists() && tFile.length() > 0) return@remember tFile
                                        val sFile = java.io.File(d, "$safeShowPrefix.$ext")
                                        if (sFile.exists() && sFile.length() > 0) return@remember sFile
                                    }
                                }
                                null
                            }

                            val posterModel: Any? = if (activePosterFile != null && activePosterFile.exists()) {
                                activePosterFile
                            } else if (active.posterUrl.isNotEmpty()) {
                                active.posterUrl
                            } else null

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(com.example.ui.theme.ObsidianCard, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (posterModel != null) {
                                    AsyncImage(
                                        model = coil.request.ImageRequest.Builder(LocalContext.current)
                                            .data(posterModel)
                                            .crossfade(100)
                                            .build(),
                                        contentDescription = "Poster",
                                        modifier = Modifier.size(56.dp, 42.dp).clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp, 42.dp)
                                            .background(ObsidianDark, RoundedCornerShape(6.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.Download, contentDescription = "Downloading", tint = CinematicRed, modifier = Modifier.size(20.dp))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = active.title,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { if (active.progress > 0) active.progress / 100f else 0f },
                                        modifier = Modifier.fillMaxWidth().height(4.dp),
                                        color = CinematicRed,
                                        trackColor = ObsidianDark
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    val statusText = when (active.status) {
                                        android.app.DownloadManager.STATUS_PAUSED -> {
                                            if (active.reason == android.app.DownloadManager.PAUSED_WAITING_FOR_NETWORK ||
                                                active.reason == android.app.DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
                                                "Waiting for network..."
                                            } else {
                                                "Paused"
                                            }
                                        }
                                        android.app.DownloadManager.STATUS_PENDING -> "Pending..."
                                        else -> "Downloading..."
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${active.progress}% (${(active.downloadedBytes / (1024 * 1024))}MB / ${if (active.totalBytes > 0) (active.totalBytes / (1024 * 1024)).toString() + "MB" else "Unknown"}) • $statusText",
                                            color = if (active.status == android.app.DownloadManager.STATUS_PAUSED) Color(0xFFFBBF24) else TextSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(8.dp))

                                // Pause / Resume & Cancel Control Buttons
                                val isPaused = active.status == android.app.DownloadManager.STATUS_PAUSED
                                val manager = remember { context.getSystemService(android.content.Context.DOWNLOAD_SERVICE) as? android.app.DownloadManager }

                                IconButton(
                                    onClick = {
                                        toggleDownloadPauseResume(context, active, isPaused)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                        contentDescription = if (isPaused) "Resume" else "Pause",
                                        tint = if (isPaused) Color(0xFF10B981) else Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        viewModel.cancelInAppDownload(active.id)
                                        manager?.remove(active.id)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Cancel Download",
                                        tint = Color(0xFFEF4444),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (downloadedFiles.isNotEmpty()) {
                        item {
                            Text("Completed", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                        }
                        val groupedFiles = downloadedFiles.groupBy { file ->
                            val name = file.nameWithoutExtension
                            val match = Regex("(.*?)(_S\\d+E\\d+.*|$)").find(name)
                            match?.groupValues?.get(1)?.replace("_", " ")?.trim() ?: name
                        }

                        items(groupedFiles.keys.toList()) { showName ->
                            val filesForShow = groupedFiles[showName] ?: emptyList()
                            val isExpanded = expandedShows.contains(showName)
                            val anyFilmDir = remember {
                                val base = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES)
                                val primary = java.io.File(base, "AnyFilm")
                                if (primary.exists()) primary else java.io.File(base, "Genga World")
                            }
                            val safeShowPrefix = remember(showName) { showName.replace(Regex("[^a-zA-Z0-9.-]"), "_") }

                            val posterFile = remember(showName, filesForShow) {
                                val searchDirs = mutableListOf<java.io.File>()
                                filesForShow.firstOrNull()?.parentFile?.let { searchDirs.add(it) }
                                filesForShow.firstOrNull()?.parentFile?.parentFile?.let { searchDirs.add(it) }
                                searchDirs.add(anyFilmDir)
                                java.io.File(anyFilmDir, safeShowPrefix).let { if (it.exists()) searchDirs.add(it) }
                                context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)?.let { searchDirs.add(java.io.File(it, "AnyFilm")) }
                                searchDirs.add(java.io.File(context.filesDir, "AnyFilm"))

                                val extensions = listOf("jpg", "webp", "png", "jpeg")

                                // 1. Try show-level poster across all candidate folders (including poster.jpg)
                                for (d in searchDirs.distinct()) {
                                    val posterJpg = java.io.File(d, "poster.jpg")
                                    if (posterJpg.exists() && posterJpg.length() > 0) return@remember posterJpg
                                    for (ext in extensions) {
                                        val p = java.io.File(d, "$safeShowPrefix.$ext")
                                        if (p.exists() && p.length() > 0) return@remember p
                                    }
                                }

                                // 2. Try any image file inside the show folder or episode folder
                                filesForShow.firstOrNull()?.parentFile?.parentFile?.let { showFolder ->
                                    val anyShowImage = showFolder.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in extensions && it.length() > 0 }
                                    if (anyShowImage != null) return@remember anyShowImage
                                }
                                filesForShow.firstOrNull()?.parentFile?.let { epFolder ->
                                    val anyEpImage = epFolder.listFiles()?.firstOrNull { it.isFile && it.extension.lowercase() in extensions && it.length() > 0 }
                                    if (anyEpImage != null) return@remember anyEpImage
                                }

                                // 3. Try episode-level posters for each episode in filesForShow
                                for (f in filesForShow) {
                                    val parent = f.parentFile
                                    for (ext in extensions) {
                                        if (parent != null) {
                                            val p = java.io.File(parent, "${f.nameWithoutExtension}.$ext")
                                            if (p.exists() && p.length() > 0) return@remember p
                                        }
                                        for (d in searchDirs.distinct()) {
                                            val p = java.io.File(d, "${f.nameWithoutExtension}.$ext")
                                            if (p.exists() && p.length() > 0) return@remember p
                                        }
                                    }
                                }
                                null
                            }

                            val matchedPosterUrl = remember(showName, historyEntities, favoriteEntities) {
                                val clean = showName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                                val hist = historyEntities.firstOrNull { h ->
                                    val hClean = h.name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                                    hClean.isNotEmpty() && (hClean == clean || clean.contains(hClean) || hClean.contains(clean))
                                }?.poster?.takeIf { it.isNotBlank() }
                                val fav = favoriteEntities.firstOrNull { f ->
                                    val fClean = f.name.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                                    fClean.isNotEmpty() && (fClean == clean || clean.contains(fClean) || fClean.contains(clean))
                                }?.poster?.takeIf { it.isNotBlank() }
                                if (hist != null || fav != null) return@remember hist ?: fav

                                val stopWords = setOf("hindi", "english", "dub", "dubbed", "sub", "subbed", "season", "episode", "s1", "s2", "s3", "s4", "s5", "the", "in", "of", "a", "an", "and")
                                val showWords = showName.lowercase().replace(Regex("[^a-z0-9]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords }
                                if (showWords.size >= 2) {
                                    val histMatch = historyEntities.firstOrNull { h ->
                                        val hWords = h.name.lowercase().replace(Regex("[^a-z0-9]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords }
                                        val common = showWords.intersect(hWords.toSet())
                                        common.size >= 2 && (common.size.toFloat() / showWords.size.toFloat()) >= 0.5f
                                    }?.poster?.takeIf { it.isNotBlank() }
                                    if (histMatch != null) return@remember histMatch

                                    val favMatch = favoriteEntities.firstOrNull { f ->
                                        val fWords = f.name.lowercase().replace(Regex("[^a-z0-9]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords }
                                        val common = showWords.intersect(fWords.toSet())
                                        common.size >= 2 && (common.size.toFloat() / showWords.size.toFloat()) >= 0.5f
                                    }?.poster?.takeIf { it.isNotBlank() }
                                    if (favMatch != null) return@remember favMatch
                                }
                                null
                            }

                            val videoFramePoster = produceState<android.graphics.Bitmap?>(initialValue = null, key1 = showName, key2 = posterFile) {
                                if (posterFile != null) {
                                    value = null
                                    return@produceState
                                }
                                val firstVid = filesForShow.firstOrNull { it.extension.lowercase() in listOf("mp4", "mkv", "webm", "avi") && it.exists() }
                                    ?: filesForShow.firstOrNull { it.exists() }
                                if (firstVid != null) {
                                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val r = android.media.MediaMetadataRetriever()
                                            r.setDataSource(firstVid.absolutePath)
                                            val durStr = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            val durMs = durStr?.toLongOrNull() ?: 60000L
                                            val timeUs = (durMs * 1000L * 0.15f).toLong().coerceAtLeast(1000000L)
                                            val bmp = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                                                r.getScaledFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 240, 360)
                                            } else {
                                                r.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                            }
                                            r.release()
                                            if (bmp != null) {
                                                val targetShowDir = firstVid.parentFile?.parentFile ?: firstVid.parentFile ?: java.io.File(anyFilmDir, safeShowPrefix)
                                                if (!targetShowDir.exists()) targetShowDir.mkdirs()
                                                try {
                                                    val saveFile = java.io.File(targetShowDir, "poster.jpg")
                                                    saveFile.outputStream().use { out ->
                                                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                                                    }
                                                } catch (_: Throwable) {}
                                                value = bmp
                                            }
                                        } catch (_: Throwable) {}
                                    }
                                }
                            }

                            // Auto-download poster in background if we found a fallback URL so it becomes persistent offline!
                            LaunchedEffect(matchedPosterUrl, posterFile) {
                                if (posterFile == null && !matchedPosterUrl.isNullOrBlank()) {
                                    val targetShowDir = filesForShow.firstOrNull()?.parentFile?.parentFile
                                        ?: java.io.File(anyFilmDir, safeShowPrefix)
                                    if (!targetShowDir.exists()) targetShowDir.mkdirs()
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val referer = if (matchedPosterUrl.contains("anifam")) "https://anifam.net/" else if (matchedPosterUrl.contains("tmdb.org")) "https://www.themoviedb.org/" else "https://moviebox.ph/"
                                            val client = okhttp3.OkHttpClient.Builder()
                                                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                                                .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                                                .followRedirects(true)
                                                .build()
                                            val req = okhttp3.Request.Builder()
                                                .url(matchedPosterUrl)
                                                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                                                .header("Referer", referer)
                                                .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                                                .build()
                                            client.newCall(req).execute().use { resp ->
                                                if (resp.isSuccessful) {
                                                    val bytes = resp.body?.bytes()
                                                    if (bytes != null && bytes.isNotEmpty()) {
                                                        val showPoster = java.io.File(targetShowDir, "poster.jpg")
                                                        if (!showPoster.exists()) showPoster.writeBytes(bytes)
                                                    }
                                                }
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            
                            Column(modifier = Modifier.fillMaxWidth().background(com.example.ui.theme.ObsidianCard, RoundedCornerShape(10.dp))) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            if (filesForShow.size == 1) {
                                                playerPickerFile = filesForShow.first()
                                            } else {
                                                expandedShows = if (isExpanded) expandedShows - showName else expandedShows + showName
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val activePosterModel: Any? = posterFile ?: matchedPosterUrl ?: videoFramePoster.value
                                    if (activePosterModel != null) {
                                        AsyncImage(
                                            model = coil.request.ImageRequest.Builder(LocalContext.current)
                                                .data(activePosterModel)
                                                .crossfade(100)
                                                .build(),
                                            contentDescription = "Poster",
                                            modifier = Modifier.size(56.dp, 84.dp).clip(RoundedCornerShape(6.dp)),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.size(56.dp, 84.dp).background(ObsidianDark, RoundedCornerShape(6.dp)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = CinematicRed, modifier = Modifier.size(24.dp))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = showName,
                                            color = TextPrimary,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (filesForShow.size > 1) "${filesForShow.size} Episodes" else "${(filesForShow.first().length() / (1024 * 1024))} MB",
                                            color = TextSecondary,
                                            fontSize = 13.sp
                                        )
                                    }
                                    if (filesForShow.size > 1) {
                                        var showMenuExpanded by remember { mutableStateOf(false) }
                                        Box {
                                            IconButton(
                                                onClick = { showMenuExpanded = true },
                                                modifier = Modifier.size(32.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.MoreVert,
                                                    contentDescription = "Options",
                                                    tint = TextSecondary,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            DropdownMenu(
                                                expanded = showMenuExpanded,
                                                onDismissRequest = { showMenuExpanded = false },
                                                shape = RoundedCornerShape(12.dp),
                                                containerColor = Color(0xFF222226),
                                                tonalElevation = 0.dp,
                                                shadowElevation = 8.dp
                                            ) {
                                                DropdownMenuItem(
                                                    text = { Text("Save All (${filesForShow.size})", color = Color.White, fontSize = 14.sp) },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.Save,
                                                            contentDescription = "Save All",
                                                            tint = Color.White,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    },
                                                    onClick = {
                                                        showMenuExpanded = false
                                                        filesForShow.forEach { f ->
                                                            saveVideoToCameraRoll(context, f)
                                                        }
                                                    }
                                                )

                                                DropdownMenuItem(
                                                    text = { Text("Delete All (${filesForShow.size})", color = Color(0xFFEF4444), fontSize = 14.sp) },
                                                    leadingIcon = {
                                                        Icon(
                                                            imageVector = Icons.Filled.Delete,
                                                            contentDescription = "Delete All",
                                                            tint = Color(0xFFEF4444),
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    },
                                                    onClick = {
                                                        showMenuExpanded = false
                                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                            filesForShow.forEach { f -> f.delete() }
                                                            val showPoster = java.io.File(anyFilmDir, "$safeShowPrefix.jpg")
                                                            if (showPoster.exists()) showPoster.delete()
                                                            anyFilmDir.listFiles()?.filter { it.extension.equals("jpg", ignoreCase = true) && it.nameWithoutExtension.startsWith(safeShowPrefix) }?.forEach { it.delete() }
                                                            PosterDownloadTracker.markFailed(safeShowPrefix)
                                                            viewModel.scanDownloadedFiles()
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                        Icon(
                                            imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                            contentDescription = "Expand",
                                            tint = TextSecondary,
                                            modifier = Modifier.padding(4.dp)
                                        )
                                    } else if (filesForShow.isNotEmpty()) {
                                        val singleFile = filesForShow.first()
                                        DownloadItemOptionsMenu(
                                            file = singleFile,
                                            iconSize = 20.dp,
                                            onDelete = {
                                                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                    val singlePoster = java.io.File(anyFilmDir, "${singleFile.nameWithoutExtension}.jpg")
                                                    if (singlePoster.exists()) singlePoster.delete()
                                                    val showPoster = java.io.File(anyFilmDir, "$safeShowPrefix.jpg")
                                                    if (showPoster.exists()) showPoster.delete()
                                                    anyFilmDir.listFiles()?.filter { it.extension.equals("jpg", ignoreCase = true) && it.nameWithoutExtension.startsWith(safeShowPrefix) }?.forEach { it.delete() }
                                                    PosterDownloadTracker.markFailed(safeShowPrefix)
                                                    singleFile.delete()
                                                    viewModel.scanDownloadedFiles()
                                                }
                                            }
                                        )
                                    }
                                }
                                
                                if (isExpanded && filesForShow.size > 1) {
                                    Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                                        filesForShow.forEach { file ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 3.dp)
                                                    .background(ObsidianDark, RoundedCornerShape(6.dp))
                                                    .clickable { playerPickerFile = file }
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = CinematicRed, modifier = Modifier.size(20.dp))
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    val epMatch = Regex("_S(\\d+)E(\\d+)").find(file.nameWithoutExtension)
                                                    val epName = if (epMatch != null) "Season ${epMatch.groupValues[1]} Episode ${epMatch.groupValues[2]}" else file.nameWithoutExtension
                                                    Text(text = epName, color = TextPrimary, fontSize = 13.sp)
                                                    Text(text = "${(file.length() / (1024 * 1024))} MB", color = TextSecondary, fontSize = 11.sp)
                                                }
                                                DownloadItemOptionsMenu(
                                                    file = file,
                                                    iconSize = 18.dp,
                                                    onDelete = {
                                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                                            if (filesForShow.size <= 1) {
                                                                // Last episode remaining: clean up posters
                                                                val epPoster = java.io.File(anyFilmDir, "${file.nameWithoutExtension}.jpg")
                                                                if (epPoster.exists()) epPoster.delete()
                                                                val showPoster = java.io.File(anyFilmDir, "$safeShowPrefix.jpg")
                                                                if (showPoster.exists()) showPoster.delete()
                                                                anyFilmDir.listFiles()?.filter { it.extension.equals("jpg", ignoreCase = true) && it.nameWithoutExtension.startsWith(safeShowPrefix) }?.forEach { it.delete() }
                                                                PosterDownloadTracker.markFailed(safeShowPrefix)
                                                            }
                                                            // When multiple episodes exist, ONLY delete the video file, preserving posters for the remaining episodes
                                                            file.delete()
                                                            viewModel.scanDownloadedFiles()
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (playerPickerFile != null) {
            val fileToPlay = playerPickerFile!!
            val externalPlayers = remember(context) { getInstalledVideoPlayers(context) }
            val appIcon = remember(context) {
                try { context.packageManager.getApplicationIcon(context.packageName) } catch (e: Exception) { null }
            }

            ModalBottomSheet(
                onDismissRequest = { playerPickerFile = null },
                containerColor = com.example.ui.theme.ObsidianCard,
                contentColor = TextPrimary,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(top = 12.dp, bottom = 8.dp)
                            .size(width = 40.dp, height = 4.dp)
                            .background(TextSecondary.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    Text(
                        text = "Play With",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val epMatch = Regex("_S(\\d+)E(\\d+)").find(fileToPlay.nameWithoutExtension)
                    val displayName = if (epMatch != null) {
                        val showPrefix = fileToPlay.nameWithoutExtension.substringBefore("_S").replace("_", " ")
                        "$showPrefix • Season ${epMatch.groupValues[1]} Episode ${epMatch.groupValues[2]}"
                    } else {
                        fileToPlay.nameWithoutExtension.replace("_", " ")
                    }
                    Text(
                        text = displayName,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp, bottom = 16.dp)
                    )

                    // 1. AnyFilm Internal Player
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianDark)
                            .clickable {
                                viewModel.playingDownloadedFile = fileToPlay
                                playerPickerFile = null
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (appIcon != null) {
                            AsyncImage(
                                model = appIcon,
                                contentDescription = "AnyFilm",
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(CinematicRed.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = CinematicRed, modifier = Modifier.size(28.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "AnyFilm Player",
                                    color = TextPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "IN-APP",
                                    color = CinematicRed,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier
                                        .background(CinematicRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            Text(
                                text = "Built-in cinema player with controls",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = CinematicRed)
                    }

                    if (externalPlayers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Other Installed Players",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        externalPlayers.forEach { player ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ObsidianDark)
                                    .clickable {
                                        playerPickerFile = null
                                        openInExternalPlayer(context, fileToPlay, player)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (player.icon != null) {
                                    AsyncImage(
                                        model = player.icon,
                                        contentDescription = player.name,
                                        modifier = Modifier
                                            .size(44.dp)
                                            .clip(RoundedCornerShape(10.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(44.dp)
                                            .background(com.example.ui.theme.ObsidianCard, RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TextSecondary)
                                    }
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = player.name,
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "External player",
                                        color = TextSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    // System Chooser Option
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ObsidianDark)
                            .clickable {
                                playerPickerFile = null
                                openInExternalPlayer(context, fileToPlay, null)
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(com.example.ui.theme.ObsidianCard, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.MoreHoriz, contentDescription = "More", tint = TextPrimary)
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "System Dialog / More Apps...",
                                color = TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Choose from standard Android popup",
                                color = TextSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        
    }
}

fun mapLanguageCodeToName(raw: String): String {
    val clean = raw.trim().lowercase()
    val code = clean.substringBefore("-").substringBefore("_").trim()
    return when (code) {
        "en", "eng", "english" -> "English"
        "bn", "ben", "bangla", "bengali" -> "বাংলা"
        "ar", "ara", "arabic" -> "العَرَبِيةُ"
        "ur", "urd", "urdu" -> "اُردُو"
        "hi", "hin", "hindi" -> "हिन्दी"
        "pa", "pan", "punjabi" -> "ਪੰਜਾਬੀ"
        "ta", "tam", "tamil" -> "தமிழ்"
        "te", "tel", "telugu" -> "తెలుగు"
        "kn", "kan", "kannada" -> "ಕನ್ನಡ"
        "ml", "mal", "malayalam" -> "മലയാളം"
        "gu", "guj", "gujarati" -> "ગુજરાતી"
        "fil", "tl", "tgl", "tagalog", "filipino" -> "Filipino"
        "fr", "fra", "fre", "french" -> "Français"
        "id", "ind", "indonesian" -> "Indonesian"
        "ms", "may", "msa", "malay" -> "Malay"
        "pt", "por", "portuguese" -> "Português"
        "ru", "rus", "russian" -> "Русский"
        "th", "tha", "thai" -> "ภาษาไทย"
        "vi", "vie", "vietnamese" -> "tiếng Việt"
        "zh", "zho", "chi", "chinese" -> "中文"
        "ja", "jpn", "japanese" -> "日本語"
        "ko", "kor", "korean" -> "한국어"
        "es", "spa", "spanish" -> "Español"
        "de", "deu", "ger", "german" -> "Deutsch"
        "it", "ita", "italian" -> "Italiano"
        "tr", "tur", "turkish" -> "Türkçe"
        "el", "ell", "greek" -> "Ελληνικά"
        "he", "heb", "hebrew" -> "עברית"
        "pl", "pol", "polish" -> "Polski"
        "nl", "nld", "dut", "dutch" -> "Nederlands"
        "in" -> "Indonesian"
        else -> raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

fun detectSubtitleLanguage(
    file: java.io.File,
    fallbackIndex: Int,
    playInfoList: List<com.example.data.model.SubtitleItem>? = null
): String {
    try {
        if (!playInfoList.isNullOrEmpty()) {
            val name = file.nameWithoutExtension
            val numMatch = Regex("(?i)sub_?(\\d+)").find(name)
            if (numMatch != null) {
                val idx = numMatch.groupValues[1].toIntOrNull()
                if (idx != null && idx < playInfoList.size) {
                    val lang = playInfoList[idx].language.trim()
                    if (lang.isNotBlank()) return lang
                }
            }
        }

        if (!file.exists() || file.length() == 0L) return "Subtitle ${fallbackIndex + 1}"

        val maxBytes = 24576
        val buffer = ByteArray(maxBytes.coerceAtMost(file.length().toInt()))
        file.inputStream().use { it.read(buffer) }
        val text = String(buffer, Charsets.UTF_8)

        val headerMatch = Regex("(?i)(?:Language|lang):\\s*([a-zA-Z\\-_]+)").find(text)
        if (headerMatch != null) {
            val code = headerMatch.groupValues[1].lowercase().trim()
            val mapped = mapLanguageCodeToName(code)
            if (mapped.isNotBlank() && !mapped.equals(code, ignoreCase = true)) return mapped
        }

        var arabicCount = 0
        var bengaliCount = 0
        var punjabiCount = 0
        var devanagariCount = 0
        var tamilCount = 0
        var teluguCount = 0
        var kannadaCount = 0
        var malayalamCount = 0
        var thaiCount = 0
        var cyrillicCount = 0
        var hangulCount = 0
        var kanaCount = 0
        var cjkCount = 0
        var greekCount = 0
        var hebrewCount = 0

        for (ch in text) {
            when (ch.code) {
                in 0x0600..0x06FF, in 0x0750..0x077F, in 0xFB50..0xFDFF, in 0xFE70..0xFEFF -> arabicCount++
                in 0x0980..0x09FF -> bengaliCount++
                in 0x0A00..0x0A7F -> punjabiCount++
                in 0x0900..0x097F -> devanagariCount++
                in 0x0B80..0x0BFF -> tamilCount++
                in 0x0C00..0x0C7F -> teluguCount++
                in 0x0C80..0x0CFF -> kannadaCount++
                in 0x0D00..0x0D7F -> malayalamCount++
                in 0x0E00..0x0E7F -> thaiCount++
                in 0x0400..0x04FF -> cyrillicCount++
                in 0xAC00..0xD7AF -> hangulCount++
                in 0x3040..0x30FF -> kanaCount++
                in 0x4E00..0x9FFF -> cjkCount++
                in 0x0370..0x03FF -> greekCount++
                in 0x0590..0x05FF -> hebrewCount++
            }
        }

        if (bengaliCount > 10) return "বাংলা"
        if (punjabiCount > 10) return "ਪੰਜਾਬੀ"
        if (devanagariCount > 10) return "हिन्दी"
        if (tamilCount > 10) return "தமிழ்"
        if (teluguCount > 10) return "తెలుగు"
        if (kannadaCount > 10) return "ಕನ್ನಡ"
        if (malayalamCount > 10) return "മലയാളം"
        if (arabicCount > 10) {
            val lower = text.lowercase()
            return if (lower.contains("کو") || lower.contains("کے") || lower.contains("میں") || lower.contains("ہے")) "اُردُو" else "العَرَبِيةُ"
        }
        if (thaiCount > 10) return "ภาษาไทย"
        if (cyrillicCount > 10) return "Русский"
        if (hangulCount > 10) return "한국어"
        if (kanaCount > 10) return "日本語"
        if (cjkCount > 10) return "中文"
        if (greekCount > 10) return "Ελληνικά"
        if (hebrewCount > 10) return "עברית"

        val lower = text.lowercase()
        if (Regex("[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡùúụủũưừứựửữỳýỵỷỹđ]").containsMatchIn(lower)) {
            return "tiếng Việt"
        }
        if (lower.contains("¿") || lower.contains("¡") || (lower.contains(" el ") && lower.contains(" que ") && lower.contains(" por "))) {
            return "Español"
        }
        if (lower.contains("ç") || (lower.contains(" vous ") && lower.contains(" dans ") && lower.contains(" pour "))) {
            return "Français"
        }
        if (lower.contains("ß") || (lower.contains(" nicht ") && lower.contains(" und ") && lower.contains(" ich "))) {
            return "Deutsch"
        }
        if (lower.contains("ã") || lower.contains("õ") || (lower.contains(" você ") && lower.contains(" não "))) {
            return "Português"
        }
        if (lower.contains(" yang ") && (lower.contains(" tidak ") || lower.contains(" dengan ") || lower.contains(" mereka "))) {
            return "Indonesian"
        }
        if (lower.contains(" ang ") && (lower.contains(" mga ") || lower.contains(" hindi ") || lower.contains(" para sa "))) {
            return "Filipino"
        }
        if (lower.contains(" the ") || lower.contains(" you ") || lower.contains(" to ") || lower.contains(" that ") || lower.contains(" is ") || lower.contains(" it ")) {
            return "English"
        }
    } catch (_: Exception) {}
    return "Subtitle ${fallbackIndex + 1}"
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OfflinePlayerScreen(
    fileToPlay: java.io.File,
    viewModel: com.example.ui.viewmodel.MovieViewModel? = null,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val epMatch = Regex("_S(\\d+)E(\\d+)").find(fileToPlay.nameWithoutExtension)
    val displayTitle = if (epMatch != null) {
        val showPrefix = fileToPlay.nameWithoutExtension.substringBefore("_S").replace("_", " ")
        "$showPrefix - S${epMatch.groupValues[1]} E${epMatch.groupValues[2]}"
    } else {
        fileToPlay.nameWithoutExtension.replace("_", " ")
    }

    // Discover local subtitle files in this episode's folder
    val localSubtitles = remember(fileToPlay) {
        val parent = fileToPlay.parentFile
        val found = mutableListOf<Pair<java.io.File, String>>()
        val searchDirs = listOfNotNull(parent, parent?.parentFile).distinct()

        // 1. Try reading subtitles.json from episode or show folder
        for (dir in searchDirs) {
            val jsonFile = java.io.File(dir, "subtitles.json")
            if (jsonFile.exists() && jsonFile.length() > 0) {
                try {
                    val content = jsonFile.readText()
                    val arr = org.json.JSONArray(content)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val lang = obj.optString("language", "")
                        val fileName = obj.optString("fileName", "")
                        if (lang.isNotBlank() && fileName.isNotBlank()) {
                            val candidate = java.io.File(dir, fileName)
                            val altCandidate = if (parent != null && parent != dir) java.io.File(parent, fileName) else null
                            val resolvedFile = when {
                                candidate.exists() && candidate.length() > 0 -> candidate
                                altCandidate != null && altCandidate.exists() && altCandidate.length() > 0 -> altCandidate
                                else -> null
                            }
                            if (resolvedFile != null) {
                                found.add(Pair(resolvedFile, lang))
                            }
                        }
                    }
                    if (found.isNotEmpty()) break
                } catch (_: Exception) {}
            }
        }

        // 2. If no subtitles found via subtitles.json, scan subtitle files on disk
        if (found.isEmpty() && parent != null) {
            val files = parent.listFiles { f ->
                f.isFile && (f.extension.equals("vtt", ignoreCase = true) || f.extension.equals("srt", ignoreCase = true))
            }?.sortedBy { it.name } ?: emptyList()

            for ((index, f) in files.withIndex()) {
                val name = f.nameWithoutExtension
                val isLegacyNumeric = name.matches(Regex("(?i)^sub_\\d+$")) || name.matches(Regex("^\\d+$"))

                val display = if (isLegacyNumeric) {
                    detectSubtitleLanguage(f, index, viewModel?.playInfo?.subTitleList)
                } else {
                    val rawLang = if (name.contains("__")) {
                        name.substringAfterLast("__")
                    } else if (name.contains("_") && !name.startsWith("sub_")) {
                        name.substringAfterLast("_")
                    } else {
                        name
                    }
                    mapLanguageCodeToName(rawLang)
                }
                found.add(Pair(f, display))
            }

            // Persist detected subtitles into subtitles.json so future loads are instant
            if (found.isNotEmpty()) {
                try {
                    val arr = org.json.JSONArray()
                    for ((file, lang) in found) {
                        val item = org.json.JSONObject()
                        item.put("language", lang)
                        item.put("fileName", file.name)
                        arr.put(item)
                    }
                    val jsonFile = java.io.File(parent, "subtitles.json")
                    jsonFile.writeText(arr.toString())
                } catch (_: Throwable) {}
            }
        }

        // Make display names unique if duplicates occur
        val uniqueFound = mutableListOf<Pair<java.io.File, String>>()
        val countMap = mutableMapOf<String, Int>()
        for ((file, lang) in found) {
            val count = countMap.getOrDefault(lang, 0) + 1
            countMap[lang] = count
            val finalName = if (count > 1) "$lang ($count)" else lang
            uniqueFound.add(Pair(file, finalName))
        }
        uniqueFound
    }

    var selectedOfflineSub by remember { mutableStateOf<Pair<java.io.File, String>?>(null) }
    var selectedOfflineSpeed by remember { mutableStateOf(1.0f) }
    var activeOfflineSheet by remember { mutableStateOf<String?>(null) } // "player_settings", "subtitle", "speed"

    var isOfflineControllerVisible by remember { mutableStateOf(true) }
    var isOfflinePlaying by remember { mutableStateOf(true) }
    var currentOfflineProgressMs by remember { mutableStateOf(0L) }
    var offlineDurationMs by remember { mutableStateOf(0L) }
    var isOfflineBuffering by remember { mutableStateOf(false) }
    var isOfflineSeeking by remember { mutableStateOf(false) }
    var offlineSeekPositionMs by remember { mutableStateOf(0L) }
    var offlineSeekFraction by remember { mutableStateOf(0f) }
    var lastOfflineSeekFinishedTime by remember { mutableStateOf(0L) }

    val offlinePosterFile = remember(fileToPlay) {
        val candidateDirs = listOfNotNull(fileToPlay.parentFile, fileToPlay.parentFile?.parentFile)
        for (d in candidateDirs) {
            val p = java.io.File(d, "poster.jpg")
            if (p.exists() && p.length() > 0) return@remember p
        }
        null
    }

    // Double-Tap Gesture Indicator States
    var offlineDoubleTapSide by remember { mutableStateOf<String?>(null) }
    var offlineDoubleTapText by remember { mutableStateOf<String?>(null) }
    var offlineGestureTrigger by remember { mutableStateOf(0L) }
    var isOfflineZoomed by remember { mutableStateOf(false) }
    var offlinePlayerViewRef by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }

    val offlineConfiguration = androidx.compose.ui.platform.LocalConfiguration.current
    val isOfflineFullscreen = offlineConfiguration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val offlineActivity = context as? android.app.Activity
    val isOfflineInPip = offlineActivity?.isInPictureInPictureMode ?: false

    BackHandler {
        if (activeOfflineSheet != null) {
            activeOfflineSheet = null
        } else {
            offlineActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            offlineActivity?.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            onDismiss()
        }
    }

    val exoPlayer = remember(fileToPlay) {
        val mediaItemBuilder = androidx.media3.common.MediaItem.Builder()
            .setUri(android.net.Uri.fromFile(fileToPlay))

        if (localSubtitles.isNotEmpty()) {
            val subConfigs = localSubtitles.map { (subFile, display) ->
                val mimeType = if (subFile.extension.equals("vtt", ignoreCase = true)) {
                    androidx.media3.common.MimeTypes.TEXT_VTT
                } else {
                    androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
                }
                androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(subFile))
                    .setMimeType(mimeType)
                    .setLanguage(display)
                    .setLabel(display)
                    .build()
            }
            mediaItemBuilder.setSubtitleConfigurations(subConfigs)
        }

        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
            setMediaItem(mediaItemBuilder.build())
            prepare()
            playWhenReady = true
        }
    }

    // Default subtitles to disabled until user picks one in settings
    LaunchedEffect(exoPlayer) {
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
            .build()
    }

    DisposableEffect(fileToPlay) {
        offlineActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        offlineActivity?.window?.decorView?.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                isOfflineBuffering = (state == androidx.media3.common.Player.STATE_BUFFERING)
                if (state == androidx.media3.common.Player.STATE_READY) {
                    val dur = exoPlayer.duration
                    if (dur > 0) offlineDurationMs = dur
                }
            }
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                val dur = exoPlayer.duration
                if (dur > 0) offlineDurationMs = dur
            }
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                isOfflinePlaying = playWhenReady
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            offlineActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            offlineActivity?.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // Keep orientation strictly locked to horizontal landscape & keep system bars hidden
    LaunchedEffect(Unit) {
        if (offlineActivity != null) {
            offlineActivity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            offlineActivity.window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    // Progress ticker
    LaunchedEffect(isOfflinePlaying) {
        if (isOfflinePlaying) {
            while (true) {
                if (!isOfflineSeeking && System.currentTimeMillis() - lastOfflineSeekFinishedTime > 1200L) {
                    val pos = exoPlayer.currentPosition
                    val dur = exoPlayer.duration
                    if (dur > 0) {
                        currentOfflineProgressMs = pos
                        offlineDurationMs = dur
                    }
                }
                delay(1000)
            }
        }
    }

    // Auto-hide controller
    LaunchedEffect(isOfflineControllerVisible, isOfflinePlaying, isOfflineSeeking) {
        if (isOfflineControllerVisible && isOfflinePlaying && !isOfflineSeeking) {
            delay(4000)
            isOfflineControllerVisible = false
        }
    }

    // Reset gesture animation
    LaunchedEffect(offlineGestureTrigger) {
        if (offlineGestureTrigger > 0L) {
            delay(750)
            offlineDoubleTapSide = null
            offlineDoubleTapText = null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { ctx ->
                val view = android.view.LayoutInflater.from(ctx).inflate(com.example.R.layout.texture_player_view, null) as androidx.media3.ui.PlayerView
                offlinePlayerViewRef = view
                view.keepScreenOn = true
                view.isHapticFeedbackEnabled = false
                view.isSoundEffectsEnabled = false
                view.useController = false
                view.apply {
                    subtitleView?.setViewType(androidx.media3.ui.SubtitleView.VIEW_TYPE_CANVAS)
                    val cleanStyle = androidx.media3.ui.CaptionStyleCompat(
                        android.graphics.Color.WHITE,
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT,
                        androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                        android.graphics.Color.BLACK,
                        null
                    )
                    subtitleView?.setStyle(cleanStyle)
                    player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Universal Gestures Layer (Single tap toggles UI, Double tap Left = -5s, Center = Pause/Play, Right = +10s)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            isOfflineControllerVisible = !isOfflineControllerVisible
                        },
                        onDoubleTap = { tapOffset: androidx.compose.ui.geometry.Offset ->
                            val width = size.width.toFloat()
                            val x = tapOffset.x
                            val leftBoundary = width * 0.35f
                            val rightBoundary = width * 0.65f

                            when {
                                x < leftBoundary -> {
                                    // Left: rewind 5s
                                    val current = exoPlayer.currentPosition
                                    val target = (current - 5000L).coerceAtLeast(0L)
                                    exoPlayer.seekTo(target)
                                    offlineDoubleTapSide = "left"
                                    offlineDoubleTapText = "-5s"
                                    offlineGestureTrigger = System.currentTimeMillis()
                                }
                                x > rightBoundary -> {
                                    // Right: forward 10s
                                    val current = exoPlayer.currentPosition
                                    val dur = exoPlayer.duration
                                    val target = if (dur > 0) (current + 10000L).coerceAtMost(dur) else current + 10000L
                                    exoPlayer.seekTo(target)
                                    offlineDoubleTapSide = "right"
                                    offlineDoubleTapText = "+10s"
                                    offlineGestureTrigger = System.currentTimeMillis()
                                }
                                else -> {
                                    // Center: toggle play / pause
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                        isOfflinePlaying = false
                                        offlineDoubleTapSide = "center"
                                        offlineDoubleTapText = "PAUSE"
                                    } else {
                                        exoPlayer.play()
                                        isOfflinePlaying = true
                                        offlineDoubleTapSide = "center"
                                        offlineDoubleTapText = "PLAY"
                                    }
                                    offlineGestureTrigger = System.currentTimeMillis()
                                }
                            }
                        }
                    )
                }
        )

        // Double-Tap Animated Feedback Badges
        androidx.compose.animation.AnimatedVisibility(
            visible = offlineDoubleTapSide != null,
            enter = fadeIn(tween(150)) + scaleIn(tween(150)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                when (offlineDoubleTapSide) {
                    "left" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.38f)
                                .align(Alignment.CenterStart)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                                )
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Replay5, contentDescription = "Rewind 5s", tint = Color.White, modifier = Modifier.size(46.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = offlineDoubleTapText ?: "-5s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    "right" -> {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(0.38f)
                                .align(Alignment.CenterEnd)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.22f))
                                )
                            ),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(46.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = offlineDoubleTapText ?: "+10s", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    "center" -> {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(20.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    if (isOfflinePlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                    contentDescription = "Play/Pause",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = offlineDoubleTapText ?: "",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Custom Playback Controller Overlay (matching normal player without settings & download)
        androidx.compose.animation.AnimatedVisibility(
            visible = isOfflineControllerVisible && !isOfflineInPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Top Bar: Back button on the left, Title in center
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .clickable(enabled = false) {}
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = {
                        offlineActivity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        offlineActivity?.window?.decorView?.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                        onDismiss()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Close Player", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = displayTitle,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { activeOfflineSheet = "player_settings" }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Playback Settings", tint = Color.White)
                    }
                }

                // Center Buffering Spinner
                if (isOfflineBuffering) {
                    CircularProgressIndicator(
                        color = CinematicRed,
                        modifier = Modifier.align(Alignment.Center).size(48.dp)
                    )
                }

                // Bottom Controls: Floating Seek Preview + Controls Row
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomStart)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                            )
                        )
                        .navigationBarsPadding()
                        .clickable(enabled = false) {}
                ) {
                    // Floating Seek Preview Popup
                    androidx.compose.animation.AnimatedVisibility(
                        visible = isOfflineSeeking,
                        enter = fadeIn(tween(120)) + scaleIn(tween(120)),
                        exit = fadeOut(tween(120))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Spacer(modifier = Modifier.size(32.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 8.dp)
                            ) {
                                SeekPreviewPopup(
                                    seekMs = offlineSeekPositionMs,
                                    fraction = offlineSeekFraction,
                                    previewSource = fileToPlay
                                )
                            }
                            Spacer(modifier = Modifier.width(120.dp))
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Play/Pause Button
                        IconButton(
                            onClick = {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                    isOfflinePlaying = false
                                } else {
                                    exoPlayer.play()
                                    isOfflinePlaying = true
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                if (isOfflinePlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        // Video Progress Bar (Thin, crisp line matching online player)
                        VideoProgressBar(
                            currentPositionMs = if (isOfflineSeeking) offlineSeekPositionMs else currentOfflineProgressMs,
                            durationMs = offlineDurationMs,
                            onSeek = { offlineSeekPositionMs = it },
                            onSeekFinished = { targetMs ->
                                lastOfflineSeekFinishedTime = System.currentTimeMillis()
                                currentOfflineProgressMs = targetMs
                                exoPlayer.seekTo(targetMs)
                            },
                            onSeekingChanged = { seeking, ms, frac ->
                                isOfflineSeeking = seeking
                                if (seeking) {
                                    isOfflineControllerVisible = true
                                }
                                offlineSeekPositionMs = ms
                                offlineSeekFraction = frac
                            },
                            enabled = true,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )

                        // Position / Duration text
                        Text(
                            text = "${formatTime(if (isOfflineSeeking) offlineSeekPositionMs else currentOfflineProgressMs)}/${formatTime(offlineDurationMs)}",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        // PIP Button
                        IconButton(
                            onClick = {
                                if (offlineActivity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    offlineActivity.enterPictureInPictureMode(
                                        android.app.PictureInPictureParams.Builder().build()
                                    )
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Filled.PictureInPicture,
                                contentDescription = "PiP Mode",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Aspect Ratio Toggle (Fit / Zoom to fill screen) - always strictly locked to horizontal
                        IconButton(
                            onClick = {
                                isOfflineZoomed = !isOfflineZoomed
                                offlinePlayerViewRef?.resizeMode = if (isOfflineZoomed) {
                                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                } else {
                                    androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = if (isOfflineZoomed) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                contentDescription = "Toggle Aspect Ratio",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // Offline Playback Settings Sheet (Subtitles & Playback Speed only)
        if (activeOfflineSheet != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .clickable { activeOfflineSheet = null }
            ) {
                Card(
                    modifier = Modifier
                        .align(if (isOfflineFullscreen) Alignment.Center else Alignment.BottomCenter)
                        .padding(if (isOfflineFullscreen) 24.dp else 0.dp)
                        .fillMaxWidth(if (isOfflineFullscreen) 0.6f else 1f)
                        .clickable(enabled = false) {},
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E20)),
                    shape = if (isOfflineFullscreen) RoundedCornerShape(16.dp) else RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // Header row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = when (activeOfflineSheet) {
                                    "player_settings" -> "Playback Settings"
                                    "subtitle" -> "Select subtitle"
                                    "speed" -> "Playback speed"
                                    else -> ""
                                },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE2E8F0)
                            )
                            IconButton(onClick = { activeOfflineSheet = null }) {
                                Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFF94A3B8))
                            }
                        }

                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 12.dp),
                            color = Color.Gray.copy(alpha = 0.2f)
                        )

                        if (activeOfflineSheet == "player_settings") {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // 1. Subtitles
                                val currentSubText = selectedOfflineSub?.second ?: "Off"
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeOfflineSheet = "subtitle" }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.ClosedCaption, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Subtitles", color = Color.White, fontSize = 14.sp)
                                    }
                                    Text(currentSubText, color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }

                                // 2. Playback Speed
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { activeOfflineSheet = "speed" }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Speed, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(16.dp))
                                        Text("Playback Speed", color = Color.White, fontSize = 14.sp)
                                    }
                                    Text("${selectedOfflineSpeed}x", color = CinematicRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else if (activeOfflineSheet == "subtitle") {
                            val subtitleOptions = listOf<Pair<java.io.File?, String>>(Pair(null, "None")) + localSubtitles.map { Pair(it.first, it.second) }
                            val chunked = subtitleOptions.chunked(2)
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(chunked.size) { index ->
                                    val rowItems = chunked[index]
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowItems.forEach { subItem ->
                                            val isSelected = (subItem.first == selectedOfflineSub?.first)
                                            Button(
                                                onClick = {
                                                    selectedOfflineSub = if (subItem.first == null) null else Pair(subItem.first!!, subItem.second)
                                                    if (subItem.first == null) {
                                                        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                            .buildUpon()
                                                            .clearOverridesOfType(androidx.media3.common.C.TRACK_TYPE_TEXT)
                                                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
                                                            .build()
                                                    } else {
                                                        val paramsBuilder = exoPlayer.trackSelectionParameters
                                                            .buildUpon()
                                                            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                                                            .setPreferredTextLanguage(subItem.second)

                                                        val textTracks = exoPlayer.currentTracks.groups.filter { it.type == androidx.media3.common.C.TRACK_TYPE_TEXT }
                                                        for (group in textTracks) {
                                                            for (i in 0 until group.length) {
                                                                val format = group.getTrackFormat(i)
                                                                if (format.label == subItem.second || format.language == subItem.second) {
                                                                    paramsBuilder.setOverrideForType(androidx.media3.common.TrackSelectionOverride(group.mediaTrackGroup, i))
                                                                    break
                                                                }
                                                            }
                                                        }
                                                        exoPlayer.trackSelectionParameters = paramsBuilder.build()
                                                    }
                                                    activeOfflineSheet = null
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) Color(0xFF115E59) else Color(0xFF2D2D30)
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                            ) {
                                                Text(
                                                    text = subItem.second,
                                                    color = if (isSelected) Color(0xFF2DD4BF) else Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        if (rowItems.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        } else if (activeOfflineSheet == "speed") {
                            val speedOptions = listOf("0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "2.0x")
                            val chunked = speedOptions.chunked(2)
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(chunked.size) { index ->
                                    val rowItems = chunked[index]
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        rowItems.forEach { item ->
                                            val speedVal = when (item) {
                                                "0.5x" -> 0.5f
                                                "0.75x" -> 0.75f
                                                "1.0x (Normal)" -> 1.0f
                                                "1.25x" -> 1.25f
                                                "1.5x" -> 1.5f
                                                "2.0x" -> 2.0f
                                                else -> 1.0f
                                            }
                                            val isSelected = Math.abs(selectedOfflineSpeed - speedVal) < 0.05f
                                            Button(
                                                onClick = {
                                                    selectedOfflineSpeed = speedVal
                                                    exoPlayer.setPlaybackSpeed(speedVal)
                                                    activeOfflineSheet = null
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isSelected) Color(0xFF115E59) else Color(0xFF2D2D30)
                                                ),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(48.dp)
                                            ) {
                                                Text(
                                                    text = item,
                                                    color = if (isSelected) Color(0xFF2DD4BF) else Color.White,
                                                    fontSize = 13.sp,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                        if (rowItems.size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun W2GPartyPlayerBottomView(
    room: com.example.data.model.W2GRoomDetail,
    viewModel: MovieViewModel,
    context: android.content.Context,
    onSyncWithHost: () -> Unit = {}
) {
    var chatMessageInput by remember { mutableStateOf("") }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()

    androidx.compose.runtime.LaunchedEffect(viewModel.w2gMessages.size) {
        if (viewModel.w2gMessages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.w2gMessages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F12))
    ) {
        // 1. Status Bar directly below video player (matches AniFam official W2G UI)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141418))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // [LIVE] badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF22C55E))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "LIVE",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (!viewModel.isHostingW2G && viewModel.isGuestWatchingOnOwn) {
                // "Watching on your own" + [Sync with host] button (matches AniFam official app)
                Text(
                    text = "Watching on your own",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.weight(1f))

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFEF4444),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSyncWithHost() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Sync with host",
                            tint = Color.White,
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Sync with host",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                // [HOST] or [GUEST] badge
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (viewModel.isHostingW2G) Color(0xFFEF4444) else Color(0xFF3B82F6))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = if (viewModel.isHostingW2G) "HOST" else "GUEST",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Playback control description
                Text(
                    text = if (viewModel.isHostingW2G) "You control playback for everyone" else "Playback is synced to the host",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Viewers count
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Group,
                    contentDescription = "Members",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "${room.members ?: 1}",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Host Avatar & Room Code strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Host Avatar with live indicator dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(34.dp)) {
                    val hostAvatar = room.host?.fullAvatarUrl ?: ""
                    if (hostAvatar.isNotEmpty()) {
                        AsyncImage(
                            model = hostAvatar,
                            contentDescription = room.host?.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF27272A), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = (room.host?.name ?: "H").take(1).uppercase(),
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    // Red dot indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFFEF4444), CircleShape)
                            .border(1.5.dp, Color(0xFF0F0F12), CircleShape)
                            .align(Alignment.BottomEnd)
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = room.host?.name ?: "Host",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Room Code with Copy
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF1E1F24))
                    .clickable {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Room Code", room.public_id)
                        clipboard?.setPrimaryClip(clip)
                        Toast.makeText(context, "Code copied: ${room.public_id}", Toast.LENGTH_SHORT).show()
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Code: ${room.public_id}",
                    color = Color(0xFFF87171),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy Code",
                    tint = TextSecondary,
                    modifier = Modifier.size(13.dp)
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.06f), thickness = 0.8.dp)

        // 2. Chat messages area (taking full vertical height weight(1f))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (viewModel.w2gMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = "No messages yet. Say hi!",
                        color = Color.White.copy(alpha = 0.45f),
                        fontSize = 13.sp
                    )
                }
            } else {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(viewModel.w2gMessages) { msg ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Top
                        ) {
                            val avatar = msg.user?.fullAvatarUrl ?: ""
                            if (avatar.isNotEmpty()) {
                                AsyncImage(
                                    model = avatar,
                                    contentDescription = msg.user?.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .background(Color(0xFFEF4444), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (msg.user?.name ?: "U").take(1).uppercase(),
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = msg.user?.name ?: "Member",
                                    color = Color.White.copy(alpha = 0.8f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Surface(
                                    color = Color(0xFF1E1F24),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = msg.body,
                                        color = TextPrimary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Bottom comment bar (pinned right above phone's back / navigation bar)
        Surface(
            color = Color(0xFF0F0F12),
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pill input box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF1E1F24), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.1f), CircleShape)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = chatMessageInput,
                        onValueChange = {
                            if (it.length <= (room.chat?.max_length ?: 200)) {
                                chatMessageInput = it
                            }
                        },
                        textStyle = androidx.compose.ui.text.TextStyle(
                            color = Color.White,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(CinematicRed),
                        modifier = Modifier.fillMaxWidth(),
                        decorationBox = { innerTextField ->
                            if (chatMessageInput.isEmpty()) {
                                Text(
                                    text = if (viewModel.w2gSlowModeRemaining > 0) "Slow mode (${viewModel.w2gSlowModeRemaining}s)..." else "Message",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontSize = 14.sp
                                )
                            }
                            innerTextField()
                        }
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Send Button with red paper plane or slow mode countdown
                val isSendEnabled = chatMessageInput.isNotBlank() && viewModel.w2gSlowModeRemaining == 0
                IconButton(
                    onClick = {
                        val toSend = chatMessageInput.trim()
                        if (toSend.isNotEmpty()) {
                            viewModel.sendW2GMessage(
                                text = toSend,
                                onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
                            )
                            chatMessageInput = ""
                        }
                    },
                    enabled = isSendEnabled,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (viewModel.w2gSlowModeRemaining > 0) {
                        Text(
                            text = "${viewModel.w2gSlowModeRemaining}s",
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Send,
                            contentDescription = "Send",
                            tint = if (chatMessageInput.isNotBlank()) Color(0xFFEF4444) else Color.White.copy(alpha = 0.25f),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
    }
}
