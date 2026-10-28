package com.example.ui.screens

import android.content.Context
import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.zIndex
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.data.model.*
import com.example.data.network.ClearKeyHelper
import com.example.ui.theme.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val liveHttpClient = OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .addInterceptor { chain ->
        val original = chain.request()
        val builder = original.newBuilder()
        if (original.header("User-Agent").isNullOrBlank()) {
            builder.header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
        }
        chain.proceed(builder.build())
    }
    .build()

private val moshi = Moshi.Builder()
    .add(KotlinJsonAdapterFactory())
    .build()

private var fallbackKeysCache: List<FallbackChannelKey>? = null
private var cachedCountries: List<CountryItem>? = null

@OptIn(UnstableApi::class)
@Composable
fun LiveTvScreen(viewModel: com.example.ui.viewmodel.MovieViewModel? = null) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("livetv_prefs", Context.MODE_PRIVATE) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel?.isLiveTvPlaying = false
        }
    }

    // Persistent country selection (Default on fresh install is India IPTV)
    var selectedCountryCode by remember {
        mutableStateOf(prefs.getString("selected_country_code", "IN") ?: "IN")
    }
    var selectedCountryName by remember {
        mutableStateOf(prefs.getString("selected_country_name", "India") ?: "India")
    }
    var selectedCountryFlag by remember {
        mutableStateOf(prefs.getString("selected_country_flag", "🇮🇳") ?: "🇮🇳")
    }

    var channels by remember { mutableStateOf<List<UnifiedChannel>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var selectedCategory by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var showCountryDialog by remember { mutableStateOf(false) }

    // Active streaming channel state
    var activePlayingChannel by remember { mutableStateOf<UnifiedChannel?>(null) }

    // Pre-warm ClearKey database in background so first channel tap is instant
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            if (fallbackKeysCache == null) {
                try {
                    val fReq = Request.Builder().url("https://jjtvxweb.pages.dev/jstr4web.json").build()
                    val fResp = liveHttpClient.newCall(fReq).execute()
                    val fBody = fResp.body?.string() ?: ""
                    val listType = Types.newParameterizedType(List::class.java, FallbackChannelKey::class.java)
                    val fAdapter = moshi.adapter<List<FallbackChannelKey>>(listType)
                    fallbackKeysCache = fAdapter.fromJson(fBody)
                } catch (_: Exception) {}
            }
        }
    }

    // Load channels whenever selected country changes
    LaunchedEffect(selectedCountryCode) {
        isLoading = true
        errorMessage = null
        selectedCategory = "All"
        try {
            val fetchedChannels = withContext(Dispatchers.IO) {
                if (selectedCountryCode == "IN_GEO") {
                    // Jio TV catalog (ClearKey DRM)
                    val req = Request.Builder().url("https://livetgtv.lovable.app/api/public/channels").build()
                    val resp = liveHttpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    val adapter = moshi.adapter(LiveChannelListResponse::class.java)
                    val jioList = adapter.fromJson(body)?.channels ?: emptyList()
                    jioList.map {
                        UnifiedChannel(
                            id = it.id,
                            name = it.name,
                            logo = it.logo,
                            category = it.category?.ifBlank { "Indian TV" } ?: "Indian TV",
                            isJioTv = true
                        )
                    }
                } else {
                    // M3U stream catalog from iptv-org
                    val countryParam = selectedCountryCode.lowercase()
                    val url = "https://iptv-org.github.io/iptv/countries/$countryParam.m3u"
                    val req = Request.Builder().url(url).build()
                    val resp = liveHttpClient.newCall(req).execute()
                    if (!resp.isSuccessful) {
                        throw Exception("No playlist available for $selectedCountryName")
                    }
                    val m3uContent = resp.body?.string() ?: ""
                    parseM3uChannels(m3uContent)
                }
            }
            channels = fetchedChannels
        } catch (e: Exception) {
            errorMessage = e.localizedMessage ?: "Failed to load channels"
        } finally {
            isLoading = false
        }
    }

    // Dynamic Categories extracted from the current channel list
    val categories = remember(channels) {
        val list = mutableListOf("All")
        val parsedCategories = channels.map { it.category }
            .filter { it.isNotBlank() && !it.equals("general", ignoreCase = true) }
            .distinct()
            .sorted()
        list.addAll(parsedCategories)
        list
    }

    // Filter channels by Category and Search query
    val filteredChannels = remember(channels, selectedCategory, searchQuery) {
        channels.filter { ch ->
            val matchesCategory = selectedCategory == "All" || ch.category.equals(selectedCategory, ignoreCase = true)
            val matchesSearch = searchQuery.isBlank() || ch.name.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesSearch
        }
    }

    // Group filtered channels by category for "All" view
    val groupedChannels = remember(filteredChannels, selectedCategory) {
        if (selectedCategory == "All") {
            filteredChannels.groupBy { it.category }
        } else {
            emptyMap()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 10.dp)
        ) {
            // Header: Live TV + Channel Count + Country Selector Pill
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(20.dp)
                            .background(CinematicRed, RoundedCornerShape(2.dp))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live TV",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${channels.size} channels",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }

                // Country Selector Pill (Flag + ISO Code)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(ObsidianCard)
                        .clickable { showCountryDialog = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = "Country",
                        tint = TextSecondary,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = selectedCountryFlag, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(5.dp))
                    Text(
                        text = if (selectedCountryCode == "IN_GEO") "IN (GEO)" else selectedCountryCode,
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Search Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = { Text("Search channel...", color = TextSecondary, fontSize = 13.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = TextSecondary, modifier = Modifier.size(20.dp))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CinematicRed,
                    unfocusedBorderColor = ObsidianCard,
                    focusedContainerColor = ObsidianCard,
                    unfocusedContainerColor = ObsidianCard,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                shape = RoundedCornerShape(12.dp)
            )

            // Category Tabs Bar
            if (categories.size > 1) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(categories) { cat ->
                        val isSelected = selectedCategory == cat
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (isSelected) CinematicRed else ObsidianCard)
                                .clickable { selectedCategory = cat }
                                .padding(horizontal = 16.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = cat,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else TextSecondary
                            )
                        }
                    }
                }
            }

            // Main Channel List / Grid
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CinematicRed, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Loading $selectedCountryName channels...", color = TextSecondary, fontSize = 14.sp)
                    }
                }
            } else if (errorMessage != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = CinematicRed, modifier = Modifier.size(46.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Could Not Load Channels", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text(errorMessage ?: "", color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                selectedCountryCode = selectedCountryCode // Re-trigger LaunchedEffect
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Retry")
                        }
                    }
                }
            } else if (filteredChannels.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.TvOff, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(46.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No channels found", color = TextSecondary, fontSize = 15.sp)
                    }
                }
            } else if (selectedCategory == "All" && searchQuery.isBlank()) {
                // Grouped by Category View (Matching Screenshot 1)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    groupedChannels.forEach { (catName, catChannels) ->
                        item(key = catName) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = catName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                                )

                                // 3-column channel cards grid
                                val rows = catChannels.chunked(3)
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    rows.forEach { rowItems ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowItems.forEach { channel ->
                                                Box(modifier = Modifier.weight(1f)) {
                                                    ChannelItemCard(
                                                        channel = channel,
                                                        onClick = {
                                                            viewModel?.isLiveTvPlaying = true
                                                            activePlayingChannel = channel
                                                        }
                                                    )
                                                }
                                            }
                                            // Fill remaining slots in row if less than 3
                                            for (i in 0 until (3 - rowItems.size)) {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Flat Grid when filtered by specific category or search query
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredChannels, key = { it.id + it.name }) { channel ->
                        ChannelItemCard(
                            channel = channel,
                            onClick = {
                                viewModel?.isLiveTvPlaying = true
                                activePlayingChannel = channel
                            }
                        )
                    }
                }
            }
        }

        // Select Country Dialog (Matching Screenshot 2)
        if (showCountryDialog) {
            SelectCountryDialog(
                currentSelectedCode = selectedCountryCode,
                onDismiss = { showCountryDialog = false },
                onSelectCountry = { country ->
                    selectedCountryCode = country.code
                    selectedCountryName = country.name
                    selectedCountryFlag = country.flag ?: "🌐"
                    prefs.edit()
                        .putString("selected_country_code", country.code)
                        .putString("selected_country_name", country.name)
                        .putString("selected_country_flag", country.flag ?: "🌐")
                        .apply()
                    showCountryDialog = false
                }
            )
        }

        // Live Stream Player Dialog (Zero Timing / Scrub Bars)
        AnimatedVisibility(
            visible = activePlayingChannel != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val channel = activePlayingChannel
            if (channel != null) {
                UnifiedLiveTvPlayerDialog(
                    channel = channel,
                    channels = filteredChannels,
                    onSelectChannel = { activePlayingChannel = it },
                    onDismiss = {
                        activePlayingChannel = null
                        viewModel?.isLiveTvPlaying = false
                    }
                )
            }
        }
    }
}

