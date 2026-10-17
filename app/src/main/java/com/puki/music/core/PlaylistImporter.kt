package com.puki.music.core

import com.puki.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ImportedPlaylistResult(
    val title: String,
    val source: String, // "YouTube Music" or "Spotify"
    val tracks: List<Track>
)

object PlaylistImporter {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val SPOTIFY_PLAYLIST_PATTERN = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?playlist/([a-zA-Z0-9]+)")
    private val SPOTIFY_ALBUM_PATTERN = Pattern.compile("open\\.spotify\\.com/(?:intl-[a-z]{2}/)?album/([a-zA-Z0-9]+)")

    suspend fun importPlaylist(urlOrId: String): Result<ImportedPlaylistResult> = withContext(Dispatchers.IO) {
        runCatching {
            val input = urlOrId.trim()
            when {
                input.contains("spotify.com") -> importFromSpotify(input)
                input.contains("youtube.com") || input.contains("youtu.be") || input.startsWith("PL") || input.startsWith("OLAK5uy_") -> importFromYouTube(input)
                else -> {
                    // Try YouTube first, fallback to Spotify
                    try {
                        importFromYouTube(input)
                    } catch (e: Exception) {
                        importFromSpotify(input)
                    }
                }
            }
        }
    }

    private suspend fun importFromYouTube(urlOrId: String): ImportedPlaylistResult {
        val playlistUrl = when {
            urlOrId.startsWith("http://") || urlOrId.startsWith("https://") -> {
                // Ensure standard youtube playlist URL
                if (urlOrId.contains("list=")) {
                    val listId = extractQueryParam(urlOrId, "list")
                    "https://www.youtube.com/playlist?list=$listId"
                } else {
                    urlOrId
                }
            }
            urlOrId.startsWith("PL") || urlOrId.startsWith("OLAK5uy_") || urlOrId.startsWith("RD") -> {
                "https://www.youtube.com/playlist?list=$urlOrId"
            }
            else -> urlOrId
        }

        val service = ServiceList.YouTube
        val playlistExtractor = service.getPlaylistExtractor(playlistUrl)
        playlistExtractor.fetchPage()

        val playlistTitle = playlistExtractor.name?.takeIf { it.isNotBlank() } ?: "Imported Playlist"
        val tracks = mutableListOf<Track>()

        // Get initial page items
        var currentItems = playlistExtractor.initialPage.items
        for (item in currentItems) {
            val views = if (item is StreamInfoItem) {
                try { item.viewCount } catch (_: Exception) { 0L }
            } else 0L

            tracks.add(
                Track(
                    id = item.url,
                    title = item.name,
                    artist = item.uploaderName ?: "",
                    durationSeconds = item.duration,
                    thumbnailUrl = item.thumbnails.lastOrNull()?.url.orEmpty(),
                    viewCount = views
                )
            )
        }

        // Try to fetch next pages if available (up to 200 tracks)
        var page = playlistExtractor.initialPage
        while (page.hasNextPage() && tracks.size < 200) {
            try {
                page = playlistExtractor.getPage(page.nextPage)
                for (item in page.items) {
                    val views = if (item is StreamInfoItem) {
                        try { item.viewCount } catch (_: Exception) { 0L }
                    } else 0L

                    tracks.add(
                        Track(
                            id = item.url,
                            title = item.name,
                            artist = item.uploaderName ?: "",
                            durationSeconds = item.duration,
                            thumbnailUrl = item.thumbnails.lastOrNull()?.url.orEmpty(),
                            viewCount = views
                        )
                    )
                }
            } catch (_: Exception) {
                break
            }
        }

        if (tracks.isEmpty()) {
            throw IllegalStateException("Playlist YouTube kosong atau tidak ditemukan.")
        }

        return ImportedPlaylistResult(
            title = playlistTitle,
            source = "YouTube Music",
            tracks = tracks
        )
    }

