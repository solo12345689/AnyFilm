package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.CategorySection
import com.example.ui.components.DashboardShimmerScreen
import com.example.ui.components.MovieGridItem
import com.example.ui.theme.*
import com.example.ui.viewmodel.MovieViewModel

@Composable
fun HomeScreen(viewModel: MovieViewModel) {
    val trending by viewModel.trendingMovies.collectAsState()
    val categoryMovies by viewModel.categoryMovies.collectAsState()
    val categorySections by viewModel.categorySections.collectAsState()

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
            !sec.type.contains("SINGLE_IMAGE", ignoreCase = true) &&
            !sec.type.contains("FILTER", ignoreCase = true) &&
            !sec.type.contains("ACTOR", ignoreCase = true) &&
            !sec.type.contains("STAR", ignoreCase = true)
        }
    }

    val bannerMovies = remember(categorySections, categoryMovies, validSections) {
        val bannerSec = categorySections.firstOrNull { it.type.contains("BANNER", ignoreCase = true) || it.title.contains("Banner", ignoreCase = true) }
        if (bannerSec != null && bannerSec.items.isNotEmpty()) {
            bannerSec.items.take(5)
        } else if (categoryMovies.isNotEmpty()) {
            categoryMovies.take(5)
        } else if (validSections.isNotEmpty()) {
            validSections.first().items.take(5)
        } else {
            emptyList()
        }
    }

    if (viewModel.isDashboardLoading) {
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
                                val optimalHeroUrl = androidx.compose.runtime.remember(heroMovie.poster) {
                                    com.example.util.ImageOptimizer.getOptimalUrl(heroMovie.poster, 720)
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
                                        .crossfade(false)
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

            // Horizontal Category filter chips (Immediately beneath Hero Banner)
            item {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    val categories by viewModel.activeCategories.collectAsState()

                    LaunchedEffect(categories) {
                        if (categories.isNotEmpty() && categories.none { it.first == viewModel.selectedCategoryTab }) {
                            viewModel.loadCategoryList(categories.first().first)
                        }
                    }

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

            // Category Content & Thematic Sections
            if (validSections.isNotEmpty()) {
                validSections.forEachIndexed { index, section ->
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (index == 0) 4.dp else 20.dp)
                        ) {
                            // First row has NO title (Category Content). Subsequent rows show their section titles!
                            val showSectionTitle = index > 0
                            if (showSectionTitle) {
                                Text(
                                    text = section.title,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                            }
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(section.items) { movie ->
                                    MovieGridItem(movie, showRating = true) {
                                        viewModel.selectSubject(movie)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (categoryMovies.isNotEmpty()) {
                // Fallback: horizontal category content if sections are still loading or unavailable
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        items(categoryMovies) { movie ->
                            MovieGridItem(movie, showRating = true) {
                                viewModel.selectSubject(movie)
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
