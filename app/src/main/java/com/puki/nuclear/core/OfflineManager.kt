package com.puki.nuclear.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import com.puki.nuclear.data.LocalMusicStore
import com.puki.nuclear.model.NetworkDownloadPreference
import com.puki.nuclear.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class BatchProgressState(
    val batchId: String,
    val title: String,
    val currentItem: Int,
    val totalItems: Int,
    val currentTrackTitle: String,
    val currentTrackPercent: Int,
    val overallPercent: Int
)

object OfflineManager {

    // Reactive progress state observed directly by Jetpack Compose UI
    val activeDownloads = mutableStateMapOf<String, Int>() // trackId -> percent (0..100)
    val activeBatch = mutableStateOf<BatchProgressState?>(null)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val thumbHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, "offline_tracks")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun sanitizeFilename(id: String): String {
        val norm = LocalMusicStore.normalizeId(id)
        return norm.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }

    fun isTrackDownloaded(context: Context, trackId: String): Boolean {
        val norm = sanitizeFilename(trackId)
        val rawId = trackId.trim()
        val plainNorm = LocalMusicStore.normalizeId(trackId)
        // If actively downloading right now, it is NOT downloaded yet
        if (activeDownloads.containsKey(rawId) || activeDownloads.containsKey(plainNorm) || activeDownloads.containsKey(norm)) {
            return false
        }
        val audioFile = File(getStorageDir(context), "$norm.opus")
        val altFile = File(getStorageDir(context), "$norm.m4a")
        return (audioFile.exists() && audioFile.length() > 100_000) ||
               (altFile.exists() && altFile.length() > 100_000)
    }

    fun getLocalAudioFile(context: Context, trackId: String): File? {
        val norm = sanitizeFilename(trackId)
        val audioFile = File(getStorageDir(context), "$norm.opus")
        if (audioFile.exists() && audioFile.length() > 100_000) return audioFile
        val altFile = File(getStorageDir(context), "$norm.m4a")
        if (altFile.exists() && altFile.length() > 100_000) return altFile
        return null
    }

    suspend fun downloadTrack(
        context: Context,
        track: Track,
        onProgress: (Int) -> Unit = {}
    ): Result<Track> = withContext(Dispatchers.IO) {
        runCatching {
            val streamUrl = StreamResolver.resolveAudioStream(track.id).getOrThrow()
            val storageDir = getStorageDir(context)
            val baseName = sanitizeFilename(track.id)
            val isOpus = streamUrl.contains("opus") || streamUrl.contains("mime=audio%2Fwebm")
            val ext = if (isOpus) "opus" else "m4a"
            val targetFile = File(storageDir, "$baseName.$ext")
            val partFile = File(storageDir, "$baseName.$ext.part")

            // Check if partial download already exists for resuming
            var existingBytes = if (partFile.exists() && partFile.length() > 0) partFile.length() else 0L

            var requestBuilder = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

            if (existingBytes > 0) {
                requestBuilder = requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            var response = httpClient.newCall(requestBuilder.build()).execute()

            // If range request was not satisfiable (HTTP 416), clear partial file and retry clean from byte 0
            if (response.code == 416) {
                response.close()
                if (partFile.exists()) partFile.delete()
                existingBytes = 0L
                val cleanRequest = Request.Builder()
                    .url(streamUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .build()
                response = httpClient.newCall(cleanRequest).execute()
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: gagal mengunduh stream")
                }

                val body = resp.body ?: throw IllegalStateException("Empty response body")
                val isPartial = resp.code == 206
                val append = isPartial && existingBytes > 0

                val totalBytes = if (isPartial) {
                    resp.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
                        ?: (existingBytes + body.contentLength())
                } else {
                    val len = body.contentLength()
                    if (len > 0) len else (track.durationSeconds.coerceAtLeast(30) * 16_000L)
                }

                var downloadedBytes = if (append) existingBytes else 0L

                val initialPct = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).coerceIn(0L, 99L).toInt() else 0
                onProgress(initialPct)

                body.byteStream().use { input ->
                    FileOutputStream(partFile, append).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        var lastReported = initialPct
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            downloadedBytes += read
                            val progress = if (totalBytes > 0) {
                                ((downloadedBytes * 100) / totalBytes).coerceIn(0L, 99L).toInt()
                            } else {
                                0
                            }
                            if (progress != lastReported) {
                                lastReported = progress
                                onProgress(progress)
                            }
                        }
                        output.flush()
                    }
                }

                if (targetFile.exists()) targetFile.delete()
                if (!partFile.renameTo(targetFile)) {
                    partFile.copyTo(targetFile, overwrite = true)
                    partFile.delete()
                }
            }

            onProgress(100)

            // Non-blocking quick thumbnail download with 5s timeout
            var localThumb = track.thumbnailUrl
            if (track.thumbnailUrl.startsWith("http")) {
                try {
                    val thumbFile = File(storageDir, "$baseName.jpg")
                    val thumbReq = Request.Builder().url(track.thumbnailUrl).build()
                    val thumbRes = thumbHttpClient.newCall(thumbReq).execute()
                    thumbRes.use { tResp ->
                        if (tResp.isSuccessful) {
                            tResp.body?.byteStream()?.use { input ->
                                FileOutputStream(thumbFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            localThumb = thumbFile.absolutePath
                        }
                    }
                } catch (_: Exception) {}
            }

            val offlineTrack = track.copy(
                localFilePath = targetFile.absolutePath,
                thumbnailUrl = localThumb
            )

            val store = LocalMusicStore(context)
            store.saveOfflineTrack(offlineTrack, targetFile.absolutePath)
            offlineTrack
        }
    }

    suspend fun downloadTrackWithProgress(
        context: Context,
        track: Track
    ): Result<Track> {
        val normId = LocalMusicStore.normalizeId(track.id)
        activeDownloads[track.id] = 0
        activeDownloads[normId] = 0
        return try {
            downloadTrack(context, track) { pct ->
                activeDownloads[track.id] = pct
                activeDownloads[normId] = pct
            }
        } finally {
            activeDownloads.remove(track.id)
            activeDownloads.remove(normId)
        }
    }

    suspend fun downloadBatchWithProgress(
        context: Context,
        batchId: String,
        batchTitle: String,
        tracks: List<Track>,
        onTrackCompleted: (Track) -> Unit = {}
    ): Int {
        val toDownload = tracks.filterNot { isTrackDownloaded(context, it.id) }
        if (toDownload.isEmpty()) return 0

        var completed = 0
        activeBatch.value = BatchProgressState(
            batchId = batchId,
            title = batchTitle,
            currentItem = 1,
            totalItems = toDownload.size,
            currentTrackTitle = toDownload.first().title,
            currentTrackPercent = 0,
            overallPercent = 0
        )

        try {
            for ((index, track) in toDownload.withIndex()) {
                val currentItemNum = index + 1
                activeBatch.value = activeBatch.value?.copy(
                    currentItem = currentItemNum,
                    currentTrackTitle = track.title,
                    currentTrackPercent = 0,
                    overallPercent = ((index * 100) / toDownload.size)
                )

                val normId = LocalMusicStore.normalizeId(track.id)
                activeDownloads[track.id] = 0
                activeDownloads[normId] = 0
                var res = try {
                    downloadTrack(context, track) { pct ->
                        activeDownloads[track.id] = pct
                        activeDownloads[normId] = pct
                        val overall = (((index * 100) + pct) / toDownload.size).coerceIn(0, 100)
                        activeBatch.value = activeBatch.value?.copy(
                            currentTrackPercent = pct,
                            overallPercent = overall
                        )
                    }
                } catch (e: Exception) {
                    Result.failure(e)
                }

                // If initial download fails, retry once with short delay (resilience against rate-limiting)
                if (res.isFailure) {
                    delay(1200L)
                    res = try {
                        downloadTrack(context, track) { pct ->
                            activeDownloads[track.id] = pct
                            activeDownloads[normId] = pct
                            val overall = (((index * 100) + pct) / toDownload.size).coerceIn(0, 100)
                            activeBatch.value = activeBatch.value?.copy(
                                currentTrackPercent = pct,
                                overallPercent = overall
                            )
                        }
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }

                activeDownloads.remove(track.id)
                activeDownloads.remove(normId)

                if (res.isSuccess) {
                    completed++
                    onTrackCompleted(track)
                }

                // Spacing interval between sequential YouTube CDN requests to avoid anti-bot throttle
                if (index < toDownload.size - 1) {
                    delay(800L)
                }
            }
        } finally {
            activeBatch.value = null
            activeDownloads.clear()
        }
        return completed
    }

    fun deleteDownloadedTrack(context: Context, trackId: String): Boolean {
        val file = getLocalAudioFile(context, trackId)
        val norm = sanitizeFilename(trackId)
        val thumbFile = File(getStorageDir(context), "$norm.jpg")
        if (thumbFile.exists()) thumbFile.delete()
        File(getStorageDir(context), "$norm.opus.part").delete()
        File(getStorageDir(context), "$norm.m4a.part").delete()

        val success = file?.delete() ?: false
        val store = LocalMusicStore(context)
        store.deleteOfflineTrack(trackId)
        return success
    }

    fun deleteAllOfflineTracks(context: Context): Boolean {
        val dir = getStorageDir(context)
        dir.listFiles()?.forEach { it.delete() }
        val store = LocalMusicStore(context)
        store.clearAllOfflineTracks()
        return true
    }

    fun getOfflineStorageUsageBytes(context: Context): Long {
        val dir = getStorageDir(context)
        var total = 0L
        dir.listFiles()?.forEach { total += it.length() }
        return total
    }

    fun getTrackFileSizeBytes(context: Context, trackId: String): Long {
        return getLocalAudioFile(context, trackId)?.length() ?: 0L
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
            bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
            else -> "$bytes B"
        }
    }

    fun getFormattedOfflineUsage(context: Context): String {
        return formatBytes(getOfflineStorageUsageBytes(context))
    }

    fun getAvailableDeviceStorageBytes(context: Context): Long {
        return try {
            val stat = android.os.StatFs(context.filesDir.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            0L
        }
    }

    fun getFormattedAvailableDeviceStorage(context: Context): String {
        return formatBytes(getAvailableDeviceStorageBytes(context))
    }

    fun canAutoDownload(context: Context, preference: NetworkDownloadPreference): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
            val activeNetwork = cm.activeNetwork ?: return false
            val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false

            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            if (!hasInternet) return false

            when (preference) {
                NetworkDownloadPreference.WIFI_ONLY -> {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                }
                NetworkDownloadPreference.ANY_NETWORK -> true
            }
        } catch (_: Exception) {
            false
        }
    }
}
