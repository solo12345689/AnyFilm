package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.MovieSubject
import com.example.data.network.OttPlatform
import com.example.data.network.TmdbOttService
import com.example.ui.theme.CinematicRed
import com.example.ui.theme.ObsidianDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.viewmodel.MovieViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

@Composable
fun OttHubScreen(
    platform: OttPlatform,
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }

    var topMovies by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var topShows by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var recentMovies by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var popularShows by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var actionContent by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var comedyContent by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var dramaContent by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }
    var thrillerContent by remember { mutableStateOf<List<MovieSubject>>(emptyList()) }

    var isLoadingHub by remember { mutableStateOf(true) }
    var reloadTrigger by remember { mutableStateOf(0) }

    // Intercept back button to close search or close hub
    BackHandler {
        if (isSearchActive) {
            isSearchActive = false
            searchQuery = ""
            searchResults = emptyList()
        } else {
            onBack()
        }
    }

    // Load rich Hub content for this platform in parallel
    LaunchedEffect(platform.id, reloadTrigger) {
        isLoadingHub = true
        try {
            coroutineScope {
                val dTopMovies = async { TmdbOttService.getTopMovies(platform) }
                val dTopShows = async { TmdbOttService.getTopShows(platform) }
                val dRecent = async { TmdbOttService.getRecentlyAdded(platform) }
                val dPopularShows = async { TmdbOttService.getPopularShows(platform) }
                val dAction = async { TmdbOttService.getGenreContent(platform, 28) }
                val dComedy = async { TmdbOttService.getGenreContent(platform, 35) }
                val dDrama = async { TmdbOttService.getGenreContent(platform, 18) }
                val dThriller = async { TmdbOttService.getGenreContent(platform, 53) }

                topMovies = dTopMovies.await()
                topShows = dTopShows.await()
                recentMovies = dRecent.await()
                popularShows = dPopularShows.await()
                actionContent = dAction.await()
                comedyContent = dComedy.await()
                dramaContent = dDrama.await()
                thrillerContent = dThriller.await()
            }
        } catch (_: Exception) {
        } finally {
            isLoadingHub = false
        }
    }

    // Debounced search when active
    LaunchedEffect(searchQuery, isSearchActive) {
        if (!isSearchActive || searchQuery.isBlank()) {
            searchResults = emptyList()
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(400)
        try {
            val results = TmdbOttService.searchPlatform(platform, searchQuery.trim())
            searchResults = results
        } catch (_: Exception) {
            searchResults = emptyList()
        } finally {
            isSearching = false
        }
    }

    val heroItem = topMovies.firstOrNull() ?: topShows.firstOrNull()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        platform.bgGradientTop,
                        ObsidianDark,
                        Color.Black
                    ),
                    startY = 0f,
                    endY = 1400f
                )
            )
    ) {
        if (isSearchActive) {
            // Dedicated In-Platform Search View
            OttSearchContent(
                platform = platform,
                searchQuery = searchQuery,
                onQueryChange = { searchQuery = it },
                searchResults = searchResults,
                isSearching = isSearching,
                onBack = {
                    isSearchActive = false
                    searchQuery = ""
                    searchResults = emptyList()
                },
                onMovieClick = { movie ->
                    viewModel.selectSubject(movie)
                }
            )
        } else if (isLoadingHub) {
            // Clean Top Bar with centered spinner while loading
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        Image(
                            painter = painterResource(id = platform.iconRes),
                            contentDescription = platform.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(7.dp))
                        )
                    }

                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = platform.brandColor)
                }
            }
        } else if (heroItem == null) {
            // If network failed to load items
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Image(
                        painter = painterResource(id = platform.iconRes),
                        contentDescription = platform.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(26.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Could not load content",
                            color = Color(0xFFCCCCCC),
                            fontSize = 15.sp
                        )
                        Button(
                            onClick = { reloadTrigger++ },
                            colors = ButtonDefaults.buttonColors(containerColor = platform.brandColor),
                            shape = RoundedCornerShape(20.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry", color = Color.White)
                        }
                    }
                }
            }
        } else {
            // Full Hub Content with 8 distinct shelves
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Top Hero section with backdrop and branded Play
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(480.dp)
                    ) {
                        // Hero Backdrop Image
                        val backdropUrl = heroItem.backdrop ?: heroItem.poster
                        AsyncImage(
                            model = backdropUrl,
                            contentDescription = heroItem.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp))
                        )

                        // Gradient overlays for cinematic contrast & title legibility
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0.0f to Color.Black.copy(alpha = 0.65f),
                                            0.35f to Color.Transparent,
                                            0.65f to platform.bgGradientTop.copy(alpha = 0.75f),
                                            1.0f to platform.bgGradientTop
                                        )
                                    )
                                )
                        )

                        // Top Navigation Bar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                IconButton(onClick = onBack) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back",
                                        tint = Color.White
                                    )
                                }
                                Image(
                                    painter = painterResource(id = platform.iconRes),
                                    contentDescription = platform.name,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(7.dp))
                                )
                            }

                            IconButton(onClick = { isSearchActive = true }) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = "Search ${platform.name}",
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }

                        // Hero Movie Information & Branded Play button
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 16.dp, vertical = 18.dp)
                        ) {
                            Text(
                                text = heroItem.name,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (heroItem.isTvShow) "Show" else "Film",
                                    fontSize = 13.sp,
                                    color = Color(0xFFCCCCCC)
                                )
                                Text(
                                    text = "·",
                                    fontSize = 13.sp,
                                    color = Color(0xFF888888)
                                )
                                Text(
                                    text = heroItem.year ?: "2026",
                                    fontSize = 13.sp,
                                    color = Color(0xFFCCCCCC)
                                )
                                if (!heroItem.rating.isNullOrBlank()) {
                                    Text(
                                        text = "·",
                                        fontSize = 13.sp,
                                        color = Color(0xFF888888)
                                    )
                                    Text(
                                        text = heroItem.rating,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = platform.brandColor
                                    )
                                }
                            }

                            if (heroItem.description.isNotBlank()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = heroItem.description,
                                    fontSize = 12.sp,
                                    color = Color(0xFFB0B0B0),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    lineHeight = 16.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Dynamic themed Play Button
                            val isBrightAccent = platform.id == "appletv" || platform.id == "prime"
                            val playTextColor = if (isBrightAccent) Color.Black else Color.White

                            Button(
                                onClick = {
                                    viewModel.selectSubject(heroItem)
                                    if (!heroItem.isTvShow) {
                                        viewModel.selectMovieDirect()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = platform.brandColor
                                ),
                                shape = RoundedCornerShape(24.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = playTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Play",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = playTextColor
                                )
                            }
                        }
                    }
                }

                // Shelf 1: Top 10 Movies / Top 10 in India (With visible big hollow numbers)
                if (topMovies.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = if (platform.id == "prime") "Top 10 in India" else "Top 10 Movies",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        TopTenRankShelf(
                            items = topMovies,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 2: Top 10 Shows (With visible big hollow numbers)
                if (topShows.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                        Text(
                            text = "Top 10 Shows",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        TopTenRankShelf(
                            items = topShows,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 3: Recently Added Movies
                if (recentMovies.isNotEmpty()) {
                    item {
                        OttStandardShelf(
                            title = "Recently Added Movies",
                            items = recentMovies,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 4: Popular Series & Shows
                if (popularShows.isNotEmpty()) {
                    item {
                        OttStandardShelf(
                            title = "Popular Series & Shows",
                            items = popularShows,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 5: Action & Adventure
                if (actionContent.isNotEmpty()) {
                    item {
                        OttStandardShelf(
                            title = "Action & Adventure",
                            items = actionContent,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 6: Comedy & Entertainment
                if (comedyContent.isNotEmpty()) {
                    item {
                        OttStandardShelf(
                            title = "Comedy & Entertainment",
                            items = comedyContent,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 7: Drama & Romance
                if (dramaContent.isNotEmpty()) {
                    item {
                        OttStandardShelf(
                            title = "Drama & Romance",
                            items = dramaContent,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }

                // Shelf 8: Sci-Fi & Thrillers
                if (thrillerContent.isNotEmpty()) {
                    item {
                        OttStandardShelf(
                            title = "Sci-Fi & Thrillers",
                            items = thrillerContent,
                            onMovieClick = { movie -> viewModel.selectSubject(movie) }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Top 10 Shelf with big visible hollow outline rank numbers (1, 2, 3...) matching ClipBox
 */
@Composable
fun TopTenRankShelf(
    items: List<MovieSubject>,
    onMovieClick: (MovieSubject) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        itemsIndexed(items.take(10), key = { idx, item -> "${item.id}_top10_$idx" }) { index, movie ->
            Box(
                modifier = Modifier
                    .width(155.dp)
                    .height(172.dp)
                    .clickable { onMovieClick(movie) }
            ) {
                // Hollow outline rank number (Visible crisp white stroke outline)
                Text(
                    text = "${index + 1}",
                    fontSize = 82.sp,
                    fontWeight = FontWeight.Black,
                    style = TextStyle(
                        color = Color(0x77FFFFFF),
                        drawStyle = Stroke(
                            miter = 10f,
                            width = 4f,
                            join = StrokeJoin.Round
                        )
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = (-2).dp, y = 8.dp)
                )

                // Poster Card aligned to the right so number sits cleanly on the left
                Card(
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .width(115.dp)
                        .height(168.dp)
                        .align(Alignment.CenterEnd),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141419))
                ) {
                    AsyncImage(
                        model = movie.poster,
                        contentDescription = movie.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Standard continuous horizontal shelf for movies and shows
 */
@Composable
fun OttStandardShelf(
    title: String,
    items: List<MovieSubject>,
    onMovieClick: (MovieSubject) -> Unit
) {
    Column {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = title,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(items, key = { idx, item -> "${item.id}_shelf_${title}_$idx" }) { _, movie ->
                Card(
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .width(112.dp)
                        .height(165.dp)
                        .clickable { onMovieClick(movie) },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF141419))
                ) {
                    AsyncImage(
                        model = movie.poster,
                        contentDescription = movie.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * Dedicated In-Platform Search View (Without description subtitle)
 */
@Composable
fun OttSearchContent(
    platform: OttPlatform,
    searchQuery: String,
    onQueryChange: (String) -> Unit,
    searchResults: List<MovieSubject>,
    isSearching: Boolean,
    onBack: () -> Unit,
    onMovieClick: (MovieSubject) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        // Top row with Back and Logo
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Image(
                painter = painterResource(id = platform.iconRes),
                contentDescription = platform.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(7.dp))
            )
        }

        // Search Input Box
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF1A1A22))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = Color(0xFF888899),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search ${platform.name}",
                            color = Color(0xFF707080),
                            fontSize = 15.sp
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp
                        ),
                        cursorBrush = SolidColor(platform.brandColor),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    IconButton(
                        onClick = { onQueryChange("") },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Clear",
                            tint = Color(0xFFAAAAAA),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Results or Loading or Empty
        if (isSearching) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = platform.brandColor)
            }
        } else if (searchResults.isNotEmpty()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                itemsIndexed(searchResults, key = { idx, item -> "${item.id}_search_$idx" }) { _, movie ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.68f)
                            .clickable { onMovieClick(movie) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF141419))
                    ) {
                        AsyncImage(
                            model = movie.poster,
                            contentDescription = movie.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
