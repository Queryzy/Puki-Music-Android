package com.puki.nuclear.ui

import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
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
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import coil.compose.AsyncImage
import com.google.common.util.concurrent.ListenableFuture
import com.puki.nuclear.core.OfflineManager
import com.puki.nuclear.core.PlaybackService
import com.puki.nuclear.core.StreamResolver
import com.puki.nuclear.data.LocalMusicStore
import com.puki.nuclear.data.Playlist
import com.puki.nuclear.model.Album
import com.puki.nuclear.model.Track
import com.puki.nuclear.ui.components.*
import com.puki.nuclear.ui.theme.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)
    private lateinit var store: LocalMusicStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalMusicStore(this)

        val sessionToken = SessionToken(
            this,
            ComponentName(this, PlaybackService::class.java)
        )
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
        }, ContextCompat.getMainExecutor(this))

        setContent {
            var selectedThemeKey by remember { mutableStateOf(store.getThemeName()) }
            val themeId = NuclearThemeId.fromKey(selectedThemeKey)

            NuclearTheme(themeId = themeId) { palette ->
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = palette.background
                ) {
                    NuclearMainScreen(
                        mediaController = mediaController,
                        store = store,
                        palette = palette,
                        currentThemeId = themeId,
                        onThemeChanged = { newTheme ->
                            selectedThemeKey = newTheme.idKey
                            store.saveThemeName(newTheme.idKey)
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
    }
}

@Composable
fun NuclearMainScreen(
    mediaController: MediaController?,
    store: LocalMusicStore,
    palette: NuclearPalette,
    currentThemeId: NuclearThemeId,
    onThemeChanged: (NuclearThemeId) -> Unit
) {
    var currentTab by remember { mutableIntStateOf(0) }
    var activeTrack by remember { mutableStateOf<Track?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var isResolvingStream by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Mix Play Queue & State
    var isMixPlayActive by remember { mutableStateOf(false) }
    var currentQueue by remember { mutableStateOf<List<Track>>(emptyList()) }

    // Dialog & Modal states
    var showThemeDialog by remember { mutableStateOf(false) }
    var showExpandedPlayer by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var playlistToAddTo by remember { mutableStateOf<Track?>(null) }
    var activePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var viewingArtist by remember { mutableStateOf<String?>(null) }

    // Reactive data lists
    var favoritesList by remember { mutableStateOf(store.getFavorites()) }
    var offlineTracks by remember { mutableStateOf(store.getOfflineTracks()) }
    var playlistsList by remember { mutableStateOf(store.getPlaylists()) }
    val downloadingTrackIds = remember { mutableStateListOf<String>() }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Playback logic: handles both local offline and remote CDN streaming
    fun playTrack(track: Track, newQueue: List<Track>? = null) {
        if (isResolvingStream) return
        activeTrack = track
        errorMessage = null
        if (newQueue != null) {
            currentQueue = newQueue
        }
        store.recordPlay(track)

        val localFile = OfflineManager.getLocalAudioFile(context, track.id)
        if (localFile != null && localFile.exists()) {
            // 100% Offline Playback: Play instantly from local storage, zero latency
            mediaController?.let { player ->
                val mediaItem = MediaItem.fromUri(Uri.fromFile(localFile))
                player.setMediaItem(mediaItem)
                player.prepare()
                player.play()
            }
            return
        }

        // Online Stream Playback
        isResolvingStream = true
        scope.launch {
            val streamRes = StreamResolver.resolveAudioStreamDetailed(track.id)
            streamRes.onSuccess { detailed ->
                activeTrack = track.copy(
                    streamUrl = detailed.streamUrl,
                    viewCount = detailed.viewCount.takeIf { it > 0 } ?: track.viewCount,
                    likeCount = detailed.likeCount.takeIf { it > 0 } ?: track.likeCount
                )
                mediaController?.let { player ->
                    val mediaItem = MediaItem.fromUri(detailed.streamUrl)
                    player.setMediaItem(mediaItem)
                    player.prepare()
                    player.play()
                }
            }.onFailure {
                errorMessage = "Gagal resolve audio CDN: ${it.localizedMessage}"
            }
            isResolvingStream = false
        }
    }

    fun startMixPlay(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        isMixPlayActive = true
        currentQueue = shuffled
        playTrack(shuffled.first(), shuffled)
        Toast.makeText(context, "Mix Play aktif: ${shuffled.size} lagu diacak", Toast.LENGTH_SHORT).show()
    }

    fun playNextTrack() {
        if (currentQueue.isEmpty()) return
        val currentIdx = currentQueue.indexOfFirst { it.id == activeTrack?.id }
        val nextIdx = if (currentIdx in 0 until currentQueue.size - 1) currentIdx + 1 else 0
        playTrack(currentQueue[nextIdx], currentQueue)
    }

    fun playPreviousTrack() {
        if (currentQueue.isEmpty()) {
            mediaController?.seekTo(0)
            return
        }
        val pos = mediaController?.currentPosition ?: 0L
        if (pos > 3000L) {
            mediaController?.seekTo(0)
            return
        }
        val currentIdx = currentQueue.indexOfFirst { it.id == activeTrack?.id }
        val prevIdx = if (currentIdx > 0) currentIdx - 1 else currentQueue.size - 1
        playTrack(currentQueue[prevIdx], currentQueue)
    }

    fun toggleOfflineDownload(track: Track) {
        val isOffline = OfflineManager.isTrackDownloaded(context, track.id)
        if (isOffline) {
            OfflineManager.deleteDownloadedTrack(context, track.id)
            offlineTracks = store.getOfflineTracks()
            Toast.makeText(context, "Dihapus dari penyimpanan offline", Toast.LENGTH_SHORT).show()
        } else {
            if (OfflineManager.activeDownloads.containsKey(track.id)) return
            Toast.makeText(context, "Mengunduh ${track.title}...", Toast.LENGTH_SHORT).show()
            scope.launch {
                val res = OfflineManager.downloadTrackWithProgress(context, track)
                res.onSuccess {
                    offlineTracks = store.getOfflineTracks()
                    Toast.makeText(context, "Tersimpan offline: ${track.title}", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Gagal mengunduh: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun downloadPlaylist(tracks: List<Track>, playlistName: String, playlistId: String = "") {
        val toDownload = tracks.filterNot { OfflineManager.isTrackDownloaded(context, it.id) }
        if (toDownload.isEmpty()) {
            Toast.makeText(context, "Semua lagu di '$playlistName' sudah terunduh offline!", Toast.LENGTH_SHORT).show()
            return
        }
        val batchKey = playlistId.ifBlank { playlistName }
        Toast.makeText(context, "Mengunduh ${toDownload.size} lagu di '$playlistName'...", Toast.LENGTH_SHORT).show()
        scope.launch {
            val completed = OfflineManager.downloadBatchWithProgress(
                context = context,
                batchId = batchKey,
                batchTitle = playlistName,
                tracks = toDownload
            ) {
                offlineTracks = store.getOfflineTracks()
            }
            offlineTracks = store.getOfflineTracks()
            Toast.makeText(context, "Selesai unduh '$playlistName' ($completed/${toDownload.size} lagu tersimpan offline)", Toast.LENGTH_LONG).show()
        }
    }

    // Auto-advance for Mix Play when song ends
    DisposableEffect(mediaController, currentQueue, isMixPlayActive, activeTrack) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (currentQueue.isNotEmpty()) {
                        val currentIdx = currentQueue.indexOfFirst { it.id == activeTrack?.id }
                        val nextIdx = if (currentIdx in 0 until currentQueue.size - 1) currentIdx + 1 else 0
                        val nextTrack = currentQueue[nextIdx]
                        playTrack(nextTrack, currentQueue)
                    }
                }
            }
        }
        mediaController?.addListener(listener)
        isPlaying = mediaController?.isPlaying == true
        onDispose {
            mediaController?.removeListener(listener)
        }
    }

    Scaffold(
        containerColor = palette.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(if (isPlaying) palette.success else palette.secondary)
                        )
                        Text(
                            text = "PUKI MUSIC",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Black,
                            fontSize = 15.sp,
                            letterSpacing = 1.sp
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { showThemeDialog = true }) {
                            Icon(
                                Icons.Default.Palette,
                                contentDescription = "Themes",
                                tint = palette.secondary
                            )
                        }
                    }
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(palette.error.copy(alpha = 0.2f))
                            .border(1.dp, palette.error, RoundedCornerShape(6.dp))
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = errorMessage ?: "",
                            color = palette.error,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { errorMessage = null }) {
                            Text("Tutup", color = palette.textPrimary, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        bottomBar = {
            Column {
                if (activeTrack != null) {
                    MiniPlayerBar(
                        track = activeTrack!!,
                        isPlaying = isPlaying,
                        isResolving = isResolvingStream,
                        palette = palette,
                        onClick = { showExpandedPlayer = true },
                        onPlayPauseToggle = {
                            mediaController?.let {
                                if (it.isPlaying) it.pause() else it.play()
                            }
                        },
                        onNextClick = { playNextTrack() }
                    )
                }

                NavigationBar(
                    containerColor = palette.surface,
                    contentColor = palette.textPrimary,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.Home, contentDescription = "Dashboard") },
                        label = { Text("Dashboard", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.primary,
                            selectedTextColor = palette.primary,
                            unselectedIconColor = palette.textSecondary,
                            unselectedTextColor = palette.textSecondary,
                            indicatorColor = palette.surfaceVariant
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = { currentTab = 1 },
                        icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                        label = { Text("Search", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.primary,
                            selectedTextColor = palette.primary,
                            unselectedIconColor = palette.textSecondary,
                            unselectedTextColor = palette.textSecondary,
                            indicatorColor = palette.surfaceVariant
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 2,
                        onClick = {
                            favoritesList = store.getFavorites()
                            offlineTracks = store.getOfflineTracks()
                            currentTab = 2
                        },
                        icon = { Icon(Icons.Default.Favorite, contentDescription = "Favorites") },
                        label = { Text("Library", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.primary,
                            selectedTextColor = palette.primary,
                            unselectedIconColor = palette.textSecondary,
                            unselectedTextColor = palette.textSecondary,
                            indicatorColor = palette.surfaceVariant
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 3,
                        onClick = {
                            playlistsList = store.getPlaylists()
                            currentTab = 3
                        },
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Playlists") },
                        label = { Text("Playlists", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.primary,
                            selectedTextColor = palette.primary,
                            unselectedIconColor = palette.textSecondary,
                            unselectedTextColor = palette.textSecondary,
                            indicatorColor = palette.surfaceVariant
                        )
                    )
                    NavigationBarItem(
                        selected = currentTab == 4,
                        onClick = { currentTab = 4 },
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings", fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.primary,
                            selectedTextColor = palette.primary,
                            unselectedIconColor = palette.textSecondary,
                            unselectedTextColor = palette.textSecondary,
                            indicatorColor = palette.surfaceVariant
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentTab) {
                0 -> DashboardTab(
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadClick = { toggleOfflineDownload(it) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onArtistClick = { viewingArtist = it }
                )
                1 -> SearchTab(
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    onTrackSelected = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadClick = { toggleOfflineDownload(it) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onArtistClick = { viewingArtist = it }
                )
                2 -> LibraryTab(
                    favorites = favoritesList,
                    offlineTracks = offlineTracks,
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadClick = { toggleOfflineDownload(it) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onArtistClick = { viewingArtist = it },
                    onClearAllOffline = {
                        OfflineManager.deleteAllOfflineTracks(context)
                        offlineTracks = store.getOfflineTracks()
                        Toast.makeText(context, "Penyimpanan musik offline telah dibersihkan", Toast.LENGTH_SHORT).show()
                    }
                )
                3 -> PlaylistsTab(
                    playlists = playlistsList,
                    activePlaylist = activePlaylist,
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    onSelectPlaylist = { activePlaylist = it },
                    onBackToPlaylists = {
                        activePlaylist = null
                        playlistsList = store.getPlaylists()
                    },
                    onNewPlaylistClick = { showNewPlaylistDialog = true },
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadTrack = { toggleOfflineDownload(it) },
                    onDownloadPlaylist = { tracks, name -> downloadPlaylist(tracks, name) },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onArtistClick = { viewingArtist = it },
                    onDeletePlaylist = { pl ->
                        store.deletePlaylist(pl.id)
                        playlistsList = store.getPlaylists()
                        if (activePlaylist?.id == pl.id) activePlaylist = null
                    }
                )
                4 -> SettingsTab(
                    palette = palette,
                    context = context,
                    offlineTracks = offlineTracks,
                    onClearAllOffline = {
                        OfflineManager.deleteAllOfflineTracks(context)
                        offlineTracks = store.getOfflineTracks()
                        Toast.makeText(context, "Seluruh musik offline dibersihkan", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    if (showThemeDialog) {
        ThemeChooserDialog(
            currentThemeId = currentThemeId,
            palette = palette,
            onDismiss = { showThemeDialog = false },
            onSelectTheme = {
                onThemeChanged(it)
                showThemeDialog = false
            }
        )
    }

    if (showNewPlaylistDialog) {
        NewPlaylistDialog(
            palette = palette,
            onDismiss = { showNewPlaylistDialog = false },
            onCreate = { name ->
                store.createPlaylist(name)
                playlistsList = store.getPlaylists()
                showNewPlaylistDialog = false
                Toast.makeText(context, "Playlist '$name' dibuat", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (playlistToAddTo != null) {
        AddToPlaylistDialog(
            track = playlistToAddTo!!,
            store = store,
            palette = palette,
            onDismiss = { playlistToAddTo = null },
            onAdded = { plName ->
                playlistsList = store.getPlaylists()
                playlistToAddTo = null
                Toast.makeText(context, "Ditambahkan ke $plName", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showExpandedPlayer && activeTrack != null) {
        val isOffline = OfflineManager.isTrackDownloaded(context, activeTrack!!.id)
        val isDownloading = downloadingTrackIds.contains(activeTrack!!.id)

        ExpandedPlayerModal(
            track = activeTrack!!,
            mediaController = mediaController,
            isPlaying = isPlaying,
            isMixPlay = isMixPlayActive,
            isOffline = isOffline,
            isDownloading = isDownloading,
            palette = palette,
            store = store,
            onToggleMixPlay = {
                isMixPlayActive = !isMixPlayActive
                val msg = if (isMixPlayActive) "Mix Play Aktif (Autoplay Acak)" else "Mix Play Dinonaktifkan"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onDownloadClick = {
                toggleOfflineDownload(activeTrack!!)
            },
            onAddToPlaylist = {
                playlistToAddTo = activeTrack
            },
            onFavoritesChanged = {
                favoritesList = store.getFavorites()
            },
            onArtistClick = { artist ->
                showExpandedPlayer = false
                viewingArtist = artist
            },
            onNextTrack = { playNextTrack() },
            onPreviousTrack = { playPreviousTrack() },
            onClose = { showExpandedPlayer = false }
        )
    }

    if (viewingArtist != null) {
        ArtistAlbumModal(
            artistName = viewingArtist!!,
            palette = palette,
            store = store,
            downloadingIds = downloadingTrackIds,
            activeTrack = activeTrack,
            onTrackClick = { track, list -> playTrack(track, list) },
            onMixPlayClick = { list -> startMixPlay(list) },
            onDownloadTrack = { toggleOfflineDownload(it) },
            onDownloadAlbum = { tracks, name -> downloadPlaylist(tracks, name) },
            onAddToPlaylist = { playlistToAddTo = it },
            onClose = { viewingArtist = null }
        )
    }
}

// ── Tab 0: Personalized Dashboard ──────────────────────────────────────────

@Composable
fun DashboardTab(
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoritesChanged: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null
) {
    val recSeed = remember { store.getRecommendationQuery() }
    var trendingTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(recSeed) {
        isLoading = true
        val result = StreamResolver.getDashboardTracks(recSeed)
        result.onSuccess {
            trendingTracks = it
        }
        isLoading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Personalized Recommendation Banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(palette.success)
                            )
                            Text(
                                text = "PERSONALIZED FEED",
                                color = palette.success,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        IconButton(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    val newSeed = store.getRecommendationQuery()
                                    val res = StreamResolver.getDashboardTracks(newSeed)
                                    res.onSuccess { trendingTracks = it }
                                    isLoading = false
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = palette.primary, modifier = Modifier.size(18.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Berdasarkan Aktivitas & Selera Musikmu",
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Seed: \"$recSeed\" • Mengadaptasi apa yang sering kamu cari & dengarkan.",
                        color = palette.secondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        // ── Card: Mix Saya (Berdasarkan Yang Sering Diputar) ──
        item {
            val myMixTracks = remember(activeTrack) { store.getMyMixTracks() }
            val freqTracks = remember(activeTrack) { store.getFrequentlyPlayed(10) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surface),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, palette.primary.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(palette.primary.copy(alpha = 0.2f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Shuffle,
                                    contentDescription = null,
                                    tint = palette.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = "MIX HARIAN SAYA",
                                color = palette.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        val trackCount = if (myMixTracks.isNotEmpty()) myMixTracks.size else trendingTracks.size
                        Text(
                            text = "$trackCount lagu",
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Mix Otomatis Berdasarkan Yang Sering Kamu Play",
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (freqTracks.isNotEmpty()) {
                            "Diracik dari artis & lagu riwayat putar (${freqTracks.take(2).joinToString { it.artist }}), favorit, dan offline."
                        } else {
                            "Otomatis meracik lagu favorit & riwayat putar kamu saat ini untuk dimainkan tanpa henti."
                        },
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val candidate = if (myMixTracks.isNotEmpty()) myMixTracks else trendingTracks
                            if (candidate.isNotEmpty()) {
                                onMixPlayClick(candidate)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(vertical = 10.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = palette.background, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "PUTAR MIX SAYA SEKARANG",
                            color = palette.background,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // Section Header with Mix Play Button
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TRENDING & REKOMENDASI",
                    color = palette.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                if (trendingTracks.isNotEmpty()) {
                    Button(
                        onClick = { onMixPlayClick(trendingTracks) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.background, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MIX PLAY", color = palette.background, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = palette.primary)
                }
            }
        } else {
            items(trendingTracks) { track ->
                val isOffline = OfflineManager.isTrackDownloaded(context, track.id)
                val trackProgress = OfflineManager.activeDownloads[track.id]
                val isDownloading = (trackProgress != null || downloadingIds.contains(track.id)) && !isOffline

                TrackItemCard(
                    track = track,
                    isSelected = activeTrack?.id == track.id,
                    isFavorite = store.isFavorite(track.id),
                    isOffline = isOffline,
                    downloadProgress = trackProgress,
                    isDownloading = isDownloading,
                    playCount = store.getPlayCount(track.id),
                    palette = palette,
                    onClick = { onTrackClick(track, trendingTracks) },
                    onArtistClick = onArtistClick,
                    onFavoriteToggle = {
                        store.toggleFavorite(track)
                        onFavoritesChanged()
                    },
                    onAddToPlaylist = { onAddToPlaylist(track) },
                    onDownloadClick = { onDownloadClick(track) }
                )
            }
        }
    }
}

// ── Tab 1: Search ───────────────────────────────────────────────────────────

@Composable
fun SearchTab(
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    onTrackSelected: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoritesChanged: () -> Unit,
    onArtistClick: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var searchMode by remember { mutableIntStateOf(0) } // 0 = Lagu, 1 = Album & EP
    var searchResults by remember { mutableStateOf<List<Track>>(emptyList()) }
    var albumResults by remember { mutableStateOf<List<Album>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun performSearch() {
        if (query.isBlank()) return
        isSearching = true
        store.recordSearch(query.trim())
        scope.launch {
            if (searchMode == 0) {
                val res = StreamResolver.searchTracks(query)
                res.onSuccess {
                    searchResults = it
                }
            } else {
                val res = StreamResolver.searchAlbums(query)
                res.onSuccess {
                    albumResults = it
                }
            }
            isSearching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (searchMode == 0) "Cari lagu, artis, atau judul..." else "Cari nama album atau penyanyi...", color = palette.textSecondary) },
            singleLine = true,
            trailingIcon = {
                IconButton(onClick = { performSearch() }) {
                    Icon(Icons.Default.Search, contentDescription = "Cari", tint = palette.primary)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = palette.primary,
                unfocusedBorderColor = palette.border,
                focusedTextColor = palette.textPrimary,
                unfocusedTextColor = palette.textPrimary,
                cursorColor = palette.primary
            ),
            shape = RoundedCornerShape(8.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Mode Selector: [ LAGU ] vs [ ALBUM & EP ]
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
                    .background(if (searchMode == 0) palette.primary else palette.surfaceVariant)
                    .clickable {
                        searchMode = 0
                        if (query.isNotBlank() && searchResults.isEmpty()) performSearch()
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "LAGU",
                    color = if (searchMode == 0) palette.background else palette.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (searchMode == 1) palette.primary else palette.surfaceVariant)
                    .clickable {
                        searchMode = 1
                        if (query.isNotBlank() && albumResults.isEmpty()) performSearch()
                    }
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "ALBUM & EP",
                    color = if (searchMode == 1) palette.background else palette.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

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
                Text(if (searchMode == 0) "CARI STREAM LAGU" else "CARI DISKOGRAFI ALBUM", color = palette.background, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            if (searchMode == 0 && searchResults.isNotEmpty()) {
                Button(
                    onClick = { onMixPlayClick(searchResults) },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.secondary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("MIX", color = palette.background, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (isSearching) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = palette.primary)
            }
        } else if (searchMode == 0) {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(searchResults) { track ->
                    val isOffline = OfflineManager.isTrackDownloaded(context, track.id)
                    val trackProgress = OfflineManager.activeDownloads[track.id]
                    val isDownloading = (trackProgress != null || downloadingIds.contains(track.id)) && !isOffline

                    TrackItemCard(
                        track = track,
                        isSelected = activeTrack?.id == track.id,
                        isFavorite = store.isFavorite(track.id),
                        isOffline = isOffline,
                        downloadProgress = trackProgress,
                        isDownloading = isDownloading,
                        playCount = store.getPlayCount(track.id),
                        palette = palette,
                        onClick = { onTrackSelected(track, searchResults) },
                        onArtistClick = onArtistClick,
                        onFavoriteToggle = {
                            store.toggleFavorite(track)
                            onFavoritesChanged()
                        },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onDownloadClick = { onDownloadClick(track) }
                    )
                }
            }
        } else {
            // Album & EP Results
            if (albumResults.isEmpty() && query.isNotBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Belum ada album ditemukan", color = palette.textSecondary, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(albumResults) { album ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArtistClick(album.artist) },
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
                                        .size(54.dp)
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
                                        color = palette.secondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Icon(
                                    Icons.Default.ChevronRight,
                                    contentDescription = "Buka Album",
                                    tint = palette.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab 2: Library (Favorites + Offline Storage) ─────────────────────────────

@Composable
fun LibraryTab(
    favorites: List<Track>,
    offlineTracks: List<Track>,
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoritesChanged: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onClearAllOffline: (() -> Unit)? = null
) {
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Favorit, 1 = Offline
    var showClearOfflineDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Section Segment Switcher
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
                    .background(if (selectedSection == 0) palette.primary else palette.surfaceVariant)
                    .clickable { selectedSection = 0 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "FAVORIT (${favorites.size})",
                    color = if (selectedSection == 0) palette.background else palette.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (selectedSection == 1) palette.primary else palette.surfaceVariant)
                    .clickable { selectedSection = 1 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "OFFLINE (${offlineTracks.size})",
                    color = if (selectedSection == 1) palette.background else palette.textSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (selectedSection == 0) {
            // Favorites Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "LAGU FAVORIT",
                    color = palette.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (favorites.isNotEmpty()) {
                    Button(
                        onClick = { onMixPlayClick(favorites) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.background, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MIX PLAY", color = palette.background, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (favorites.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FavoriteBorder, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Belum Ada Lagu Favorit", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(favorites) { track ->
                        val isOffline = OfflineManager.isTrackDownloaded(context, track.id)
                        val trackProgress = OfflineManager.activeDownloads[track.id]
                        val isDownloading = (trackProgress != null || downloadingIds.contains(track.id)) && !isOffline

                        TrackItemCard(
                            track = track,
                            isSelected = activeTrack?.id == track.id,
                            isFavorite = true,
                            isOffline = isOffline,
                            downloadProgress = trackProgress,
                            isDownloading = isDownloading,
                            palette = palette,
                            onClick = { onTrackClick(track, favorites) },
                            onArtistClick = onArtistClick,
                            onFavoriteToggle = {
                                store.toggleFavorite(track)
                                onFavoritesChanged()
                            },
                            onAddToPlaylist = { onAddToPlaylist(track) },
                            onDownloadClick = { onDownloadClick(track) }
                        )
                    }
                }
            }
        } else {
            // Offline Storage Section & Storage Manager Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                border = BorderStroke(1.dp, palette.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "💾 PENYIMPANAN OFFLINE",
                            color = palette.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${OfflineManager.getFormattedOfflineUsage(context)} • ${offlineTracks.size} lagu",
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Text(
                            text = "Ruang bebas perangkat: ${OfflineManager.getFormattedAvailableDeviceStorage(context)}",
                            color = palette.textSecondary,
                            fontSize = 10.sp
                        )
                    }

                    if (offlineTracks.isNotEmpty() && onClearAllOffline != null) {
                        Button(
                            onClick = { showClearOfflineDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.error.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, palette.error),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("BERSIHKAN", color = palette.error, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (showClearOfflineDialog) {
                AlertDialog(
                    onDismissRequest = { showClearOfflineDialog = false },
                    title = { Text("Bersihkan Musik Offline?", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
                    text = { Text("Hapus seluruh ${offlineTracks.size} lagu offline dari penyimpanan perangkat? File audio akan dihapus.", color = palette.textSecondary, fontSize = 12.sp) },
                    confirmButton = {
                        Button(
                            onClick = {
                                showClearOfflineDialog = false
                                onClearAllOffline?.invoke()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                        ) {
                            Text("Hapus Semua", color = palette.background, fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showClearOfflineDialog = false }) {
                            Text("Batal", color = palette.textSecondary)
                        }
                    },
                    containerColor = palette.surfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DAFTAR LAGU TERSIMPAN",
                    color = palette.success,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                if (offlineTracks.isNotEmpty()) {
                    Button(
                        onClick = { onMixPlayClick(offlineTracks) },
                        colors = ButtonDefaults.buttonColors(containerColor = palette.success),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.background, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("MIX OFFLINE", color = palette.background, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (offlineTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.DownloadForOffline, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Belum Ada Musik Offline", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Tekan ikon unduh pada lagu untuk memutar tanpa internet.", color = palette.textSecondary, fontSize = 12.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(offlineTracks) { track ->
                        val fileSize = OfflineManager.formatBytes(OfflineManager.getTrackFileSizeBytes(context, track.id))
                        val playCount = store.getPlayCount(track.id)

                        TrackItemCard(
                            track = track,
                            isSelected = activeTrack?.id == track.id,
                            isFavorite = store.isFavorite(track.id),
                            isOffline = true,
                            isDownloading = false,
                            playCount = playCount,
                            fileSizeText = fileSize,
                            palette = palette,
                            onClick = { onTrackClick(track, offlineTracks) },
                            onArtistClick = onArtistClick,
                            onFavoriteToggle = {
                                store.toggleFavorite(track)
                                onFavoritesChanged()
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

// ── Tab 3: Playlists ────────────────────────────────────────────────────────

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    activePlaylist: Playlist?,
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    onSelectPlaylist: (Playlist) -> Unit,
    onBackToPlaylists: () -> Unit,
    onNewPlaylistClick: () -> Unit,
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadTrack: (Track) -> Unit,
    onDownloadPlaylist: (List<Track>, String) -> Unit,
    onFavoritesChanged: () -> Unit,
    onArtistClick: ((String) -> Unit)? = null,
    onDeletePlaylist: (Playlist) -> Unit
) {
    val context = LocalContext.current

    if (activePlaylist != null) {
        val tracks = remember(activePlaylist.id) { store.getPlaylistTracks(activePlaylist.id) }
        var currentTracks by remember { mutableStateOf(tracks) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBackToPlaylists) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = palette.textPrimary)
                    }
                    Column {
                        Text(
                            text = activePlaylist.name,
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "${currentTracks.size} lagu",
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                val activeBatch = OfflineManager.activeBatch.value
                val isBatchDownloadingThis = activeBatch != null && activeBatch.batchId == activePlaylist.id

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (currentTracks.isNotEmpty()) {
                        IconButton(onClick = { onDownloadPlaylist(currentTracks, activePlaylist.name) }) {
                            if (isBatchDownloadingThis && activeBatch != null) {
                                Text("${activeBatch.overallPercent}%", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            } else {
                                Icon(Icons.Default.Download, contentDescription = "Download All", tint = palette.primary)
                            }
                        }
                        IconButton(onClick = { onMixPlayClick(currentTracks) }) {
                            Icon(Icons.Default.Shuffle, contentDescription = "Mix", tint = palette.primary)
                        }
                    }
                    IconButton(onClick = { onDeletePlaylist(activePlaylist) }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete", tint = palette.error)
                    }
                }
            }

            val currentBatch = OfflineManager.activeBatch.value
            if (currentBatch != null && currentBatch.batchId == activePlaylist.id) {
                Spacer(modifier = Modifier.height(6.dp))
                BatchDownloadBanner(batch = currentBatch, palette = palette)
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (currentTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Playlist masih kosong", color = palette.textSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(currentTracks) { track ->
                        val isOffline = OfflineManager.isTrackDownloaded(context, track.id)
                        val trackProgress = OfflineManager.activeDownloads[track.id]
                        val isActivelyDownloading = (trackProgress != null || downloadingIds.contains(track.id)) && !isOffline

                        TrackItemCard(
                            track = track,
                            isSelected = activeTrack?.id == track.id,
                            isFavorite = store.isFavorite(track.id),
                            isOffline = isOffline,
                            downloadProgress = trackProgress,
                            isDownloading = isActivelyDownloading,
                            playCount = store.getPlayCount(track.id),
                            palette = palette,
                            onClick = { onTrackClick(track, currentTracks) },
                            onArtistClick = onArtistClick,
                            onFavoriteToggle = {
                                store.toggleFavorite(track)
                                onFavoritesChanged()
                            },
                            onDownloadClick = { onDownloadTrack(track) },
                            trailingAction = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { onDownloadTrack(track) }, modifier = Modifier.size(36.dp)) {
                                        if (trackProgress != null && !isOffline) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp)) {
                                                CircularProgressIndicator(
                                                    progress = { (trackProgress.coerceIn(0, 100)) / 100f },
                                                    modifier = Modifier.size(24.dp),
                                                    color = palette.primary,
                                                    strokeWidth = 2.5.dp,
                                                    trackColor = palette.surfaceVariant
                                                )
                                                Text(
                                                    text = "$trackProgress%",
                                                    fontSize = 7.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = palette.primary,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        } else if (isActivelyDownloading) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = palette.primary, strokeWidth = 2.dp)
                                        } else {
                                            Icon(
                                                imageVector = if (isOffline) Icons.Default.DownloadDone else Icons.Default.Download,
                                                contentDescription = if (isOffline) "Offline" else "Unduh",
                                                tint = if (isOffline) palette.success else palette.textSecondary,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                    IconButton(onClick = {
                                        store.removeTrackFromPlaylist(activePlaylist.id, track.id)
                                        currentTracks = store.getPlaylistTracks(activePlaylist.id)
                                    }, modifier = Modifier.size(34.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Hapus", tint = palette.textSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "PLAYLIST SAYA",
                    color = palette.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Button(
                    onClick = onNewPlaylistClick,
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("BARU", color = palette.background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (playlists.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = palette.textSecondary, modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Belum Ada Playlist", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(playlists) { pl ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectPlaylist(pl) },
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                            border = androidx.compose.foundation.BorderStroke(1.dp, palette.border)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(palette.surface),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.QueueMusic, contentDescription = null, tint = palette.secondary)
                                    }
                                    Column {
                                        Text(pl.name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                        Text("${pl.trackCount} lagu", color = palette.textSecondary, fontSize = 12.sp)
                                    }
                                }

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (pl.trackCount > 0) {
                                        IconButton(
                                            onClick = {
                                                val plTracks = store.getPlaylistTracks(pl.id)
                                                onDownloadPlaylist(plTracks, pl.name)
                                            }
                                        ) {
                                            Icon(
                                                Icons.Default.Download,
                                                contentDescription = "Unduh 1 Playlist",
                                                tint = palette.primary
                                            )
                                        }
                                    }
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = palette.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Tab 4: Settings ──────────────────────────────────────────────────────────

@Composable
fun SettingsTab(
    palette: NuclearPalette,
    context: android.content.Context,
    offlineTracks: List<Track>,
    onClearAllOffline: () -> Unit
) {
    var showClearDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Storage Usage Card
        item {
            Text(
                text = "PENYIMPANAN PERANGKAT",
                color = palette.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                border = BorderStroke(1.dp, palette.border)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Musik Offline",
                                color = palette.textSecondary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = OfflineManager.getFormattedOfflineUsage(context),
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Lagu Tersimpan",
                                color = palette.textSecondary,
                                fontSize = 11.sp
                            )
                            Text(
                                text = "${offlineTracks.size} lagu",
                                color = palette.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.border.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sisa Ruang Bebas HP",
                            color = palette.textSecondary,
                            fontSize = 12.sp
                        )
                        Text(
                            text = OfflineManager.getFormattedAvailableDeviceStorage(context),
                            color = palette.success,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    if (offlineTracks.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showClearDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.error.copy(alpha = 0.15f)),
                            border = BorderStroke(1.dp, palette.error),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = palette.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "BERSIHKAN SELURUH MUSIK OFFLINE",
                                color = palette.error,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Streaming Engines
        item {
            Text(
                text = "STREAMING ENGINES & INTEGRASI",
                color = palette.primary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
        }

        val sources = listOf(
            Triple("YouTube Innertube", "Active (Direct Googlevideo Opus CDN)", true),
            Triple("Offline Storage Engine", "Active (Zero-Network Local Playback)", true),
            Triple("LRCLIB Synchronized Lyrics", "Active (Timestamped Karaoke Lyrics)", true),
            Triple("SoundCloud Scraper", "Plugin Ready", true),
            Triple("Bandcamp Aggregator", "Plugin Ready", true)
        )

        items(sources) { (name, status, active) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                border = BorderStroke(1.dp, palette.border)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(name, color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(status, color = palette.textSecondary, fontSize = 11.sp)
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (active) palette.success else palette.error)
                    )
                }
            }
        }

        // App Info
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant.copy(alpha = 0.5f)),
                border = BorderStroke(1.dp, palette.border.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Puki Music v1.0.10",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Android Native (Media3 + NewPipeExtractor)\nBebas Iklan • Background Playback • Zero Telemetry",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Hapus Semua Musik Offline?", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp) },
            text = { Text("Tindakan ini akan menghapus ${offlineTracks.size} file audio yang telah diunduh dari memori HP.", color = palette.textSecondary, fontSize = 12.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearAllOffline()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.error)
                ) {
                    Text("Hapus Semua", color = palette.background, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Batal", color = palette.textSecondary)
                }
            },
            containerColor = palette.surfaceVariant
        )
    }
}

// ── Component: Mini-Player Bar ──────────────────────────────────────────────

@Composable
fun MiniPlayerBar(
    track: Track,
    isPlaying: Boolean,
    isResolving: Boolean,
    palette: NuclearPalette,
    onClick: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNextClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .background(palette.surfaceVariant)
            .border(1.dp, palette.border, RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = track.thumbnailUrl,
            contentDescription = track.title,
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(6.dp)),
            contentScale = ContentScale.Crop
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                color = palette.textPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (isResolving) "Resolving Opus CDN..." else track.artist,
                color = if (isResolving) palette.tertiary else palette.secondary,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (isResolving) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = palette.primary,
                strokeWidth = 2.dp
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPlayPauseToggle) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = palette.primary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                if (onNextClick != null) {
                    IconButton(onClick = onNextClick) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Lagu Selanjutnya",
                            tint = palette.textPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }
    }
}
