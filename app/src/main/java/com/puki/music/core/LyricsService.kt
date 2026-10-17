package com.puki.music.core

import android.content.Context
import com.puki.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsResult(
    val isSynced: Boolean,
    val lines: List<LyricLine> = emptyList(),
    val plainLyrics: String? = null
)

object LyricsService {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val lrcPattern = Pattern.compile("\\[(\\d{2}):(\\d{2}(?:\\.\\d+)?)\\](.*)")

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "lyrics")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun sanitizeFilename(id: String): String {
        return id.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
    }

    private fun cleanSongTitle(title: String): String {
        return title
            .replace("(?i)\\b(official|music|video|audio|remastered|remaster|hd|4k|hq|visualizer|lyric video|lyrics|explicit|clean|original soundtrack|ost)\\b".toRegex(), "")
            .replace("(?i)\\s*-\\s*(from|live|acoustic|instrumental|theme).*".toRegex(), "")
            .replace("(?i)\\(.*?\\)".toRegex(), "")
            .replace("(?i)\\[.*?\\]".toRegex(), "")
            .replace("(?i)ft\\..*".toRegex(), "")
            .replace("(?i)feat\\..*".toRegex(), "")
            .trim()
            .replace("\\s+".toRegex(), " ")
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace(" - Topic", "")
            .replace("VEVO", "")
            .trim()
    }

    fun parseLrc(lrcText: String): List<LyricLine> {
        val lines = mutableListOf<LyricLine>()
        for (rawLine in lrcText.lines()) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue
            val matcher = lrcPattern.matcher(trimmed)
            if (matcher.matches()) {
                val min = matcher.group(1)?.toLongOrNull() ?: 0L
                val secDouble = matcher.group(2)?.toDoubleOrNull() ?: 0.0
                val text = matcher.group(3)?.trim().orEmpty()
                val timeMs = (min * 60 * 1000) + (secDouble * 1000).toLong()
                lines.add(LyricLine(timeMs = timeMs, text = text))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    fun findActiveLyricIndex(lines: List<LyricLine>, currentPositionMs: Long): Int {
        if (lines.isEmpty()) return -1
        for (i in lines.indices.reversed()) {
            if (currentPositionMs >= lines[i].timeMs) {
                return i
            }
        }
        return 0
    }

    suspend fun getLyrics(context: Context, track: Track): Result<LyricsResult> = withContext(Dispatchers.IO) {
        runCatching {
            val cacheFile = File(getCacheDir(context), "${sanitizeFilename(track.id)}.json")

            // 1. Check local file cache
            if (cacheFile.exists() && cacheFile.length() > 20) {
                try {
                    val jsonStr = cacheFile.readText()
                    val obj = JSONObject(jsonStr)
                    val isSynced = obj.optBoolean("isSynced", false)
                    val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() }
                    val lines = mutableListOf<LyricLine>()
                    val linesArr = obj.optJSONArray("lines")
                    if (linesArr != null) {
                        for (i in 0 until linesArr.length()) {
                            val lineObj = linesArr.getJSONObject(i)
                            lines.add(LyricLine(lineObj.getLong("timeMs"), lineObj.getString("text")))
                        }
                    }
                    if (lines.isNotEmpty() || plain != null) {
                        return@runCatching LyricsResult(isSynced = isSynced, lines = lines, plainLyrics = plain)
                    }
                } catch (_: Exception) {}
            }

            // 2. Fetch from LRCLIB
            val cleanTitle = cleanSongTitle(track.title)
            val cleanArtist = cleanArtistName(track.artist)
            val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            val encTitle = URLEncoder.encode(cleanTitle, "UTF-8")

            var jsonObject: JSONObject? = null

            // Try exact get first
            val getUrl = "https://lrclib.net/api/get?artist_name=$encArtist&track_name=$encTitle&duration=${track.durationSeconds}"
            val req = Request.Builder()
                .url(getUrl)
                .header("User-Agent", "PukiMusic/1.0 (https://github.com/nukeop/nuclear)")
                .build()

            val res = try { httpClient.newCall(req).execute() } catch (_: Exception) { null }
            if (res != null && res.isSuccessful) {
                val bodyStr = res.body?.string().orEmpty()
                if (bodyStr.isNotBlank()) {
                    jsonObject = JSONObject(bodyStr)
                }
            }

            // Fallback: search query
            if (jsonObject == null || (jsonObject.optString("syncedLyrics").isBlank() && jsonObject.optString("plainLyrics").isBlank())) {
                val searchQueries = mutableListOf<String>()
                searchQueries.add("$cleanArtist $cleanTitle")
                searchQueries.add(cleanTitle)
                searchQueries.add("${track.artist} ${track.title}")
                searchQueries.add(track.title)

                // Jika judul ada unsur kanji/kana, tambahkan juga versi romaji-nya ke antrean search
                val romajiTitle = RomajiHelper.toRomaji(cleanTitle)
                if (romajiTitle != cleanTitle && romajiTitle.isNotBlank()) {
                    searchQueries.add("$cleanArtist $romajiTitle")
                    searchQueries.add(romajiTitle)
                }

                val distinctQueries = searchQueries.distinct()

                for (q in distinctQueries) {
                    val searchUrl = "https://lrclib.net/api/search?q=${URLEncoder.encode(q, "UTF-8")}"
                    val searchReq = Request.Builder()
                        .url(searchUrl)
                        .header("User-Agent", "PukiMusic/1.0 (https://github.com/nukeop/nuclear)")
                        .build()

                    val searchRes = try { httpClient.newCall(searchReq).execute() } catch (_: Exception) { null }
                    if (searchRes != null && searchRes.isSuccessful) {
                        val searchBody = searchRes.body?.string().orEmpty()
                        if (searchBody.isNotBlank()) {
                            val arr = JSONArray(searchBody)
                            if (arr.length() > 0) {
                                var bestObj = arr.getJSONObject(0)
                                for (i in 0 until arr.length()) {
                                    val item = arr.getJSONObject(i)
                                    if (item.optString("syncedLyrics").isNotBlank()) {
                                        bestObj = item
                                        break
                                    }
                                }
                                jsonObject = bestObj
                                if (jsonObject.optString("syncedLyrics").isNotBlank()) break
                            }
                        }
                    }
                }
            }

            if (jsonObject == null) {
                throw IllegalStateException("Lirik tidak ditemukan di database LRCLIB")
            }

            val synced = jsonObject.optString("syncedLyrics", "").trim()
            val plain = jsonObject.optString("plainLyrics", "").trim().takeIf { it.isNotBlank() }

            val parsedLines = if (synced.isNotBlank()) parseLrc(synced) else emptyList()
            val isSynced = parsedLines.isNotEmpty()

            val result = LyricsResult(
                isSynced = isSynced,
                lines = parsedLines,
                plainLyrics = if (!isSynced) plain else null
            )

            // Cache to disk
            try {
                val cacheObj = JSONObject()
                cacheObj.put("isSynced", result.isSynced)
                cacheObj.put("plainLyrics", result.plainLyrics ?: "")
                val linesArr = JSONArray()
                for (line in result.lines) {
                    val lineObj = JSONObject()
                    lineObj.put("timeMs", line.timeMs)
                    lineObj.put("text", line.text)
                    linesArr.put(lineObj)
                }
                cacheObj.put("lines", linesArr)
                cacheFile.writeText(cacheObj.toString())
            } catch (_: Exception) {}

            result
        }
    }
}
