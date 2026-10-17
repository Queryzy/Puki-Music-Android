package com.puki.music.core

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.puki.music.data.LocalMusicStore
import com.puki.music.model.RepeatMode
import com.puki.music.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

@OptIn(UnstableApi::class)
object PlaybackStateManager {
    private const val TAG = "PlaybackStateManager"

    // Persistent Process-level Coroutine Scope (100% independent of Activity or UI Composable lifecycle)
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var appContext: Context? = null

    private val _activeTrack = MutableStateFlow<Track?>(null)
    val activeTrack = _activeTrack.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<Track>>(emptyList())
    val currentQueue = _currentQueue.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isResolvingStream = MutableStateFlow(false)
    val isResolvingStream = _isResolvingStream.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.ALL)
    val repeatMode = _repeatMode.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle = _isShuffle.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs = _playbackDurationMs.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()

    @Volatile
    var player: Player? = null

    private var resolveJob: Job? = null
    private var progressJob: Job? = null
    private val preResolvedUrls = java.util.concurrent.ConcurrentHashMap<String, StreamResolver.StreamResolution>()

    init {
        startProgressTracker()
    }

    fun initContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = serviceScope.launch {
            while (true) {
                try {
                    val p = player
                    if (p != null && p.isPlaying) {
                        val curPos = p.currentPosition.coerceAtLeast(0L)
                        val dur = p.duration.coerceAtLeast(0L)
                        _playbackPositionMs.value = curPos
                        _playbackDurationMs.value = dur

                        // Pre-resolve next track in queue 20s before current track finishes (1:1 Spotify & YT Music Gapless)
                        if (dur > 20_000L && curPos >= dur - 20_000L) {
                            preResolveNextTrack()
                        }
                    }
                } catch (_: Exception) {}
                delay(500)
            }
        }
    }

    private fun preResolveNextTrack() {
        val queue = _currentQueue.value
        val track = _activeTrack.value ?: return
        if (queue.size <= 1) return

        val curIdx = queue.indexOfFirst { it.id == track.id }
        val nextTrack = if (_isShuffle.value) {
            queue.filter { it.id != track.id }.randomOrNull()
        } else if (curIdx in 0 until queue.size - 1) {
            queue[curIdx + 1]
        } else if (_repeatMode.value == RepeatMode.ALL) {
            queue.firstOrNull()
        } else null

        if (nextTrack != null && !preResolvedUrls.containsKey(nextTrack.id)) {
            val ctx = appContext
            val isOffline = ctx != null && OfflineManager.getLocalAudioFile(ctx, nextTrack.id)?.exists() == true
            if (!isOffline) {
                serviceScope.launch(Dispatchers.IO) {
                    StreamResolver.resolveAudioStreamDetailed(nextTrack.id).onSuccess { res ->
                        preResolvedUrls[nextTrack.id] = res
                        Log.d(TAG, "Gapless pre-resolved next track: ${nextTrack.title}")
                    }
                }
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    fun setShuffle(shuffle: Boolean) {
        _isShuffle.value = shuffle
    }

    fun setPlayerInstance(p: Player) {
        player = p
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                _isPlaying.value = playing
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    handleTrackEnded()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "ExoPlayer error [${error.errorCodeName} / ${error.errorCode}]: ${error.message}")
                _errorMessage.value = "Playback error (${error.errorCodeName}): ${error.message}"
                handlePlayerError()
            }
        })
    }

    fun playTrack(context: Context, track: Track, newQueue: List<Track>? = null) {
        initContext(context)

        // Ensure PlaybackService is explicitly started so player instance is ready
        try {
            val serviceIntent = Intent(context, PlaybackService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (_: Exception) {}

        resolveJob?.cancel()
        PreCacheManager.cancel()
        _activeTrack.value = track
        _errorMessage.value = null
        if (newQueue != null && newQueue.isNotEmpty()) {
            _currentQueue.value = newQueue
        } else if (_currentQueue.value.none { it.id == track.id }) {
            _currentQueue.value = _currentQueue.value + track
        }

        try {
            LocalMusicStore(context).recordPlay(track)
        } catch (_: Exception) {}

        val localFile = OfflineManager.getLocalAudioFile(context, track.id)
        if (localFile != null && localFile.exists()) {
            // Local file playback (Pure 0 latency, 100% offline)
            _isResolvingStream.value = false
            resolveJob = serviceScope.launch {
                val artworkBytes = fetchArtworkBytes(track.thumbnailUrl)
                withContext(Dispatchers.Main) {
                    val p = player ?: return@withContext
                    val metadata = MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(if (track.thumbnailUrl.isNotBlank()) Uri.parse(track.thumbnailUrl) else null)
                        .apply {
                            if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                                setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                            }
                        }
                        .build()
                    val mediaItem = MediaItem.Builder()
                        .setMediaId(track.id)
                        .setUri(Uri.fromFile(localFile))
                        .setMediaMetadata(metadata)
                        .build()
                    p.setMediaItem(mediaItem)
                    p.prepare()
                    p.play()
                    _isPlaying.value = true
                    _isResolvingStream.value = false
                }
            }
            return
        }

        // Instant Gapless Transition: if stream was already pre-resolved in RAM, play immediately (0ms latency)!
        val preResolved = preResolvedUrls.remove(track.id)
        if (preResolved != null) {
            _isResolvingStream.value = false
            resolveJob = serviceScope.launch(Dispatchers.IO) {
                val views = if (preResolved.viewCount > 0) preResolved.viewCount else track.viewCount
                val likes = if (preResolved.likeCount > 0) preResolved.likeCount else track.likeCount
                val updatedTrack = track.copy(
                    streamUrl = preResolved.streamUrl,
                    viewCount = views,
                    likeCount = likes
                )
                val artworkBytes = fetchArtworkBytes(track.thumbnailUrl)
                withContext(Dispatchers.Main) {
                    val p = player
                    if (p != null) {
                        val metadata = MediaMetadata.Builder()
                            .setTitle(updatedTrack.title)
                            .setArtist(updatedTrack.artist)
                            .setArtworkUri(if (updatedTrack.thumbnailUrl.isNotBlank()) Uri.parse(updatedTrack.thumbnailUrl) else null)
                            .apply {
                                if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                }
                            }
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setMediaId(updatedTrack.id)
                            .setUri(preResolved.streamUrl)
                            .setMediaMetadata(metadata)
                            .build()
                        p.setMediaItem(mediaItem)
                        p.prepare()
                        p.play()
                        _isPlaying.value = true
                    }
                }
            }
            return
        }

        // Online streaming playback (Handled strictly in background)
        _isResolvingStream.value = true
        resolveJob = serviceScope.launch(Dispatchers.IO) {
            val streamRes = StreamResolver.resolveAudioStreamDetailed(track.id)
            streamRes.onSuccess { detailed ->
                val updatedTrack = track.copy(
                    streamUrl = detailed.streamUrl,
                    viewCount = detailed.viewCount.takeIf { it > 0 } ?: track.viewCount,
                    likeCount = detailed.likeCount.takeIf { it > 0 } ?: track.likeCount
                )
                val artworkBytes = fetchArtworkBytes(track.thumbnailUrl)

                withContext(Dispatchers.Main) {
                    _activeTrack.value = updatedTrack
                    var p = player
                    if (p == null) {
                        // Wait up to 2 seconds for PlaybackService player binding if cold starting
                        for (i in 1..10) {
                            delay(200)
                            p = player
                            if (p != null) break
                        }
                    }
                    if (p != null) {
                        val metadata = MediaMetadata.Builder()
                            .setTitle(updatedTrack.title)
                            .setArtist(updatedTrack.artist)
                            .setArtworkUri(if (updatedTrack.thumbnailUrl.isNotBlank()) Uri.parse(updatedTrack.thumbnailUrl) else null)
                            .apply {
                                if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                }
                            }
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setMediaId(updatedTrack.id)
                            .setUri(detailed.streamUrl)
                            .setMediaMetadata(metadata)
                            .build()
                        p.setMediaItem(mediaItem)
                        p.prepare()
                        p.play()
                        _isPlaying.value = true
                    }
                    _isResolvingStream.value = false
                    PreCacheManager.preCacheCurrentAndNextTrack(context, detailed.streamUrl)
                }
            }.onFailure { err ->
                withContext(Dispatchers.Main) {
                    _isResolvingStream.value = false
                    Log.e(TAG, "Stream failed for track ${track.title}: ${err.message}", err)
                    _errorMessage.value = "Gagal memuat stream audio: ${err.message}"

                    // 1:1 YT Music Behavior: Auto skip to next track in queue if track is deleted/region blocked/broken
                    val queue = _currentQueue.value
                    if (queue.size > 1) {
                        val curIdx = queue.indexOfFirst { it.id == track.id }
                        if (curIdx != -1 && curIdx < queue.size - 1) {
                            Log.i(TAG, "Auto-skipping broken track '${track.title}' to next track...")
                            playNext()
                        }
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) {
                p.prepare()
            }
            p.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs)
    }

    fun playNext() {
        val ctx = appContext ?: return
        val queue = _currentQueue.value
        val track = _activeTrack.value
        if (queue.isEmpty()) return

        val nextTrack = if (_isShuffle.value && queue.size > 1) {
            queue.filter { it.id != track?.id }.randomOrNull() ?: queue.first()
        } else {
            val curIdx = queue.indexOfFirst { it.id == track?.id }
            val nextIdx = if (curIdx in 0 until queue.size - 1) curIdx + 1 else 0
            queue[nextIdx]
        }
        playTrack(ctx, nextTrack)
    }

    fun playPrevious() {
        val p = player
        if (p != null && p.currentPosition > 5000L) {
            p.seekTo(0)
            return
        }
        val ctx = appContext ?: return
        val queue = _currentQueue.value
        val track = _activeTrack.value
        if (queue.isEmpty()) return

        val curIdx = queue.indexOfFirst { it.id == track?.id }
        val prevIdx = if (curIdx > 0) curIdx - 1 else queue.size - 1
        playTrack(ctx, queue[prevIdx])
    }

    private fun handleTrackEnded() {
        val queue = _currentQueue.value
        val track = _activeTrack.value
        val p = player ?: return

        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                p.seekTo(0)
                p.play()
            }
            RepeatMode.ALL -> {
                if (queue.isNotEmpty()) {
                    playNext()
                }
            }
            RepeatMode.OFF -> {
                val curIdx = queue.indexOfFirst { it.id == track?.id }
                if (curIdx in 0 until queue.size - 1) {
                    playNext()
                } else {
                    p.pause()
                }
            }
        }
    }

    private fun handlePlayerError() {
        val ctx = appContext ?: return
        val curTrack = _activeTrack.value ?: return
        val p = player ?: return
        val currentPos = p.currentPosition.coerceAtLeast(0L)

        serviceScope.launch(Dispatchers.IO) {
            delay(1000)
            val localFile = OfflineManager.getLocalAudioFile(ctx, curTrack.id)
            if (localFile != null && localFile.exists()) {
                withContext(Dispatchers.Main) {
                    p.prepare()
                    if (currentPos > 0) p.seekTo(currentPos)
                    p.play()
                }
            } else {
                val streamRes = StreamResolver.resolveAudioStreamDetailed(curTrack.id)
                streamRes.onSuccess { detailed ->
                    val artworkBytes = fetchArtworkBytes(curTrack.thumbnailUrl)
                    withContext(Dispatchers.Main) {
                        val metadata = MediaMetadata.Builder()
                            .setTitle(curTrack.title)
                            .setArtist(curTrack.artist)
                            .setArtworkUri(if (curTrack.thumbnailUrl.isNotBlank()) Uri.parse(curTrack.thumbnailUrl) else null)
                            .apply {
                                if (artworkBytes != null && artworkBytes.isNotEmpty()) {
                                    setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                                }
                            }
                            .build()
                        val mediaItem = MediaItem.Builder()
                            .setMediaId(curTrack.id)
                            .setUri(detailed.streamUrl)
                            .setMediaMetadata(metadata)
                            .build()
                        p.setMediaItem(mediaItem)
                        p.prepare()
                        if (currentPos > 0) p.seekTo(currentPos)
                        p.play()
                    }
                }.onFailure {
                    withContext(Dispatchers.Main) {
                        p.prepare()
                        if (currentPos > 0) p.seekTo(currentPos)
                        p.play()
                    }
                }
            }
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
}
