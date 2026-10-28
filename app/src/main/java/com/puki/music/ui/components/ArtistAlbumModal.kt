package com.puki.music.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import coil.compose.AsyncImage
import com.puki.music.core.DeezerRepository
import com.puki.music.core.OfflineManager
import com.puki.music.core.StreamResolver
import com.puki.music.core.YouTubeMusicRepository
import com.puki.music.data.LocalMusicStore
import com.puki.music.model.Album
import com.puki.music.model.Track
import com.puki.music.ui.theme.NuclearPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ArtistAlbumModal(
    artistName: String,
    initialAlbum: Album? = null,
    palette: NuclearPalette,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    activeTrack: Track?,
    favoriteIds: Set<String> = emptySet(),
    offlineTrackIds: Set<String> = emptySet(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadTrack: (Track) -> Unit,
    onDownloadAlbum: (List<Track>, String) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoriteToggle: ((Track) -> Unit)? = null,
    onClose: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Albums, 1 = Top Tracks
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var topTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoadingAlbums by remember { mutableStateOf(initialAlbum == null) }
    var isLoadingTracks by remember { mutableStateOf(false) }

    // Active expanded album details
    var selectedAlbum by remember { mutableStateOf<Album?>(initialAlbum) }
    var albumTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoadingAlbumTracks by remember { mutableStateOf(initialAlbum != null) }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openAlbum(album: Album) {
        selectedAlbum = album
        isLoadingAlbumTracks = true
        scope.launch {
            if (album.url.startsWith("dz_")) {
                val dzAlbumId = album.url.removePrefix("dz_").toLongOrNull() ?: 0L
                val dzRes = DeezerRepository.getAlbumTracks(context, dzAlbumId)
                dzRes.onSuccess { dzTracks ->
                    albumTracks = dzTracks.map { dz ->
                        Track(
                            id = "dz_${dz.id}",
                            title = dz.title,
                            artist = dz.artistName,
                            durationSeconds = dz.durationSeconds,
                            thumbnailUrl = dz.coverUrl
                        )
                    }
                }.onFailure {
                    Toast.makeText(context, "Gagal memuat tracklist album", Toast.LENGTH_SHORT).show()
                }
            } else if (album.url.startsWith("MPREb_")) {
                // Official YouTube Music InnerTube Album
                val ytmRes = YouTubeMusicRepository.getAlbumTracks(album.url)
                ytmRes.onSuccess { tracks ->
                    albumTracks = tracks
                }.onFailure {
                    Toast.makeText(context, "Gagal memuat tracklist album YTM", Toast.LENGTH_SHORT).show()
                }
            } else {
                val res = StreamResolver.getAlbumTracks(album.url)
                res.onSuccess { tracks ->
                    albumTracks = tracks
                    launch(Dispatchers.IO) {
                        tracks.forEachIndexed { index, track ->
                            if (track.viewCount <= 0L) {
                                val detail = StreamResolver.resolveAudioStreamDetailed(track.id)
                                detail.onSuccess { d ->
                                    if (d.viewCount > 0L) {
                                        withContext(Dispatchers.Main) {
                                            albumTracks = albumTracks.mapIndexed { i, t ->
                                                if (i == index) t.copy(viewCount = d.viewCount, likeCount = d.likeCount) else t
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.onFailure {
                    Toast.makeText(context, "Gagal memuat tracklist album", Toast.LENGTH_SHORT).show()
                }
            }
            isLoadingAlbumTracks = false
        }
    }

    // If an initial album was passed, open it immediately
    LaunchedEffect(initialAlbum) {
        if (initialAlbum != null) {
            openAlbum(initialAlbum)
        }
    }

    // Load albums on launch (Dual-Engine Fusion: YTM Official First + Deezer Studio)
    LaunchedEffect(artistName) {
        isLoadingAlbums = true
        val combined = mutableListOf<Album>()
        val seen = mutableSetOf<String>()

        // 1. YouTube Music Official Albums (100% full discography for Indo & Global)
        val ytmRes = YouTubeMusicRepository.searchAlbums(artistName, limit = 20)
        ytmRes.onSuccess { ytmList ->
            val targetLower = artistName.lowercase().trim()
            for (a in ytmList) {
                val aArt = a.artist.lowercase().trim()
                // Strict check: artist name must match or contain query to prevent hijacking
                if (aArt == targetLower || aArt.contains(targetLower) || targetLower.contains(aArt)) {
                    val key = "${a.title.lowercase().trim()}_$aArt"
                    if (seen.add(key)) {
                        combined.add(a)
                    }
                }
            }
        }

        // 2. Deezer clean albums as studio supplement
        val dzSearch = DeezerRepository.searchCleanTracks(context, artistName, limit = 5)
        dzSearch.onSuccess { dzTracks ->
            val targetLower = artistName.lowercase().trim()
            val firstArtist = dzTracks.firstOrNull { 
                val artName = it.artistName.lowercase().trim()
                artName == targetLower || artName.startsWith(targetLower)
            }
            if (firstArtist != null) {
                val dzAlbumsRes = DeezerRepository.getArtistAlbums(context, firstArtist.artistId)
                dzAlbumsRes.onSuccess { dzList ->
                    for (dz in dzList) {
                        val key = "${dz.title.lowercase().trim()}_${dz.artistName.lowercase().trim()}"
                        if (seen.add(key)) {
                            combined.add(
                                Album(
                                    id = "dz_${dz.id}",
                                    title = dz.title,
                                    artist = dz.artistName,
                                    trackCount = dz.trackCount.toLong(),
                                    thumbnailUrl = dz.coverUrl,
                                    url = "dz_${dz.id}"
                                )
                            )
                        }
                    }
                }
            }
        }

        if (combined.isNotEmpty()) {
            albums = combined
        } else {
            // Secondary legacy fallback
            val res = StreamResolver.searchAlbums(artistName)
            res.onSuccess {
                albums = it
            }
        }
        isLoadingAlbums = false
    }

    // Load top tracks when user switches to tab 1
    LaunchedEffect(selectedTab) {
        if (selectedTab == 1 && topTracks.isEmpty() && !isLoadingTracks) {
            isLoadingTracks = true
            val res = StreamResolver.searchTracks("$artistName popular songs hits")
            res.onSuccess {
                topTracks = it
            }
            isLoadingTracks = false
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = palette.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(onClick = {
                            if (selectedAlbum != null) {
                                selectedAlbum = null
                                albumTracks = emptyList()
                            } else {
                                onClose()
                            }
                        }) {
                            Icon(
                                Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = palette.textPrimary
                            )
                        }

                        Column {
                            Text(
                                text = if (selectedAlbum != null) selectedAlbum!!.title else artistName,
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = if (selectedAlbum != null) "ALBUM OLEH $artistName" else "DISKOGRAFI & DAFTAR ALBUM",
                                color = palette.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = palette.textSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    if (selectedAlbum != null) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // ── Album Detail View ───────────────────────────────────
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(palette.surfaceVariant)
                                    .border(1.dp, palette.border, RoundedCornerShape(10.dp))
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AsyncImage(
                                    model = selectedAlbum!!.thumbnailUrl,
                                    contentDescription = selectedAlbum!!.title,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black),
                                    contentScale = ContentScale.Crop
                                )

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = selectedAlbum!!.title,
                                        color = palette.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${albumTracks.size.takeIf { it > 0 } ?: selectedAlbum!!.trackCount} Lagu • $artistName",
                                        color = palette.secondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Action buttons for Album
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        if (albumTracks.isNotEmpty()) onMixPlayClick(albumTracks)
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("PUTAR ALBUM", color = palette.background, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                }

                                val activeBatch = OfflineManager.activeBatch.value
                                val isBatchDownloadingThis = activeBatch != null && activeBatch.batchId == selectedAlbum!!.id

                                Button(
                                    onClick = {
                                        if (albumTracks.isNotEmpty() && !isBatchDownloadingThis) {
                                            onDownloadAlbum(albumTracks, selectedAlbum!!.title)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(containerColor = if (isBatchDownloadingThis) palette.surfaceVariant else palette.secondary),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    if (isBatchDownloadingThis && activeBatch != null) {
                                        Text("${activeBatch.overallPercent}% UNDUH", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    } else {
                                        Icon(Icons.Default.Download, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("UNDUH 1 ALBUM", color = palette.background, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }

                            val currentBatch = OfflineManager.activeBatch.value
                            if (currentBatch != null && currentBatch.batchId == selectedAlbum!!.id) {
                                Spacer(modifier = Modifier.height(8.dp))
                                BatchDownloadBanner(batch = currentBatch, palette = palette)
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (isLoadingAlbumTracks) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = palette.primary)
                                }
                            } else if (albumTracks.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text("Daftar lagu album tidak ditemukan", color = palette.textSecondary, fontSize = 12.sp)
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(albumTracks) { track ->
                                        val normId = LocalMusicStore.normalizeId(track.id)
                                        val trackProgress = OfflineManager.activeDownloads[track.id] ?: OfflineManager.activeDownloads[normId]
                                        val isActivelyDownloading = trackProgress != null || downloadingIds.contains(track.id) || downloadingIds.contains(normId)
                                        val isOffline = !isActivelyDownloading && (offlineTrackIds.contains(track.id) || offlineTrackIds.contains(normId))
                                        val isFav = favoriteIds.contains(track.id) || favoriteIds.contains(normId)

                                        TrackItemCard(
                                            track = track,
                                            isSelected = activeTrack?.id == track.id,
                                            isFavorite = isFav,
                                            isOffline = isOffline,
                                            downloadProgress = trackProgress,
                                            isDownloading = isActivelyDownloading,
                                            palette = palette,
                                            onClick = { onTrackClick(track, albumTracks) },
                                            onFavoriteToggle = {
                                                if (onFavoriteToggle != null) onFavoriteToggle(track) else store.toggleFavorite(track)
                                            },
                                            onAddToPlaylist = { onAddToPlaylist(track) },
                                            onDownloadClick = { onDownloadTrack(track) }
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(palette.surfaceVariant)
                            .padding(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selectedTab == 0) palette.primary else palette.surfaceVariant)
                                .clickable { selectedTab = 0 }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "ALBUM & EP (${albums.size})",
                                color = if (selectedTab == 0) palette.background else palette.textSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (selectedTab == 1) palette.primary else palette.surfaceVariant)
                                .clickable { selectedTab = 1 }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "LAGU POPULER",
                                color = if (selectedTab == 1) palette.background else palette.textSecondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    if (selectedTab == 0) {
                        // Albums List
                        if (isLoadingAlbums) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.primary)
                            }
                        } else if (albums.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Album, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("Belum ada album ditemukan untuk $artistName", color = palette.textSecondary, fontSize = 12.sp)
                                }
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(albums) { album ->
                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { openAlbum(album) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            AsyncImage(
                                                model = album.thumbnailUrl,
                                                contentDescription = album.title,
                                                modifier = Modifier
                                                    .size(52.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color.Black),
                                                contentScale = ContentScale.Crop
                                            )

                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = album.title,
                                                    color = palette.textPrimary,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = "${if (album.trackCount > 0) "${album.trackCount} lagu • " else ""}${album.artist}",
                                                    color = palette.textSecondary,
                                                    fontSize = 11.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }

                                            Icon(
                                                Icons.Default.ChevronRight,
                                                contentDescription = "Lihat Album",
                                                tint = palette.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // Top Tracks List
                        if (isLoadingTracks) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = palette.primary)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(topTracks) { track ->
                                    val normId = LocalMusicStore.normalizeId(track.id)
                                    val trackProgress = OfflineManager.activeDownloads[track.id] ?: OfflineManager.activeDownloads[normId]
                                    val isActivelyDownloading = trackProgress != null || downloadingIds.contains(track.id) || downloadingIds.contains(normId)
                                    val isOffline = !isActivelyDownloading && (offlineTrackIds.contains(track.id) || offlineTrackIds.contains(normId) || OfflineManager.isTrackDownloaded(context, track.id))
                                    val isFav = favoriteIds.contains(track.id) || favoriteIds.contains(normId)

                                    TrackItemCard(
                                        track = track,
                                        isSelected = activeTrack?.id == track.id,
                                        isFavorite = isFav,
                                        isOffline = isOffline,
                                        downloadProgress = trackProgress,
                                        isDownloading = isActivelyDownloading,
                                        palette = palette,
                                        onClick = { onTrackClick(track, topTracks) },
                                        onFavoriteToggle = {
                                        if (onFavoriteToggle != null) onFavoriteToggle(track) else store.toggleFavorite(track)
                                    },
                                        onAddToPlaylist = { onAddToPlaylist(track) },
                                        onDownloadClick = { onDownloadTrack(track) }
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
}