/**
 * Fast M3U Parser for IPTV-org playlists
 */
private fun parseM3uChannels(content: String): List<UnifiedChannel> {
    val channels = mutableListOf<UnifiedChannel>()
    val lines = content.lines()
    var currentName = ""
    var currentLogo: String? = null
    var currentGroup = "General"

    val logoRegex = Regex("""tvg-logo="([^"]*)"""")
    val groupRegex = Regex("""group-title="([^"]*)"""")

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#EXTINF:")) {
            currentLogo = logoRegex.find(trimmed)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            val rawGroup = groupRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
            // Clean multi-group titles (take the first if separated by semicolon)
            currentGroup = rawGroup.split(";").firstOrNull()?.trim()?.ifBlank { "General" } ?: "General"
            val commaIdx = trimmed.lastIndexOf(',')
            currentName = if (commaIdx != -1) trimmed.substring(commaIdx + 1).trim() else "Live Channel"
        } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            if (currentName.isNotEmpty()) {
                channels.add(
                    UnifiedChannel(
                        id = trimmed,
                        name = currentName,
                        logo = currentLogo,
                        category = currentGroup,
                        streamUrl = trimmed,
                        isJioTv = false
                    )
                )
            }
            currentName = ""
            currentLogo = null
            currentGroup = "General"
        }
    }
    return channels
}

