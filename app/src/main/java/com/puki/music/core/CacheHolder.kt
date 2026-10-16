package com.puki.music.core

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

@OptIn(UnstableApi::class)
object CacheHolder {
    @Volatile
    private var instance: SimpleCache? = null

    @Synchronized
    fun getCache(context: Context): SimpleCache {
        return instance ?: synchronized(this) {
            instance ?: run {
                val cacheDir = File(context.cacheDir, "puki_media_cache")
                val evictor = LeastRecentlyUsedCacheEvictor(512 * 1024 * 1024L) // 512 MB LRU
                val databaseProvider = StandaloneDatabaseProvider(context.applicationContext)
                SimpleCache(cacheDir, evictor, databaseProvider).also { instance = it }
            }
        }
    }
}
