package com.puki.nuclear.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val changelog: String,
    val downloadUrl: String,
    val apkSize: Long
)

object AppUpdateManager {

    private const val GITEA_API_URL = "https://gitea.tsolusi.com/api/v1/repos/nanda/puki-music/releases/latest"
    // Scoped read-only updater token for repo nanda/puki-music
    private const val UPDATER_TOKEN = "ee8c3835fc4d54b5925ebfb4ba750c2f9a6ed62c"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun isVersionNewer(remoteTag: String, currentVersion: String): Boolean {
        val r = remoteTag.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        val c = currentVersion.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rPart = r.getOrElse(i) { 0 }
            val cPart = c.getOrElse(i) { 0 }
            if (rPart > cPart) return true
            if (rPart < cPart) return false
        }
        return false
    }

    suspend fun checkForUpdate(currentVersionName: String): Result<ReleaseInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(GITEA_API_URL)
                .header("Authorization", "token $UPDATER_TOKEN")
                .header("Accept", "application/json")
                .build()

            val res = httpClient.newCall(req).execute()
            if (!res.isSuccessful) {
                throw IllegalStateException("Gitea API HTTP ${res.code}")
            }

            val body = res.body?.string() ?: throw IllegalStateException("Empty response body")
            val json = JSONObject(body)

            val tag = json.optString("tag_name", "")
            val name = json.optString("name", tag)
            val notes = json.optString("body", "Pembaruan versi terbaru Puki Music.")

            if (tag.isBlank() || !isVersionNewer(tag, currentVersionName)) {
                return@runCatching null
            }

            var downloadUrl = ""
            var apkSize = 0L

            val assets = json.optJSONArray("assets")
            if (assets != null && assets.length() > 0) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        apkSize = asset.optLong("size", 0L)
                        break
                    }
                }
            }

            if (downloadUrl.isBlank()) {
                downloadUrl = "https://gitea.tsolusi.com/nanda/puki-music/releases/download/$tag/Puki-Music.apk"
            }

            ReleaseInfo(
                tagName = tag,
                versionName = tag.removePrefix("v"),
                changelog = notes,
                downloadUrl = downloadUrl,
                apkSize = apkSize
            )
        }
    }

    suspend fun downloadUpdateApk(
        context: Context,
        downloadUrl: String,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val updatesDir = File(context.cacheDir, "updates")
            if (!updatesDir.exists()) updatesDir.mkdirs()

            val apkFile = File(updatesDir, "Puki-Music-latest.apk")
            val partFile = File(updatesDir, "Puki-Music-latest.apk.part")
            if (partFile.exists()) partFile.delete()

            val req = Request.Builder()
                .url(downloadUrl)
                .header("Authorization", "token $UPDATER_TOKEN")
                .build()

            val res = httpClient.newCall(req).execute()
            if (!res.isSuccessful) {
                throw IllegalStateException("Gagal unduh APK: HTTP ${res.code}")
            }

            val body = res.body ?: throw IllegalStateException("Empty response body")
            val totalBytes = body.contentLength()
            var downloaded = 0L

            body.byteStream().use { input ->
                FileOutputStream(partFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    var lastReported = -1
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        val progress = if (totalBytes > 0) {
                            ((downloaded * 100) / totalBytes).coerceIn(0L, 100L).toInt()
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

            if (apkFile.exists()) apkFile.delete()
            if (!partFile.renameTo(apkFile)) {
                partFile.copyTo(apkFile, overwrite = true)
                partFile.delete()
            }

            onProgress(100)
            apkFile
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    fun installUpdate(context: Context, apkFile: File): Result<Unit> {
        return runCatching {
            val authority = "${context.packageName}.provider"
            val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        }
    }
}