@Composable
fun ChannelItemCard(
    channel: UnifiedChannel,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(125.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = ObsidianCard),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Channel Logo Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                if (!channel.logo.isNullOrBlank()) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp),
                        contentScale = ContentScale.Fit,
                        placeholder = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFF1E1E22)),
                        error = androidx.compose.ui.graphics.painter.ColorPainter(Color(0xFF1E1E22))
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Tv,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Channel Name
            Text(
                text = channel.name,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Select Country Dialog (Alphabetical, India and India Geo-Locked in natural position)
 */
@Composable
fun SelectCountryDialog(
    currentSelectedCode: String,
    onDismiss: () -> Unit,
    onSelectCountry: (CountryItem) -> Unit
) {
    var countries by remember { mutableStateOf<List<CountryItem>>(cachedCountries ?: emptyList()) }
    var isLoading by remember { mutableStateOf(countries.isEmpty()) }
    var countrySearch by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (countries.isEmpty()) {
            try {
                val list = withContext(Dispatchers.IO) {
                    val req = Request.Builder().url("https://iptv-org.github.io/api/countries.json").build()
                    val resp = liveHttpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: ""
                    val listType = Types.newParameterizedType(List::class.java, CountryItem::class.java)
                    val adapter = moshi.adapter<List<CountryItem>>(listType)
                    val rawList = adapter.fromJson(body) ?: emptyList()

                    // Build list with exact requested names:
                    // 'India' for in.m3u, and 'India Geo-Locked' for Jio TV
                    val result = mutableListOf<CountryItem>()
                    rawList.forEach { c ->
                        if (c.code.equals("IN", ignoreCase = true)) {
                            result.add(CountryItem(name = "India", code = "IN", flag = "🇮🇳"))
                            result.add(CountryItem(name = "India Geo-Locked", code = "IN_GEO", flag = "🇮🇳"))
                        } else {
                            result.add(c)
                        }
                    }
                    // Sort alphabetically by country name
                    result.sortedBy { it.name }
                }
                cachedCountries = list
                countries = list
            } catch (_: Exception) {}
            isLoading = false
        }
    }

    val filtered = remember(countries, countrySearch) {
        if (countrySearch.isBlank()) countries else {
            countries.filter {
                it.name.contains(countrySearch, ignoreCase = true) || it.code.contains(countrySearch, ignoreCase = true)
            }
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f),
            colors = CardDefaults.cardColors(containerColor = ObsidianCard),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                Text(
                    text = "Select Country",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = countrySearch,
                    onValueChange = { countrySearch = it },
                    placeholder = { Text("Search country...", color = TextSecondary, fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CinematicRed,
                        unfocusedBorderColor = Color.DarkGray,
                        focusedContainerColor = Color.Black.copy(alpha = 0.3f),
                        unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CinematicRed, modifier = Modifier.size(32.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filtered, key = { it.code + it.name }) { country ->
                            val isSelected = currentSelectedCode == country.code
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) CinematicRed.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable { onSelectCountry(country) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = country.flag ?: "🌐",
                                        fontSize = 20.sp
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = country.name,
                                        fontSize = 15.sp,
                                        color = if (isSelected) CinematicRed else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = country.code,
                                    fontSize = 13.sp,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = "Close",
                            color = CinematicRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

data class LiveAudioTrackItem(
    val language: String,
    val displayName: String,
    val isSelected: Boolean
)

/**
 * Advanced Live TV Player:
 * - PiP (Picture in Picture)
 * - Channel Surfing (Next / Previous buttons & Double-Tap gestures)
 * - Touch Lock mode (locks controls against accidental touches)
 * - Stream Reload / Reconnect button
 * - Audio Track Selector (Hindi, English, Regional languages)
 * - Video Stream Resolution badge (1080p FHD, 720p HD, etc.)
 * - Aspect Ratio toggle (Fit / Zoom / Stretch)
 * - Immersive Landscape Fullscreen (hides Android system bars)
 * - In-Player Searchable Quick Channel Drawer
 * - Keep Screen Awake
 * - Zero timing & zero scrub bars
 */
@OptIn(UnstableApi::class)
@Composable
fun UnifiedLiveTvPlayerDialog(
    channel: UnifiedChannel,
    channels: List<UnifiedChannel> = emptyList(),
    onSelectChannel: (UnifiedChannel) -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val activity = remember { context as? android.app.Activity }
    val dialogView = LocalView.current
    val playerCoroutineScope = rememberCoroutineScope()

    var attemptCount by remember { mutableStateOf(1) }
    var streamResolutionKey by remember { mutableStateOf(0L) }

    var isResolving by remember { mutableStateOf(true) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    var areControlsVisible by remember { mutableStateOf(true) }
    var isMuted by remember { mutableStateOf(false) }
    var isPlayingStream by remember { mutableStateOf(true) }
    var showChannelDrawer by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(true) }
    var isLocked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // Stream info & audio tracks
    var videoResolution by remember { mutableStateOf<String?>(null) }
    var availableAudioTracks by remember { mutableStateOf<List<LiveAudioTrackItem>>(emptyList()) }
    var showAudioDialog by remember { mutableStateOf(false) }


    // Channel surfing calculation
    val currentIndex = remember(channel.id, channels) {
        channels.indexOfFirst { it.id == channel.id }
    }
    val prevChannel = remember(currentIndex, channels) {
        if (channels.isNotEmpty() && currentIndex != -1) {
            val pIdx = if (currentIndex > 0) currentIndex - 1 else channels.size - 1
            channels[pIdx]
        } else null
    }
    val nextChannel = remember(currentIndex, channels) {
        if (channels.isNotEmpty() && currentIndex != -1) {
            val nIdx = if (currentIndex < channels.size - 1) currentIndex + 1 else 0
            channels[nIdx]
        } else null
    }

    // Double tap feedback state
    var doubleTapActionText by remember { mutableStateOf<String?>(null) }
    var doubleTapActionSide by remember { mutableStateOf<String?>(null) }
    var gestureAnimTrigger by remember { mutableStateOf(0L) }

    val exoPlayer = remember {
        val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30000,
                /* maxBufferMs = */ 60000,
                /* bufferForPlaybackMs = */ 2500,
                /* bufferForPlaybackAfterRebufferMs = */ 4000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }

    // Keep screen awake flag on dialog window
    DisposableEffect(dialogView) {
        val window = activity?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // System bars immersive mode handling
    fun updateSystemBars(immersive: Boolean) {
        activity?.window?.let { window ->
            WindowCompat.setDecorFitsSystemWindows(window, !immersive)
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            if (immersive) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    // Handle orientation and immersive mode changes
    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            updateSystemBars(true)
        } else {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            updateSystemBars(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            updateSystemBars(false)
            exoPlayer.stop()
            exoPlayer.release()
        }
    }

    // Feedback badge dismiss timer
    LaunchedEffect(gestureAnimTrigger) {
        if (gestureAnimTrigger > 0L) {
            kotlinx.coroutines.delay(850)
            doubleTapActionSide = null
            doubleTapActionText = null
        }
    }

    // Auto-hide controls after 4.5s
    LaunchedEffect(areControlsVisible, isPlayingStream, showChannelDrawer, showAudioDialog, isLocked) {
        if (areControlsVisible && isPlayingStream && !showChannelDrawer && !showAudioDialog && !isLocked) {
            kotlinx.coroutines.delay(4500)
            areControlsVisible = false
        }
    }

    // Single registered player listener to avoid memory leaks & duplicate callbacks
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    isResolving = false
                    isPlayingStream = true
                    attemptCount = 1
                    resolveError = null
                } else if (state == Player.STATE_BUFFERING) {
                    isResolving = true
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (attemptCount < 3) {
                    attemptCount++
                    isResolving = true
                    playerCoroutineScope.launch {
                        // Allow 3.5s before requesting a fresh API token & stream manifest
                        kotlinx.coroutines.delay(3500)
                        streamResolutionKey = System.currentTimeMillis()
                    }
                } else {
                    resolveError = "Unable to connect to live stream (${error.message ?: "Network error"}). Please check connection or try again."
                    isResolving = false
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    videoResolution = when {
                        videoSize.height >= 1080 -> "1080p FHD"
                        videoSize.height >= 720 -> "720p HD"
                        videoSize.height >= 480 -> "480p"
                        else -> "${videoSize.height}p"
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val audioList = mutableListOf<LiveAudioTrackItem>()
                for (group in tracks.groups) {
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        val trackGroup = group.mediaTrackGroup
                        for (i in 0 until trackGroup.length) {
                            val format = trackGroup.getFormat(i)
                            val lang = format.language ?: "und"
                            val label = format.label ?: when (lang) {
                                "hi", "hin" -> "Hindi"
                                "en", "eng" -> "English"
                                "ta", "tam" -> "Tamil"
                                "te", "tel" -> "Telugu"
                                "bn", "ben" -> "Bengali"
                                "mr", "mar" -> "Marathi"
                                "pa", "pan" -> "Punjabi"
                                "gu", "guj" -> "Gujarati"
                                "kn", "kan" -> "Kannada"
                                "ml", "mal" -> "Malayalam"
                                else -> if (lang != "und") lang.uppercase() else "Audio Track ${audioList.size + 1}"
                            }
                            audioList.add(
                                LiveAudioTrackItem(
                                    language = lang,
                                    displayName = label,
                                    isSelected = group.isTrackSelected(i)
                                )
                            )
                        }
                    }
                }
                availableAudioTracks = audioList
            }
        }

        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
        }
    }

    // Reconnect / Reload stream function
    fun reloadCurrentStream() {
        attemptCount = 1
        streamResolutionKey = System.currentTimeMillis()
    }

    // Resolve stream and initiate playback whenever channel changes or retry/reload triggered
    LaunchedEffect(channel.id, streamResolutionKey) {
        isResolving = true
        resolveError = null
        videoResolution = null
        availableAudioTracks = emptyList()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()

        var resolvedData: Pair<String, Map<String, String>>? = null
        var lastException: Exception? = null

        try {
            resolvedData = withContext(Dispatchers.IO) {
                if (channel.isJioTv) {
                    val req = Request.Builder()
                        .url("https://livetgtv.lovable.app/api/public/channels/${channel.id}")
                        .build()
                    val resp = liveHttpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: throw Exception("Empty channel response")
                    val adapter = moshi.adapter(LiveChannelDetailsResponse::class.java)
                    val details = adapter.fromJson(body) ?: throw Exception("Failed to parse channel info")

                    var url = details.sources?.firstOrNull() ?: throw Exception("No stream source available")

                    // Extract token or fetch live cookie if __hdnea__ missing to prevent HTTP 403
                    var token = if (url.contains("__hdnea__")) {
                        url.substringAfter("__hdnea__=").substringBefore("&")
                    } else ""

                    if (token.isBlank()) {
                        try {
                            val cReq = Request.Builder().url("https://allinonereborn2.online/jstrweb2/cookies.json").build()
                            val cResp = liveHttpClient.newCall(cReq).execute()
                            val cBody = cResp.body?.string() ?: ""
                            val listType = Types.newParameterizedType(List::class.java, Map::class.java)
                            val mapAdapter = moshi.adapter<List<Map<String, Any>>>(listType)
                            val cookieList = mapAdapter.fromJson(cBody)
                            val fullCookie = cookieList?.firstNotNullOfOrNull { it["cookie"] as? String }
                            if (!fullCookie.isNullOrBlank()) {
                                token = fullCookie.substringAfter("__hdnea__=").trim()
                                val sep = if (url.contains("?")) "&" else "?"
                                url = "$url${sep}__hdnea__=$token"
                            }
                        } catch (_: Exception) {}
                    }

                    val keysMap = mutableMapOf<String, String>()

                    // 1. Primary API clearKeys map (format: {"keyId": "key"})
                    details.drm?.clearKeys?.forEach { (kid, k) ->
                        if (kid.isNotBlank() && k.isNotBlank()) {
                            keysMap[kid] = k
                        }
                    }

                    // 2. Primary API keyId & key fields
                    if (keysMap.isEmpty()) {
                        val kId = details.drm?.keyId
                        val k = details.drm?.key
                        if (!kId.isNullOrBlank() && !k.isNullOrBlank()) {
                            keysMap[kId] = k
                        }
                    }

                    // 3. Fallback ClearKey database lookup (jstr4web.json)
                    if (keysMap.isEmpty()) {
                        if (fallbackKeysCache == null) {
                            try {
                                val fReq = Request.Builder().url("https://jjtvxweb.pages.dev/jstr4web.json").build()
                                val fResp = liveHttpClient.newCall(fReq).execute()
                                val fBody = fResp.body?.string() ?: ""
                                val listType = Types.newParameterizedType(List::class.java, FallbackChannelKey::class.java)
                                val fAdapter = moshi.adapter<List<FallbackChannelKey>>(listType)
                                fallbackKeysCache = fAdapter.fromJson(fBody)
                            } catch (_: Exception) {}
                        }

                        val match = fallbackKeysCache?.find { it.id == channel.id }
                        if (match != null) {
                            if (!match.keyId.isNullOrBlank() && !match.key.isNullOrBlank()) {
                                keysMap[match.keyId] = match.key
                            }
                            match.keys?.forEach { pair ->
                                if (!pair.keyId.isNullOrBlank() && !pair.key.isNullOrBlank()) {
                                    keysMap[pair.keyId] = pair.key
                                }
                            }
                        }
                    }
                    Pair(url, keysMap)
                } else {
                    Pair(channel.streamUrl, emptyMap())
                }
            }
        } catch (e: Exception) {
            lastException = e
        }

        if (resolvedData == null) {
            if (attemptCount < 3) {
                attemptCount++
                kotlinx.coroutines.delay(3500)
                streamResolutionKey = System.currentTimeMillis()
                return@LaunchedEffect
            } else {
                resolveError = lastException?.localizedMessage ?: "Failed to connect to stream"
                isResolving = false
                return@LaunchedEffect
            }
        }

        val (resolvedUrl, resolvedKeysMap) = resolvedData

        try {
            // Clean HTTP DataSource with standard User-Agent (matching mpv/browser, 25s timeout)
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(25000)
                .setReadTimeoutMs(25000)

            val token = if (resolvedUrl.contains("__hdnea__")) {
                resolvedUrl.substringAfter("__hdnea__=").substringBefore("&")
            } else null

            if (!token.isNullOrBlank()) {
                httpDataSourceFactory.setDefaultRequestProperties(
                    mapOf("Cookie" to "__hdnea__=$token")
                )
            }

            // Wrap in ResolvingDataSource so relative DASH/HLS segments also get ?__hdnea__=... appended
            val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (!token.isNullOrBlank()) {
                ResolvingDataSource.Factory(
                    httpDataSourceFactory,
                    object : ResolvingDataSource.Resolver {
                        override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                            val uriStr = dataSpec.uri.toString()
                            if (!uriStr.contains("__hdnea__")) {
                                val sep = if (uriStr.contains("?")) "&" else "?"
                                val newUri = android.net.Uri.parse("$uriStr${sep}__hdnea__=$token")
                                return dataSpec.buildUpon().setUri(newUri).build()
                            }
                            return dataSpec
                        }
                    }
                )
            } else {
                httpDataSourceFactory
            }

            val loadErrorHandlingPolicy = DefaultLoadErrorHandlingPolicy(8)

            val isDash = resolvedUrl.contains(".mpd")
            val liveConfiguration = MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(5000L) // 5 seconds safe buffer behind live edge to prevent stutter and voice repeating
                .setMinOffsetMs(2000L)
                .setMaxOffsetMs(12000L)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(resolvedUrl)
                .setMimeType(if (isDash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(liveConfiguration)
                .build()

            val mediaSource = if (isDash) {
                val dashFactory = DashMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                if (resolvedKeysMap.isNotEmpty()) {
                    val clearKeyBytes = ClearKeyHelper.createClearKeyJson(resolvedKeysMap)
                    val drmCallback = LocalMediaDrmCallback(clearKeyBytes)
                    val drmSessionManager = DefaultDrmSessionManager.Builder()
                        .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
                        .setMultiSession(true)
                        .build(drmCallback)
                    dashFactory.setDrmSessionManagerProvider { drmSessionManager }
                }
                dashFactory.createMediaSource(mediaItem)
            } else {
                HlsMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                    .createMediaSource(mediaItem)
            }

            // Prioritize Hindi then English audio tracks
            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages("hi", "hin", "en", "eng")
                .build()

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.play()

        } catch (e: Exception) {
            resolveError = e.localizedMessage ?: "Failed to load stream"
            isResolving = false
        }
    }

    BackHandler {
        onDismiss()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .zIndex(100f)
    ) {
            // Player View with keepScreenOn & dynamic aspect ratio
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = false
                        setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                        this.keepScreenOn = true
                        this.resizeMode = resizeMode
                        layoutParams = android.widget.FrameLayout.LayoutParams(
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                            android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                        )
                    }
                },
                update = { view ->
                    view.resizeMode = resizeMode
                    view.keepScreenOn = true
                },
                modifier = Modifier.fillMaxSize()
            )

            // Touch Gestures Layer (Single-tap toggles UI, Double-tap left/right for channel switch, Center for play/pause)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(isLocked, prevChannel, nextChannel, isPlayingStream) {
                        detectTapGestures(
                            onTap = {
                                if (isLocked) {
                                    areControlsVisible = !areControlsVisible
                                } else {
                                    areControlsVisible = !areControlsVisible
                                }
                            },
                            onDoubleTap = { tapOffset ->
                                if (!isLocked) {
                                    val width = size.width.toFloat()
                                    val x = tapOffset.x
                                    val leftBoundary = width * 0.32f
                                    val rightBoundary = width * 0.68f

                                    when {
                                        x < leftBoundary -> {
                                            if (prevChannel != null) {
                                                onSelectChannel(prevChannel)
                                                doubleTapActionSide = "left"
                                                doubleTapActionText = "⏮ ${prevChannel.name}"
                                                gestureAnimTrigger = System.currentTimeMillis()
                                            }
                                        }
                                        x > rightBoundary -> {
                                            if (nextChannel != null) {
                                                onSelectChannel(nextChannel)
                                                doubleTapActionSide = "right"
                                                doubleTapActionText = "⏭ ${nextChannel.name}"
                                                gestureAnimTrigger = System.currentTimeMillis()
                                            }
                                        }
                                        else -> {
                                            if (isPlayingStream) {
                                                exoPlayer.pause()
                                                isPlayingStream = false
                                                doubleTapActionSide = "center"
                                                doubleTapActionText = "PAUSE"
                                            } else {
                                                exoPlayer.play()
                                                isPlayingStream = true
                                                doubleTapActionSide = "center"
                                                doubleTapActionText = "PLAY"
                                            }
                                            gestureAnimTrigger = System.currentTimeMillis()
                                        }
                                    }
                                }
                            }
                        )
                    }
            )

            // Double-Tap Animated Feedback Badges
            AnimatedVisibility(
                visible = doubleTapActionSide != null,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = doubleTapActionText ?: "",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Locked Screen Floating Unlock Button
            if (isLocked) {
                AnimatedVisibility(
                    visible = areControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(20.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color.Black.copy(alpha = 0.75f))
                            .clickable { isLocked = false }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, contentDescription = "Locked", tint = CinematicRed, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Screen Locked (Tap to Unlock)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Normal Animated Live Controls Overlay (When not locked)
            if (!isLocked) {
                AnimatedVisibility(
                    visible = areControlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Top Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Black.copy(alpha = 0.92f), Color.Transparent)
                                    )
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            // Left: Back button + Logo + Channel Name & Category
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                IconButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .size(38.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = "Close", tint = Color.White)
                                }
                                Spacer(modifier = Modifier.width(10.dp))

                                if (!channel.logo.isNullOrBlank()) {
                                    AsyncImage(
                                        model = channel.logo,
                                        contentDescription = channel.name,
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color.White.copy(alpha = 0.1f)),
                                        contentScale = ContentScale.Fit
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }

                                Column {
                                    Text(
                                        text = channel.name,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "LIVE • ${channel.category}",
                                            color = CinematicRed,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (videoResolution != null) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                                            ) {
                                                Text(
                                                    text = videoResolution ?: "",
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Top Action Controls: Audio Tracks, Reload, Aspect Ratio, PiP, Screen Lock, Fullscreen
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Audio Track Picker Button (if multiple tracks exist)
                                if (availableAudioTracks.size > 1) {
                                    IconButton(
                                        onClick = { showAudioDialog = true },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            Icons.Filled.Audiotrack,
                                            contentDescription = "Audio Tracks",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                // Quick Reload / Reconnect Stream Button
                                IconButton(
                                    onClick = { reloadCurrentStream() },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(
                                        Icons.Filled.Refresh,
                                        contentDescription = "Reload",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Aspect Ratio Toggle (FIT / ZOOM / STRETCH)
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .clickable {
                                            resizeMode = when (resizeMode) {
                                                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                                                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
                                                else -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                                            }
                                        }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = when (resizeMode) {
                                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "ZOOM"
                                            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL -> "STRETCH"
                                            else -> "FIT"
                                        },
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Picture in Picture Button
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                    IconButton(
                                        onClick = {
                                            try {
                                                val pipParams = android.app.PictureInPictureParams.Builder()
                                                    .setAspectRatio(android.util.Rational(16, 9))
                                                    .build()
                                                activity?.enterPictureInPictureMode(pipParams)
                                            } catch (_: Exception) {}
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(Icons.Filled.PictureInPictureAlt, contentDescription = "PiP", tint = Color.White, modifier = Modifier.size(18.dp))
                                    }
                                }

                                // Lock Screen Controls Button
                                IconButton(
                                    onClick = {
                                        isLocked = true
                                        showChannelDrawer = false
                                        showAudioDialog = false
                                    },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(Icons.Filled.LockOpen, contentDescription = "Lock Controls", tint = Color.White, modifier = Modifier.size(18.dp))
                                }

                                // Fullscreen / Landscape Rotation Button
                                IconButton(
                                    onClick = {
                                        isFullscreen = !isFullscreen
                                    },
                                    modifier = Modifier
                                        .size(34.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isFullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                                        contentDescription = "Fullscreen",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Center Channel Surfing Controls: [⏮ Prev Channel] [▶ Play/Pause] [⏭ Next Channel]
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(28.dp)
                        ) {
                            // Previous Channel Button
                            IconButton(
                                onClick = {
                                    if (prevChannel != null) {
                                        onSelectChannel(prevChannel)
                                        doubleTapActionSide = "left"
                                        doubleTapActionText = "⏮ ${prevChannel.name}"
                                        gestureAnimTrigger = System.currentTimeMillis()
                                    }
                                },
                                enabled = prevChannel != null,
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipPrevious,
                                    contentDescription = "Previous Channel",
                                    tint = if (prevChannel != null) Color.White else Color.Gray,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Center Play / Pause Action Button
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                                    .clickable {
                                        if (isPlayingStream) {
                                            exoPlayer.pause()
                                            isPlayingStream = false
                                        } else {
                                            exoPlayer.play()
                                            isPlayingStream = true
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlayingStream) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    contentDescription = if (isPlayingStream) "Pause" else "Play",
                                    tint = CinematicRed,
                                    modifier = Modifier.size(38.dp)
                                )
                            }

                            // Next Channel Button
                            IconButton(
                                onClick = {
                                    if (nextChannel != null) {
                                        onSelectChannel(nextChannel)
                                        doubleTapActionSide = "right"
                                        doubleTapActionText = "⏭ ${nextChannel.name}"
                                        gestureAnimTrigger = System.currentTimeMillis()
                                    }
                                },
                                enabled = nextChannel != null,
                                modifier = Modifier
                                    .size(46.dp)
                                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = "Next Channel",
                                    tint = if (nextChannel != null) Color.White else Color.Gray,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }

                        // Bottom Section: Channel Switcher Drawer + Status Bar
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    Brush.verticalGradient(
                                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.94f))
                                    )
                                )
                        ) {
                            // In-Player Quick Channel Switcher Drawer
                            AnimatedVisibility(
                                visible = showChannelDrawer && channels.isNotEmpty(),
                                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(Color.Black.copy(alpha = 0.88f))
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = "Quick Channels (${channels.size})",
                                        color = TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )

                                    // Channels Horizontal Row
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(horizontal = 2.dp)
                                    ) {
                                        items(channels, key = { it.id + it.name }) { ch ->
                                            val isCurrent = ch.id == channel.id
                                            Card(
                                                modifier = Modifier
                                                    .width(115.dp)
                                                    .height(72.dp)
                                                    .clickable {
                                                        onSelectChannel(ch)
                                                        showChannelDrawer = false
                                                    },
                                                colors = CardDefaults.cardColors(
                                                    containerColor = if (isCurrent) CinematicRed.copy(alpha = 0.35f) else ObsidianCard
                                                ),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(6.dp),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.Center
                                                ) {
                                                    if (!ch.logo.isNullOrBlank()) {
                                                        AsyncImage(
                                                            model = ch.logo,
                                                            contentDescription = ch.name,
                                                            modifier = Modifier.size(26.dp),
                                                            contentScale = ContentScale.Fit
                                                        )
                                                    }
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = ch.name,
                                                        fontSize = 10.sp,
                                                        color = if (isCurrent) CinematicRed else TextPrimary,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // Bottom Status Bar
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(7.dp)
                                            .background(CinematicRed, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "LIVE BROADCAST",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Channel Drawer Toggle Button
                                    if (channels.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (showChannelDrawer) CinematicRed else Color.Black.copy(alpha = 0.6f))
                                                .clickable { showChannelDrawer = !showChannelDrawer }
                                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = "CHANNELS (${channels.size})",
                                                color = Color.White,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    // Mute / Unmute Button
                                    IconButton(
                                        onClick = {
                                            isMuted = !isMuted
                                            exoPlayer.volume = if (isMuted) 0f else 1f
                                        },
                                        modifier = Modifier
                                            .size(34.dp)
                                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                    ) {
                                        Icon(
                                            imageVector = if (isMuted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                                            contentDescription = if (isMuted) "Unmute" else "Mute",
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Audio Track Selection Dialog
            if (showAudioDialog && availableAudioTracks.isNotEmpty()) {
                AlertDialog(
                    onDismissRequest = { showAudioDialog = false },
                    title = {
                        Text("Audio Language Tracks", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    },
                    text = {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(availableAudioTracks) { track ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (track.isSelected) CinematicRed.copy(alpha = 0.2f) else ObsidianCard)
                                        .clickable {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
                                                .buildUpon()
                                                .setPreferredAudioLanguage(track.language)
                                                .build()
                                            showAudioDialog = false
                                        }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = track.displayName,
                                        color = if (track.isSelected) CinematicRed else TextPrimary,
                                        fontWeight = if (track.isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 14.sp
                                    )
                                    if (track.isSelected) {
                                        Icon(Icons.Filled.Check, contentDescription = "Selected", tint = CinematicRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showAudioDialog = false }) {
                            Text("Close", color = CinematicRed)
                        }
                    },
                    containerColor = ObsidianCard
                )
            }

            // Buffering Indicator
            if (isResolving && resolveError == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CircularProgressIndicator(color = CinematicRed, modifier = Modifier.size(36.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (attemptCount > 1) "Connecting to Live Stream (Attempt $attemptCount/3)..." else "Connecting to Live Stream...",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Error Message Overlay
            if (resolveError != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = CinematicRed, modifier = Modifier.size(40.dp))
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Stream Unavailable", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(resolveError ?: "", color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onDismiss) {
                                    Text("Back", color = TextSecondary)
                                }
                                if (nextChannel != null) {
                                    OutlinedButton(onClick = { onSelectChannel(nextChannel) }) {
                                        Text("Next Channel", color = TextPrimary)
                                    }
                                }
                                Button(
                                    onClick = { reloadCurrentStream() },
                                    colors = ButtonDefaults.buttonColors(containerColor = CinematicRed)
                                ) {
                                    Text("Retry")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
