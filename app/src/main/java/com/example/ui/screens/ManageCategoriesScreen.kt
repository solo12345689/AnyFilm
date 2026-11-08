package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.*
import com.example.ui.viewmodel.MovieViewModel

val availableMovieBoxCategories = listOf(
    3 to "Education",
    4 to "Music",
    9 to "18+",
    19 to "Western TV Series",
    23 to "Kids Movies",
    28 to "Nollywood",
    37 to "Combat",
    38 to "Cooking",
    40 to "Courses"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageCategoriesScreen(
    viewModel: MovieViewModel,
    onBack: () -> Unit
) {
    val activeCategories by viewModel.activeCategories.collectAsState()
    val activeIds = activeCategories.map { it.first }

    LaunchedEffect(viewModel.isAniFam) {
        if (viewModel.isAniFam) {
            viewModel.loadAniFamGenres()
        }
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Categories", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianDark
                )
            )
        },
        containerColor = ObsidianDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text(
                text = if (viewModel.isAniFam) "Select Anime Genres to pin to your Home Feed" else "Customize your Home Feed",
                color = TextSecondary,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            if (viewModel.isAniFam) {
                if (viewModel.isLoadingAniFamGenres && viewModel.aniFamGenres.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = CinematicRed)
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(viewModel.aniFamGenres) { genre ->
                            val isAdded = activeIds.contains(genre.id)

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(ObsidianCard, RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    if (genre.fullImageUrl.isNotEmpty()) {
                                        AsyncImage(
                                            model = genre.fullImageUrl,
                                            contentDescription = genre.name,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier
                                                .size(width = 60.dp, height = 38.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                    }
                                    Text(
                                        text = genre.name,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        if (isAdded) {
                                            viewModel.removeCategory(genre.id)
                                        } else {
                                            viewModel.addCategory(genre.id, genre.name)
                                        }
                                    }
                                ) {
                                    if (isAdded) {
                                        Icon(
                                            Icons.Default.Remove,
                                            contentDescription = "Remove",
                                            tint = CinematicRed
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = "Add",
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(availableMovieBoxCategories) { cat ->
                        val isAdded = activeIds.contains(cat.first)

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(ObsidianCard, RoundedCornerShape(12.dp))
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = cat.second,
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium
                            )

                            IconButton(
                                onClick = {
                                    if (isAdded) {
                                        viewModel.removeCategory(cat.first)
                                    } else {
                                        viewModel.addCategory(cat.first, cat.second)
                                    }
                                }
                            ) {
                                if (isAdded) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Remove",
                                        tint = CinematicRed
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Add",
                                        tint = Color.White
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
