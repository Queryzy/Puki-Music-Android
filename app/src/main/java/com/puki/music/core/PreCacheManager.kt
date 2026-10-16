package com.puki.music.core

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
object PreCacheManager {
    private const val TAG = "PreCacheManager"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var currentCacheJob: Job? = null

    fun preCacheCurrentAndNextTrack(context: Context, currentStreamUrl: String) {
        currentCacheJob?.cancel()
        // ExoPlayer's CacheDataSource + DefaultLoadControl (180s buffer) already caches 100% of the active
        // stream natively to disk without thread-locking SimpleCache or triggering YouTube CDN concurrency drops.
        currentCacheJob = scope.launch {
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
                            Log.d(TAG, "Pre-resolving metadata for next track: ${nextTrack.title}")
                            StreamResolver.resolveAudioStreamDetailed(nextTrack.id)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pre-cache non-fatal warning: ${e.message}")
            }
        }
    }

    fun cancel() {
        currentCacheJob?.cancel()
    }
}
