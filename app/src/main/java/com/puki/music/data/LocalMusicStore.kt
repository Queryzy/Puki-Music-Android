package com.puki.music.data

import android.content.Context
import android.content.SharedPreferences
import com.puki.music.model.NetworkDownloadPreference
import com.puki.music.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Playlist(
    val id: String,
    val name: String,
    val trackCount: Int,
    val autoDownload: Boolean = false
)

data class ArtistAffinity(
    val name: String,
    val playCount: Int,
    val favoriteCount: Int,
    val score: Int,
    val avatarUrl: String = ""
)

data class BehaviorFeedSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val query: String,
    val reason: String
)

class LocalMusicStore(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("puki_music_store", Context.MODE_PRIVATE)

    // ── Play History & Personal Mix ────────────────────────────────────────

    fun recordPlay(track: Track, fromSync: Boolean = false) {
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

        if (!fromSync) {
            try {
                com.puki.music.sync.SyncManager.getInstance(context).queueEvent("RECORD_PLAY", JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("duration", track.durationSeconds)
                    put("thumbnailUrl", track.thumbnailUrl)
                    put("playedAt", System.currentTimeMillis())
                })
            } catch (_: Exception) {}
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

    fun recordSearch(query: String, fromSync: Boolean = false) {
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

        if (!fromSync) {
            try {
                com.puki.music.sync.SyncManager.getInstance(context).queueEvent("ADD_SEARCH", JSONObject().apply {
                    put("query", trimmed)
                })
            } catch (_: Exception) {}
        }
    }

    fun removeRecentSearch(query: String) {
        val trimmed = query.trim()
        val current = getRecentSearches().toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        val array = JSONArray()
        for (item in current) {
            array.put(item)
        }
        prefs.edit().putString(KEY_SEARCHES, array.toString()).apply()
    }

    fun clearAllRecentSearches() {
        prefs.edit().putString(KEY_SEARCHES, "[]").apply()
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

    fun getUserTopArtists(limit: Int = 6): List<ArtistAffinity> {
        val playHistoryJson = prefs.getString(KEY_PLAY_HISTORY, "[]") ?: "[]"
        val playArray = JSONArray(playHistoryJson)
        val artistPlays = mutableMapOf<String, Int>()
        val artistLastPlayed = mutableMapOf<String, Long>()
        val artistThumbs = mutableMapOf<String, String>()

        for (i in 0 until playArray.length()) {
            val obj = playArray.getJSONObject(i)
            val artist = obj.optString("artist", "").trim()
            if (artist.isNotBlank() && !artist.equals("Unknown", ignoreCase = true)) {
                val plays = obj.optInt("playCount", 1)
                val last = obj.optLong("lastPlayed", 0L)
                val thumb = obj.optString("thumbnailUrl", "")
                val key = artist
                artistPlays[key] = (artistPlays[key] ?: 0) + plays
                if (last > (artistLastPlayed[key] ?: 0L)) {
                    artistLastPlayed[key] = last
                }
                if (thumb.isNotBlank() && !artistThumbs.containsKey(key)) {
                    artistThumbs[key] = thumb
                }
            }
        }

        val favs = getFavorites()
        val artistFavs = mutableMapOf<String, Int>()
        for (f in favs) {
            val artist = f.artist.trim()
            if (artist.isNotBlank() && !artist.equals("Unknown", ignoreCase = true)) {
                val key = artist
                artistFavs[key] = (artistFavs[key] ?: 0) + 1
                if (f.thumbnailUrl.isNotBlank() && !artistThumbs.containsKey(key)) {
                    artistThumbs[key] = f.thumbnailUrl
                }
            }
        }

        val offline = getOfflineTracks()
        val artistOffline = mutableMapOf<String, Int>()
        for (o in offline) {
            val artist = o.artist.trim()
            if (artist.isNotBlank() && !artist.equals("Unknown", ignoreCase = true)) {
                val key = artist
                artistOffline[key] = (artistOffline[key] ?: 0) + 1
                if (o.thumbnailUrl.isNotBlank() && !artistThumbs.containsKey(key)) {
                    artistThumbs[key] = o.thumbnailUrl
                }
            }
        }

        val allArtists = (artistPlays.keys + artistFavs.keys + artistOffline.keys).toSet()
        val affinities = mutableListOf<ArtistAffinity>()
        val now = System.currentTimeMillis()
        val threeDaysMs = 3 * 24 * 3600 * 1000L

        for (a in allArtists) {
            val plays = artistPlays[a] ?: 0
            val favCount = artistFavs[a] ?: 0
            val dlCount = artistOffline[a] ?: 0
            val lastPlayed = artistLastPlayed[a] ?: 0L
            val recencyBonus = if (now - lastPlayed < threeDaysMs && lastPlayed > 0) 3 else 0

            // Formula: Plays * 2 + Favorites * 5 + Downloads * 3 + Recency
            val score = (plays * 2) + (favCount * 5) + (dlCount * 3) + recencyBonus
            if (score > 0) {
                affinities.add(
                    ArtistAffinity(
                        name = a,
                        playCount = plays,
                        favoriteCount = favCount,
                        score = score,
                        avatarUrl = artistThumbs[a] ?: ""
                    )
                )
            }
        }

        return affinities.sortedByDescending { it.score }.take(limit)
    }

    fun getBehaviorFeedSections(): List<BehaviorFeedSection> {
        val topArtists = getUserTopArtists(5)
        val freqTracks = getFrequentlyPlayed(10)
        val sections = mutableListOf<BehaviorFeedSection>()

        val topSong1 = freqTracks.firstOrNull()
        val topSong2 = freqTracks.getOrNull(1)

        if (topSong1 != null) {
            // Rekomendasi berdasarkan lagu yang paling sering diputar
            val songQuery = "${topSong1.artist} ${topSong1.title} mix audio"
            sections.add(
                BehaviorFeedSection(
                    id = "song_affinity_1",
                    title = "Mirip '${topSong1.title}'",
                    subtitle = "Lagu yang cocok dengan track yang paling sering kamu putar",
                    query = songQuery,
                    reason = "Berdasarkan '${topSong1.title}' oleh ${topSong1.artist}"
                )
            )

            if (topSong2 != null && !topSong2.artist.equals(topSong1.artist, ignoreCase = true)) {
                sections.add(
                    BehaviorFeedSection(
                        id = "song_affinity_2",
                        title = "Mirip '${topSong2.title}'",
                        subtitle = "Rekomendasi track senada untuk seleramu",
                        query = "${topSong2.artist} ${topSong2.title} mix songs",
                        reason = "Sering kamu putar belakangan ini"
                    )
                )
            }
        }

        if (topArtists.isNotEmpty()) {
            val top1 = topArtists[0].name
            val top2 = topArtists.getOrNull(1)?.name

            // 1. Personalized Core Mix
            val mixQuery = if (top2 != null) "$top1 $top2 mix audio" else "$top1 best songs"
            val mixDesc = if (top2 != null) "Kombinasi lagu dari $top1 & $top2" else "Koleksi lagu terbaik dari $top1"
            sections.add(
                BehaviorFeedSection(
                    id = "mix_personal",
                    title = "Mix Selera Saya",
                    subtitle = mixDesc,
                    query = mixQuery,
                    reason = "Berdasarkan ${topArtists.take(2).joinToString(" & ") { it.name }}"
                )
            )

            // 2. Focused on #1 Top Artist
            sections.add(
                BehaviorFeedSection(
                    id = "artist_top_1",
                    title = "Spesial $top1",
                    subtitle = "Lagu-lagu teratas dari artis favoritmu",
                    query = "$top1 top tracks audio",
                    reason = "Paling sering kamu dengarkan (${topArtists[0].playCount}x putar)"
                )
            )

            // 3. Focused on #2 Top Artist if exists
            if (top2 != null) {
                sections.add(
                    BehaviorFeedSection(
                        id = "artist_top_2",
                        title = "Karena Kamu Suka $top2",
                        subtitle = "Trek pilihan dari $top2",
                        query = "$top2 popular songs",
                        reason = "Artis favorit kedua kamu"
                    )
                )
            }

            // 4. Discovery / Similar tracks
            sections.add(
                BehaviorFeedSection(
                    id = "discovery",
                    title = "Eksplorasi Lagu Terkait",
                    subtitle = "Menemukan musik yang sesuai karakter seleramu",
                    query = "$top1 similar artists songs indonesia",
                    reason = "Rekomendasi algoritma selera musik"
                )
            )

            // 5. General trending fallback
            sections.add(
                BehaviorFeedSection(
                    id = "chart_trending",
                    title = "Trending Indonesia",
                    subtitle = "Tangga lagu terpopuler di Indonesia saat ini",
                    query = "Trending Musik Indonesia Populer",
                    reason = "Chart publik"
                )
            )
        } else {
            // Fresh user without enough listening behavior yet
            sections.add(
                BehaviorFeedSection(
                    id = "chart_trending",
                    title = "Trending Indonesia",
                    subtitle = "Musik terpopuler hari ini (Dengarkan lagu untuk personalisasi feed)",
                    query = "Trending Musik Indonesia Populer",
                    reason = "Chart umum"
                )
            )
            sections.add(
                BehaviorFeedSection(
                    id = "chart_pop",
                    title = "Populer Indonesia",
                    subtitle = "Koleksi hits pop Indonesia pilihan",
                    query = "Lagu Pop Indonesia Terbaru Hits",
                    reason = "Rekomendasi awal"
                )
            )
            sections.add(
                BehaviorFeedSection(
                    id = "chart_indie",
                    title = "Indie & Alternatif",
                    subtitle = "Musik santai dan akustik indie Indonesia",
                    query = "Lagu Indie Indonesia Terbaik",
                    reason = "Rekomendasi awal"
                )
            )
        }

        return sections
    }

    fun getRecommendationQuery(): String {
        return getBehaviorFeedSections().first().query
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
            obj.put("viewCount", t.viewCount)
            obj.put("likeCount", t.likeCount)
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
                        localFilePath = filePath,
                        viewCount = obj.optLong("viewCount", 0L),
                        likeCount = obj.optLong("likeCount", 0L)
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

    fun updateTrackMetrics(trackId: String, viewCount: Long, likeCount: Long) {
        val norm = normalizeId(trackId)

        // 1. Update in Offline Tracks
        val offlineList = getOfflineTracks().toMutableList()
        var offlineChanged = false
        for (i in offlineList.indices) {
            if (normalizeId(offlineList[i].id) == norm) {
                offlineList[i] = offlineList[i].copy(viewCount = viewCount, likeCount = likeCount)
                offlineChanged = true
            }
        }
        if (offlineChanged) {
            val array = JSONArray()
            for (t in offlineList) {
                val obj = JSONObject()
                obj.put("id", t.id)
                obj.put("title", t.title)
                obj.put("artist", t.artist)
                obj.put("duration", t.durationSeconds)
                obj.put("thumbnailUrl", t.thumbnailUrl)
                obj.put("localFilePath", t.localFilePath)
                obj.put("viewCount", t.viewCount)
                obj.put("likeCount", t.likeCount)
                array.put(obj)
            }
            prefs.edit().putString(KEY_OFFLINE, array.toString()).apply()
        }

        // 2. Update in Favorites
        val favList = getFavorites().toMutableList()
        var favChanged = false
        for (i in favList.indices) {
            if (normalizeId(favList[i].id) == norm) {
                favList[i] = favList[i].copy(viewCount = viewCount, likeCount = likeCount)
                favChanged = true
            }
        }
        if (favChanged) {
            saveFavoritesList(favList)
        }

        // 3. Update in Playlists
        for (pl in getPlaylists()) {
            val plTracks = getPlaylistTracks(pl.id).toMutableList()
            var plChanged = false
            for (i in plTracks.indices) {
                if (normalizeId(plTracks[i].id) == norm) {
                    plTracks[i] = plTracks[i].copy(viewCount = viewCount, likeCount = likeCount)
                    plChanged = true
                }
            }
            if (plChanged) {
                val array = JSONArray()
                for (t in plTracks) {
                    val obj = JSONObject()
                    obj.put("id", t.id)
                    obj.put("title", t.title)
                    obj.put("artist", t.artist)
                    obj.put("duration", t.durationSeconds)
                    obj.put("thumbnailUrl", t.thumbnailUrl)
                    obj.put("viewCount", t.viewCount)
                    obj.put("likeCount", t.likeCount)
                    array.put(obj)
                }
                prefs.edit().putString("${KEY_PL_TRACKS}_${pl.id}", array.toString()).apply()
            }
        }
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
                    thumbnailUrl = obj.getString("thumbnailUrl"),
                    viewCount = obj.optLong("viewCount", 0L),
                    likeCount = obj.optLong("likeCount", 0L),
                    likedAt = obj.optLong("likedAt", 0L)
                )
            )
        }
        return list.sortedWith(
            compareByDescending<Track> { it.likedAt }
                .thenBy { it.title.lowercase(java.util.Locale.ROOT) }
        )
    }

    fun isFavorite(trackId: String): Boolean {
        val norm = normalizeId(trackId)
        return getFavorites().any { normalizeId(it.id) == norm }
    }

    fun toggleFavorite(track: Track): Boolean {
        val norm = normalizeId(track.id)
        val list = getFavorites().toMutableList()
        val existingIndex = list.indexOfFirst { normalizeId(it.id) == norm }
        val now = System.currentTimeMillis()
        val isNowFav = if (existingIndex >= 0) {
            list.removeAt(existingIndex)
            false
        } else {
            val favTrack = track.copy(likedAt = now)
            list.add(0, favTrack)
            true
        }
        saveFavoritesList(list)

        try {
            com.puki.music.sync.SyncManager.getInstance(context).queueEvent("TOGGLE_FAVORITE", JSONObject().apply {
                put("isLiked", isNowFav)
                put("likedAt", now)
                put("track", JSONObject().apply {
                    put("id", track.id)
                    put("title", track.title)
                    put("artist", track.artist)
                    put("duration", track.durationSeconds)
                    put("thumbnailUrl", track.thumbnailUrl)
                    put("viewCount", track.viewCount)
                    put("likeCount", track.likeCount)
                    put("likedAt", now)
                })
            })
        } catch (_: Exception) {}

        return isNowFav
    }

    fun setFavoriteState(track: Track, isLiked: Boolean, timestamp: Long = System.currentTimeMillis()) {
        val norm = normalizeId(track.id)
        val list = getFavorites().toMutableList()
        val existingIndex = list.indexOfFirst { normalizeId(it.id) == norm }
        val effectiveLikedAt = if (track.likedAt > 0L) track.likedAt else timestamp
        if (isLiked) {
            val favTrack = track.copy(likedAt = effectiveLikedAt)
            if (existingIndex < 0) {
                list.add(0, favTrack)
                saveFavoritesList(list)
            } else {
                list[existingIndex] = favTrack
                saveFavoritesList(list)
            }
        } else {
            if (existingIndex >= 0) {
                list.removeAt(existingIndex)
                saveFavoritesList(list)
            }
        }
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
            obj.put("viewCount", track.viewCount)
            obj.put("likeCount", track.likeCount)
            obj.put("likedAt", track.likedAt)
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
        val newId = "pl_${System.currentTimeMillis()}"
        return createPlaylistWithId(newId, name, autoDownload, fromSync = false)
    }

    fun createPlaylistWithId(playlistId: String, name: String, autoDownload: Boolean = false, fromSync: Boolean = false): Playlist {
        val playlists = getPlaylists().toMutableList()
        val existing = playlists.find { it.id == playlistId || it.name.equals(name, ignoreCase = true) }
        if (existing != null) {
            return existing
        }
        val newPl = Playlist(id = playlistId, name = name, trackCount = 0, autoDownload = autoDownload)
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

        if (!fromSync) {
            try {
                com.puki.music.sync.SyncManager.getInstance(context).queueEvent("UPDATE_PLAYLIST", JSONObject().apply {
                    put("playlistId", playlistId)
                    put("name", name)
                    put("operation", "CREATE")
                })
            } catch (_: Exception) {}
        }

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
                    thumbnailUrl = obj.getString("thumbnailUrl"),
                    viewCount = obj.optLong("viewCount", 0L),
                    likeCount = obj.optLong("likeCount", 0L)
                )
            )
        }
        return list
    }

    fun addTrackToPlaylist(playlistId: String, track: Track, fromSync: Boolean = false): Boolean {
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
                obj.put("viewCount", t.viewCount)
                obj.put("likeCount", t.likeCount)
                array.put(obj)
            }
            prefs.edit().putString("${KEY_PL_TRACKS}_$playlistId", array.toString()).apply()

            if (!fromSync) {
                try {
                    com.puki.music.sync.SyncManager.getInstance(context).queueEvent("UPDATE_PLAYLIST", JSONObject().apply {
                        put("playlistId", playlistId)
                        put("operation", "ADD_TRACK")
                        put("track", JSONObject().apply {
                            put("id", track.id)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("duration", track.durationSeconds)
                            put("thumbnailUrl", track.thumbnailUrl)
                            put("viewCount", track.viewCount)
                            put("likeCount", track.likeCount)
                        })
                    })
                } catch (_: Exception) {}
            }

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

    // ── Export & Import User Behavior Profile (Cross-Device Sync) ───────────

    fun exportBehaviorJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        // 1. Playlists Meta & Playlist Tracks
        val playlists = getPlaylists()
        val plArr = JSONArray()
        for (pl in playlists) {
            val plObj = JSONObject().apply {
                put("id", pl.id)
                put("name", pl.name)
                put("autoDownload", pl.autoDownload)
                val tracks = getPlaylistTracks(pl.id)
                val trArr = JSONArray()
                for (t in tracks) {
                    trArr.put(JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("duration", t.durationSeconds)
                        put("thumbnailUrl", t.thumbnailUrl)
                        put("viewCount", t.viewCount)
                        put("likeCount", t.likeCount)
                    })
                }
                put("tracks", trArr)
            }
            plArr.put(plObj)
        }
        root.put("playlists", plArr)

        // 2. Favorites / Liked Songs
        val favs = getFavorites()
        val favArr = JSONArray()
        for (f in favs) {
            favArr.put(JSONObject().apply {
                put("id", f.id)
                put("title", f.title)
                put("artist", f.artist)
                put("duration", f.durationSeconds)
                put("thumbnailUrl", f.thumbnailUrl)
                put("viewCount", f.viewCount)
                put("likeCount", f.likeCount)
            })
        }
        root.put("favorites", favArr)

        // 3. Play History & Listening Behavior (Most Played)
        root.put("play_history", JSONArray(prefs.getString(KEY_PLAY_HISTORY, "[]") ?: "[]"))

        // 4. Recent Searches
        root.put("recent_searches", JSONArray(prefs.getString(KEY_SEARCHES, "[]") ?: "[]"))

        // 5. Settings Preferences
        root.put("app_theme", getThemeName())
        root.put("auto_download_liked", isAutoDownloadLikedEnabled())
        root.put("auto_download_network", getAutoDownloadNetworkPreference().name)

        return root.toString(2)
    }

    fun importBehaviorJson(jsonStr: String): Result<Int> {
        return runCatching {
            val root = JSONObject(jsonStr)

            // 1. Playlists & Tracks
            if (root.has("playlists")) {
                val plArr = root.getJSONArray("playlists")
                val currentPlaylists = getPlaylists().toMutableList()
                for (i in 0 until plArr.length()) {
                    val plObj = plArr.getJSONObject(i)
                    val plId = plObj.getString("id")
                    val plName = plObj.getString("name")
                    val autoDownload = plObj.optBoolean("autoDownload", false)

                    // Restore playlist tracks
                    val existingTr = getPlaylistTracks(plId).toMutableList()
                    if (plObj.has("tracks")) {
                        val trArr = plObj.getJSONArray("tracks")
                        val seenIds = existingTr.map { normalizeId(it.id) }.toMutableSet()
                        for (j in 0 until trArr.length()) {
                            val tObj = trArr.getJSONObject(j)
                            val t = Track(
                                id = tObj.getString("id"),
                                title = tObj.getString("title"),
                                artist = tObj.getString("artist"),
                                durationSeconds = tObj.optLong("duration", 0L),
                                thumbnailUrl = tObj.optString("thumbnailUrl", ""),
                                viewCount = tObj.optLong("viewCount", 0L),
                                likeCount = tObj.optLong("likeCount", 0L)
                            )
                            if (!seenIds.contains(normalizeId(t.id))) {
                                seenIds.add(normalizeId(t.id))
                                existingTr.add(t)
                            }
                        }
                        val array = JSONArray()
                        for (track in existingTr) {
                            val obj = JSONObject()
                            obj.put("id", track.id)
                            obj.put("title", track.title)
                            obj.put("artist", track.artist)
                            obj.put("duration", track.durationSeconds)
                            obj.put("thumbnailUrl", track.thumbnailUrl)
                            obj.put("viewCount", track.viewCount)
                            obj.put("likeCount", track.likeCount)
                            array.put(obj)
                        }
                        prefs.edit().putString("${KEY_PL_TRACKS}_$plId", array.toString()).apply()
                    }

                    // Merge or create playlist
                    val existingPlIdx = currentPlaylists.indexOfFirst { it.id == plId }
                    if (existingPlIdx >= 0) {
                        currentPlaylists[existingPlIdx] = currentPlaylists[existingPlIdx].copy(
                            trackCount = existingTr.size,
                            autoDownload = autoDownload
                        )
                    } else {
                        currentPlaylists.add(Playlist(plId, plName, existingTr.size, autoDownload))
                    }
                }

                val plMetaArr = JSONArray()
                for (pl in currentPlaylists) {
                    val obj = JSONObject()
                    obj.put("id", pl.id)
                    obj.put("name", pl.name)
                    obj.put("autoDownload", pl.autoDownload)
                    plMetaArr.put(obj)
                }
                prefs.edit().putString(KEY_PLAYLISTS, plMetaArr.toString()).apply()
            }

            // 2. Favorites / Liked Songs
            var mergedFavsCount = 0
            if (root.has("favorites")) {
                val favArr = root.getJSONArray("favorites")
                val curFavs = getFavorites().toMutableList()
                val seenFavIds = curFavs.map { normalizeId(it.id) }.toMutableSet()
                for (i in 0 until favArr.length()) {
                    val fObj = favArr.getJSONObject(i)
                    val t = Track(
                        id = fObj.getString("id"),
                        title = fObj.getString("title"),
                        artist = fObj.getString("artist"),
                        durationSeconds = fObj.optLong("duration", 0L),
                        thumbnailUrl = fObj.optString("thumbnailUrl", ""),
                        viewCount = fObj.optLong("viewCount", 0L),
                        likeCount = fObj.optLong("likeCount", 0L)
                    )
                    if (!seenFavIds.contains(normalizeId(t.id))) {
                        seenFavIds.add(normalizeId(t.id))
                        curFavs.add(t)
                        mergedFavsCount++
                    }
                }
                saveFavoritesList(curFavs)
            }

            // 3. Play History (Merge based on playCount and lastPlayed)
            if (root.has("play_history")) {
                val impHistory = root.getJSONArray("play_history")
                val curHistoryStr = prefs.getString(KEY_PLAY_HISTORY, "[]") ?: "[]"
                val curHistory = JSONArray(curHistoryStr)
                val map = mutableMapOf<String, JSONObject>()

                for (i in 0 until curHistory.length()) {
                    val obj = curHistory.getJSONObject(i)
                    map[normalizeId(obj.getString("id"))] = obj
                }

                for (i in 0 until impHistory.length()) {
                    val obj = impHistory.getJSONObject(i)
                    val id = normalizeId(obj.getString("id"))
                    if (map.containsKey(id)) {
                        val existing = map[id]!!
                        val totalPlays = existing.optInt("playCount", 1) + obj.optInt("playCount", 1)
                        val latest = maxOf(existing.optLong("lastPlayed", 0L), obj.optLong("lastPlayed", 0L))
                        existing.put("playCount", totalPlays)
                        existing.put("lastPlayed", latest)
                    } else {
                        map[id] = obj
                    }
                }

                val mergedList = map.values.sortedWith(
                    compareByDescending<JSONObject> { it.optInt("playCount", 1) }
                        .thenByDescending { it.optLong("lastPlayed", 0L) }
                ).take(50)

                val outArr = JSONArray()
                for (item in mergedList) outArr.put(item)
                prefs.edit().putString(KEY_PLAY_HISTORY, outArr.toString()).apply()
            }

            // 4. Preferences & Theme
            if (root.has("app_theme")) {
                saveThemeName(root.getString("app_theme"))
            }
            if (root.has("auto_download_liked")) {
                setAutoDownloadLikedEnabled(root.getBoolean("auto_download_liked"))
            }
            if (root.has("auto_download_network")) {
                try {
                    setAutoDownloadNetworkPreference(NetworkDownloadPreference.valueOf(root.getString("auto_download_network")))
                } catch (_: Exception) {}
            }

            mergedFavsCount
        }
    }

    // ── Remote Delta Synchronization Hook ──────────────────────────────────

    fun applyRemoteSyncEvent(event: JSONObject) {
        try {
            val action = event.optString("action")
            val data = event.optJSONObject("data") ?: return
            when (action) {
                "RECORD_PLAY" -> {
                    val id = data.optString("id")
                    val title = data.optString("title")
                    val artist = data.optString("artist")
                    val duration = data.optLong("duration", 0L)
                    val thumbnailUrl = data.optString("thumbnailUrl")
                    if (id.isNotBlank()) {
                        recordPlay(Track(id, title, artist, duration, thumbnailUrl), fromSync = true)
                    }
                }
                "TOGGLE_FAVORITE" -> {
                    val isLiked = data.optBoolean("isLiked", true)
                    val trackObj = data.optJSONObject("track") ?: return
                    val id = trackObj.optString("id")
                    val title = trackObj.optString("title")
                    val artist = trackObj.optString("artist")
                    val duration = trackObj.optLong("duration", 0L)
                    val thumbnailUrl = trackObj.optString("thumbnailUrl")
                    val vCount = trackObj.optLong("viewCount", 0L)
                    val lCount = trackObj.optLong("likeCount", 0L)
                    val likedAt = trackObj.optLong("likedAt", data.optLong("likedAt", 0L))
                    val track = Track(
                        id = id,
                        title = title,
                        artist = artist,
                        durationSeconds = duration,
                        thumbnailUrl = thumbnailUrl,
                        viewCount = vCount,
                        likeCount = lCount,
                        likedAt = likedAt
                    )
                    setFavoriteState(track, isLiked, if (likedAt > 0L) likedAt else System.currentTimeMillis())
                }
                "ADD_SEARCH" -> {
                    val query = data.optString("query")
                    if (query.isNotBlank()) {
                        recordSearch(query, fromSync = true)
                    }
                }
                "UPDATE_PLAYLIST" -> {
                    val playlistId = data.optString("playlistId")
                    val playlistName = data.optString("name")
                    val op = data.optString("operation")
                    if (playlistId.isNotBlank()) {
                        if (op == "CREATE" && playlistName.isNotBlank()) {
                            createPlaylistWithId(playlistId, playlistName, fromSync = true)
                        } else if (op == "ADD_TRACK") {
                            val trackObj = data.optJSONObject("track")
                            if (trackObj != null) {
                                val track = Track(
                                    id = trackObj.optString("id"),
                                    title = trackObj.optString("title"),
                                    artist = trackObj.optString("artist"),
                                    durationSeconds = trackObj.optLong("duration", 0L),
                                    thumbnailUrl = trackObj.optString("thumbnailUrl"),
                                    viewCount = trackObj.optLong("viewCount", 0L),
                                    likeCount = trackObj.optLong("likeCount", 0L)
                                )
                                addTrackToPlaylist(playlistId, track, fromSync = true)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("LocalMusicStore", "Error applying remote sync event", e)
        }
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
