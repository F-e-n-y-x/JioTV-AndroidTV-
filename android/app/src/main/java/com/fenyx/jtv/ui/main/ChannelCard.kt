package com.fenyx.jtv.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.fenyx.jtv.theme.Surface
import androidx.tv.material3.MaterialTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.fenyx.jtv.data.SettingsManager
import com.fenyx.jtv.theme.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch


// v1 grid card, kept only for SearchScreen until it moves to ChannelRow.
@Composable
fun ChannelCard(
    channel: com.fenyx.jtv.data.Channel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    isMoving: Boolean = false
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        onClick = onClick,
        onLongClick = onLongClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        // Clear 10-foot focus cue: the card scales up (was disabled at 1.0f) plus the focus border.
        // The card being moved is "lifted": bigger, accent-filled and always outlined.
        scale = ClickableSurfaceDefaults.scale(focusedScale = if (isMoving) 1.12f else TvDimens.FocusedScale),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isMoving) TvPrimaryContainer.copy(alpha = 0.5f) else TvDarkSurface,
            focusedContainerColor = if (isMoving) TvPrimaryContainer.copy(alpha = 0.6f) else TvDarkSurfaceVariant
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (isMoving) androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(3.dp, TvPrimary),
                shape = RoundedCornerShape(12.dp)
            ) else androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(
                border = androidx.compose.foundation.BorderStroke(if (isMoving) 3.dp else 2.dp, if (isMoving) Color.White else TvFocusBorder),
                shape = RoundedCornerShape(12.dp)
            )
        )
    ) {
        if (isMoving || isFavorite) {
            Text(
                if (isMoving) "⇅" else "★",
                color = if (isMoving) Color.White else androidx.compose.ui.graphics.Color(0xFFFFC107),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            )
        }
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