    private suspend fun importFromSpotify(url: String): ImportedPlaylistResult {
        var playlistId = ""
        var isAlbum = false

        val mPl = SPOTIFY_PLAYLIST_PATTERN.matcher(url)
        if (mPl.find()) {
            playlistId = mPl.group(1).orEmpty()
        } else {
            val mAlb = SPOTIFY_ALBUM_PATTERN.matcher(url)
            if (mAlb.find()) {
                playlistId = mAlb.group(1).orEmpty()
                isAlbum = true
            }
        }

        if (playlistId.isBlank()) {
            // Check bare ID
            val trimmed = url.substringBefore("?").substringAfterLast("/")
            if (trimmed.length == 22 && trimmed.matches(Regex("[a-zA-Z0-9]+"))) {
                playlistId = trimmed
            } else {
                throw IllegalArgumentException("URL Spotify tidak valid.")
            }
        }

        val embedUrl = if (isAlbum) {
            "https://open.spotify.com/embed/album/$playlistId"
        } else {
            "https://open.spotify.com/embed/playlist/$playlistId"
        }

        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IllegalStateException("Gagal memuat playlist Spotify (HTTP ${response.code}).")
        }

        val html = response.body?.string().orEmpty()
        val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">([^<]+)</script>")
        val matcher = nextDataPattern.matcher(html)

        if (!matcher.find()) {
            throw IllegalStateException("Format data Spotify tidak dapat dibaca dari embed.")
        }

        val jsonStr = matcher.group(1)
        val root = JSONObject(jsonStr)
        val props = root.optJSONObject("props")
        val pageProps = props?.optJSONObject("pageProps")
        val state = pageProps?.optJSONObject("state")
        val dataObj = state?.optJSONObject("data")
        val entity = dataObj?.optJSONObject("entity") ?: pageProps?.optJSONObject("entity")

        if (entity == null) {
            throw IllegalStateException("Objek playlist Spotify kosong.")
        }

        val playlistName = entity.optString("name").takeIf { !it.isNullOrBlank() }
            ?: entity.optString("title").takeIf { !it.isNullOrBlank() }
            ?: "Spotify Playlist"

        val trackListJson = entity.optJSONArray("trackList") ?: org.json.JSONArray()
        val tempTracks = mutableListOf<Pair<String, String>>() // (title, artist)

        for (i in 0 until trackListJson.length()) {
            val itemObj = trackListJson.optJSONObject(i) ?: continue
            val title = itemObj.optString("title")
            val subtitle = itemObj.optString("subtitle") // Artist name(s)
            if (title.isNotBlank()) {
                tempTracks.add(title to subtitle)
            }
        }

        if (tempTracks.isEmpty()) {
            throw IllegalStateException("Daftar lagu Spotify kosong.")
        }

        // Map Spotify metadata into playable Puki Tracks
        // Use ytsearch: queries as track identifiers; when played or downloaded, Puki streams the best match
        val resolvedTracks = mutableListOf<Track>()
        for ((title, artist) in tempTracks) {
            val fullQuery = if (artist.isNotBlank()) "$artist - $title" else title
            val virtualId = "ytsearch:$fullQuery"
            resolvedTracks.add(
                Track(
                    id = virtualId,
                    title = title,
                    artist = artist,
                    durationSeconds = 0,
                    thumbnailUrl = "",
                    viewCount = 0L
                )
            )
        }

        return ImportedPlaylistResult(
            title = playlistName,
            source = "Spotify",
            tracks = resolvedTracks
        )
    }

    private fun extractQueryParam(url: String, key: String): String {
        return try {
            val query = if (url.contains("?")) url.substringAfter("?") else url
            val pairs = query.split("&")
            for (pair in pairs) {
                val parts = pair.split("=")
                if (parts.size >= 2 && parts[0] == key) {
                    return URLDecoder.decode(parts[1], "UTF-8")
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }
}
