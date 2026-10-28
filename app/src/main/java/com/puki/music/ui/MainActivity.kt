package com.puki.music.ui

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.Brush
import coil.compose.AsyncImage
import com.puki.music.R
import com.google.common.util.concurrent.ListenableFuture
import com.puki.music.core.AppUpdateManager
import com.puki.music.core.DeezerRepository
import com.puki.music.core.OfflineManager
import com.puki.music.core.PlaybackService
import com.puki.music.core.PlaybackStateManager
import com.puki.music.core.PlaylistImporter
import com.puki.music.core.ReleaseInfo
import com.puki.music.core.StreamResolver
import com.puki.music.data.LocalMusicStore
import com.puki.music.data.ArtistAffinity
import com.puki.music.data.BehaviorFeedSection
import com.puki.music.data.Playlist
import com.puki.music.model.Album
import com.puki.music.model.NetworkDownloadPreference
import com.puki.music.model.RepeatMode
import com.puki.music.model.Track
import com.puki.music.ui.components.*
import com.puki.music.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController by mutableStateOf<MediaController?>(null)
    private lateinit var store: LocalMusicStore

    override fun onResume() {
        super.onResume()
        try {
            com.puki.music.sync.SyncManager.getInstance(this).onAppForegrounded()
        } catch (_: Exception) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = LocalMusicStore(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        PlaybackStateManager.initContext(this)

        // PlaybackService is bound and started asynchronously on-demand by MediaController via sessionToken.
        // Never call startForegroundService here to avoid the 5-second OS ANR watchdog.

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
    val activeTrack by PlaybackStateManager.activeTrack.collectAsState()
    val isPlaying by PlaybackStateManager.isPlaying.collectAsState()
    val isResolvingStream by PlaybackStateManager.isResolvingStream.collectAsState()
    val errorMessage by PlaybackStateManager.errorMessage.collectAsState()
    val repeatMode by PlaybackStateManager.repeatMode.collectAsState()
    val currentQueue by PlaybackStateManager.currentQueue.collectAsState()
    val playbackPositionMs by PlaybackStateManager.playbackPositionMs.collectAsState()
    val playbackDurationMs by PlaybackStateManager.playbackDurationMs.collectAsState()
    var isMixPlayActive by remember { mutableStateOf(false) }

    // Dialog & Modal states
    var showThemeDialog by remember { mutableStateOf(false) }
    var showExpandedPlayer by remember { mutableStateOf(false) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var showImportPlaylistDialog by remember { mutableStateOf(false) }
    var isImportingPlaylist by remember { mutableStateOf(false) }
    var playlistToAddTo by remember { mutableStateOf<Track?>(null) }
    var activePlaylist by remember { mutableStateOf<Playlist?>(null) }
    var viewingArtist by remember { mutableStateOf<String?>(null) }
    var viewingAlbum by remember { mutableStateOf<Album?>(null) }

    // Persistent Search Tab State (Retained across tab switching)
    var persistentSearchQuery by remember { mutableStateOf("") }
    var persistentSearchFilter by remember { mutableIntStateOf(0) }
    var persistentOfficialTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var persistentYoutubeTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var persistentAlbumResults by remember { mutableStateOf<List<Album>>(emptyList()) }
    var persistentTopArtistResult by remember { mutableStateOf<com.puki.music.model.DeezerArtist?>(null) }
    val splashProgress = remember { androidx.compose.animation.core.Animatable(0f) }
    var isAppInitialized by remember { mutableStateOf(false) }

    // Initial Splash Screen loading animation (YouTube Music seekbar style)
    LaunchedEffect(Unit) {
        splashProgress.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 1100,
                easing = androidx.compose.animation.core.FastOutSlowInEasing
            )
        )
        kotlinx.coroutines.delay(150)
        isAppInitialized = true
    }

    // Reactive data lists
    var favoritesList by remember { mutableStateOf(store.getFavorites()) }
    var offlineTracks by remember { mutableStateOf(store.getOfflineTracks()) }
    var playlistsList by remember { mutableStateOf(store.getPlaylists()) }
    val downloadingTrackIds = remember { mutableStateListOf<String>() }

    val favoriteTrackIds = remember(favoritesList) {
        favoritesList.map { LocalMusicStore.normalizeId(it.id) }.toSet()
    }
    val offlineTrackIds = remember(offlineTracks) {
        offlineTracks.map { LocalMusicStore.normalizeId(it.id) }.toSet()
    }

    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // App In-App Update State
    val currentVersionName = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0.16"
        } catch (_: Exception) {
            "1.0.16"
        }
    }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var availableUpdate by remember { mutableStateOf<ReleaseInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableIntStateOf(0) }

    fun checkAppUpdate(showToastIfLatest: Boolean = true) {
        if (isCheckingUpdate) return
        isCheckingUpdate = true
        OfflineManager.downloadScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val res = AppUpdateManager.checkForUpdate(currentVersionName)
            isCheckingUpdate = false
            res.onSuccess { release ->
                if (release != null) {
                    availableUpdate = release
                } else if (showToastIfLatest) {
                    Toast.makeText(context, "Puki Music sudah versi terbaru (v$currentVersionName)", Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                if (showToastIfLatest) {
                    Toast.makeText(context, "Gagal memeriksa pembaruan: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun startUpdateDownload(release: ReleaseInfo) {
        if (!AppUpdateManager.canInstallPackages(context)) {
            Toast.makeText(context, "Mohon izinkan 'Instal aplikasi tidak dikenal' untuk Puki Music", Toast.LENGTH_LONG).show()
            AppUpdateManager.openInstallPermissionSettings(context)
            return
        }
        isDownloadingUpdate = true
        updateDownloadProgress = 0
        OfflineManager.downloadScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val res = AppUpdateManager.downloadUpdateApk(context, release.downloadUrl) { pct ->
                updateDownloadProgress = pct
            }
            isDownloadingUpdate = false
            res.onSuccess { apkFile ->
                availableUpdate = null
                val installRes = AppUpdateManager.installUpdate(context, apkFile)
                installRes.onFailure {
                    Toast.makeText(context, "Gagal membuka installer: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }.onFailure {
                Toast.makeText(context, "Gagal mengunduh update: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        checkAppUpdate(showToastIfLatest = false)

        // Auto-heal / Backfill plays & likes for legacy downloaded, liked songs, or playlist items
        OfflineManager.downloadScope.launch(Dispatchers.IO) {
            val allTracksToAudit = mutableListOf<Track>()
            val seen = mutableSetOf<String>()

            for (t in offlineTracks) {
                val norm = LocalMusicStore.normalizeId(t.id)
                if (t.viewCount <= 0 && seen.add(norm)) {
                    allTracksToAudit.add(t)
                }
            }
            for (t in favoritesList) {
                val norm = LocalMusicStore.normalizeId(t.id)
                if (t.viewCount <= 0 && seen.add(norm)) {
                    allTracksToAudit.add(t)
                }
            }
            for (pl in store.getPlaylists()) {
                for (t in store.getPlaylistTracks(pl.id)) {
                    val norm = LocalMusicStore.normalizeId(t.id)
                    if (t.viewCount <= 0 && seen.add(norm)) {
                        allTracksToAudit.add(t)
                    }
                }
            }

            for (target in allTracksToAudit) {
                try {
                    val detailed = StreamResolver.resolveAudioStreamDetailed(target.id).getOrNull()
                    if (detailed != null && detailed.viewCount > 0) {
                        store.updateTrackMetrics(target.id, detailed.viewCount, detailed.likeCount)
                        withContext(Dispatchers.Main) {
                            offlineTracks = store.getOfflineTracks()
                            favoritesList = store.getFavorites()
                            playlistsList = store.getPlaylists()
                            val curActive = activePlaylist
                            if (curActive != null) {
                                activePlaylist = store.getPlaylists().firstOrNull { it.id == curActive.id }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun triggerAutoDownloadIfAllowed(track: Track, label: String) {
        val normId = LocalMusicStore.normalizeId(track.id)
        if (offlineTrackIds.contains(normId) || OfflineManager.isTrackDownloaded(context, track.id)) return
        if (OfflineManager.activeDownloads.containsKey(track.id)) return

        val netPref = store.getAutoDownloadNetworkPreference()
        if (!OfflineManager.canAutoDownload(context, netPref)) {
            val requirement = if (netPref == NetworkDownloadPreference.WIFI_ONLY) "Wi-Fi" else "Koneksi Internet"
            Toast.makeText(context, "Auto-download ditunda ($label): Menunggu $requirement", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(context, "Auto-download $label: Mengunduh '${track.title}'...", Toast.LENGTH_SHORT).show()
        OfflineManager.downloadScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            val res = OfflineManager.downloadTrackWithProgress(context, track)
            res.onSuccess {
                offlineTracks = store.getOfflineTracks()
                Toast.makeText(context, "Tersimpan offline: '${track.title}'", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Gagal auto-download '${track.title}': ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun handleFavoriteToggle(track: Track) {
        val trackToSave = if (track.viewCount <= 0 && activeTrack?.id == track.id && (activeTrack?.viewCount ?: 0L) > 0) {
            track.copy(viewCount = activeTrack!!.viewCount, likeCount = activeTrack!!.likeCount)
        } else {
            track
        }
        val isNowFav = store.toggleFavorite(trackToSave)
        favoritesList = store.getFavorites()
        if (isNowFav) {
            Toast.makeText(context, "Ditambahkan ke Favorit ❤️", Toast.LENGTH_SHORT).show()
            // Asynchronously resolve metrics in background if missing
            if (trackToSave.viewCount <= 0) {
                OfflineManager.downloadScope.launch(Dispatchers.IO) {
                    val detailed = StreamResolver.resolveAudioStreamDetailed(trackToSave.id).getOrNull()
                    if (detailed != null && detailed.viewCount > 0) {
                        store.toggleFavorite(trackToSave) // remove
                        store.toggleFavorite(trackToSave.copy(viewCount = detailed.viewCount, likeCount = detailed.likeCount)) // add with metrics
                        withContext(Dispatchers.Main) {
                            favoritesList = store.getFavorites()
                        }
                    }
                }
            }
            if (store.isAutoDownloadLikedEnabled()) {
                triggerAutoDownloadIfAllowed(trackToSave, "Favorit")
            }
        } else {
            Toast.makeText(context, "Dihapus dari Favorit", Toast.LENGTH_SHORT).show()
        }
    }

    suspend fun fetchArtworkBytes(urlStr: String): ByteArray? = withContext(Dispatchers.IO) {
        if (urlStr.isBlank()) return@withContext null
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                instanceFollowRedirects = true
            }
            if (conn.responseCode == 200) {
                conn.inputStream.use { it.readBytes() }
            } else null
        } catch (_: Exception) {
            null
        }
    }

    // Playback logic: delegates to standalone PlaybackStateManager in Service
    fun playTrack(track: Track, newQueue: List<Track>? = null) {
        PlaybackStateManager.playTrack(context, track, newQueue)
    }

    fun startMixPlay(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val shuffled = tracks.shuffled()
        isMixPlayActive = true
        PlaybackStateManager.playTrack(context, shuffled.first(), shuffled)
        Toast.makeText(context, "Mix Play aktif: ${shuffled.size} lagu diacak", Toast.LENGTH_SHORT).show()
    }

    fun playNextTrack() {
        PlaybackStateManager.playNext()
    }

    fun playPreviousTrack() {
        PlaybackStateManager.playPrevious()
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
            OfflineManager.downloadScope.launch(kotlinx.coroutines.Dispatchers.Main) {
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
        if (OfflineManager.activeBatch.value != null) {
            Toast.makeText(context, "Sedang mengunduh antrean lagu sebelumnya, mohon tunggu...", Toast.LENGTH_SHORT).show()
            return
        }
        val toDownload = tracks.filterNot { OfflineManager.isTrackDownloaded(context, it.id) }
        if (toDownload.isEmpty()) {
            Toast.makeText(context, "Semua lagu di '$playlistName' sudah terunduh offline!", Toast.LENGTH_SHORT).show()
            return
        }
        val batchKey = playlistId.ifBlank { playlistName }
        Toast.makeText(context, "Mengunduh ${toDownload.size} lagu di '$playlistName'...", Toast.LENGTH_SHORT).show()
        OfflineManager.downloadScope.launch(kotlinx.coroutines.Dispatchers.Main) {
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

    fun downloadUndownloadedFavorites() {
        val undownloaded = favoritesList.filterNot {
            offlineTrackIds.contains(LocalMusicStore.normalizeId(it.id)) || OfflineManager.isTrackDownloaded(context, it.id)
        }
        if (undownloaded.isEmpty()) {
            Toast.makeText(context, "Semua lagu favorit sudah tersimpan offline!", Toast.LENGTH_SHORT).show()
            return
        }
        val netPref = store.getAutoDownloadNetworkPreference()
        if (!OfflineManager.canAutoDownload(context, netPref)) {
            val requirement = if (netPref == NetworkDownloadPreference.WIFI_ONLY) "Wi-Fi" else "Koneksi Internet"
            Toast.makeText(context, "Auto-download ditunda: Menunggu $requirement", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(context, "Auto-Download: Mengunduh ${undownloaded.size} lagu favorit...", Toast.LENGTH_SHORT).show()
        downloadPlaylist(undownloaded, "Lagu Disukai")
    }

    if (!isAppInitialized) {
        // ── Splash Screen (YouTube Music Style Seek Bar) ─────────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.background),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_app_logo),
                    contentDescription = "Puki Music",
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Puki ",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Music",
                        style = androidx.compose.ui.text.TextStyle(
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFFFCA766),
                                    Color(0xFFF07575),
                                    Color(0xFFE8537D)
                                )
                            ),
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))

                // YouTube Music Style Slim Seek Bar
                val barWidth = 200.dp
                val barHeight = 3.5.dp
                val progress = splashProgress.value

                Box(
                    modifier = Modifier
                        .width(barWidth)
                        .height(12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // Track Background
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.2f))
                    )

                    // Active Red Seek Line
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .height(barHeight)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0xFFFF2A2A))
                    )

                    // Moving Playhead Thumb Dot
                    if (progress > 0.02f) {
                        Box(
                            modifier = Modifier
                                .offset(x = (barWidth * progress) - 4.dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFFF2A2A))
                        )
                    }
                }
            }
        }
        return
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
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_app_logo),
                            contentDescription = "Puki Logo",
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Puki ",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "Music",
                                style = androidx.compose.ui.text.TextStyle(
                                    brush = Brush.linearGradient(
                                        colors = listOf(
                                            Color(0xFFFCA766),
                                            Color(0xFFF07575),
                                            Color(0xFFE8537D)
                                        )
                                    ),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    letterSpacing = 0.5.sp
                                )
                            )
                        }
                    }

                    // Status Indicator
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isPlaying) palette.success else palette.secondary.copy(alpha = 0.5f))
                    )
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
                        TextButton(onClick = { PlaybackStateManager.clearError() }) {
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
                        positionMs = playbackPositionMs,
                        durationMs = playbackDurationMs,
                        palette = palette,
                        onClick = { showExpandedPlayer = true },
                        onPlayPauseToggle = { PlaybackStateManager.togglePlayPause() },
                        onNextClick = { PlaybackStateManager.playNext() },
                        onSeekTo = { PlaybackStateManager.seekTo(it) }
                    )
                }

                NavigationBar(
                    containerColor = palette.surface,
                    contentColor = palette.textPrimary,
                    tonalElevation = 0.dp
                ) {
                    NavigationBarItem(
                        selected = currentTab == 0 && viewingArtist == null && viewingAlbum == null,
                        onClick = {
                            viewingArtist = null
                            viewingAlbum = null
                            currentTab = 0
                        },
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
                        selected = (currentTab == 1 || viewingArtist != null || viewingAlbum != null),
                        onClick = {
                            currentTab = 1
                        },
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
                        selected = currentTab == 2 && viewingArtist == null && viewingAlbum == null,
                        onClick = {
                            viewingArtist = null
                            viewingAlbum = null
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
                        selected = currentTab == 3 && viewingArtist == null && viewingAlbum == null,
                        onClick = {
                            viewingArtist = null
                            viewingAlbum = null
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
                        selected = currentTab == 4 && viewingArtist == null && viewingAlbum == null,
                        onClick = {
                            viewingArtist = null
                            viewingAlbum = null
                            currentTab = 4
                        },
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
            if (viewingArtist != null || viewingAlbum != null) {
                ArtistAlbumModal(
                    artistName = viewingAlbum?.artist ?: viewingArtist!!,
                    initialAlbum = viewingAlbum,
                    palette = palette,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    activeTrack = activeTrack,
                    favoriteIds = favoriteTrackIds,
                    offlineTrackIds = offlineTrackIds,
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadTrack = { toggleOfflineDownload(it) },
                    onDownloadAlbum = { tracks, name -> downloadPlaylist(tracks, name) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoriteToggle = { handleFavoriteToggle(it) },
                    onClose = {
                        viewingArtist = null
                        viewingAlbum = null
                    }
                )
            } else {
                when (currentTab) {
                0 -> DashboardTab(
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    favoriteIds = favoriteTrackIds,
                    offlineTrackIds = offlineTrackIds,
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadClick = { toggleOfflineDownload(it) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onFavoriteToggle = { handleFavoriteToggle(it) },
                    onArtistClick = { viewingArtist = it }
                )
                1 -> SearchTab(
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    favoriteIds = favoriteTrackIds,
                    offlineTrackIds = offlineTrackIds,
                    query = persistentSearchQuery,
                    onQueryChange = { persistentSearchQuery = it },
                    searchFilter = persistentSearchFilter,
                    onSearchFilterChange = { persistentSearchFilter = it },
                    officialTracks = persistentOfficialTracks,
                    onOfficialTracksChange = { persistentOfficialTracks = it },
                    youtubeTracks = persistentYoutubeTracks,
                    onYoutubeTracksChange = { persistentYoutubeTracks = it },
                    albumResults = persistentAlbumResults,
                    onAlbumResultsChange = { persistentAlbumResults = it },
                    topArtistResult = persistentTopArtistResult,
                    onTopArtistResultChange = { persistentTopArtistResult = it },
                    onTrackSelected = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadClick = { toggleOfflineDownload(it) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onFavoriteToggle = { handleFavoriteToggle(it) },
                    onArtistClick = { viewingArtist = it },
                    onAlbumClick = { viewingAlbum = it }
                )
                2 -> LibraryTab(
                    favorites = favoritesList,
                    offlineTracks = offlineTracks,
                    palette = palette,
                    activeTrack = activeTrack,
                    store = store,
                    downloadingIds = downloadingTrackIds,
                    favoriteIds = favoriteTrackIds,
                    offlineTrackIds = offlineTrackIds,
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadClick = { toggleOfflineDownload(it) },
                    onAddToPlaylist = { playlistToAddTo = it },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onFavoriteToggle = { handleFavoriteToggle(it) },
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
                    favoriteIds = favoriteTrackIds,
                    offlineTrackIds = offlineTrackIds,
                    onSelectPlaylist = { activePlaylist = it },
                    onBackToPlaylists = {
                        activePlaylist = null
                        playlistsList = store.getPlaylists()
                    },
                    onNewPlaylistClick = { showNewPlaylistDialog = true },
                    onImportPlaylistClick = { showImportPlaylistDialog = true },
                    onTrackClick = { track, list -> playTrack(track, list) },
                    onMixPlayClick = { list -> startMixPlay(list) },
                    onDownloadTrack = { toggleOfflineDownload(it) },
                    onDownloadPlaylist = { tracks, name -> downloadPlaylist(tracks, name) },
                    onToggleAutoDownload = { pl, enabled ->
                        store.setPlaylistAutoDownload(pl.id, enabled)
                        playlistsList = store.getPlaylists()
                        activePlaylist = playlistsList.firstOrNull { it.id == pl.id }
                        if (enabled) {
                            val tracks = store.getPlaylistTracks(pl.id)
                            val undownloaded = tracks.filterNot { OfflineManager.isTrackDownloaded(context, it.id) }
                            if (undownloaded.isNotEmpty()) {
                                Toast.makeText(context, "Auto-Download aktif: Mengunduh ${undownloaded.size} lagu di '${pl.name}'", Toast.LENGTH_SHORT).show()
                                downloadPlaylist(tracks, pl.name, pl.id)
                            } else {
                                Toast.makeText(context, "Auto-Download '${pl.name}' aktif", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Auto-Download '${pl.name}' dinonaktifkan", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onFavoritesChanged = { favoritesList = store.getFavorites() },
                    onFavoriteToggle = { handleFavoriteToggle(it) },
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
                    store = store,
                    favoritesList = favoritesList,
                    offlineTracks = offlineTracks,
                    favoriteTrackIds = favoriteTrackIds,
                    offlineTrackIds = offlineTrackIds,
                    currentVersionName = currentVersionName,
                    isCheckingUpdate = isCheckingUpdate,
                    onCheckUpdate = { checkAppUpdate(showToastIfLatest = true) },
                    onDownloadFavorites = { downloadUndownloadedFavorites() },
                    onClearAllOffline = {
                        OfflineManager.deleteAllOfflineTracks(context)
                        offlineTracks = store.getOfflineTracks()
                        Toast.makeText(context, "Seluruh musik offline dibersihkan", Toast.LENGTH_SHORT).show()
                    },
                    currentThemeId = currentThemeId,
                    onThemeChanged = onThemeChanged,
                    onFavoritesChanged = {
                        favoritesList = store.getFavorites()
                        playlistsList = store.getPlaylists()
                    }
                )
            }
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
            onCreate = { name, autoDownload ->
                store.createPlaylist(name, autoDownload = autoDownload)
                playlistsList = store.getPlaylists()
                showNewPlaylistDialog = false
                val msg = if (autoDownload) "Playlist '$name' dibuat (Auto-Download Aktif)" else "Playlist '$name' dibuat"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showImportPlaylistDialog) {
        ImportPlaylistDialog(
            palette = palette,
            isImporting = isImportingPlaylist,
            onDismiss = { showImportPlaylistDialog = false },
            onImport = { url, customName, autoDownload ->
                isImportingPlaylist = true
                scope.launch {
                    val importRes = PlaylistImporter.importPlaylist(url)
                    importRes.onSuccess { result ->
                        val finalName = if (customName.isNotBlank()) customName else result.title
                        val newPl = store.createPlaylist(finalName, autoDownload = autoDownload)
                        for (track in result.tracks) {
                            store.addTrackToPlaylist(newPl.id, track)
                        }
                        playlistsList = store.getPlaylists()
                        isImportingPlaylist = false
                        showImportPlaylistDialog = false
                        Toast.makeText(context, "Berhasil impor '${result.title}' (${result.tracks.size} lagu)", Toast.LENGTH_SHORT).show()

                        if (autoDownload && result.tracks.isNotEmpty()) {
                            downloadPlaylist(result.tracks, finalName, newPl.id)
                        }
                    }.onFailure { err ->
                        isImportingPlaylist = false
                        Toast.makeText(context, "Gagal impor: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    if (playlistToAddTo != null) {
        val targetTrack = playlistToAddTo!!
        AddToPlaylistDialog(
            track = targetTrack,
            store = store,
            palette = palette,
            onDismiss = { playlistToAddTo = null },
            onAdded = { pl, trk ->
                playlistsList = store.getPlaylists()
                playlistToAddTo = null
                Toast.makeText(context, "Ditambahkan ke ${pl.name}", Toast.LENGTH_SHORT).show()
                if (pl.autoDownload) {
                    triggerAutoDownloadIfAllowed(trk, pl.name)
                }
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
            repeatMode = repeatMode,
            onToggleRepeat = {
                val nextMode = when (repeatMode) {
                    RepeatMode.OFF -> RepeatMode.ALL
                    RepeatMode.ALL -> RepeatMode.ONE
                    RepeatMode.ONE -> RepeatMode.OFF
                }
                PlaybackStateManager.setRepeatMode(nextMode)
                val label = when (nextMode) {
                    RepeatMode.OFF -> "Repeat Dinonaktifkan"
                    RepeatMode.ALL -> "Repeat Semua Lagu (Playlist/Album)"
                    RepeatMode.ONE -> "Repeat 1 Lagu Ini Saja"
                }
                Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
            },
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
            isFavorite = activeTrack?.let { favoriteTrackIds.contains(LocalMusicStore.normalizeId(it.id)) } ?: false,
            playbackQueue = currentQueue,
            onQueueTrackSelect = { selectedTrack ->
                playTrack(selectedTrack, currentQueue)
            },
            onFavoritesChanged = {
                favoritesList = store.getFavorites()
            },
            onFavoriteToggle = {
                activeTrack?.let { handleFavoriteToggle(it) }
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

    if (availableUpdate != null) {
        UpdateDialog(
            palette = palette,
            releaseInfo = availableUpdate!!,
            isDownloading = isDownloadingUpdate,
            downloadProgress = updateDownloadProgress,
            onDismiss = {
                if (!isDownloadingUpdate) availableUpdate = null
            },
            onStartUpdate = {
                availableUpdate?.let { startUpdateDownload(it) }
            }
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
    favoriteIds: Set<String> = emptySet(),
    offlineTrackIds: Set<String> = emptySet(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoritesChanged: () -> Unit,
    onFavoriteToggle: ((Track) -> Unit)? = null,
    onArtistClick: ((String) -> Unit)? = null
) {
    val topArtists = remember(activeTrack) { store.getUserTopArtists(6) }
    val sections = remember(activeTrack) { store.getBehaviorFeedSections() }
    var selectedSectionId by remember { mutableStateOf(sections.firstOrNull()?.id ?: "mix_personal") }
    val activeSection = sections.firstOrNull { it.id == selectedSectionId } ?: sections.first()

    var trendingTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var deezerTopCharts by remember { mutableStateOf<List<Track>>(emptyList()) }
    var deezerTopAlbums by remember { mutableStateOf<List<com.puki.music.model.DeezerAlbum>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Load Deezer Clean Official Charts & Albums for Dashboard
    LaunchedEffect(Unit) {
        val dzRes = DeezerRepository.getDashboardCharts(context)
        dzRes.onSuccess { dzData ->
            deezerTopCharts = dzData.topTracks.map { dz ->
                Track(
                    id = "dz_${dz.id}",
                    title = dz.title,
                    artist = dz.artistName,
                    durationSeconds = dz.durationSeconds,
                    thumbnailUrl = dz.coverUrl
                )
            }
            deezerTopAlbums = dzData.topAlbums
        }
    }

    LaunchedEffect(activeSection.query) {
        isLoading = true
        val result = StreamResolver.getDashboardTracks(activeSection.query)
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
                                text = if (topArtists.isNotEmpty()) "PROFIL SELERA AKTIF (${topArtists.size} ARTIS)" else "PERSONALIZED FEED",
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
                                    val res = StreamResolver.getDashboardTracks(activeSection.query)
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
                        text = activeSection.title,
                        color = palette.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = activeSection.subtitle,
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(palette.primary.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = activeSection.reason,
                                color = palette.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Horizontal Section Selector Chips
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(sections) { sec ->
                            val isSelected = sec.id == activeSection.id
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedSectionId = sec.id },
                                label = {
                                    Text(
                                        text = sec.title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = palette.primary,
                                    selectedLabelColor = palette.background,
                                    containerColor = palette.surface,
                                    labelColor = palette.textSecondary
                                ),
                                border = BorderStroke(1.dp, if (isSelected) palette.primary else palette.border)
                            )
                        }
                    }
                }
            }
        }

        // Horizontal Row of Top Listened Artists (Behavioral Affinity)
        if (topArtists.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ARTIS PALING SERING DIDENGAR",
                            color = palette.primary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Berdasarkan behavior",
                            color = palette.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(topArtists) { artist ->
                            Card(
                                modifier = Modifier
                                    .width(135.dp)
                                    .clickable { onArtistClick?.invoke(artist.name) },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                border = BorderStroke(1.dp, palette.border)
                            ) {
                                Column(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(52.dp)
                                            .clip(CircleShape)
                                            .background(palette.surface)
                                            .border(1.5.dp, palette.primary.copy(alpha = 0.5f), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (artist.avatarUrl.isNotBlank()) {
                                            AsyncImage(
                                                model = artist.avatarUrl,
                                                contentDescription = artist.name,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .clip(CircleShape)
                                            )
                                        } else {
                                            Text(
                                                text = artist.name.take(1).uppercase(),
                                                color = palette.primary,
                                                fontSize = 20.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = artist.name,
                                        color = palette.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "${artist.playCount}x putar • ${artist.favoriteCount} fav",
                                        color = palette.textSecondary,
                                        fontSize = 9.sp,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Section: Tangga Lagu Terpopuler Resmi (Deezer Top Charts HD) ──
        if (deezerTopCharts.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                                    .background(palette.tertiary)
                            )
                            Text(
                                text = "TANGGA LAGU RESMI (TOP CHARTS)",
                                color = palette.tertiary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(
                            text = "HD 1000px Cover",
                            color = palette.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(deezerTopCharts.take(15)) { track ->
                            Card(
                                modifier = Modifier
                                    .width(130.dp)
                                    .clickable { onTrackClick(track, deezerTopCharts) },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                border = BorderStroke(1.dp, palette.border)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    AsyncImage(
                                        model = track.thumbnailUrl,
                                        contentDescription = track.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = track.title,
                                        color = palette.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = track.artist,
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

        // ── Section: Album Rilis Baru Resmi (Deezer New Albums) ──
        if (deezerTopAlbums.isNotEmpty()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                                    .background(palette.secondary)
                            )
                            Text(
                                text = "ALBUM RILIS TERBARU (OFFICIAL)",
                                color = palette.secondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }
                        Text(
                            text = "Katalog Resmi",
                            color = palette.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(deezerTopAlbums.take(15)) { album ->
                            Card(
                                modifier = Modifier
                                    .width(130.dp)
                                    .clickable {
                                        onArtistClick?.invoke(album.artistName)
                                    },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant),
                                border = BorderStroke(1.dp, palette.border)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    AsyncImage(
                                        model = album.coverUrl,
                                        contentDescription = album.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color.Black)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = album.title,
                                        color = palette.textPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = album.artistName,
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
                    text = activeSection.title.uppercase(),
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
                    onClick = { onTrackClick(track, trendingTracks) },
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

// ── Tab 1: Search (Moved to com.puki.music.ui.components.SearchTabContent.kt) ───────────────────────────────────────────────────────────

// ── Tab 2: Library (Favorites + Offline Storage) ─────────────────────────────

@Composable
fun LibraryTab(
    favorites: List<Track>,
    offlineTracks: List<Track>,
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    favoriteIds: Set<String> = emptySet(),
    offlineTrackIds: Set<String> = emptySet(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadClick: (Track) -> Unit,
    onAddToPlaylist: (Track) -> Unit,
    onFavoritesChanged: () -> Unit,
    onFavoriteToggle: ((Track) -> Unit)? = null,
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onTrackClick(favorites.first(), favorites) },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = palette.background, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("PUTAR", color = palette.background, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = { onMixPlayClick(favorites) },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.surfaceVariant),
                            border = BorderStroke(1.dp, palette.border),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Shuffle, contentDescription = null, tint = palette.primary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("MIX", color = palette.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
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
                        val normId = LocalMusicStore.normalizeId(track.id)
                        val trackProgress = OfflineManager.activeDownloads[track.id] ?: OfflineManager.activeDownloads[normId]
                        val isActivelyDownloading = trackProgress != null || downloadingIds.contains(track.id) || downloadingIds.contains(normId)
                        val isOffline = !isActivelyDownloading && (offlineTrackIds.contains(track.id) || offlineTrackIds.contains(normId))

                        TrackItemCard(
                            track = track,
                            isSelected = activeTrack?.id == track.id,
                            isFavorite = true,
                            isOffline = isOffline,
                            downloadProgress = trackProgress,
                            isDownloading = isActivelyDownloading,
                            palette = palette,
                            onClick = { onTrackClick(track, favorites) },
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
                        val normId = LocalMusicStore.normalizeId(track.id)
                        val isFav = favoriteIds.contains(track.id) || favoriteIds.contains(normId)
                        val fileSize = OfflineManager.formatBytes(OfflineManager.getTrackFileSizeBytes(context, track.id))
                        val playCount = store.getPlayCount(track.id)

                        TrackItemCard(
                            track = track,
                            isSelected = activeTrack?.id == track.id,
                            isFavorite = isFav,
                            isOffline = true,
                            isDownloading = false,
                            playCount = playCount,
                            fileSizeText = fileSize,
                            palette = palette,
                            onClick = { onTrackClick(track, offlineTracks) },
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

// ── Tab 3: Playlists ────────────────────────────────────────────────────────

@Composable
fun PlaylistsTab(
    playlists: List<Playlist>,
    activePlaylist: Playlist?,
    palette: NuclearPalette,
    activeTrack: Track?,
    store: LocalMusicStore,
    downloadingIds: List<String>,
    favoriteIds: Set<String> = emptySet(),
    offlineTrackIds: Set<String> = emptySet(),
    onSelectPlaylist: (Playlist) -> Unit,
    onBackToPlaylists: () -> Unit,
    onNewPlaylistClick: () -> Unit,
    onImportPlaylistClick: () -> Unit = {},
    onTrackClick: (Track, List<Track>) -> Unit,
    onMixPlayClick: (List<Track>) -> Unit,
    onDownloadTrack: (Track) -> Unit,
    onDownloadPlaylist: (List<Track>, String) -> Unit,
    onToggleAutoDownload: (Playlist, Boolean) -> Unit,
    onFavoritesChanged: () -> Unit,
    onFavoriteToggle: ((Track) -> Unit)? = null,
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
                        IconButton(onClick = { onTrackClick(currentTracks.first(), currentTracks) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Putar Semua", tint = palette.primary)
                        }
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

            Spacer(modifier = Modifier.height(8.dp))

            // Auto-Download Playlist Toggle Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(palette.surfaceVariant)
                    .border(1.dp, palette.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (activePlaylist.autoDownload) Icons.Default.CloudDone else Icons.Default.CloudDownload,
                        contentDescription = null,
                        tint = if (activePlaylist.autoDownload) palette.primary else palette.textSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text("Auto-Download Playlist", color = palette.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Text(
                            if (activePlaylist.autoDownload) "Lagu baru otomatis diunduh offline" else "Nonaktif - unduh manual",
                            color = palette.textSecondary,
                            fontSize = 10.sp
                        )
                    }
                }
                Switch(
                    checked = activePlaylist.autoDownload,
                    onCheckedChange = { onToggleAutoDownload(activePlaylist, it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = palette.background,
                        checkedTrackColor = palette.primary
                    )
                )
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
                            onClick = { onTrackClick(track, currentTracks) },
                            onArtistClick = onArtistClick,
                            onFavoriteToggle = {
                                if (onFavoriteToggle != null) {
                                    onFavoriteToggle(track)
                                } else {
                                    store.toggleFavorite(track)
                                    onFavoritesChanged()
                                }
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onImportPlaylistClick,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.primary),
                        border = BorderStroke(1.dp, palette.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = palette.primary, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("IMPORT", color = palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
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
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("${pl.trackCount} lagu", color = palette.textSecondary, fontSize = 12.sp)
                                            if (pl.autoDownload) {
                                                Text(
                                                    "• AUTO-DL",
                                                    color = palette.primary,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }
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
    store: LocalMusicStore,
    favoritesList: List<Track>,
    offlineTracks: List<Track>,
    favoriteTrackIds: Set<String> = emptySet(),
    offlineTrackIds: Set<String> = emptySet(),
    currentVersionName: String = "1.0.65",
    isCheckingUpdate: Boolean = false,
    onCheckUpdate: () -> Unit = {},
    onDownloadFavorites: () -> Unit,
    onClearAllOffline: () -> Unit,
    currentThemeId: NuclearThemeId = NuclearThemeId.MIDNIGHT_GLOW,
    onThemeChanged: (NuclearThemeId) -> Unit = {},
    onFavoritesChanged: () -> Unit = {}
) {
    var showClearDialog by remember { mutableStateOf(false) }
    var showChangelogDialog by remember { mutableStateOf(false) }
    var showExportSuccessDialog by remember { mutableStateOf<String?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var importInputText by remember { mutableStateOf("") }
    var isAutoDlLiked by remember { mutableStateOf(store.isAutoDownloadLikedEnabled()) }
    var networkPref by remember { mutableStateOf(store.getAutoDownloadNetworkPreference()) }

    val syncManager = remember { com.puki.music.sync.SyncManager.getInstance(context) }
    val syncStatus by syncManager.statusFlow.collectAsState()
    var showQrDialog by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var currentPairingUri by remember { mutableStateOf("") }
    var customRelayInput by remember { mutableStateOf(syncManager.getRelayUrl()) }

    var offlineUsageText by remember { mutableStateOf("Memuat...") }
    var availableStorageText by remember { mutableStateOf("Memuat...") }
    var undownloadedCount by remember { mutableStateOf(0) }

    LaunchedEffect(offlineTracks.size, favoritesList.size) {
        withContext(Dispatchers.IO) {
            val usage = OfflineManager.getFormattedOfflineUsage(context)
            val available = OfflineManager.getFormattedAvailableDeviceStorage(context)
            val count = favoritesList.count {
                val norm = LocalMusicStore.normalizeId(it.id)
                !offlineTrackIds.contains(norm) && !OfflineManager.isTrackDownloaded(context, it.id)
            }
            withContext(Dispatchers.Main) {
                offlineUsageText = usage
                availableStorageText = available
                undownloadedCount = count
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Version & In-App Update Card
        item {
            Text(
                text = "TEMA & TAMPILAN APLIKASI",
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NuclearThemeId.values().forEach { theme ->
                        val themePal = getNuclearPalette(theme)
                        val isSelected = currentThemeId == theme
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) themePal.surface else Color.Transparent)
                                .border(
                                    1.dp,
                                    if (isSelected) themePal.primary else palette.border.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { onThemeChanged(theme) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(themePal.primary)
                                )
                                Column {
                                    Text(
                                        text = theme.displayName,
                                        color = if (isSelected) themePal.textPrimary else palette.textPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp
                                    )
                                    Text(
                                        text = theme.description,
                                        color = if (isSelected) themePal.textSecondary else palette.textSecondary,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = themePal.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // App Version & In-App Update Card
        item {
            Text(
                text = "PEMBARUAN APLIKASI",
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Puki Music",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Text(
                                text = "by Nands",
                                color = palette.secondary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = "Versi $currentVersionName",
                                color = palette.textSecondary,
                                fontSize = 12.sp
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(palette.primary.copy(alpha = 0.2f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Gitea Release",
                                    color = palette.primary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onCheckUpdate,
                        enabled = !isCheckingUpdate,
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isCheckingUpdate) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = palette.background,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("MEMERIKSA...", color = palette.background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CEK UPDATE", color = palette.background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Catatan Rilis / Changelog (Clickable clean row)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showChangelogDialog = true },
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = palette.surfaceVariant.copy(alpha = 0.7f)),
                border = BorderStroke(1.dp, palette.border.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(Icons.Default.Notes, contentDescription = null, tint = palette.primary, modifier = Modifier.size(20.dp))
                        Column {
                            Text(
                                text = "CATATAN RILIS (CHANGELOG)",
                                color = palette.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Ketuk untuk melihat detail pembaruan",
                                color = palette.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                    Text(
                        text = "LIHAT ›",
                        color = palette.primary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // ── Cross-Device Telemetry Sync (E2EE Relay) ──
        item {
            Text(
                text = "SINKRONISASI TELEMETRI ANTAR-PERANGKAT",
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
                        Text(
                            text = "Cloud Relay (Zero-Knowledge E2EE)",
                            color = palette.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        val statusText = when {
                            customRelayInput.isBlank() -> "○ BELUM DIKONFIGURASI"
                            syncStatus.state == com.puki.music.sync.SyncConnectionState.SYNCED -> "● TERHUBUNG"
                            syncStatus.state == com.puki.music.sync.SyncConnectionState.CONNECTING -> "○ MENYAMBUNG..."
                            syncStatus.state == com.puki.music.sync.SyncConnectionState.ERROR -> "⚠ ERROR"
                            else -> "○ NONAKTIF"
                        }
                        val statusColor = when {
                            customRelayInput.isBlank() -> palette.textSecondary
                            syncStatus.state == com.puki.music.sync.SyncConnectionState.SYNCED -> palette.success
                            syncStatus.state == com.puki.music.sync.SyncConnectionState.CONNECTING -> palette.tertiary
                            syncStatus.state == com.puki.music.sync.SyncConnectionState.ERROR -> palette.error
                            else -> palette.textSecondary
                        }
                        Text(
                            text = statusText,
                            color = statusColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sinkronisasi otomatis riwayat putar, pencarian, lagu favorit, dan playlist ke iOS/Desktop tanpa login akun.",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )
                    if (syncStatus.state == com.puki.music.sync.SyncConnectionState.ERROR && !syncStatus.errorMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Error: ${syncStatus.errorMessage}",
                            color = palette.error,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "URL RELAY SERVER",
                        color = palette.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customRelayInput,
                        onValueChange = {
                            customRelayInput = it
                            syncManager.setRelayUrl(it)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://sync.domain.top atau http://192.168.x.x:8080", fontSize = 11.sp, color = palette.textSecondary.copy(alpha = 0.5f)) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = palette.primary,
                            unfocusedBorderColor = palette.border,
                            focusedTextColor = palette.textPrimary,
                            unfocusedTextColor = palette.textPrimary,
                            cursorColor = palette.primary
                        )
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (customRelayInput.isBlank()) {
                                    Toast.makeText(context, "Silakan isi URL Relay Server terlebih dahulu!", Toast.LENGTH_LONG).show()
                                    return@Button
                                }
                                try {
                                    val uri = if (syncManager.isSyncEnabled()) {
                                        syncManager.getPairingUri()
                                    } else {
                                        syncManager.initializeNewVault(customRelayInput)
                                    }
                                    currentPairingUri = uri
                                    showQrDialog = com.puki.music.sync.QrHelper.generateQrBitmap(
                                        content = uri,
                                        size = 512,
                                        foregroundColor = android.graphics.Color.WHITE,
                                        backgroundColor = android.graphics.Color.BLACK
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Gagal membuat QR: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🔗 TAMPILKAN QR", color = palette.background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                val act = context as? Activity
                                if (act != null) {
                                    com.puki.music.sync.QrHelper.startQrScanner(
                                        activity = act,
                                        onSuccess = { scannedUri ->
                                            val ok = syncManager.parseAndApplyPairingUri(scannedUri)
                                            if (ok) {
                                                Toast.makeText(context, "Perangkat berhasil terhubung!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Format QR tidak valid", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        onCanceled = {},
                                        onFailure = { err ->
                                            Toast.makeText(context, "Scan gagal: ${err.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, palette.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📷 SCAN QR LAIN", color = palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (syncStatus.isEnabled) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = {
                                    syncManager.seedLocalDataToOutbox(force = true)
                                    syncManager.triggerSyncNow()
                                    Toast.makeText(context, "Menyinkronkan telemetri...", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("🔄 Sinkronkan Sekarang", color = palette.primary, fontSize = 10.sp)
                            }
                            TextButton(
                                onClick = {
                                    syncManager.disableSync()
                                    Toast.makeText(context, "Sinkronisasi dinonaktifkan", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Putuskan Hubungan", color = palette.error, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }

        // ── Backup & Sync Behavior Profile (Cross-Device) ──
        item {
            Text(
                text = "CADANGAN & SINKRONISASI SELERA",
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
                    Text(
                        text = "Profil Selera & Kebiasaan Musik",
                        color = palette.textPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ekspor seluruh riwayat putar, daftar playlist, lagu favorit, dan algoritma selera ke file JSON agar tidak perlu mengulang dari nol saat ganti HP.",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                try {
                                    val json = store.exportBehaviorJson()
                                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    val clip = android.content.ClipData.newPlainText("Puki Music Profile", json)
                                    clipboard.setPrimaryClip(clip)

                                    // Simpan juga ke file download publik agar mudah dipindah
                                    val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                                    val backupFile = java.io.File(downloadsDir, "puki_music_profile_${System.currentTimeMillis() / 1000}.json")
                                    backupFile.writeText(json)

                                    showExportSuccessDialog = backupFile.absolutePath
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Gagal mengekspor: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📤 EKSPOR PROFIL", color = palette.background, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                importInputText = ""
                                showImportDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, palette.primary),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("📥 IMPOR PROFIL", color = palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

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
                                text = offlineUsageText,
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
                            text = availableStorageText,
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

        // Automation & Download Settings
        item {
            Text(
                text = "OTOMASI & PENGATURAN DOWNLOAD",
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
                    // Auto-Download Liked Songs Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text(
                                text = "Auto-Download Lagu Disukai",
                                color = palette.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Otomatis unduh saat lagu ditandai favorit (❤️)",
                                color = palette.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = isAutoDlLiked,
                            onCheckedChange = { enabled ->
                                isAutoDlLiked = enabled
                                store.setAutoDownloadLikedEnabled(enabled)
                                if (enabled) {
                                    Toast.makeText(context, "Auto-download lagu favorit diaktifkan", Toast.LENGTH_SHORT).show()
                                    onDownloadFavorites()
                                } else {
                                    Toast.makeText(context, "Auto-download lagu favorit dinonaktifkan", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = palette.background,
                                checkedTrackColor = palette.primary
                            )
                        )
                    }

                    val currentBatch = OfflineManager.activeBatch.value
                    if (currentBatch != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        BatchDownloadBanner(batch = currentBatch, palette = palette)
                    }

                    if (undownloadedCount > 0 && currentBatch == null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onDownloadFavorites,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, tint = palette.background, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "UNDUH $undownloadedCount LAGU FAVORIT YANG BELUM OFFLINE",
                                color = palette.background,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(palette.border.copy(alpha = 0.5f))
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    // Network Preference Option
                    Text(
                        text = "Jaringan yang Diizinkan untuk Download Otomatis",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Aturan koneksi data untuk seluruh auto-download",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (networkPref == NetworkDownloadPreference.WIFI_ONLY) palette.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .border(1.dp, if (networkPref == NetworkDownloadPreference.WIFI_ONLY) palette.primary else palette.border, RoundedCornerShape(8.dp))
                            .clickable {
                                networkPref = NetworkDownloadPreference.WIFI_ONLY
                                store.setAutoDownloadNetworkPreference(NetworkDownloadPreference.WIFI_ONLY)
                                Toast.makeText(context, "Mode auto-download: Hanya Wi-Fi", Toast.LENGTH_SHORT).show()
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                tint = if (networkPref == NetworkDownloadPreference.WIFI_ONLY) palette.primary else palette.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text("Hanya Wi-Fi (Hemat Kuota)", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Unduh otomatis hanya saat tersambung Wi-Fi", color = palette.textSecondary, fontSize = 10.sp)
                            }
                        }
                        RadioButton(
                            selected = networkPref == NetworkDownloadPreference.WIFI_ONLY,
                            onClick = {
                                networkPref = NetworkDownloadPreference.WIFI_ONLY
                                store.setAutoDownloadNetworkPreference(NetworkDownloadPreference.WIFI_ONLY)
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.primary)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (networkPref == NetworkDownloadPreference.ANY_NETWORK) palette.primary.copy(alpha = 0.15f) else Color.Transparent)
                            .border(1.dp, if (networkPref == NetworkDownloadPreference.ANY_NETWORK) palette.primary else palette.border, RoundedCornerShape(8.dp))
                            .clickable {
                                networkPref = NetworkDownloadPreference.ANY_NETWORK
                                store.setAutoDownloadNetworkPreference(NetworkDownloadPreference.ANY_NETWORK)
                                Toast.makeText(context, "Mode auto-download: Wi-Fi & Data Seluler", Toast.LENGTH_SHORT).show()
                                if (isAutoDlLiked && undownloadedCount > 0) {
                                    onDownloadFavorites()
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = if (networkPref == NetworkDownloadPreference.ANY_NETWORK) palette.primary else palette.textSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text("Wi-Fi & Data Seluler", color = palette.textPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text("Unduh kapan pun ada jaringan internet", color = palette.textSecondary, fontSize = 10.sp)
                            }
                        }
                        RadioButton(
                            selected = networkPref == NetworkDownloadPreference.ANY_NETWORK,
                            onClick = {
                                networkPref = NetworkDownloadPreference.ANY_NETWORK
                                store.setAutoDownloadNetworkPreference(NetworkDownloadPreference.ANY_NETWORK)
                                if (isAutoDlLiked && undownloadedCount > 0) {
                                    onDownloadFavorites()
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = palette.primary)
                        )
                    }
                }
            }
        }

        // System & Background Performance Card
        item {
            Text(
                text = "LATAR BELAKANG & NOTIFIKASI",
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Notifikasi Download & Player",
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Status unduh & kontrol pemutar di status bar",
                                    color = palette.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                    }
                                    try { context.startActivity(fallback) } catch (_: Exception) {}
                                }
                            }
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, tint = palette.primary)
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                Icons.Default.BatteryChargingFull,
                                contentDescription = null,
                                tint = palette.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Abaikan Optimasi Baterai",
                                    color = palette.textPrimary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    text = "Cegah HP mematikan download saat layar mati",
                                    color = palette.textSecondary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                        Button(
                            onClick = {
                                val powerManager = context.getSystemService(android.os.PowerManager::class.java)
                                val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
                                } else false

                                if (isIgnoring) {
                                    Toast.makeText(context, "Sudah dikecualikan dari optimasi baterai (Aktif)", Toast.LENGTH_SHORT).show()
                                } else {
                                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                    } else {
                                        Intent(Settings.ACTION_SETTINGS)
                                    }
                                    try {
                                        context.startActivity(intent)
                                    } catch (_: Exception) {
                                        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                        try { context.startActivity(fallback) } catch (_: Exception) {}
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = palette.primary.copy(alpha = 0.2f)),
                            border = BorderStroke(1.dp, palette.primary),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("IZINKAN", color = palette.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                        text = "Puki Music v$currentVersionName • by Nands",
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

    if (showExportSuccessDialog != null) {
        AlertDialog(
            onDismissRequest = { showExportSuccessDialog = null },
            title = { Text("Profil Berhasil Diekspor!", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "1. Data JSON telah disalin otomatis ke Clipboard HP.\n2. Salinan file tersimpan di:",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = showExportSuccessDialog ?: "",
                        color = palette.textPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Kamu bisa kirim file ini atau paste teks JSON di HP barumu lewat menu 'IMPOR PROFIL'.",
                        color = palette.textSecondary,
                        fontSize = 11.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showExportSuccessDialog = null },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                ) {
                    Text("MENGERTI", color = palette.background, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = palette.surfaceVariant
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("Impor Profil Selera & Playlist", color = palette.primary, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "Tempelkan (Paste) teks JSON profil yang sudah kamu ekspor dari HP lama di bawah ini:",
                        color = palette.textSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = importInputText,
                        onValueChange = { importInputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        placeholder = { Text("Tempel JSON di sini...", color = palette.textSecondary.copy(alpha = 0.5f), fontSize = 11.sp) },
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, color = palette.textPrimary, fontFamily = FontFamily.Monospace),
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = importInputText.trim()
                        if (input.isNotBlank()) {
                            val res = store.importBehaviorJson(input)
                            res.onSuccess { count ->
                                Toast.makeText(context, "Profil berhasil dipulihkan! ($count lagu favorit diperbarui)", Toast.LENGTH_LONG).show()
                                showImportDialog = false
                                onFavoritesChanged()
                            }.onFailure { err ->
                                Toast.makeText(context, "Format JSON tidak valid: ${err.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                ) {
                    Text("PULIHKAN", color = palette.background, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text("BATAL", color = palette.textSecondary)
                }
            },
            containerColor = palette.surfaceVariant
        )
    }

    if (showChangelogDialog) {
        AlertDialog(
            onDismissRequest = { showChangelogDialog = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Notes, contentDescription = null, tint = palette.primary, modifier = Modifier.size(20.dp))
                    Text(
                        "CATATAN RILIS LENGKAP",
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "• v1.0.66: Dual-Engine Fusion YouTube Music Official & Deezer HD:\n" +
                                "  - Integrasi Master Index YouTube Music (WEB_REMIX): Katalog album resmi 100% lengkap untuk musisi Indonesia (NOAH, Peterpan, Last Child, Dewa 19, dll) & Internasional.\n" +
                                "  - Anti-Salah Tangkap Artis: Logika exact matching presisi mencegah pencarian band lokal dibajak musisi luar negeri.\n" +
                                "  - Tracklist Bawaan Studio: Membuka album langsung memuat tracklist resmi dengan video ID valid untuk pemutaran instan.\n\n" +
                                "• v1.0.65: Migrasi Sumber Pembaruan ke GitHub Releases:\n" +
                                "  - Auto-Update Mandiri Publik: Pengecekan versi dan unduhan APK berpindah sepenuhnya ke GitHub API resmi (Queryzy/Puki-Music-Android).\n" +
                                "  - Keamanan Token: Menghilangkan hardcoded token internal Gitea dari source code aplikasi publik.\n\n" +
                                "• v1.0.64: Fix Coroutine Throttling & Mutex Lock:\n" +
                                "  - Mutex Concurrency Lock: Mencegah eksekusi sync ganda atau status stuck 'Menyambungkan'.\n" +
                                "  - Atomic Batch Outbox: Seeding database lokal ditulis sekaligus ke memori tanpa spam coroutine.\n" +
                                "  - Caching Registrasi Vault: Menghilangkan perhitungan ulang PoW pada setiap interval sinkronisasi.\n\n" +
                                "• v1.0.63: Fix Full Initial Sync & Outbox Flush:\n" +
                                "  - Seeding Otomatis Seluruh Database Lokal: Lagu favorit lama, semua riwayat pencarian, dan playlist otomatis di-seed ke relay saat pairing atau klik Sinkronkan Sekarang (1:1 Set Union).\n" +
                                "  - Multi-Batch Outbox Flush: Pengiriman outbox kini memproses antrean tuntas hingga kosong tanpa tertunda.\n\n" +
                                "• v1.0.62: Fix E2EE Sync Authentication & Auto-Heal:\n" +
                                "  - Perbaikan Otentikasi HMAC: Penyelarasan key K_auth dengan server relay Cloudflare D1 agar bebas dari error 401.\n" +
                                "  - Detail Diagnostik Error: Pesan error koneksi relay ditampilkan langsung di antarmuka sinkronisasi.\n\n" +
                                "• v1.0.61: Cross-Device E2EE Telemetry Sync (Cloud Relay & Zero-Knowledge):\n" +
                                "  - Sinkronisasi Otomatis Multi-Device: Riwayat pencarian, lagu yang diputar, daftar favorit, dan custom playlist kini otomatis sinkron antar-HP (Android & iOS) dan desktop.\n" +
                                "  - Zero-Knowledge Client-Side AES-256-GCM: Seluruh metadata terenkripsi di perangkat; server relay tidak bisa melihat isi data maupun riwayat pengguna.\n" +
                                "  - One-Tap QR Pairing: Sambungkan perangkat baru cukup dengan scan kode QR atau salin tautan pairing puki://sync.\n" +
                                "  - Non-Destructive Set Union & Delta Sync: Data dari beberapa perangkat otomatis digabung secara cerdas tanpa risiko saling menimpa.\n" +
                                "  - Background Outbox & Auto-Heal: Event disimpan lokal saat offline dan otomatis dikirim saat koneksi internet kembali aktif.\n\n" +
                                "• v1.0.60: YouTube Music Style Splash Loading Seek Bar:\n" +
                                "  - Animasi progress bar horizontal ramping khas YouTube Music menggantikan spinner bulat di splash screen awal.\n" +
                                "  - Dilengkapi garis penunjuk waktu dan titik playhead yang berjalan mulus saat sinkronisasi katalog.\n\n" +
                                "• v1.0.59: YouTube Music Search UX & Search State Hoisting:\n" +
                                "  - Hoisting State Pencarian: Kata kunci dan hasil pencarian tetap tersimpan saat berpindah tab (Dashboard, Library, Playlist, Settings).\n" +
                                "  - Riwayat Pencarian Vertikal: Daftar pencarian terbaru dilengkapi tombol hapus satuan, hapus semua, dan tombol insert query.\n" +
                                "  - Carousel 'Terakhir Diputar': Navigasi cepat lagu yang sering didengarkan di bagian atas menu telusuri.\n" +
                                "  - Tombol Jelajah Musik Cepat: Akses instan Tangga Lagu dan Rilis Baru.\n" +
                                "  - Navigasi Sub-View Bersih: Memilih menu bawah saat membuka album/artis langsung menutup overlay dan menuju tab tujuan.\n\n" +
                                "• v1.0.58: Deezer Omnisource HD, 3D Master Logo & Interactive Seek Bar:\n" +
                                "  - Integrasi Katalog Deezer HD: Pencarian lagu resmi kualitas studio dengan thumbnail album beresolusi tinggi.\n" +
                                "  - Interactive Mini Player Progress Bar: Garis seekbar merah interaktif dengan touch scrubbing dan indikator waktu monospace.\n" +
                                "  - Master Logo 3D Puki Music: Ikon circular badge 3D transparan baru dan gradasi sunset pada header teks.\n" +
                                "  - Tema Puki Midnight Glow: Palet warna hitam obsidian gelap dengan aksen sunset glow.\n" +
                                "  - Pemindahan Pengaturan Tema: Opsi ganti tema dipindahkan rapi ke tab Settings.\n\n" +
                                "• v1.0.57: Chinese Pinyin Engine, Behavior Export/Import & Song Affinity Feed:\n" +
                                "  - Modul Pinyin Bahasa Cina: Transliterasi karakter Hanzi ke huruf alfabet Pinyin secara otomatis jika toggle Romaji aktif.\n" +
                                "  - Tombol '🔄 REFRESH LIRIK' diposisikan tepat di sebelah kiri tombol Romaji di bar sub-header lirik agar mudah diakses.\n" +
                                "  - Fitur Ekspor & Impor Profil Selera di Settings: Pindah device tanpa kehilangan playlist, lagu favorit, tema, dan preferensi.\n" +
                                "  - Algoritma Rekomendasi Berbasis Lagu Yang Sering Diputar: Feed Home menyajikan track serupa ('Mirip [Judul Lagu]') dari kebiasaan mendengar lagu aktif.\n\n" +
                                "• v1.0.56: Lyrics Cache Invalidation & Direct Refresh Button:\n" +
                                "  - Tombol '🔄 REFRESH LIRIK' langsung di player lirik: menghapus cache lokal lama yang sempat keliru dan memaksa query ulang lirik sinkron asli ke LRCLIB.\n" +
                                "  - Label status lirik dinamis: menampilkan '📜 LIRIK SYNC' untuk karaoke tersinkronisasi dan '📜 LIRIK' jika lirik teks biasa.\n" +
                                "  - Korean Hangul Support & Japanese Kuromoji Tokenizer terintegrasi penuh.\n\n" +
                                "• v1.0.55: Korean Hangul-to-Romanization Engine Support:\n" +
                                "  - Menambahkan dekomposisi suku kata Hangeul Korea (Choseong, Jungseong, Jongseong). Lirik lagu Korea (seperti lagu 'Say Yes' Loco & Punch: '너의 입술로 말을 해줘') kini 100% terkonversi rapi ke huruf Latin/Romanization ('neoui ipsulro maleul haejwo say yes, say yes').\n\n" +
                                "• v1.0.54: Aggressive Artist Channel Sanitizer for Lyrics:\n" +
                                "  - Pembersihan nama uploader YouTube otomatis: menghapus imbuhan seperti 'Official YouTube Channel', 'Records', 'Entertainment' dari nama artis (misal: 'FLOW Official YouTube Channel' -> 'FLOW'). Lirik lagu Naruto 'Sign' dan lagu musisi resmi lainnya kini 100% langsung ditemukan.\n\n" +
                                "• v1.0.53: Dedicated Romaji Sub-Bar & Non-Overlapping Hitbox:\n" +
                                "  - Tombol toggle ROMAJI dipisahkan ke bar mandiri di atas kotak lirik; tidak lagi menumpuk (overlap) dengan teks lirik pertama sehingga 100% responsif dan tidak memicu lompatan lagu (seekTo) yang tidak disengaja.\n\n" +
                                "• v1.0.52: Kuromoji Japanese Morphological Analyzer for 100% Kanji Romaji:\n" +
                                "  - Integrasi library Kuromoji (Japanese NLP): seluruh huruf Kanji Jepang (seperti 世界, 生きる, 意味, 探して) kini diparsing morfologinya dan 100% diubah ke Romaji murni (sekai, ikiru, imi, sagashite) tanpa ada Kanji yang tertinggal.\n\n" +
                                "• v1.0.51: Symmetrical Tabs & Strict Duration Matching for Lyrics:\n" +
                                "  - Tab ARTWORK, LIRIK SYNC, dan ANTREAN dibuat 100% simetris berbobot rata (33.3% tiap tab).\n" +
                                "  - Tombol toggle ROMAJI dipindahkan rapi ke dalam view internal LIRIK SYNC (sudut kanan atas).\n" +
                                "  - Filter toleransi durasi ketat (±5 detik) di LRCLIB agar lirik tidak lagi tertukar dengan lagu artis lain yang judulnya sama.\n" +
                                "  - Penyesuaian transliterator Jepang murni (Kana-Latin/Japanese-Latin) agar ejaan Romaji tidak kacau menjadi Pinyin Cina.\n\n" +
                                "• v1.0.50: Clean Settings Changelog View:\n" +
                                "  - Tampilan Catatan Rilis di Settings kini rapi dalam bentuk kartu ringkas; detail hanya muncul saat di-klik (modal pop-up).\n\n" +
                                "• v1.0.49: Multi-Tier Dual-Script Lyrics Search (Kanji + Romaji):\n" +
                                "  - Jika lagu Jepang disimpan dengan judul Kanji/Kana, sistem otomatis mencari versi asli dan versi Romaji-nya sekaligus ke LRCLIB agar peluang lirik ditemukan naik drastis.\n\n" +
                                "• v1.0.48: Real-Time Romaji Transliteration Toggle:\n" +
                                "  - Tombol toggle [ 🔤 ROMAJI ] di tab LIRIK SYNC: otomatis mengubah lirik Jepang (Kanji & Kana) / Korea menjadi teks alfabet Latin (Romaji).\n\n" +
                                "• v1.0.47: Universal Auto-Heal Metrics for Imported Playlists:\n" +
                                "  - Menambahkan crawler background auto-heal metrik plays & badge POPULER untuk seluruh lagu di playlist yang diimpor dari Spotify/YouTube.\n\n" +
                                "• v1.0.46: Auto Fallback Search & Auto-Skip Broken Tracks (1:1 YT Music):\n" +
                                "  - Jika track dihapus/region lock (video not found), otomatis fallback search track alternatif di YT.\n" +
                                "  - Jika lagu tetap unplayable di antrean, player otomatis auto-skip ke track berikutnya tanpa macet.\n\n" +
                                "• v1.0.45: Fix OkHttpDataSource & Prioritas IPv4 Google Video CDN:\n" +
                                "  - Ganti engine HTTP ExoPlayer ke OkHttpDataSource dengan IPv4-first DNS handler.\n" +
                                "  - Eliminasi bug socket timeout IPv6 Android saat connect ke subdomain googlevideo.com.\n\n" +
                                "• v1.0.44: Fix Cold-Start Playback Binding & Guaranteed Service Readiness.\n\n" +
                                "• v1.0.43: Fix CDN Streaming Handshake & Accurate Error Reporting.\n\n" +
                                "• v1.0.42: Fitur Import Playlist YouTube Music & Spotify.\n\n" +
                                "• v1.0.41: Algoritma Pencarian Album YouTube Music Presisi.\n\n" +
                                "• v1.0.40: Strict Relevance Filter untuk Pencarian Album.\n\n" +
                                "• v1.0.35: Multi-thread Downloader & Official Music First.\n\n" +
                                "• v1.0.30: Production Keystore Signing (RSA 4096-bit).",
                        color = palette.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = { showChangelogDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary)
                ) {
                    Text("TUTUP", color = palette.background, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = palette.surfaceVariant
        )
    }

    if (showQrDialog != null) {
        AlertDialog(
            onDismissRequest = { showQrDialog = null },
            title = {
                Text(
                    text = "QR Sinkronisasi Antar-Perangkat",
                    color = palette.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Scan kode QR ini menggunakan kamera Puki Music di iPhone/iPad/PC untuk menghubungkan sinkronisasi.",
                        color = palette.textSecondary,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Image(
                        bitmap = showQrDialog!!.asImageBitmap(),
                        contentDescription = "Pairing QR Code",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Puki Sync URI", currentPairingUri)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Link pairing disalin ke Clipboard!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(6.dp),
                        border = BorderStroke(1.dp, palette.primary)
                    ) {
                        Text("SALIN LINK PAIRING", color = palette.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showQrDialog = null }) {
                    Text("TUTUP", color = palette.primary, fontWeight = FontWeight.Bold)
                }
            },
            containerColor = palette.surfaceVariant
        )
    }
}


