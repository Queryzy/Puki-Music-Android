package com.puki.music.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import com.puki.music.core.PlaybackStateManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import com.puki.music.core.LyricsResult
import com.puki.music.core.LyricsService
import coil.compose.AsyncImage
import com.puki.music.data.LocalMusicStore
import com.puki.music.model.RepeatMode
import com.puki.music.model.Track
import com.puki.music.ui.theme.NuclearPalette
import kotlinx.coroutines.delay

@Composable
fun ExpandedPlayerModal(
    track: Track,
    mediaController: MediaController?,
    isPlaying: Boolean,
    isMixPlay: Boolean,
    isOffline: Boolean,
    isDownloading: Boolean,
    palette: NuclearPalette,
    store: LocalMusicStore,
    repeatMode: RepeatMode = RepeatMode.OFF,
    isFavorite: Boolean? = null,
    playbackQueue: List<Track> = emptyList(),
    onQueueTrackSelect: ((Track) -> Unit)? = null,
    onToggleRepeat: () -> Unit = {},
    onToggleMixPlay: () -> Unit,
    onDownloadClick: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onFavoritesChanged: () -> Unit,
    onFavoriteToggle: (() -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null,
    onNextTrack: (() -> Unit)? = null,
    onPreviousTrack: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    val durationMs = (track.durationSeconds * 1000).coerceAtLeast(1L)
    var localFav by remember(track.id) { mutableStateOf(store.isFavorite(track.id)) }
    val effectiveFav = isFavorite ?: localFav
    var playerCenterMode by remember { mutableIntStateOf(0) } // 0 = Artwork, 1 = Lirik, 2 = Antrean
    var lyricsResult by remember(track.id) { mutableStateOf<LyricsResult?>(null) }
    var isLoadingLyrics by remember(track.id) { mutableStateOf(false) }
    var lyricsError by remember(track.id) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(track.id, playerCenterMode) {
        if (playerCenterMode == 1 && lyricsResult == null && !isLoadingLyrics) {
            isLoadingLyrics = true
            lyricsError = null
            val res = LyricsService.getLyrics(context, track)
            res.onSuccess {
                lyricsResult = it
            }.onFailure {
                lyricsError = it.localizedMessage ?: "Lirik tidak ditemukan"
            }
            isLoadingLyrics = false
        }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            mediaController?.let {
                currentPositionMs = it.currentPosition
            }
            delay(250)
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            palette.surfaceVariant.copy(alpha = 0.95f),
                            palette.background,
                            palette.background
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Top Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Close",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (isOffline) "OFFLINE STORAGE PLAYBACK" else "PLAYING FROM CDN",
                            color = if (isOffline) palette.success else palette.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "NUCLEAR AUDIO ENGINE",
                            color = palette.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.sp
                        )
                    }

                    Row {
                        IconButton(onClick = onDownloadClick) {
                            if (isDownloading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = palette.primary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = if (isOffline) Icons.Default.DownloadDone else Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = if (isOffline) palette.success else palette.textPrimary
                                )
                            }
                        }
                        IconButton(onClick = onAddToPlaylist) {
                            Icon(
                                Icons.Default.PlaylistAdd,
                                contentDescription = "Add to playlist",
                                tint = palette.textPrimary
                            )
                        }
                        IconButton(
                            onClick = {
                                if (onFavoriteToggle != null) {
                                    onFavoriteToggle()
                                    localFav = !effectiveFav
                                } else {
                                    localFav = store.toggleFavorite(track)
                                    onFavoritesChanged()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (effectiveFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                contentDescription = "Fav",
                                tint = if (effectiveFav) palette.primary else palette.textPrimary
                            )
                        }
                    }
                }

                // Mode Switcher: [ ARTWORK ] vs [ LIRIK SYNC ] vs [ ANTREAN ]
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(palette.surfaceVariant)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (playerCenterMode == 0) palette.primary else palette.surfaceVariant)
                            .clickable { playerCenterMode = 0 }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "🎵 ARTWORK",
                            color = if (playerCenterMode == 0) palette.background else palette.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (playerCenterMode == 1) palette.primary else palette.surfaceVariant)
                            .clickable { playerCenterMode = 1 }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "📜 LIRIK SYNC",
                            color = if (playerCenterMode == 1) palette.background else palette.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (playerCenterMode == 2) palette.primary else palette.surfaceVariant)
                            .clickable { playerCenterMode = 2 }
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (playbackQueue.isNotEmpty()) "📑 ANTREAN (${playbackQueue.size})" else "📑 ANTREAN",
                            color = if (playerCenterMode == 2) palette.background else palette.textSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (playerCenterMode == 0) {
                    // Center Album Artwork with Quality Pill & Stats
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        AsyncImage(
                            model = track.thumbnailUrl,
                            contentDescription = track.title,
                            modifier = Modifier
                                .size(230.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .border(1.5.dp, palette.primary.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Studio Quality Badge
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(palette.surface)
                                .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(palette.success)
                            )
                            Text(
                                text = if (isOffline) "LOCAL OPUS • 48 kHz BIT-PERFECT" else "OPUS 160 KBPS • 48 kHz HIGH-RES",
                                color = palette.success,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // Popularity & Play Count Stats Row
                        val playCount = store.getPlayCount(track.id)
                        if (track.formattedViews != null || track.formattedLikes != null || playCount > 0) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (track.isPopular) {
                                    Text(
                                        text = "🔥 POPULER",
                                        color = palette.secondary,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (track.formattedViews != null) {
                                    Text(
                                        text = "👁 ${track.formattedViews} views",
                                        color = palette.textSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (track.formattedLikes != null) {
                                    Text(
                                        text = "👍 ${track.formattedLikes}",
                                        color = palette.textSecondary,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                if (playCount > 0) {
                                    Text(
                                        text = "🎧 Diputar ${playCount}x",
                                        color = palette.primary,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Track Title & Artist
                        Text(
                            text = track.title,
                            color = palette.textPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = track.artist,
                            color = palette.secondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = if (onArtistClick != null) {
                                Modifier.clickable { onArtistClick(track.artist) }
                            } else Modifier
                        )

                        if (onArtistClick != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(palette.surfaceVariant)
                                    .border(1.dp, palette.border, RoundedCornerShape(12.dp))
                                    .clickable { onArtistClick(track.artist) }
                                    .padding(horizontal = 10.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Album,
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = "LIHAT ALBUM & DISKOGRAFI",
                                    color = palette.primary,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                } else if (playerCenterMode == 1) {
                    // Synced Karaoke Lyrics View
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isLoadingLyrics) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = palette.primary, modifier = Modifier.size(32.dp))
                                Spacer(modifier = Modifier.height(10.dp))
                                Text("Mengambil lirik sinkron...", color = palette.textSecondary, fontSize = 11.sp)
                            }
                        } else if (lyricsResult != null && lyricsResult!!.isSynced) {
                            val lines = lyricsResult!!.lines
                            val activeIndex = LyricsService.findActiveLyricIndex(lines, currentPositionMs)
                            val listState = rememberLazyListState()

                            LaunchedEffect(activeIndex) {
                                if (activeIndex >= 0) {
                                    listState.animateScrollToItem((activeIndex - 2).coerceAtLeast(0))
                                }
                            }

                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                itemsIndexed(lines) { idx, line ->
                                    val isActive = idx == activeIndex
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isActive) palette.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                                            .clickable {
                                                mediaController?.seekTo(line.timeMs)
                                                currentPositionMs = line.timeMs
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = line.text,
                                            color = if (isActive) palette.primary else palette.textSecondary.copy(alpha = 0.65f),
                                            fontSize = if (isActive) 16.sp else 13.sp,
                                            fontWeight = if (isActive) FontWeight.ExtraBold else FontWeight.Normal,
                                            textAlign = TextAlign.Start,
                                            lineHeight = 22.sp
                                        )
                                    }
                                }
                            }
                        } else if (lyricsResult != null && lyricsResult!!.plainLyrics != null) {
                            // Plain lyrics fallback
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = lyricsResult!!.plainLyrics!!,
                                    color = palette.textPrimary,
                                    fontSize = 13.sp,
                                    lineHeight = 22.sp
                                )
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.MusicOff, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(36.dp))
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = lyricsError ?: "Lirik tidak ditemukan di database LRCLIB",
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        isLoadingLyrics = true
                                        lyricsError = null
                                        lyricsResult = null
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                                    shape = RoundedCornerShape(6.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Text("COBA LAGI", color = palette.background, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // Current Playback Queue View (Album / Playlist / Mix / Liked Tracks)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(340.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.surfaceVariant.copy(alpha = 0.5f))
                            .border(1.dp, palette.border, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        if (playbackQueue.isEmpty()) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    Icons.Default.QueueMusic,
                                    contentDescription = null,
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Tidak ada antrean lagu yang aktif",
                                    color = palette.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                        } else {
                            val normCurrentId = LocalMusicStore.normalizeId(track.id)
                            val currentIdx = playbackQueue.indexOfFirst {
                                LocalMusicStore.normalizeId(it.id) == normCurrentId
                            }
                            val queueListState = rememberLazyListState()

                            LaunchedEffect(track.id) {
                                if (currentIdx >= 0) {
                                    queueListState.animateScrollToItem((currentIdx - 1).coerceAtLeast(0))
                                }
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "ANTREAN PUTAR (${playbackQueue.size})",
                                        color = palette.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                    if (currentIdx >= 0) {
                                        Text(
                                            text = "Lagu #${currentIdx + 1} dari ${playbackQueue.size}",
                                            color = palette.textSecondary,
                                            fontSize = 10.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                LazyColumn(
                                    state = queueListState,
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    itemsIndexed(playbackQueue) { idx, qTrack ->
                                        val isCurrent = LocalMusicStore.normalizeId(qTrack.id) == normCurrentId
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { onQueueTrackSelect?.invoke(qTrack) },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (isCurrent) palette.primary.copy(alpha = 0.15f) else palette.surface
                                            ),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isCurrent) palette.primary else palette.border.copy(alpha = 0.5f)
                                            )
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Text(
                                                    text = "%02d".format(idx + 1),
                                                    color = if (isCurrent) palette.primary else palette.textSecondary,
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                                                )

                                                AsyncImage(
                                                    model = qTrack.thumbnailUrl,
                                                    contentDescription = null,
                                                    contentScale = ContentScale.Crop,
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .clip(RoundedCornerShape(6.dp))
                                                )

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = qTrack.title,
                                                        color = if (isCurrent) palette.primary else palette.textPrimary,
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = qTrack.artist,
                                                        color = palette.textSecondary,
                                                        fontSize = 10.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }

                                                if (isCurrent) {
                                                    Box(
                                                        modifier = Modifier
                                                            .clip(RoundedCornerShape(4.dp))
                                                            .background(palette.primary)
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = "SEDANG DIPUTAR",
                                                            color = palette.background,
                                                            fontSize = 8.sp,
                                                            fontWeight = FontWeight.Bold
                                                        )
                                                    }
                                                } else {
                                                    val min = qTrack.durationSeconds / 60
                                                    val sec = qTrack.durationSeconds % 60
                                                    Text(
                                                        text = "%d:%02d".format(min, sec),
                                                        color = palette.textSecondary,
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace
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

                // Progress Bar & Duration
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = (currentPositionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f),
                        onValueChange = { fraction ->
                            val seekTo = (fraction * durationMs).toLong()
                            currentPositionMs = seekTo
                            mediaController?.seekTo(seekTo)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = palette.primary,
                            activeTrackColor = palette.primary,
                            inactiveTrackColor = palette.surfaceVariant
                        )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val posSec = currentPositionMs / 1000
                        val durSec = durationMs / 1000
                        Text(
                            "%d:%02d".format(posSec / 60, posSec % 60),
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "%d:%02d".format(durSec / 60, durSec % 60),
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Controls Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onToggleMixPlay) {
                        Icon(
                            Icons.Default.Shuffle,
                            contentDescription = "Mix Play",
                            tint = if (isMixPlay) palette.primary else palette.textSecondary
                        )
                    }

                    IconButton(
                        onClick = { onPreviousTrack?.invoke() },
                        enabled = onPreviousTrack != null
                    ) {
                        Icon(
                            Icons.Default.SkipPrevious,
                            contentDescription = "Lagu Sebelumnya",
                            tint = if (onPreviousTrack != null) palette.textPrimary else palette.textSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Large Glowing Play/Pause Button
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(CircleShape)
                            .background(palette.primary)
                            .clickable {
                                PlaybackStateManager.togglePlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = palette.background,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    IconButton(
                        onClick = { onNextTrack?.invoke() },
                        enabled = onNextTrack != null
                    ) {
                        Icon(
                            Icons.Default.SkipNext,
                            contentDescription = "Lagu Selanjutnya",
                            tint = if (onNextTrack != null) palette.textPrimary else palette.textSecondary.copy(alpha = 0.35f),
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    IconButton(onClick = onToggleRepeat) {
                        Icon(
                            imageVector = if (repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                            contentDescription = "Repeat: $repeatMode",
                            tint = when (repeatMode) {
                                RepeatMode.OFF -> palette.textSecondary.copy(alpha = 0.35f)
                                RepeatMode.ALL -> palette.primary
                                RepeatMode.ONE -> palette.secondary
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Footer
                val repeatLabel = when (repeatMode) {
                    RepeatMode.OFF -> "REPEAT OFF"
                    RepeatMode.ALL -> "REPEAT ALL"
                    RepeatMode.ONE -> "REPEAT ONE"
                }
                Text(
                    text = if (isMixPlay) "MIX PLAY ACTIVE • $repeatLabel" else "$repeatLabel • 32-BIT FLOAT STEREO",
                    color = if (isMixPlay || repeatMode != RepeatMode.OFF) palette.primary else palette.textSecondary.copy(alpha = 0.6f),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}
