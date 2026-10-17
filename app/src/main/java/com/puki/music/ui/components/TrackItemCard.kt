package com.puki.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.puki.music.model.Track
import com.puki.music.ui.theme.NuclearPalette

@Composable
fun TrackItemCard(
    track: Track,
    isSelected: Boolean,
    isFavorite: Boolean,
    isOffline: Boolean,
    downloadProgress: Int? = null,
    isDownloading: Boolean = false,
    playCount: Int = 0,
    fileSizeText: String? = null,
    palette: NuclearPalette,
    onClick: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onFavoriteToggle: (() -> Unit)? = null,
    onAddToPlaylist: (() -> Unit)? = null,
    onDownloadClick: (() -> Unit)? = null,
    trailingAction: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) palette.surfaceVariant else palette.surface)
            .border(1.dp, if (isSelected) palette.primary else palette.border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        val isActivelyDownloading = downloadProgress != null || isDownloading
        val showOfflineStatus = isOffline && !isActivelyDownloading

        Box {
            AsyncImage(
                model = track.thumbnailUrl,
                contentDescription = track.title,
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black),
                contentScale = ContentScale.Crop
            )
            if (showOfflineStatus) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(palette.success)
                        .align(Alignment.BottomEnd),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Offline",
                        tint = Color.Black,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = if (isSelected) palette.tertiary else palette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showOfflineStatus) {
                    Text(
                        text = "OFFLINE",
                        color = palette.success,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "•",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }
                Text(
                    text = track.artist,
                    color = if (onArtistClick != null) palette.secondary else palette.textSecondary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (onArtistClick != null) {
                        Modifier.clickable { onArtistClick(track.artist) }
                    } else Modifier
                )
                if (track.formattedViews != null) {
                    Text(
                        text = "•",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                    Text(
                        text = "${track.formattedViews} plays",
                        color = palette.primary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "• ${track.formattedDuration}",
                    color = palette.textSecondary,
                    fontSize = 11.sp
                )
            }

            if (track.isPopular || playCount > 0 || (fileSizeText != null && isOffline)) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (track.isPopular) {
                        Text(
                            text = "🔥 POPULER",
                            color = palette.secondary,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (playCount > 0) {
                        Text(
                            text = "🎧 ${playCount}x",
                            color = palette.primary,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (fileSizeText != null && isOffline) {
                        Text(
                            text = "💾 $fileSizeText",
                            color = palette.success.copy(alpha = 0.8f),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        if (trailingAction != null) {
            trailingAction()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDownloadClick != null) {
                    IconButton(onClick = onDownloadClick, modifier = Modifier.size(36.dp)) {
                        if (downloadProgress != null) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                                CircularProgressIndicator(
                                    progress = { (downloadProgress.coerceIn(0, 100)) / 100f },
                                    modifier = Modifier.size(24.dp),
                                    color = palette.primary,
                                    strokeWidth = 2.5.dp,
                                    trackColor = palette.surfaceVariant
                                )
                                Text(
                                    text = "$downloadProgress%",
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = palette.primary,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        } else if (isDownloading && !isOffline) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = palette.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = if (showOfflineStatus) Icons.Default.DownloadDone else Icons.Default.Download,
                                contentDescription = if (showOfflineStatus) "Tersimpan" else "Unduh Offline",
                                tint = if (showOfflineStatus) palette.success else palette.textSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                if (onAddToPlaylist != null) {
                    IconButton(onClick = onAddToPlaylist, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.PlaylistAdd,
                            contentDescription = "Playlist",
                            tint = palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (onFavoriteToggle != null) {
                    IconButton(onClick = onFavoriteToggle, modifier = Modifier.size(34.dp)) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Fav",
                            tint = if (isFavorite) palette.primary else palette.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
