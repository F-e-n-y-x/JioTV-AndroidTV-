package com.fenyx.jtv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.tv.material3.Surface
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun MainScreen(
    onChannelClick: (Int, String?) -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainViewModel = viewModel()
) {
    val channels by viewModel.channels.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()

    val context = LocalContext.current
    val settingsManager = remember { SettingsManager(context) }
    val epgMode by settingsManager.epgModeFlow.collectAsState(initial = false)
    val epgData by viewModel.epgData.collectAsState()
    val favoriteChannels by viewModel.favoriteChannels.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.fetchChannels()
    }
    
    LaunchedEffect(epgMode) {
        if (epgMode) {
            viewModel.fetchEpg()
        }
    }

    Row(modifier = modifier.fillMaxSize().background(TvDarkBackground)) {

        // ─── Left Sidebar (Category Navigation) ───
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(210.dp)
                .background(TvDarkSurface)
                .padding(vertical = 12.dp)
        ) {


            // Category list
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(groups) { group ->
                    val isSelected = selectedGroup == group
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                        onClick = { viewModel.setSelectedGroup(group) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (isSelected) TvPrimaryContainer.copy(alpha = 0.3f) else Color.Transparent,
                            focusedContainerColor = TvDarkSurfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {

                            Text(
                                text = group,
                                color = if (isSelected) TvPrimary else TvOnSurface,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Bottom actions
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .padding(horizontal = 16.dp)
                    .background(TvDarkSurfaceVariant)
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Refresh
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                onClick = { viewModel.retry() },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = TvDarkSurfaceVariant
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TvOnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Refresh", color = TvOnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Settings
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                onClick = onSettingsClick,
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = TvDarkSurfaceVariant
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = TvOnSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Settings", color = TvOnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        // ─── Right Content Area (Channel Grid) ───
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            if (isLoading) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = TvPrimary)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Loading channels...", color = TvOnSurfaceVariant)
                }
            } else if (error != null) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⚠", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = error!!,
                        color = TvOnSurfaceVariant,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Surface(
                        onClick = { viewModel.retry() },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = TvPrimaryContainer,
                            focusedContainerColor = TvPrimary
                        )
                    ) {
                        Text(
                            "Retry",
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 12.dp),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            } else {
                val filteredChannels by viewModel.filteredChannels.collectAsState()

                // Pre-compute channel index map once (O(n)) instead of indexOf per item (O(n²))
                val allChannels = viewModel.getAllChannels()
                val channelIndexMap = remember(allChannels) {
                    allChannels.withIndex().associate { (i, ch) -> ch.id to i }
                }

                Column(modifier = Modifier.fillMaxSize()) {


                    if (filteredChannels.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No channels in this category", color = TvOnSurfaceVariant)
                        }
                    } else if (epgMode) {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(items = filteredChannels, key = { it.id }) { channel ->
                                val channelIndex = channelIndexMap[channel.id] ?: 0

                                val programs = epgData[channel.id] ?: emptyList()
                                LaunchedEffect(channel.id) {
                                    if (programs.isEmpty()) {
                                        viewModel.fetchNativeEpgIfMissing(channel.id)
                                    }
                                }

                                EpgChannelRow(
                                    channel = channel,
                                    epgPrograms = programs,
                                    onClick = { onChannelClick(channelIndex, selectedGroup) }
                                )
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(150.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            items(items = filteredChannels, key = { it.id }) { channel ->
                                val channelIndex = channelIndexMap[channel.id] ?: 0

                                ChannelCard(
                                    channel = channel,
                                    onClick = { onChannelClick(channelIndex, selectedGroup) }
                                )
                            }
                        }
                    }
                }
            }


        }
    }
}

@Composable
private fun ChannelCard(
    channel: com.fenyx.jtv.data.Channel,
    onClick: () -> Unit
) {
    val context = LocalContext.current

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvDarkSurface,
            focusedContainerColor = TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, TvFocusBorder),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Channel logo
            if (channel.logoUrl.isNotEmpty()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(channel.logoUrl)
                        .size(112) // Downsample to 2x display size (56dp) to save memory
                        .build(),
                    contentDescription = channel.name,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TvDarkSurfaceVariant),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Fallback icon
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(TvDarkSurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = TvOnSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Channel name
            Text(
                channel.name,
                style = MaterialTheme.typography.bodySmall,
                color = TvOnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )

            // LIVE badge
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .background(TvLiveRed.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "LIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvLiveRed,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

@Composable
fun EpgChannelRow(
    channel: com.fenyx.jtv.data.Channel,
    epgPrograms: List<com.fenyx.jtv.data.EpgProgram>,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    // Reuse a single formatter instance instead of allocating per-recomposition
    val timeFormat = remember { java.text.SimpleDateFormat("hh:mm a", java.util.Locale.getDefault()) }
    // Update time every 30s instead of every frame to reduce recomposition triggers
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(30_000)
        }
    }
    val currentProgram = remember(epgPrograms, now) { epgPrograms.find { it.startMs <= now && it.stopMs > now } }
    val nextPrograms = remember(epgPrograms, now) { epgPrograms.filter { it.startMs > now }.take(3) }

    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = TvDarkSurface,
            focusedContainerColor = TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(2.dp, TvFocusBorder),
                shape = RoundedCornerShape(8.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo and Name
            Column(
                modifier = Modifier.width(100.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (channel.logoUrl.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(channel.logoUrl).size(96).build(),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Color.White),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(TvDarkSurfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = channel.name.take(1),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    channel.name,
                    style = MaterialTheme.typography.labelMedium,
                    color = TvOnSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Timeline
            Row(modifier = Modifier.weight(1f)) {
                if (currentProgram != null) {
                    val progress = ((now - currentProgram.startMs).toFloat() / (currentProgram.stopMs - currentProgram.startMs)).coerceIn(0f, 1f)
                    
                    Box(
                        modifier = Modifier
                            .weight(0.45f)
                            .background(TvPrimaryContainer.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                            .border(1.dp, TvPrimary.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(TvLiveRed))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("NOW PLAYING", color = TvLiveRed, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(currentProgram.title, color = TvOnBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(8.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(1.5.dp)),
                                color = TvPrimary,
                                trackColor = TvDarkSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("${timeFormat.format(java.util.Date(currentProgram.startMs))} - ${timeFormat.format(java.util.Date(currentProgram.stopMs))}", color = TvOnSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                } else if (epgPrograms.isEmpty()) {
                    Text("No EPG Data Available", color = TvOnSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
                }
                
                nextPrograms.forEach { prog ->
                    Box(
                        modifier = Modifier
                            .weight(0.25f)
                            .padding(end = 8.dp)
                            .background(TvDarkBackground.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Column {
                            Text(prog.title, color = TvOnSurface, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(timeFormat.format(java.util.Date(prog.startMs)), color = TvOnSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }
}
