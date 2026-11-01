package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import com.example.R
import com.example.ui.theme.*
import com.example.ui.viewmodel.MovieViewModel
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MovieViewModel,
    onNavigateToHistory: () -> Unit = {},
    onNavigateToWatchlist: () -> Unit = {},
    onNavigateToManageCategories: () -> Unit = {}
) {
    var showLoginForm by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val history by viewModel.historyList.collectAsState()
    val favorites by viewModel.favoritesList.collectAsState()

    val groupedHistory = remember(history) {
        history.groupBy { it.subjectId }.map { it.value.first() }
    }

    LaunchedEffect(viewModel.isLoggedIn) {
        if (viewModel.isLoggedIn) {
            viewModel.checkUserSession()
            showLoginForm = false
        }
    }

    BackHandler(enabled = showLoginForm) {
        showLoginForm = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ObsidianDark)
            .padding(horizontal = 16.dp, vertical = 20.dp)
    ) {
        if (viewModel.isLoggedIn || !showLoginForm) {
            val user = viewModel.currentUserProfile
            var showEditNicknameDialog by remember { mutableStateOf(false) }
            var editNickname by remember { mutableStateOf(user?.nickname ?: "") }

            val photoPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
            ) { uri: android.net.Uri? ->
                if (uri != null) {
                    try {
                        val inputStream = context.contentResolver.openInputStream(uri)
                        val bytes = inputStream?.readBytes()
                        if (bytes != null) {
                            viewModel.uploadAvatarAndModifyProfile(
                                nickname = user?.nickname ?: "",
                                imageBytes = bytes,
                                onSuccess = {
                                    Toast.makeText(context, "Avatar updated", Toast.LENGTH_SHORT).show()
                                },
                                onError = { err ->
                                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to load image: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            if (showEditNicknameDialog && viewModel.isLoggedIn) {
                AlertDialog(
                    onDismissRequest = { showEditNicknameDialog = false },
                    title = { Text("Edit Nickname", color = TextPrimary, fontWeight = FontWeight.Bold) },
                    text = {
                        OutlinedTextField(
                            value = editNickname,
                            onValueChange = { editNickname = it },
                            label = { Text("Nickname") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedBorderColor = CinematicRed,
                                unfocusedBorderColor = Color.Gray.copy(alpha = 0.3f)
                            )
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.updateProfile(
                                    nickname = editNickname,
                                    avatar = user?.avatar ?: "",
                                    onSuccess = {
                                        Toast.makeText(context, "Nickname updated", Toast.LENGTH_SHORT).show()
                                        showEditNicknameDialog = false
                                    },
                                    onError = { err ->
                                        Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                    }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = CinematicRed)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showEditNicknameDialog = false }) {
                            Text("Cancel", color = TextSecondary)
                        }
                    },
                    containerColor = ObsidianCard
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.Top
            ) {
                // Section 1: Hero Profile Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (viewModel.isLoggedIn) {
                                editNickname = user?.nickname ?: ""
                                showEditNicknameDialog = true
                            } else {
                                showLoginForm = true
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Avatar Circle
                        Box(
                            modifier = Modifier
                                .size(54.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF222538))
                                .clickable {
                                    if (viewModel.isLoggedIn) {
                                        photoPickerLauncher.launch("image/*")
                                    } else {
                                        showLoginForm = true
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val localAvatar = remember(user?.avatar) {
                                val file = java.io.File(context.filesDir, "avatar.jpg")
                                if (file.exists() && file.length() > 0) file else user?.avatar
                            }
                            if (viewModel.isLoggedIn && (!user?.avatar.isNullOrEmpty() || (localAvatar is java.io.File && localAvatar.exists()))) {
                                AsyncImage(
                                    model = localAvatar,
                                    contentDescription = "Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Image(
                                    painter = painterResource(id = R.drawable.ic_default_avatar),
                                    contentDescription = "Default Avatar",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        // Nickname and status
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (viewModel.isLoggedIn && !user?.nickname.isNullOrEmpty()) user!!.nickname else "Log in to your account",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            if (viewModel.isLoggedIn) {
                                Text(
                                    text = "ID: ${if (!user?.userId.isNullOrEmpty()) user!!.userId else "00000000"}",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            } else {
                                Text(
                                    text = "Sync history, favorites & settings",
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                        }

                        if (!viewModel.isLoggedIn) {
                            Button(
                                onClick = { showLoginForm = true },
                                colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                                shape = RoundedCornerShape(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Text("Log In", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit Profile",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Section Label: MEDIA LIBRARY
                Text(
                    text = "MEDIA LIBRARY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary.copy(alpha = 0.8f),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )

                // Group 1: Media Library Card (Watch History + Watchlist)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Watch History Row
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToHistory() }
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Filled.History,
                                        contentDescription = "Watch History",
                                        tint = TextPrimary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "Watch History",
                                                fontSize = 15.sp,
                                                color = TextPrimary,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (groupedHistory.isNotEmpty()) {
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = "${groupedHistory.size}",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                }
                                            }
                                        }
                                        Text(
                                            text = if (groupedHistory.isNotEmpty()) "${groupedHistory.size} recently watched" else "View your watch history and progress",
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = "Navigate",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            // History Posters Carousel
                            if (groupedHistory.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                LazyRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(groupedHistory.take(5)) { item ->
                                        Box(
                                            modifier = Modifier
                                                .width(96.dp)
                                                .height(54.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF222538))
                                                .clickable { onNavigateToHistory() }
                                        ) {
                                            AsyncImage(
                                                model = item.poster,
                                                contentDescription = item.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(
                                                        Brush.verticalGradient(
                                                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)),
                                                            startY = 25f
                                                        )
                                                    )
                                            )
                                            Text(
                                                text = item.name,
                                                color = Color.White,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Medium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.06f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                        // My Watchlist Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToWatchlist() }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Bookmark,
                                    contentDescription = "My Watchlist",
                                    tint = CinematicRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "My Watchlist",
                                            fontSize = 15.sp,
                                            color = TextPrimary,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        if (favorites.isNotEmpty()) {
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Box(
                                                modifier = Modifier
                                                    .background(CinematicRed.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${favorites.size}",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = CinematicRed
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = if (favorites.isNotEmpty()) "${favorites.size} saved" else "View your saved movies & series",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Navigate",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Section Label: PREFERENCES
                Text(
                    text = "PREFERENCES",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary.copy(alpha = 0.8f),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )

                // Group 2: Content & Feed Preferences (Location + Manage Categories)
                var showLocationDialog by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Location Selection Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showLocationDialog = true }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.LocationOn,
                                    contentDescription = "Location",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Location",
                                        fontSize = 15.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = when (viewModel.customCountryIso) {
                                            "us" -> "United States"
                                            "br" -> "Brazil"
                                            "ru" -> "Russia"
                                            else -> "India"
                                        },
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Navigate",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.06f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                        // Manage Categories Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigateToManageCategories() }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.List,
                                    contentDescription = "Manage Categories",
                                    tint = TextPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Manage Categories",
                                        fontSize = 15.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Add or remove Home Feed tabs",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Navigate",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }


                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Section Label: ABOUT & COMMUNITY
                Text(
                    text = "ABOUT & COMMUNITY",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextSecondary.copy(alpha = 0.8f),
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                )

                // Group 3: Community & Updates
                var showSettingsTelegramDialog by remember { mutableStateOf(false) }

                if (showSettingsTelegramDialog) {
                    TelegramJoinDialog(
                        onDismiss = { showSettingsTelegramDialog = false },
                        telegramUrl = "https://t.me/Gengaworldofficial"
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = ObsidianCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {

                        // Join Telegram Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showSettingsTelegramDialog = true }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Send,
                                    contentDescription = "Telegram",
                                    tint = Color(0xFF0EA2DE),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .rotate(-25f)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Join Telegram Channel",
                                        fontSize = 15.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Get early access, requests & updates",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            Icon(
                                imageVector = Icons.Filled.ChevronRight,
                                contentDescription = "Navigate",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        HorizontalDivider(
                            color = Color.White.copy(alpha = 0.06f),
                            thickness = 1.dp,
                            modifier = Modifier.padding(horizontal = 14.dp)
                        )

                        // Check for Updates Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !viewModel.isCheckingUpdate) {
                                    viewModel.checkForUpdates(isManualCheck = true) { hasUpdate, message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.SystemUpdate,
                                    contentDescription = "Check for Updates",
                                    tint = CinematicRed,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Check for Updates",
                                        fontSize = 15.sp,
                                        color = TextPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    val appVersion = remember {
                                        try {
                                            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "4.1"
                                        } catch (e: Exception) { "4.1" }
                                    }
                                    Text(
                                        text = if (viewModel.isCheckingUpdate) "Checking latest version..." else "Current: v$appVersion",
                                        fontSize = 12.sp,
                                        color = TextSecondary
                                    )
                                }
                            }
                            if (viewModel.isCheckingUpdate) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = CinematicRed,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.ChevronRight,
                                    contentDescription = "Check",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                if (showLocationDialog) {
                    AlertDialog(
                        onDismissRequest = { showLocationDialog = false },
                        title = { Text("Select Region", color = TextPrimary, fontWeight = FontWeight.Bold) },
                        text = {
                            Column {
                                val regions = listOf(
                                    Triple("90101", "us", "United States"),
                                    Triple("40401", "in", "India"),
                                    Triple("72402", "br", "Brazil"),
                                    Triple("25001", "ru", "Russia")
                                )
                                regions.forEach { reg ->
                                    val isSelected = viewModel.customMcc == reg.first && viewModel.customCountryIso == reg.second
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                viewModel.applyCustomSandbox(reg.first, reg.second)
                                                showLocationDialog = false
                                            }
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = isSelected,
                                            onClick = {
                                                viewModel.applyCustomSandbox(reg.first, reg.second)
                                                showLocationDialog = false
                                            },
                                            colors = RadioButtonDefaults.colors(selectedColor = CinematicRed)
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(reg.third, color = TextPrimary, fontSize = 16.sp)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showLocationDialog = false }) {
                                Text("Close", color = TextSecondary)
                            }
                        },
                        containerColor = ObsidianCard
                    )
                }


                Spacer(modifier = Modifier.height(28.dp))

                // Sign Out Button (if logged in)
                if (viewModel.isLoggedIn) {
                    Button(
                        onClick = {
                            viewModel.logoutUser {
                                Toast.makeText(context, "Logged out successfully", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.9f)
                            .align(Alignment.CenterHorizontally)
                            .height(48.dp)
                            .testTag("logout_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = CinematicRed),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(imageVector = Icons.Filled.ExitToApp, contentDescription = "Log Out")
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sign Out", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }

                Spacer(modifier = Modifier.height(80.dp))
            }
        } else {
            AuthSheet(viewModel = viewModel, onDismiss = { showLoginForm = false })
        }
    }
}
