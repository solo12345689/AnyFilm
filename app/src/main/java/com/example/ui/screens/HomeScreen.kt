package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WifiOff
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.example.data.network.OttPlatform
import com.example.data.network.TmdbOttService
import com.example.data.model.CategorySection
import com.example.data.model.MovieSubject
import com.example.ui.components.DashboardShimmerScreen
import com.example.ui.components.MovieGridItem
import com.example.ui.theme.*
import com.example.ui.viewmodel.MovieViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen(viewModel: MovieViewModel) {
    val trending by viewModel.trendingMovies.collectAsState()
    val categoryMovies by viewModel.categoryMovies.collectAsState()
    val categorySections by viewModel.categorySections.collectAsState()

    var selectedCatalogSection by remember { mutableStateOf<CategorySection?>(null) }
    var selectedOttPlatform by remember { mutableStateOf<OttPlatform?>(null) }

    val validSections = remember(categorySections) {
        categorySections.filter { sec ->
            sec.items.isNotEmpty() &&
            !sec.type.contains("BANNER", ignoreCase = true) &&
            !sec.title.contains("Banner", ignoreCase = true) &&
            !sec.title.equals("Categories", ignoreCase = true) &&
            !sec.title.contains("Star", ignoreCase = true) &&
            !sec.title.contains("Actor", ignoreCase = true) &&
            !sec.title.contains("Short", ignoreCase = true) &&
            !sec.title.contains("Join Us", ignoreCase = true) &&
            !sec.title.contains("Watch on TV", ignoreCase = true) &&
            !sec.title.contains("Get Free Premium", ignoreCase = true) &&
            !sec.title.contains("Diwali", ignoreCase = true) &&
            !sec.title.contains("Offer", ignoreCase = true) &&
            !sec.type.contains("SINGLE_IMAGE", ignoreCase = true) &&
            !sec.type.contains("FILTER", ignoreCase = true) &&
            !sec.type.contains("ACTOR", ignoreCase = true) &&
            !sec.type.contains("STAR", ignoreCase = true)
        }
    }

    val bannerMovies = remember(categorySections, categoryMovies, validSections) {
        val bannerSec = categorySections.firstOrNull { it.type.contains("BANNER", ignoreCase = true) || it.title.contains("Banner", ignoreCase = true) }
        val filteredBannerItems = bannerSec?.items?.filter { b ->
            b.id.isNotBlank() && b.id != "0" &&
            !b.name.contains("Diwali", ignoreCase = true) &&
            !b.name.contains("Offer", ignoreCase = true) &&
            !b.name.contains("Premium", ignoreCase = true) &&
            !b.name.contains("TV", ignoreCase = true) &&
            !b.name.contains("Join", ignoreCase = true)
        } ?: emptyList()

        if (filteredBannerItems.isNotEmpty()) {
            filteredBannerItems.take(5)
        } else if (categoryMovies.isNotEmpty()) {
            categoryMovies.take(5)
        } else if (validSections.isNotEmpty()) {
            validSections.first().items.take(5)
        } else {
            emptyList()
        }
    }

    if (selectedCatalogSection != null) {
        AllCatalogScreen(
            section = selectedCatalogSection,
            viewModel = viewModel,
            onBack = { selectedCatalogSection = null }
        )
    } else if (selectedOttPlatform != null) {
        OttHubScreen(
            platform = selectedOttPlatform!!,
            viewModel = viewModel,
            onBack = { selectedOttPlatform = null }
        )
    } else if (viewModel.isDashboardLoading) {
        DashboardShimmerScreen()
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(ObsidianDark)
        ) {
            // Hero Slider/Banner Section (Auto-cycling Carousel)
            if (bannerMovies.isNotEmpty()) {
                item {
                    val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { bannerMovies.size })

                    LaunchedEffect(pagerState, bannerMovies) {
                        while (bannerMovies.isNotEmpty()) {
                            kotlinx.coroutines.delay(4000)
                            val nextPage = (pagerState.currentPage + 1) % bannerMovies.size
                            pagerState.animateScrollToPage(nextPage)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(350.dp)
                    ) {
                        androidx.compose.foundation.pager.HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val heroMovie = bannerMovies[page]
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clickable { viewModel.selectSubject(heroMovie) }
                            ) {
                                val optimalHeroUrl = androidx.compose.runtime.remember(heroMovie.backdrop, heroMovie.poster) {
                                    val sourceUrl = heroMovie.backdrop?.ifBlank { null } ?: heroMovie.poster
                                    com.example.util.ImageOptimizer.getOptimalUrl(sourceUrl, 1080)
                                }
                                val heroAmbient = androidx.compose.runtime.remember(heroMovie.averageHue) {
                                    com.example.util.ImageOptimizer.parseAverageHue(heroMovie.averageHue)
                                }
                                val heroPlaceholder = androidx.compose.runtime.remember(heroMovie.blurHash, heroAmbient) {
                                    com.example.util.ImageOptimizer.getBlurHashPainter(heroMovie.blurHash, 30, 20)
                                        ?: androidx.compose.ui.graphics.painter.ColorPainter(heroAmbient)
                                }

                                AsyncImage(
                                    model = coil.request.ImageRequest.Builder(LocalContext.current)
                                        .data(optimalHeroUrl)
                                        .precision(coil.size.Precision.EXACT)
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = heroMovie.name,
                                    placeholder = heroPlaceholder,
                                    error = heroPlaceholder,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.verticalGradient(
                                                colors = listOf(
                                                    Color.Transparent,
                                                    Color.Transparent,
                                                    ObsidianDark.copy(alpha = 0.5f),
                                                    ObsidianDark
                                                )
                                            )
                                        )
                                )
                                Column(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .background(CinematicRed, RoundedCornerShape(4.dp))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = "TRENDING",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = heroMovie.name,
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = heroMovie.genres.joinToString(" • "),
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row {
                                        Button(
                                            onClick = { viewModel.selectSubject(heroMovie) },
                                            colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Details & Play", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 2. OTT Platform Icons row (Visible ONLY in MovieBox provider)
            if (!viewModel.isAniFam) {
                item {
                    OttPlatformRow(
                        platforms = TmdbOttService.allPlatforms,
                        onPlatformClick = { platform ->
                            selectedOttPlatform = platform
                        }
                    )
                }
            }

            // Horizontal Category filter chips (Immediately beneath OTT Row)
            item {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    val categories by viewModel.activeCategories.collectAsState()

                    LaunchedEffect(categories) {
                        if (categories.isNotEmpty() && categories.none { it.first == viewModel.selectedCategoryTab }) {
                            viewModel.loadCategoryList(categories.first().first)
                        }
                    }

                    // Hide category tab row when only 1 category exists
                    if (categories.size > 1) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = if (categories.size <= 4) Arrangement.SpaceBetween else Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            items(categories) { cat ->
                                FilterChip(
                                    selected = viewModel.selectedCategoryTab == cat.first,
                                    onClick = { viewModel.loadCategoryList(cat.first) },
                                    label = { Text(cat.second) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = CinematicRed,
                                        selectedLabelColor = Color.White,
                                        containerColor = ObsidianCard,
                                        labelColor = TextSecondary
                                    ),
                                    border = null
                                )
                            }
                        }
                    }
                }
            }

            // Category Content & Thematic Sections
            if (validSections.size > 1) {
                // Multi-section feed (e.g. Home with Trending, Latest, Popular, etc.): horizontal shelves
                validSections.forEachIndexed { index, section ->
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (index == 0) 4.dp else 20.dp)
                        ) {
                            val showSectionTitle = index > 0
                            if (showSectionTitle) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = section.title,
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    // "ALL" button shown on sections after the first section (index > 0)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { selectedCatalogSection = section }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = "ALL",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = TextSecondary
                                        )
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Icon(
                                            imageVector = Icons.Filled.ChevronRight,
                                            contentDescription = "All",
                                            tint = TextSecondary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }
                            }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val uniqueSectionItems = section.items.distinctBy { it.id }
                                itemsIndexed(uniqueSectionItems, key = { itemIdx, movie -> "${movie.id}_$itemIdx" }) { _, movie ->
                                    MovieGridItem(movie) {
                                        viewModel.selectSubject(movie)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val singleList = if (validSections.size == 1) validSections[0].items else categoryMovies
                if (singleList.isNotEmpty()) {
                    val chunked = singleList.chunked(3)
                    items(chunked) { rowItems ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { movie ->
                                Box(modifier = Modifier.weight(1f)) {
                                    MovieGridItem(movie, modifier = Modifier.fillMaxWidth()) {
                                        viewModel.selectSubject(movie)
                                    }
                                }
                            }
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                } else {
                    item {
                        Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 80.dp, bottom = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WifiOff,
                                contentDescription = "Connection issue",
                                tint = TextSecondary,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Text(
                                text = "Unable to load feed",
                                color = TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Check network or switch provider in Settings",
                                color = TextSecondary,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { viewModel.loadDashboardContent() },
                                colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Retry", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

            // Bottom space padding
            item {
                Spacer(modifier = Modifier.height(80.dp))
            }
        }
    }
}

/**
 * Sleek OTT Platform Row placed directly below Hero Banner
 */
@Composable
fun OttPlatformRow(
    platforms: List<OttPlatform>,
    onPlatformClick: (OttPlatform) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp)
    ) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(platforms, key = { it.id }) { platform ->
                Surface(
                    onClick = { onPlatformClick(platform) },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.Transparent,
                    modifier = Modifier.size(56.dp)
                ) {
                    Image(
                        painter = painterResource(id = platform.iconRes),
                        contentDescription = platform.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}



@Composable
fun AllCatalogScreen(
    section: CategorySection? = null,
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val displayList = remember(section) {
        section?.items?.distinctBy { it.id } ?: emptyList()
    }

    val screenTitle = section?.title?.ifBlank { "All Content" } ?: "All Content"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = TextPrimary
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = screenTitle,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 3-Column Content Grid for the selected section
        if (displayList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 100.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text("No movies found", color = TextSecondary, fontSize = 14.sp)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(
                    items = displayList,
                    key = { index, movie -> "${movie.id}_$index" }
                ) { _, movie ->
                    MovieGridItem(movie = movie, modifier = Modifier.fillMaxWidth()) {
                        viewModel.selectSubject(movie)
                    }
                }
            }
        }
    }
}



