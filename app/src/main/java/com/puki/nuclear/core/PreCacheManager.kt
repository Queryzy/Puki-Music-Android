package com.puki.nuclear.core

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
object PreCacheManager {
    private const val TAG = "PreCacheManager"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentCacheJob: Job? = null
    @Volatile
    private var currentWriter: CacheWriter? = null

    fun preCacheCurrentAndNextTrack(context: Context, currentStreamUrl: String) {
        currentCacheJob?.cancel()
        try {
            currentWriter?.cancel()
        } catch (_: Exception) {}

        currentCacheJob = scope.launch {
            val simpleCache = CacheHolder.getCache(context.applicationContext)

            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(20_000)
                .setReadTimeoutMs(30_000)
                .setAllowCrossProtocolRedirects(true)

            val cacheDataSource = CacheDataSource.Factory()
                .setCache(simpleCache)
                .setUpstreamDataSourceFactory(httpFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()

            // 1. Pre-cache CURRENT active playing track immediately at full bandwidth
            try {
                val curDataSpec = DataSpec.Builder()
                    .setUri(Uri.parse(currentStreamUrl))
                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                    .build()

                val writer = CacheWriter(
                    cacheDataSource,
                    curDataSpec,
                    ByteArray(128 * 1024),
                    null
                )
                currentWriter = writer
                writer.cache()
                Log.d(TAG, "Current track 100% pre-cached to disk successfully.")
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Current track pre-cache non-fatal warning: ${e.message}")
            }

            // 2. Pre-cache NEXT upcoming track in queue to achieve 0ms gapless Spotify transitions
            try {
                val queue = PlaybackStateManager.currentQueue.value
                val activeTrack = PlaybackStateManager.activeTrack.value
                if (queue.isNotEmpty() && activeTrack != null) {
                    val curIdx = queue.indexOfFirst { it.id == activeTrack.id }
                    val nextTrack = if (PlaybackStateManager.isShuffle.value && queue.size > 1) {
                        queue.filter { it.id != activeTrack.id }.randomOrNull()
                    } else if (curIdx in 0 until queue.size - 1) {
                        queue[curIdx + 1]
                    } else null

                    if (nextTrack != null) {
                        val isAlreadyOffline = OfflineManager.getLocalAudioFile(context, nextTrack.id)?.exists() == true
                        if (!isAlreadyOffline) {
                            Log.d(TAG, "Pre-resolving next track stream: ${nextTrack.title}")
                            val res = StreamResolver.resolveAudioStreamDetailed(nextTrack.id)
                            res.onSuccess { detailed ->
                                val nextDataSpec = DataSpec.Builder()
                                    .setUri(Uri.parse(detailed.streamUrl))
                                    .setFlags(DataSpec.FLAG_ALLOW_CACHE_FRAGMENTATION)
                                    .build()

                                val nextWriter = CacheWriter(
                                    cacheDataSource,
                                    nextDataSpec,
                                    ByteArray(128 * 1024),
                                    null
                                )
                                currentWriter = nextWriter
                                nextWriter.cache()
                                Log.d(TAG, "Next track 100% pre-cached to disk: ${nextTrack.title}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Next track pre-cache non-fatal warning: ${e.message}")
            }
        }
    }

    fun cancel() {
        currentCacheJob?.cancel()
        try {
            currentWriter?.cancel()
        } catch (_: Exception) {}
    }
}
