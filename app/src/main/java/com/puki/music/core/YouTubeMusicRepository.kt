package com.puki.music.core

import android.content.Context
import android.util.Log
import com.puki.music.model.Album
import com.puki.music.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object YouTubeMusicRepository {
    private const val TAG = "YTMRepo"
    private const val BASE_URL = "https://music.youtube.com/youtubei/v1"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun createBaseContext(): JSONObject {
        val client = JSONObject()
            .put("clientName", "WEB_REMIX")
            .put("clientVersion", "1.20240401.01.00")
            .put("hl", "id")
            .put("gl", "ID")

        return JSONObject().put("client", client)
    }

    private suspend fun postInnerTube(endpoint: String, payload: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = payload.toString().toRequestBody(mediaType)

        val req = Request.Builder()
            .url("$BASE_URL/$endpoint?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .header("Content-Type", "application/json")
            .header("Referer", "https://music.youtube.com/")
            .post(body)
            .build()

        val resp = httpClient.newCall(req).execute()
        resp.use {
            if (!it.isSuccessful) {
                throw IllegalStateException("YTM API Error: HTTP ${it.code}")
            }
            val text = it.body?.string() ?: throw IllegalStateException("Empty YTM body")
            JSONObject(text)
        }
    }

    /**
     * Search official albums from YouTube Music InnerTube (WEB_REMIX).
     * Two-tier strategy:
     * 1. Check if the query matches an Official Artist Card. If yes, fetch studio albums
     *    directly from the artist's channel shelf (100% accurate, 0 noise, full albums).
     * 2. Fallback to search query with strict recordType filter (excluding 'single', 'audiobook').
     */
    suspend fun searchAlbums(query: String, limit: Int = 20): Result<List<Album>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = query.trim()
            if (q.isBlank()) return@runCatching emptyList()

            // Strategy 1: Artist Discography First
            val artistAlbums = fetchAlbumsFromArtistChannel(q, limit)
            if (artistAlbums.isNotEmpty()) {
                return@runCatching artistAlbums
            }

            // Strategy 2: Global Search with strict record type filter
            val payload = JSONObject()
                .put("context", createBaseContext())
                .put("query", q)
                .put("params", "EgWKAQIYAWoMEAMQBBAJEA4QChAF")

            val json = postInnerTube("search", payload)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")

            val contents = tabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return@runCatching emptyList()

            val albums = mutableListOf<Album>()
            for (s in 0 until contents.length()) {
                val sec = contents.optJSONObject(s) ?: continue
                val shelf = sec.optJSONObject("musicShelfRenderer") ?: continue
                val items = shelf.optJSONArray("contents") ?: continue

                for (i in 0 until items.length()) {
                    if (albums.size >= limit) break
                    val item = items.optJSONObject(i) ?: continue
                    val flex = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    val browseEndpoint = flex.optJSONObject("navigationEndpoint")
                        ?.optJSONObject("browseEndpoint")
                    val browseId = browseEndpoint?.optString("browseId") ?: ""
                    if (browseId.isBlank()) continue

                    val flexColumns = flex.optJSONArray("flexColumns") ?: continue
                    if (flexColumns.length() < 2) continue

                    // Column 0: Album Title
                    val col0 = flexColumns.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    val title = col0?.optJSONObject(0)?.optString("text") ?: ""
                    if (title.isBlank()) continue

                    // Column 1: Record Type • Artist • Year
                    val col1 = flexColumns.optJSONObject(1)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")

                    var recordType = ""
                    var artistName = ""
                    if (col1 != null) {
                        if (col1.length() > 0) {
                            recordType = col1.optJSONObject(0)?.optString("text")?.lowercase()?.trim() ?: ""
                        }
                        for (r in 0 until col1.length()) {
                            val run = col1.optJSONObject(r) ?: continue
                            val runNav = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                            val pageType = runNav?.optJSONObject("browseEndpointContextSupportedConfigs")
                                ?.optJSONObject("browseEndpointContextMusicConfig")
                                ?.optString("pageType")
                            if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                                artistName = run.optString("text")
                                break
                            }
                        }
                        if (artistName.isBlank() && col1.length() >= 3) {
                            artistName = col1.optJSONObject(2)?.optString("text") ?: ""
                        }
                    }
                    if (artistName.isBlank()) artistName = q

                    // Strict Album Filter: Drop single / buku audio / podcast (only allow Album or EP)
                    if (recordType.contains("single") || recordType.contains("buku audio") || recordType.contains("audiobook")) {
                        continue
                    }

                    // Thumbnail
                    val thumbs = flex.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")

                    var thumbUrl = ""
                    if (thumbs != null && thumbs.length() > 0) {
                        thumbUrl = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") ?: ""
                    }
                    if (thumbUrl.contains("=w") && thumbUrl.contains("-h")) {
                        thumbUrl = thumbUrl.replace(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
                    }

                    albums.add(
                        Album(
                            id = browseId,
                            title = title,
                            artist = artistName,
                            trackCount = 0L,
                            thumbnailUrl = thumbUrl,
                            url = browseId
                        )
                    )
                }
            }
            albums
        }
    }

    private suspend fun fetchAlbumsFromArtistChannel(query: String, limit: Int): List<Album> {
        return try {
            val payload = JSONObject()
                .put("context", createBaseContext())
                .put("query", query)

            val json = postInnerTube("search", payload)
            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
            val sections = tabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            // Look for musicCardShelfRenderer (Official Artist Card)
            var channelBrowseId = ""
            var matchedArtistName = ""
            for (s in 0 until sections.length()) {
                val sec = sections.optJSONObject(s) ?: continue
                val card = sec.optJSONObject("musicCardShelfRenderer") ?: continue
                val runs = card.optJSONObject("title")?.optJSONArray("runs")
                val cardTitle = runs?.optJSONObject(0)?.optString("text") ?: ""
                val nav = runs?.optJSONObject(0)?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val bid = nav?.optString("browseId") ?: ""

                // Verify relevance: artist title loosely matches query
                if (bid.isNotBlank() && (cardTitle.contains(query, ignoreCase = true) || query.contains(cardTitle, ignoreCase = true))) {
                    channelBrowseId = bid
                    matchedArtistName = cardTitle
                    break
                }
            }

            if (channelBrowseId.isBlank()) return emptyList()

            // Browse Artist Channel
            val bPayload = JSONObject()
                .put("context", createBaseContext())
                .put("browseId", channelBrowseId)

            val bJson = postInnerTube("browse", bPayload)
            var bTabs = bJson.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
            if (bTabs == null) {
                bTabs = bJson.optJSONObject("contents")
                    ?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")
            }

            val bSec = bTabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return emptyList()

            val albums = mutableListOf<Album>()
            for (s in 0 until bSec.length()) {
                val secItem = bSec.optJSONObject(s) ?: continue
                val shelf = secItem.optJSONObject("musicCarouselShelfRenderer") ?: continue
                val header = shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                val shelfTitle = header?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""

                // Only take from "Album" shelf
                if (shelfTitle.contains("Album", ignoreCase = true)) {
                    val items = shelf.optJSONArray("contents") ?: continue
                    for (i in 0 until items.length()) {
                        if (albums.size >= limit) break
                        val item = items.optJSONObject(i) ?: continue
                        val mir = item.optJSONObject("musicTwoRowItemRenderer") ?: continue

                        val aNav = mir.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        val aBrowseId = aNav?.optString("browseId") ?: ""
                        if (aBrowseId.isBlank()) continue

                        val titleRuns = mir.optJSONObject("title")?.optJSONArray("runs")
                        val aTitle = titleRuns?.optJSONObject(0)?.optString("text") ?: ""
                        if (aTitle.isBlank()) continue

                        val thumbs = mir.optJSONObject("thumbnailRenderer")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        var thumbUrl = ""
                        if (thumbs != null && thumbs.length() > 0) {
                            thumbUrl = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url") ?: ""
                        }
                        if (thumbUrl.contains("=w") && thumbUrl.contains("-h")) {
                            thumbUrl = thumbUrl.replace(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
                        }

                        albums.add(
                            Album(
                                id = aBrowseId,
                                title = aTitle,
                                artist = matchedArtistName,
                                trackCount = 0L,
                                thumbnailUrl = thumbUrl,
                                url = aBrowseId
                            )
                        )
                    }
                    break // Found Album shelf
                }
            }
            albums
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Retrieve full tracklist of an album via its browseId (e.g. MPREb_...).
     */
    suspend fun getAlbumTracks(browseId: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = JSONObject()
                .put("context", createBaseContext())
                .put("browseId", browseId)

            val json = postInnerTube("browse", payload)
            val twoCol = json.optJSONObject("contents")
                ?.optJSONObject("twoColumnBrowseResultsRenderer")

            // Extract album artwork & default artist name from Header
            val tabs = twoCol?.optJSONArray("tabs")
            val secRend = tabs?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents")

            var albumCover = ""
            var defaultArtist = ""
            if (secRend != null && secRend.length() > 0) {
                val header = secRend.optJSONObject(0)?.optJSONObject("musicResponsiveHeaderRenderer")
                val headerThumbs = header?.optJSONObject("thumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
                if (headerThumbs != null && headerThumbs.length() > 0) {
                    albumCover = headerThumbs.optJSONObject(headerThumbs.length() - 1)?.optString("url") ?: ""
                }
                val subRuns = header?.optJSONObject("subtitle")?.optJSONArray("runs")
                if (subRuns != null) {
                    for (r in 0 until subRuns.length()) {
                        val run = subRuns.optJSONObject(r) ?: continue
                        val runNav = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        val pageType = runNav?.optJSONObject("browseEndpointContextSupportedConfigs")
                            ?.optJSONObject("browseEndpointContextMusicConfig")
                            ?.optString("pageType")
                        if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                            defaultArtist = run.optString("text")
                            break
                        }
                    }
                }
            }

            if (albumCover.contains("=w") && albumCover.contains("-h")) {
                albumCover = albumCover.replace(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
            }

            // Extract tracks from secondaryContents
            val secContents = twoCol?.optJSONObject("secondaryContents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: return@runCatching emptyList()

            val tracks = mutableListOf<Track>()
            for (s in 0 until secContents.length()) {
                val secItem = secContents.optJSONObject(s) ?: continue
                val shelf = secItem.optJSONObject("musicShelfRenderer") ?: continue
                val items = shelf.optJSONArray("contents") ?: continue

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val flex = item.optJSONObject("musicResponsiveListItemRenderer") ?: continue

                    // Track Title
                    val flexCols = flex.optJSONArray("flexColumns") ?: continue
                    val titleRuns = flexCols.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                    val trackTitle = titleRuns?.optJSONObject(0)?.optString("text") ?: ""
                    if (trackTitle.isBlank()) continue

                    // Track Video ID
                    val playBtn = flex.optJSONObject("overlay")
                        ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicPlayButtonRenderer")
                    val videoId = playBtn?.optJSONObject("playNavigationEndpoint")
                        ?.optJSONObject("watchEndpoint")
                        ?.optString("videoId") ?: ""
                    if (videoId.isBlank()) continue

                    // Track Artist
                    var trackArtist = defaultArtist
                    if (flexCols.length() > 1) {
                        val subRuns = flexCols.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")
                        if (subRuns != null) {
                            for (r in 0 until subRuns.length()) {
                                val run = subRuns.optJSONObject(r) ?: continue
                                val runNav = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                                val pageType = runNav?.optJSONObject("browseEndpointContextSupportedConfigs")
                                    ?.optJSONObject("browseEndpointContextMusicConfig")
                                    ?.optString("pageType")
                                if (pageType == "MUSIC_PAGE_TYPE_ARTIST") {
                                    trackArtist = run.optString("text")
                                    break
                                }
                            }
                        }
                    }

                    // Duration parsing (mm:ss -> seconds)
                    val fixCols = flex.optJSONArray("fixedColumns")
                    val durStr = fixCols?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                        ?.optJSONObject("text")
                        ?.optJSONArray("runs")
                        ?.optJSONObject(0)
                        ?.optString("text") ?: "0:00"

                    val durParts = durStr.split(":")
                    val durSec = if (durParts.size == 2) {
                        (durParts[0].toLongOrNull() ?: 0L) * 60 + (durParts[1].toLongOrNull() ?: 0L)
                    } else if (durParts.size == 3) {
                        (durParts[0].toLongOrNull() ?: 0L) * 3600 + (durParts[1].toLongOrNull() ?: 0L) * 60 + (durParts[2].toLongOrNull() ?: 0L)
                    } else 0L

                    tracks.add(
                        Track(
                            id = videoId,
                            title = trackTitle,
                            artist = trackArtist,
                            durationSeconds = durSec,
                            thumbnailUrl = albumCover
                        )
                    )
                }
            }
            tracks
        }
    }
}
