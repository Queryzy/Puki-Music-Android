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
        return id.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }

    fun isTrackDownloaded(context: Context, trackId: String): Boolean {
        val norm = sanitizeFilename(trackId)
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

            val request = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: gagal mengunduh stream")
            }

            val body = response.body ?: throw IllegalStateException("Empty response body")
            val totalBytes = body.contentLength()
            val estimatedBytes = if (totalBytes > 0) totalBytes else (track.durationSeconds.coerceAtLeast(30) * 16_000L)
            var downloadedBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(targetFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var read: Int
                    var lastReported = 0
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedBytes += read
                        val progress = ((downloadedBytes * 100) / estimatedBytes).coerceIn(0L, 99L).toInt()
                        if (progress != lastReported) {
                            lastReported = progress
                            onProgress(progress)
                        }
                    }
                    output.flush()
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
                    if (thumbRes.isSuccessful) {
                        thumbRes.body?.byteStream()?.use { input ->
                            FileOutputStream(thumbFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                        localThumb = thumbFile.absolutePath
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
        activeDownloads[track.id] = 0
        return try {
            downloadTrack(context, track) { pct ->
                activeDownloads[track.id] = pct
            }
        } finally {
            activeDownloads.remove(track.id)
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

                activeDownloads[track.id] = 0
                val res = try {
                    downloadTrack(context, track) { pct ->
                        activeDownloads[track.id] = pct
                        val overall = (((index * 100) + pct) / toDownload.size).coerceIn(0, 100)
                        activeBatch.value = activeBatch.value?.copy(
                            currentTrackPercent = pct,
                            overallPercent = overall
                        )
                    }
                } finally {
                    activeDownloads.remove(track.id)
                }

                if (res.isSuccess) {
                    completed++
                    onTrackCompleted(track)
                }
            }
        } finally {
            activeBatch.value = null
        }
        return completed
    }

    fun deleteDownloadedTrack(context: Context, trackId: String): Boolean {
        val file = getLocalAudioFile(context, trackId)
        val norm = sanitizeFilename(trackId)
        val thumbFile = File(getStorageDir(context), "$norm.jpg")
        if (thumbFile.exists()) thumbFile.delete()

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
