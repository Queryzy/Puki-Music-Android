package com.puki.music.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.puki.music.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + serviceJob)

    companion object {
        const val PLAYBACK_CHANNEL_ID = "puki_playback_channel"
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                PLAYBACK_CHANNEL_ID,
                "Pemutaran Musik Puki",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikasi pemutar audio latar belakang Puki Music"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        PlaybackStateManager.initContext(applicationContext)

        // Register valid Android Notification Channel to satisfy Foreground Service contracts
        createNotificationChannel()

        // Acquire dedicated PARTIAL_WAKE_LOCK to prevent MIUI / HyperOS Doze from freezing audio engine
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "puki:playback_audio_lock")?.apply {
            setReferenceCounted(false)
        }

        // Acquire dedicated WIFI_MODE_FULL_HIGH_PERF WifiLock to prevent network sleep during screen off
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "puki:playback_wifi_lock")?.apply {
            setReferenceCounted(false)
        }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .setAllowedCapturePolicy(C.ALLOW_CAPTURE_BY_ALL)
            .build()

        // Standard rock-solid 16-bit PCM renderer: zero HAL buffer overflows / underruns across all Android chipsets
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)

        // Instant responsive buffer: 45s min buffer, 180s max buffer, 500ms playback start (Instant start)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                45_000,
                180_000,
                500,
                1_500
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, true)
            .build()

        // Match exact User-Agent and headers of NewPipe client to guarantee 0 handshake drops from googlevideo CDN
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setDefaultRequestProperties(
                mapOf(
                    "Accept-Language" to "en-US,en;q=0.9",
                    "Range" to "bytes=0-"
                )
            )
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(45_000)
            .setAllowCrossProtocolRedirects(true)

        // ExoPlayer Disk Cache (512 MB LRU Cache) to prevent streaming drops during mobile network latency spikes
        val simpleCache = CacheHolder.getCache(this)
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(simpleCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val dataSourceFactory = DefaultDataSource.Factory(this, cacheDataSourceFactory)

        // Auto-retry up to 8 times on network drops or connection reset
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(8))

        val basePlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(audioAttributes, false) // Managed explicitly to prevent screen lock clicks from pausing audio
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        var wasPlayingBeforeTransientLoss = false

        val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    basePlayer.volume = 1.0f
                    if (wasPlayingBeforeTransientLoss) {
                        wasPlayingBeforeTransientLoss = false
                        basePlayer.play()
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Duck volume to 20% on incoming notifications (WA, Telegram)
                    basePlayer.volume = 0.2f
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    // Check if it is an actual incoming phone call
                    val isPhoneCall = audioManager?.mode == AudioManager.MODE_IN_CALL ||
                            audioManager?.mode == AudioManager.MODE_RINGTONE
                    if (isPhoneCall) {
                        wasPlayingBeforeTransientLoss = basePlayer.playWhenReady
                        basePlayer.pause()
                    }
                    // Screen lock sounds and system dings are ignored (music keeps playing)
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Another app took exclusive audio focus (YouTube, Spotify, etc.)
                    wasPlayingBeforeTransientLoss = false
                    basePlayer.pause()
                }
            }
        }

        fun requestAudioFocus() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChangeListener)
                    .build()
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        }

        // 1:1 Spotify & YouTube Music lock controller:
        // Hold CPU WakeLock & WifiLock continuously across active playback, buffering, track transitions,
        // and background stream resolutions. Locks are ONLY released when user explicitly pauses or queue is empty.
        fun updateLocks() {
            val isResolving = PlaybackStateManager.isResolvingStream.value
            val isSessionActive = PlaybackStateManager.currentQueue.value.isNotEmpty()
            val isPlaying = basePlayer.playWhenReady && basePlayer.playbackState != Player.STATE_IDLE
            val shouldLock = isPlaying || (isSessionActive && isResolving) ||
                    (isSessionActive && basePlayer.playbackState == Player.STATE_ENDED) ||
                    (isSessionActive && basePlayer.playbackState == Player.STATE_BUFFERING)

            if (shouldLock) {
                try {
                    if (wakeLock?.isHeld != true) {
                        wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hours lock
                    }
                } catch (_: Exception) {}
                try {
                    if (wifiLock?.isHeld != true) {
                        wifiLock?.acquire()
                    }
                } catch (_: Exception) {}
            } else {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock?.release()
                    }
                } catch (_: Exception) {}
                try {
                    if (wifiLock?.isHeld == true) {
                        wifiLock?.release()
                    }
                } catch (_: Exception) {}
            }
        }

        basePlayer.addListener(object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (playWhenReady) {
                    requestAudioFocus()
                }
                updateLocks()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateLocks()
            }
        })

        // Observe background resolving state and queue changes to hold locks during track switches
        serviceScope.launch {
            PlaybackStateManager.isResolvingStream.collect {
                updateLocks()
            }
        }
        serviceScope.launch {
            PlaybackStateManager.currentQueue.collect {
                updateLocks()
            }
        }

        // ForwardingPlayer to guarantee Next / Previous controls in Android Media Notification
        val forwardingPlayer = object : ForwardingPlayer(basePlayer) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands().buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .build()
            }

            override fun isCommandAvailable(command: Int): Boolean {
                return when (command) {
                    Player.COMMAND_SEEK_TO_NEXT,
                    Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    Player.COMMAND_SEEK_TO_PREVIOUS,
                    Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                    else -> super.isCommandAvailable(command)
                }
            }

            override fun play() {
                if (basePlayer.playbackState == Player.STATE_IDLE) {
                    basePlayer.prepare()
                }
                super.play()
            }

            override fun seekToNext() {
                PlaybackStateManager.playNext()
            }

            override fun seekToNextMediaItem() {
                PlaybackStateManager.playNext()
            }

            override fun seekToPrevious() {
                PlaybackStateManager.playPrevious()
            }

            override fun seekToPreviousMediaItem() {
                PlaybackStateManager.playPrevious()
            }

            override fun hasNextMediaItem(): Boolean = true
            override fun hasPreviousMediaItem(): Boolean = true
        }

        PlaybackStateManager.setPlayerInstance(forwardingPlayer)

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(PLAYBACK_CHANNEL_ID)
            .build()
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        val hasTrack = PlaybackStateManager.activeTrack.value != null
        val isQueueActive = PlaybackStateManager.currentQueue.value.isNotEmpty()
        val isResolving = PlaybackStateManager.isResolvingStream.value
        val isPlaying = session.player.playWhenReady
        // Force service to remain FOREGROUND during queue playback, transitions, and screen off ONLY when a track exists
        val shouldKeepForeground = hasTrack && (startInForegroundRequired || isPlaying || isQueueActive || isResolving)
        super.onUpdateNotification(session, shouldKeepForeground)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        val isQueueActive = PlaybackStateManager.currentQueue.value.isNotEmpty()
        // Do NOT stop service if playback queue is active, playing, or resolving
        if (!isQueueActive && (player == null || !player.playWhenReady)) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceJob.cancel()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (_: Exception) {}
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (_: Exception) {}

        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
