package com.puki.music.core

import android.content.Context
import android.util.Log
import com.puki.music.model.DeezerAlbum
import com.puki.music.model.DeezerArtist
import com.puki.music.model.DeezerDashboardData
import com.puki.music.model.DeezerTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object DeezerRepository {
    private const val TAG = "DeezerRepo"
    private const val BASE_URL = "https://api.deezer.com"
    private const val CACHE_TTL_MS = 2 * 60 * 60 * 1000L // 2 Hours TTL

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun getCacheDir(context: Context): File {
        val dir = File(context.cacheDir, "deezer_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun readCache(context: Context, key: String): String? {
        return try {
            val file = File(getCacheDir(context), "${key.hashCode()}.json")
            if (file.exists() && System.currentTimeMillis() - file.lastModified() < CACHE_TTL_MS) {
                file.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cache read failed for key $key", e)
            null
        }
    }

    private fun writeCache(context: Context, key: String, data: String) {
        try {
            val file = File(getCacheDir(context), "${key.hashCode()}.json")
            file.writeText(data)
        } catch (e: Exception) {
            Log.w(TAG, "Cache write failed for key $key", e)
        }
    }

    private suspend fun fetchJson(context: Context, endpoint: String, useCache: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
        if (useCache) {
            val cached = readCache(context, endpoint)
            if (cached != null) {
                try {
                    return@withContext JSONObject(cached)
                } catch (_: Exception) {}
            }
        }

        val url = if (endpoint.startsWith("http")) endpoint else "$BASE_URL$endpoint"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "PukiMusic/1.0 (Android)")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("Deezer API error: ${response.code} ${response.message}")
        }
        val body = response.body?.string() ?: "{}"
        if (useCache) {
            writeCache(context, endpoint, body)
        }
        JSONObject(body)
    }

    // ── 1. Dashboard Charts (Tracks, Albums, Artists) ───────────────────────────

    suspend fun getDashboardCharts(context: Context): Result<DeezerDashboardData> = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetchJson(context, "/chart/0")

            val tracks = mutableListOf<DeezerTrack>()
            val tracksObj = json.optJSONObject("tracks")
            if (tracksObj != null) {
                val data = tracksObj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    tracks.add(parseTrack(item))
                }
            }

            val albums = mutableListOf<DeezerAlbum>()
            val albumsObj = json.optJSONObject("albums")
            if (albumsObj != null) {
                val data = albumsObj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    albums.add(parseAlbum(item))
                }
            }

            val artists = mutableListOf<DeezerArtist>()
            val artistsObj = json.optJSONObject("artists")
            if (artistsObj != null) {
                val data = artistsObj.optJSONArray("data") ?: JSONArray()
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    artists.add(parseArtist(item))
                }
            }

            DeezerDashboardData(
                topTracks = tracks,
                topAlbums = albums,
                topArtists = artists
            )
        }
    }

    // ── 2. Artist Detail & Discography ────────────────────────────────────────

    suspend fun getArtist(context: Context, artistId: Long): Result<DeezerArtist> = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetchJson(context, "/artist/$artistId")
            parseArtist(json)
        }
    }

    suspend fun getArtistTopTracks(context: Context, artistId: Long, limit: Int = 10): Result<List<DeezerTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetchJson(context, "/artist/$artistId/top?limit=$limit")
            val data = json.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<DeezerTrack>()
            for (i in 0 until data.length()) {
                list.add(parseTrack(data.getJSONObject(i)))
            }
            list
        }
    }

    suspend fun getArtistAlbums(context: Context, artistId: Long, limit: Int = 50): Result<List<DeezerAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val json = fetchJson(context, "/artist/$artistId/albums?limit=$limit")
            val data = json.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<DeezerAlbum>()
            for (i in 0 until data.length()) {
                list.add(parseAlbum(data.getJSONObject(i)))
            }
            list
        }
    }

    // ── 3. Album Detail & Tracks ──────────────────────────────────────────────

    suspend fun getAlbumTracks(context: Context, albumId: Long): Result<List<DeezerTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val albumJson = fetchJson(context, "/album/$albumId")
            val albumTitle = albumJson.optString("title", "")
            val coverUrl = albumJson.optString("cover_xl", albumJson.optString("cover_big", ""))
            val artistObj = albumJson.optJSONObject("artist")
            val artistName = artistObj?.optString("name", "Various Artists") ?: "Various Artists"
            val artistId = artistObj?.optLong("id", 0L) ?: 0L

            val tracksObj = albumJson.optJSONObject("tracks")
            val data = tracksObj?.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<DeezerTrack>()
            for (i in 0 until data.length()) {
                val t = data.getJSONObject(i)
                val tArtist = t.optJSONObject("artist")?.optString("name", artistName) ?: artistName
                val tArtistId = t.optJSONObject("artist")?.optLong("id", artistId) ?: artistId
                list.add(
                    DeezerTrack(
                        id = t.getLong("id"),
                        title = t.optString("title_short", t.optString("title", "")),
                        artistName = tArtist,
                        artistId = tArtistId,
                        albumTitle = albumTitle,
                        albumId = albumId,
                        durationSeconds = t.optLong("duration", 0L),
                        coverUrl = coverUrl,
                        isrc = if (t.has("isrc")) t.optString("isrc") else null
                    )
                )
            }
            list
        }
    }

    // ── 4. Search Clean Catalog (Tracks, Albums, Artists) ───────────────────

    suspend fun searchCleanTracks(context: Context, query: String, limit: Int = 30): Result<List<DeezerTrack>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            // order=RANKING sorts strictly by official popularity/relevance
            val json = fetchJson(context, "/search/track?q=$encoded&order=RANKING&limit=$limit", useCache = true)
            val data = json.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<DeezerTrack>()
            for (i in 0 until data.length()) {
                list.add(parseTrack(data.getJSONObject(i)))
            }
            list
        }
    }

    suspend fun searchCleanAlbums(context: Context, query: String, limit: Int = 20): Result<List<DeezerAlbum>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = fetchJson(context, "/search/album?q=$encoded&order=RANKING&limit=$limit", useCache = true)
            val data = json.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<DeezerAlbum>()
            for (i in 0 until data.length()) {
                list.add(parseAlbum(data.getJSONObject(i)))
            }
            list
        }
    }

    suspend fun searchCleanArtists(context: Context, query: String, limit: Int = 10): Result<List<DeezerArtist>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val json = fetchJson(context, "/search/artist?q=$encoded&order=RANKING&limit=$limit", useCache = true)
            val data = json.optJSONArray("data") ?: JSONArray()
            val list = mutableListOf<DeezerArtist>()
            for (i in 0 until data.length()) {
                list.add(parseArtist(data.getJSONObject(i)))
            }
            list
        }
    }

    // ── Helper Parsers ────────────────────────────────────────────────────────

    private fun parseTrack(item: JSONObject): DeezerTrack {
        val artist = item.optJSONObject("artist")
        val album = item.optJSONObject("album")
        val coverUrl = album?.optString("cover_xl")?.takeIf { it.isNotBlank() }
            ?: album?.optString("cover_big")
            ?: album?.optString("cover_medium")
            ?: ""

        return DeezerTrack(
            id = item.getLong("id"),
            title = item.optString("title_short", item.optString("title", "")),
            artistName = artist?.optString("name", "Unknown Artist") ?: "Unknown Artist",
            artistId = artist?.optLong("id", 0L) ?: 0L,
            albumTitle = album?.optString("title", "") ?: "",
            albumId = album?.optLong("id", 0L) ?: 0L,
            durationSeconds = item.optLong("duration", 0L),
            coverUrl = coverUrl,
            isrc = if (item.has("isrc")) item.optString("isrc") else null
        )
    }

    private fun parseAlbum(item: JSONObject): DeezerAlbum {
        val artist = item.optJSONObject("artist")
        val coverUrl = item.optString("cover_xl").takeIf { it.isNotBlank() }
            ?: item.optString("cover_big")
            ?: item.optString("cover_medium")
            ?: ""

        return DeezerAlbum(
            id = item.getLong("id"),
            title = item.optString("title", ""),
            coverUrl = coverUrl,
            artistName = artist?.optString("name", "") ?: "",
            artistId = artist?.optLong("id", 0L) ?: 0L,
            releaseDate = if (item.has("release_date")) item.optString("release_date") else null,
            trackCount = item.optInt("nb_tracks", 0),
            recordType = item.optString("record_type", "album")
        )
    }

    private fun parseArtist(item: JSONObject): DeezerArtist {
        val pictureUrl = item.optString("picture_xl").takeIf { it.isNotBlank() }
            ?: item.optString("picture_big")
            ?: item.optString("picture_medium")
            ?: ""

        return DeezerArtist(
            id = item.getLong("id"),
            name = item.optString("name", ""),
            pictureUrl = pictureUrl,
            fanCount = item.optInt("nb_fan", 0),
            albumCount = item.optInt("nb_album", 0)
        )
    }
}
