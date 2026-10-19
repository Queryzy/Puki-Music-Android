package com.puki.music.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.puki.music.core.DeezerRepository
import com.puki.music.core.OfflineManager
import com.puki.music.core.StreamResolver
import com.puki.music.data.LocalMusicStore
import com.puki.music.model.Album
import com.puki.music.model.DeezerArtist
import com.puki.music.model.Track
import com.puki.music.ui.theme.NuclearPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun SearchTab(
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    favoriteIds: Set<String> = emptySet(),
    offlineTrackIds: Set<String> = emptySet(),
    query: String,
    onQueryChange: (String) -> Unit,
    searchFilter: Int,
    onSearchFilterChange: (Int) -> Unit,
    officialTracks: List<Track>,
    onOfficialTracksChange: (List<Track>) -> Unit,
    youtubeTracks: List<Track>,
    onYoutubeTracksChange: (List<Track>) -> Unit,
    albumResults: List<Album>,
    onAlbumResultsChange: (List<Album>) -> Unit,
    topArtistResult: DeezerArtist?,
    onTopArtistResultChange: (DeezerArtist?) -> Unit,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoritesChanged: () -> Unit,
    onFavoriteToggle: ((Track) -> Unit)? = null,
    onArtistClick: (String) -> Unit,
    onAlbumClick: (Album) -> Unit = {}
) {
    var isSearching by remember { mutableStateOf(false) }
    var recentSearches by remember { mutableStateOf(store.getRecentSearches()) }
    val frequentlyPlayed = remember(activeTrack) { store.getFrequentlyPlayed(8) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun performSearch(targetQuery: String = query) {
        val q = targetQuery.trim()
        if (q.isBlank()) return
        onQueryChange(q)
        isSearching = true
        store.recordSearch(q)
        recentSearches = store.getRecentSearches()

        scope.launch {
            val dzTrackJob = async(Dispatchers.IO) { DeezerRepository.searchCleanTracks(context, q, limit = 30) }
            val ytTrackJob = async(Dispatchers.IO) { StreamResolver.searchTracks(q) }
            val dzAlbumJob = async(Dispatchers.IO) { DeezerRepository.searchCleanAlbums(context, q, limit = 20) }
            val dzArtistJob = async(Dispatchers.IO) { DeezerRepository.searchCleanArtists(context, q, limit = 5) }

            val dzTrackRes = dzTrackJob.await()
            val ytTrackRes = ytTrackJob.await()
            val dzAlbumRes = dzAlbumJob.await()
            val dzArtistRes = dzArtistJob.await()

            dzTrackRes.onSuccess { list ->
                onOfficialTracksChange(list.map { dz ->
                    Track(
                        id = "dz_${dz.id}",
                        title = dz.title,
                        artist = dz.artistName,
                        durationSeconds = dz.durationSeconds,
                        thumbnailUrl = dz.coverUrl
                    )
                })
            }

            ytTrackRes.onSuccess { list ->
                onYoutubeTracksChange(list)
            }

            dzAlbumRes.onSuccess { list ->
                if (list.isNotEmpty()) {
                    onAlbumResultsChange(list.map { dz ->
                        Album(
                            id = "dz_${dz.id}",
                            title = dz.title,
                            artist = dz.artistName,
                            trackCount = dz.trackCount.toLong(),
                            thumbnailUrl = dz.coverUrl,
                            url = "dz_${dz.id}"
                        )
                    })
                } else {
                    val ytAlbum = StreamResolver.searchAlbums(q)
                    ytAlbum.onSuccess { onAlbumResultsChange(it) }
                }
            }

            dzArtistRes.onSuccess { list ->
                onTopArtistResultChange(list.firstOrNull())
            }

            isSearching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Search Input Bar (With Clear button & Search icon)
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Telusuri musik...", color = palette.textSecondary) },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = "Search", tint = palette.primary)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = {
                        onQueryChange("")
                        onOfficialTracksChange(emptyList())
                        onYoutubeTracksChange(emptyList())
                        onAlbumResultsChange(emptyList())
                        onTopArtistResultChange(null)
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Hapus", tint = palette.textSecondary)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.primary,
                unfocusedBorderColor = palette.border,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                cursorColor = palette.primary
            ),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // If query is empty & no active search results: Show YouTube Music style Discovery & History
        if (query.isBlank() && officialTracks.isEmpty() && youtubeTracks.isEmpty() && albumResults.isEmpty()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Horizontal Recents Cards Carousel (Like YouTube Music)
                if (frequentlyPlayed.isNotEmpty()) {
                    item {
                        Column {
                            Text(
                                text = "TERAKHIR DIPUTAR",
                                color = palette.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(frequentlyPlayed) { tr ->
                                    Card(
                                        modifier = Modifier
                                            .width(130.dp)
                                            .clickable { onTrackSelected(tr, frequentlyPlayed) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                        border = BorderStroke(1.dp, palette.border)
                                    ) {
                                        Column(modifier = Modifier.padding(8.dp)) {
                                            AsyncImage(
                                                model = tr.thumbnailUrl,
                                                contentDescription = tr.title,
                                                modifier = Modifier
                                                    .size(114.dp)
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(Color.Black),
                                                contentScale = ContentScale.Crop
                                            )
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = tr.title,
                                                color = palette.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = tr.artist,
                                                color = palette.textSecondary,
                                                fontSize = 10.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Vertical Search History Queries (Clock Icon + Query + Insert Arrow)
                if (recentSearches.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PENCARIAN TERBARU",
                                color = palette.textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                            Text(
                                text = "Hapus Semua",
                                color = palette.tertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.clickable {
                                    store.clearAllRecentSearches()
                                    recentSearches = emptyList()
                                }
                            )
                        }
                    }

                    items(recentSearches) { hist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    onQueryChange(hist)
                                    performSearch(hist)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = "History",
                                    tint = palette.textSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = hist,
                                    color = palette.textPrimary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { onQueryChange(hist) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.NorthWest,
                                        contentDescription = "Insert",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        store.removeRecentSearch(hist)
                                        recentSearches = store.getRecentSearches()
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Hapus",
                                        tint = palette.textSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // 3. Quick Explore Discovery Buttons (New Releases, Charts, Rock/Pop)
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "JELAJAHI MUSIK",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onQueryChange("Top Hits 2026")
                                performSearch("Top Hits 2026")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.surfaceVariant),
                            border = BorderStroke(1.dp, palette.border),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.TrendingUp, contentDescription = null, tint = palette.primary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tangga Lagu", color = palette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Button(
                            onClick = {
                                onQueryChange("Indie Indonesia")
                                performSearch("Indie Indonesia")
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.surfaceVariant),
                            border = BorderStroke(1.dp, palette.border),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.Album, contentDescription = null, tint = palette.secondary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Rilis Baru", color = palette.textPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        } else {
            // Active Search Filter Chips
            val filterLabels = listOf("SEMUA", "LAGU RESMI (HD)", "YOUTUBE / REMIX", "ALBUM")
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filterLabels.size) { idx ->
                    val isSel = searchFilter == idx
                    FilterChip(
                        selected = isSel,
                        onClick = {
                            onSearchFilterChange(idx)
                            if (query.isNotBlank() && officialTracks.isEmpty() && youtubeTracks.isEmpty()) performSearch()
                        },
                        label = {
                            Text(
                                text = filterLabels[idx],
                                fontSize = 11.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = palette.primary,
                            selectedLabelColor = palette.background,
                            containerColor = palette.surfaceVariant,
                            labelColor = palette.textSecondary
                        ),
                        border = BorderStroke(1.dp, if (isSel) palette.primary else palette.border)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Row: Cari Ulang & Putar Semua
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { performSearch() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("CARI KATALOG", color = palette.background, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                val currentActiveList = when (searchFilter) {
                    1 -> officialTracks
                    2 -> youtubeTracks
                    else -> (officialTracks + youtubeTracks)
                }

                if (currentActiveList.isNotEmpty()) {
                    Button(
                        onClick = { onMixPlayClick(currentActiveList) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.secondary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("PUTAR SEMUA", color = palette.background, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isSearching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(color = palette.primary)
                        Text(
                            "Menghubungkan katalog Deezer & YouTube...",
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }
            } else {
                val displayedTracks = when (searchFilter) {
                    1 -> officialTracks
                    2 -> youtubeTracks
                    3 -> emptyList()
                    else -> (officialTracks + youtubeTracks)
                }

                if (displayedTracks.isEmpty() && albumResults.isEmpty() && searchFilter != 3) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Tidak ada hasil ditemukan untuk '$query'", color = palette.textSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. Matched Album Results
                        if (searchFilter == 0 || searchFilter == 3) {
                            if (albumResults.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "ALBUM RESMI TERKAIT (${albumResults.size})",
                                        color = palette.primary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(albumResults) { album ->
                                            Card(
                                                modifier = Modifier
                                                    .width(135.dp)
                                                    .clickable { onAlbumClick(album) },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                                border = BorderStroke(1.dp, palette.border)
                                            ) {
                                                Column(modifier = Modifier.padding(8.dp)) {
                                                    AsyncImage(
                                                        model = album.thumbnailUrl,
                                                        contentDescription = album.title,
                                                        modifier = Modifier
                                                            .size(119.dp)
                                                            .clip(RoundedCornerShape(6.dp))
                                                            .background(Color.Black),
                                                        contentScale = ContentScale.Crop
                                                    )
                                                    Spacer(modifier = Modifier.height(6.dp))
                                                    Text(
                                                        text = album.title,
                                                        color = palette.textPrimary,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 12.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = album.artist,
                                                        color = palette.textSecondary,
                                                        fontSize = 10.sp,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(10.dp))
                                }
                            }
                        }

                        // 2. Matched Artist Card
                        if ((searchFilter == 0 || searchFilter == 1) && topArtistResult != null) {
                            item {
                                val art = topArtistResult
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onArtistClick(art.name) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                    border = BorderStroke(1.dp, palette.primary)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        AsyncImage(
                                            model = art.pictureUrl,
                                            contentDescription = art.name,
                                            modifier = Modifier
                                                .size(56.dp)
                                                .clip(CircleShape)
                                                .border(2.dp, palette.primary, CircleShape),
                                            contentScale = ContentScale.Crop
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "PROFIL ARTIS RESMI",
                                                color = palette.primary,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                letterSpacing = 1.sp
                                            )
                                            Text(
                                                text = art.name,
                                                color = palette.textPrimary,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 15.sp
                                            )
                                            Text(
                                                text = "${art.albumCount} Album • Buka Diskografi",
                                                color = palette.textSecondary,
                                                fontSize = 11.sp
                                            )
                                        }
                                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = palette.primary)
                                    }
                                }
                            }
                        }

                        // 3. Track List Results
                        items(displayedTracks) { track ->
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
                                playCount = store.getPlayCount(track.id),
                                palette = palette,
                                onClick = { onTrackSelected(track, displayedTracks) },
                                onArtistClick = onArtistClick,
                                onFavoriteToggle = {
                                    if (onFavoriteToggle != null) {
                                        onFavoriteToggle(track)
                                    } else {
                                        store.toggleFavorite(track)
                                        onFavoritesChanged()
                                    }
                                },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onDownloadClick = { onDownloadClick(track) }
                            )
                        }
                    }
                }
            }
        }
    }
}
