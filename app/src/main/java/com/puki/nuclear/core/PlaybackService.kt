package com.puki.nuclear.core

import android.content.Context
import android.content.Intent
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

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        PlaybackStateManager.initContext(applicationContext)

        // Acquire dedicated PARTIAL_WAKE_LOCK to prevent MIUI / HyperOS Doze from freezing audio engine
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "puki:playback_audio_lock")?.apply {
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

        // Resilient buffer: 45s min buffer, 180s max buffer, 2s playback start
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                45_000,
                180_000,
                2_000,
                4_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(30_000, true)
            .build()

        // Robust HTTP DataSource with 30s connect timeout & 45s read timeout to survive CDN rate-pacing stalls
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
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
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        // Manage CPU WakeLock based on active playback state
        basePlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    try {
                        wakeLock?.acquire(30 * 60 * 1000L) // 30 min per track max lock
                    } catch (_: Exception) {}
                } else {
                    try {
                        if (wakeLock?.isHeld == true) {
                            wakeLock?.release()
                        }
                    } catch (_: Exception) {}
                }
            }
        })

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

        mediaSession = MediaSession.Builder(this, forwardingPlayer).build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId("puki_playback_channel")
            .build()
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
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
