package com.puki.nuclear.data

import android.content.Context
import android.content.SharedPreferences
import com.puki.nuclear.model.NetworkDownloadPreference
import com.puki.nuclear.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val autoDownload: Boolean = false
)

class LocalMusicStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("puki_music_store", Context.MODE_PRIVATE)

    // ── Play History & Personal Mix ────────────────────────────────────────

    fun recordPlay(track: Track) {
        val norm = normalizeId(track.id)
        val jsonStr = prefs.getString(KEY_PLAY_HISTORY, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val items = mutableListOf<JSONObject>()
        var found = false

        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (normalizeId(obj.getString("id")) == norm) {
                val currentCount = obj.optInt("playCount", 1)
                obj.put("playCount", currentCount + 1)
                obj.put("lastPlayed", System.currentTimeMillis())
                obj.put("title", track.title)
                obj.put("artist", track.artist)
                obj.put("thumbnailUrl", track.thumbnailUrl)
                found = true
            }
            items.add(obj)
        }

        if (!found) {
            val newObj = JSONObject().apply {
                put("id", track.id)
                put("title", track.title)
                put("artist", track.artist)
                put("duration", track.durationSeconds)
                put("thumbnailUrl", track.thumbnailUrl)
                put("playCount", 1)
                put("lastPlayed", System.currentTimeMillis())
            }
            items.add(0, newObj)
        }

        // Sort by playCount desc, then lastPlayed desc
        items.sortWith(
            compareByDescending<JSONObject> { it.optInt("playCount", 1) }
                .thenByDescending { it.optLong("lastPlayed", 0L) }
        )

        val capped = items.take(50)
        val outArray = JSONArray()
        for (item in capped) {
            outArray.put(item)
        }
        prefs.edit().putString(KEY_PLAY_HISTORY, outArray.toString()).apply()

        // Also seed recommendation query from played artist
        if (track.artist.isNotBlank()) {
            recordSearch(track.artist)
        }
    }

    fun getFrequentlyPlayed(limit: Int = 30): List<Track> {
        val jsonStr = prefs.getString(KEY_PLAY_HISTORY, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Track>()
        for (i in 0 until array.length().coerceAtMost(limit)) {
            val obj = array.getJSONObject(i)
            list.add(
                Track(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    durationSeconds = obj.optLong("duration", 0L),
                    thumbnailUrl = obj.getString("thumbnailUrl")
                )
            )
        }
        return list
    }

    fun getMyMixTracks(): List<Track> {
        val freq = getFrequentlyPlayed(30)
        val favs = getFavorites()
        val offline = getOfflineTracks()

        val combined = mutableListOf<Track>()
        val seen = mutableSetOf<String>()

        for (t in freq + favs + offline) {
            val norm = normalizeId(t.id)
            if (!seen.contains(norm)) {
                seen.add(norm)
                combined.add(t)
            }
        }
        return combined
    }

    // ── Search History & Recommendations ───────────────────────────────────

    fun recordSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return

        val current = getRecentSearches().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        val capped = current.take(15)

        val array = JSONArray()
        for (item in capped) {
            array.put(item)
        }
        prefs.edit().putString(KEY_SEARCHES, array.toString()).apply()
    }

    fun getRecentSearches(): List<String> {
        val jsonStr = prefs.getString(KEY_SEARCHES, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            list.add(array.getString(i))
        }
        return list
    }

    fun getRecommendationQuery(): String {
        val searches = getRecentSearches()
        if (searches.isNotEmpty()) {
            val topSearch = searches.first()
            return if (topSearch.contains("lagu", ignoreCase = true) || topSearch.contains("song", ignoreCase = true)) {
                topSearch
            } else {
                "$topSearch popular songs"
            }
        }

        val favs = getFavorites()
        if (favs.isNotEmpty()) {
            val topArtist = favs.first().artist
            return "$topArtist best tracks"
        }

        return "Trending Musik Indonesia Populer"
    }

    // ── Offline Downloads ──────────────────────────────────────────────────

    fun saveOfflineTrack(track: Track, filePath: String) {
        val norm = normalizeId(track.id)
        val list = getOfflineTracks().toMutableList()
        list.removeAll { normalizeId(it.id) == norm }

        val offlineTrack = track.copy(localFilePath = filePath)
        list.add(0, offlineTrack)

        val array = JSONArray()
        for (t in list) {
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("title", t.title)
            obj.put("artist", t.artist)
            obj.put("duration", t.durationSeconds)
            obj.put("thumbnailUrl", t.thumbnailUrl)
            obj.put("localFilePath", t.localFilePath)
            array.put(obj)
        }
        prefs.edit().putString(KEY_OFFLINE, array.toString()).apply()
    }

    fun getOfflineTracks(): List<Track> {
        val jsonStr = prefs.getString(KEY_OFFLINE, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Track>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val filePath = obj.optString("localFilePath", "")
            val file = if (filePath.isNotBlank()) File(filePath) else null

            // Only return if local file genuinely exists on disk
            if (file != null && file.exists() && file.length() > 50_000) {
                list.add(
                    Track(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        artist = obj.getString("artist"),
                        durationSeconds = obj.getLong("duration"),
                        thumbnailUrl = obj.getString("thumbnailUrl"),
                        localFilePath = filePath
                    )
                )
            }
        }
        return list
    }

    fun isOffline(trackId: String): Boolean {
        val norm = normalizeId(trackId)
        return getOfflineTracks().any { normalizeId(it.id) == norm }
    }

    fun getOfflineFilePath(trackId: String): String? {
        val norm = normalizeId(trackId)
        return getOfflineTracks().firstOrNull { normalizeId(it.id) == norm }?.localFilePath
    }

    fun deleteOfflineTrack(trackId: String) {
        val norm = normalizeId(trackId)
        val list = getOfflineTracks().filterNot { normalizeId(it.id) == norm }

        val array = JSONArray()
        for (t in list) {
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("title", t.title)
            obj.put("artist", t.artist)
            obj.put("duration", t.durationSeconds)
            obj.put("thumbnailUrl", t.thumbnailUrl)
            obj.put("localFilePath", t.localFilePath)
            array.put(obj)
        }
        prefs.edit().putString(KEY_OFFLINE, array.toString()).apply()
    }

    fun getPlayCount(trackId: String): Int {
        val norm = normalizeId(trackId)
        val jsonStr = prefs.getString(KEY_PLAY_HISTORY, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            if (normalizeId(obj.getString("id")) == norm) {
                return obj.optInt("playCount", 0)
            }
        }
        return 0
    }

    fun clearAllOfflineTracks() {
        prefs.edit().putString(KEY_OFFLINE, "[]").apply()
    }

    // ── Favorites ──────────────────────────────────────────────────────────

    fun getFavorites(): List<Track> {
        val jsonStr = prefs.getString(KEY_FAVORITES, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Track>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                Track(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    durationSeconds = obj.getLong("duration"),
                    thumbnailUrl = obj.getString("thumbnailUrl")
                )
            )
        }
        return list
    }

    fun isFavorite(trackId: String): Boolean {
        val norm = normalizeId(trackId)
        return getFavorites().any { normalizeId(it.id) == norm }
    }

    fun toggleFavorite(track: Track): Boolean {
        val norm = normalizeId(track.id)
        val list = getFavorites().toMutableList()
        val existingIndex = list.indexOfFirst { normalizeId(it.id) == norm }
        val isNowFav = if (existingIndex >= 0) {
            list.removeAt(existingIndex)
            false
        } else {
            list.add(0, track)
            true
        }
        saveFavoritesList(list)
        return isNowFav
    }

    private fun saveFavoritesList(list: List<Track>) {
        val array = JSONArray()
        for (track in list) {
            val obj = JSONObject()
            obj.put("id", track.id)
            obj.put("title", track.title)
            obj.put("artist", track.artist)
            obj.put("duration", track.durationSeconds)
            obj.put("thumbnailUrl", track.thumbnailUrl)
            array.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
    }

    // ── Playlists ──────────────────────────────────────────────────────────

    fun getPlaylists(): List<Playlist> {
        val jsonStr = prefs.getString(KEY_PLAYLISTS, "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Playlist>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val playlistId = obj.getString("id")
            val tracks = getPlaylistTracks(playlistId)
            val autoDl = obj.optBoolean("autoDownload", false)
            list.add(
                Playlist(
                    id = playlistId,
                    name = obj.getString("name"),
                    trackCount = tracks.size,
                    autoDownload = autoDl
                )
            )
        }
        return list
    }

    fun createPlaylist(name: String, autoDownload: Boolean = false): Playlist {
        val playlists = getPlaylists().toMutableList()
        val newId = "pl_${System.currentTimeMillis()}"
        val newPl = Playlist(id = newId, name = name, trackCount = 0, autoDownload = autoDownload)
        playlists.add(newPl)

        val array = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            obj.put("autoDownload", pl.autoDownload)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
        return newPl
    }

    fun setPlaylistAutoDownload(playlistId: String, autoDownload: Boolean) {
        val playlists = getPlaylists()
        val array = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            obj.put("autoDownload", if (pl.id == playlistId) autoDownload else pl.autoDownload)
            array.put(obj)
        }
        prefs.edit().putString(KEY_PLAYLISTS, array.toString()).apply()
    }

    fun isPlaylistAutoDownload(playlistId: String): Boolean {
        return getPlaylists().firstOrNull { it.id == playlistId }?.autoDownload == true
    }

    fun deletePlaylist(playlistId: String) {
        val playlists = getPlaylists().filterNot { it.id == playlistId }
        val array = JSONArray()
        for (pl in playlists) {
            val obj = JSONObject()
            obj.put("id", pl.id)
            obj.put("name", pl.name)
            obj.put("autoDownload", pl.autoDownload)
            array.put(obj)
        }
        prefs.edit()
            .putString(KEY_PLAYLISTS, array.toString())
            .remove("${KEY_PL_TRACKS}_$playlistId")
            .apply()
    }

    fun getPlaylistTracks(playlistId: String): List<Track> {
        val jsonStr = prefs.getString("${KEY_PL_TRACKS}_$playlistId", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<Track>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                Track(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    artist = obj.getString("artist"),
                    durationSeconds = obj.getLong("duration"),
                    thumbnailUrl = obj.getString("thumbnailUrl")
                )
            )
        }
        return list
    }

    fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
        val norm = normalizeId(track.id)
        val tracks = getPlaylistTracks(playlistId).toMutableList()
        if (tracks.none { normalizeId(it.id) == norm }) {
            tracks.add(track)
            val array = JSONArray()
            for (t in tracks) {
                val obj = JSONObject()
                obj.put("id", t.id)
                obj.put("title", t.title)
                obj.put("artist", t.artist)
                obj.put("duration", t.durationSeconds)
                obj.put("thumbnailUrl", t.thumbnailUrl)
                array.put(obj)
            }
            prefs.edit().putString("${KEY_PL_TRACKS}_$playlistId", array.toString()).apply()
            return true
        }
        return false
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val norm = normalizeId(trackId)
        val tracks = getPlaylistTracks(playlistId).filterNot { normalizeId(it.id) == norm }
        val array = JSONArray()
        for (t in tracks) {
            val obj = JSONObject()
            obj.put("id", t.id)
            obj.put("title", t.title)
            obj.put("artist", t.artist)
            obj.put("duration", t.durationSeconds)
            obj.put("thumbnailUrl", t.thumbnailUrl)
            array.put(obj)
        }
        prefs.edit().putString("${KEY_PL_TRACKS}_$playlistId", array.toString()).apply()
    }

    // ── Auto-Download Preferences ──────────────────────────────────────────

    fun isAutoDownloadLikedEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DOWNLOAD_LIKED, false)
    }

    fun setAutoDownloadLikedEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_LIKED, enabled).apply()
    }

    fun getAutoDownloadNetworkPreference(): NetworkDownloadPreference {
        val raw = prefs.getString(KEY_AUTO_DOWNLOAD_NETWORK, NetworkDownloadPreference.ANY_NETWORK.name)
        return try {
            NetworkDownloadPreference.valueOf(raw ?: NetworkDownloadPreference.ANY_NETWORK.name)
        } catch (_: Exception) {
            NetworkDownloadPreference.ANY_NETWORK
        }
    }

    fun setAutoDownloadNetworkPreference(pref: NetworkDownloadPreference) {
        prefs.edit().putString(KEY_AUTO_DOWNLOAD_NETWORK, pref.name).apply()
    }

    // ── Theme Preference ───────────────────────────────────────────────────

    fun getThemeName(): String {
        return prefs.getString(KEY_THEME, "nuclear:default") ?: "nuclear:default"
    }

    fun saveThemeName(name: String) {
        prefs.edit().putString(KEY_THEME, name).apply()
    }

    companion object {
        fun normalizeId(id: String): String {
            return if (id.contains("v=")) {
                id.substringAfter("v=").substringBefore("&")
            } else {
                id.trim()
            }
        }
        private const val KEY_FAVORITES = "fav_tracks"
        private const val KEY_PLAYLISTS = "playlists_meta"
        private const val KEY_PL_TRACKS = "pl_tracks"
        private const val KEY_THEME = "app_theme"
        private const val KEY_SEARCHES = "recent_searches"
        private const val KEY_OFFLINE = "offline_tracks"
        private const val KEY_PLAY_HISTORY = "play_history"
        private const val KEY_AUTO_DOWNLOAD_LIKED = "auto_download_liked"
        private const val KEY_AUTO_DOWNLOAD_NETWORK = "auto_download_network"
    }
}
