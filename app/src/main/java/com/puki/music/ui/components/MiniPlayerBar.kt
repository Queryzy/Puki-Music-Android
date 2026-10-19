package com.puki.music.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.puki.music.model.Track
import com.puki.music.ui.theme.NuclearPalette

@Composable
fun MiniPlayerBar(
    track: Track,
    isPlaying: Boolean,
    isResolving: Boolean,
    positionMs: Long = 0L,
    durationMs: Long = 0L,
    palette: NuclearPalette,
    onClick: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextClick: (() -> Unit)? = null,
    onSeekTo: ((Long) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Effective total duration in ms
    val effectiveDurationMs = remember(track.durationSeconds, durationMs) {
        if (durationMs > 1000L) durationMs else (track.durationSeconds * 1000L).coerceAtLeast(1000L)
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var barWidthPx by remember { mutableIntStateOf(1) }

    val currentFraction = if (isDragging) {
        dragFraction
    } else {
        (positionMs.toFloat() / effectiveDurationMs.toFloat()).coerceIn(0f, 1f)
    }

    val displayPositionMs = if (isDragging) {
        (dragFraction * effectiveDurationMs).toLong()
    } else {
        positionMs.coerceIn(0L, effectiveDurationMs)
    }

    val redAccent = Color(0xFFFF2A2A)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            .background(palette.surface)
            .border(1.dp, palette.border.copy(alpha = 0.5f), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
    ) {
        // Main Track Info Row (Tap anywhere here opens expanded player)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = track.title,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = palette.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isResolving) "Menghubungkan audio stream..." else track.artist,
                    color = if (isResolving) redAccent else palette.textSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isResolving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = redAccent,
                    strokeWidth = 2.dp
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onPlayPauseToggle) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    if (onNextClick != null) {
                        IconButton(onClick = onNextClick) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = palette.textSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }

        // Interactive Red Seek Bar Container
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .padding(bottom = 6.dp)
        ) {
            // Scrubbing area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .onSizeChanged { barWidthPx = it.width.coerceAtLeast(1) }
                    .pointerInput(effectiveDurationMs) {
                        detectTapGestures(
                            onPress = { offset ->
                                isDragging = true
                                dragFraction = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                                val released = tryAwaitRelease()
                                if (released) {
                                    val targetMs = (dragFraction * effectiveDurationMs).toLong()
                                    onSeekTo?.invoke(targetMs)
                                }
                                isDragging = false
                            }
                        )
                    }
                    .pointerInput(effectiveDurationMs) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                isDragging = true
                                dragFraction = (offset.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                            },
                            onDragEnd = {
                                val targetMs = (dragFraction * effectiveDurationMs).toLong()
                                onSeekTo?.invoke(targetMs)
                                isDragging = false
                            },
                            onDragCancel = {
                                isDragging = false
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragFraction = (change.position.x / barWidthPx.toFloat()).coerceIn(0f, 1f)
                            }
                        )
                    },
                contentAlignment = Alignment.CenterStart
            ) {
                // Background Track Line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(Color.White.copy(alpha = 0.2f))
                )

                // Active Played Track (Red)
                Box(
                    modifier = Modifier
                        .fillMaxWidth(currentFraction)
                        .height(3.dp)
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(redAccent)
                )

                // Thumb Dot (Appears on touch / drag)
                val thumbSize by animateDpAsState(
                    targetValue = if (isDragging) 12.dp else 0.dp,
                    label = "thumb_size"
                )

                if (isDragging || currentFraction > 0.005f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(currentFraction)
                            .wrapContentWidth(Alignment.End)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isDragging) thumbSize else 6.dp)
                                .clip(CircleShape)
                                .background(redAccent)
                        )
                    }
                }
            }

            // Duration Labels: Current Minute / Total Minute
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 1.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val curSec = (displayPositionMs / 1000L).coerceAtLeast(0L)
                val totSec = (effectiveDurationMs / 1000L).coerceAtLeast(0L)

                Text(
                    text = "%d:%02d".format(curSec / 60, curSec % 60),
                    color = if (isDragging) redAccent else palette.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isDragging) FontWeight.Bold else FontWeight.Normal
                )

                Text(
                    text = "%d:%02d".format(totSec / 60, totSec % 60),
                    color = palette.textSecondary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
