package com.puki.nuclear.core

import com.puki.nuclear.model.Album
import com.puki.nuclear.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfoItem

object StreamResolver {

    suspend fun getDashboardTracks(querySeed: String? = null): Result<List<Track>> = withContext(Dispatchers.IO) {
        val q = if (!querySeed.isNullOrBlank()) querySeed else "Trending Musik Indonesia Populer"
        searchTracks(q)
    }

    suspend fun searchTracks(query: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        runCatching {
            val service = ServiceList.YouTube
            val searchExtractor: SearchExtractor = service.getSearchExtractor(query)
            searchExtractor.fetchPage()

            val results = mutableListOf<Track>()
            val items = searchExtractor.initialPage.items

            for (item in items) {
                if (item is StreamInfoItem) {
                    val bestThumb = item.thumbnails.lastOrNull()?.url.orEmpty()

                    results.add(
                        Track(
                            id = item.url,
                            title = item.name,
                            artist = item.uploaderName,
                            durationSeconds = item.duration,
                            thumbnailUrl = bestThumb,
                            viewCount = try { item.viewCount } catch (_: Exception) { 0L }
                        )
                    )
                }
            }
            results
        }
    }

    suspend fun searchAlbums(artistOrQuery: String): Result<List<Album>> = withContext(Dispatchers.IO) {
        runCatching {
            val service = ServiceList.YouTube
            val query = if (artistOrQuery.contains("album", ignoreCase = true)) {
                artistOrQuery
            } else {
                "$artistOrQuery full album"
            }

            val searchExtractor: SearchExtractor = try {
                service.getSearchExtractor(query, listOf("playlists"), null)
            } catch (_: Exception) {
                service.getSearchExtractor(query)
            }
            searchExtractor.fetchPage()

            val results = mutableListOf<Album>()
            for (item in searchExtractor.initialPage.items) {
                if (item is PlaylistInfoItem) {
                    results.add(
                        Album(
                            id = item.url,
                            title = item.name,
                            artist = item.uploaderName ?: artistOrQuery,
                            trackCount = item.streamCount,
                            thumbnailUrl = item.thumbnails.lastOrNull()?.url.orEmpty(),
                            url = item.url
                        )
                    )
                }
            }
            results
        }
    }

    suspend fun getAlbumTracks(albumUrl: String): Result<List<Track>> = withContext(Dispatchers.IO) {
        runCatching {
            val service = ServiceList.YouTube
            val playlistExtractor = service.getPlaylistExtractor(albumUrl)
            playlistExtractor.fetchPage()

            val results = mutableListOf<Track>()
            for (item in playlistExtractor.initialPage.items) {
                results.add(
                    Track(
                        id = item.url,
                        title = item.name,
                        artist = item.uploaderName ?: "",
                        durationSeconds = item.duration,
                        thumbnailUrl = item.thumbnails.lastOrNull()?.url.orEmpty()
                    )
                )
            }
            results
        }
    }

    data class StreamResolution(
        val streamUrl: String,
        val viewCount: Long,
        val likeCount: Long
    )

    suspend fun resolveAudioStreamDetailed(trackUrl: String, maxRetries: Int = 3): Result<StreamResolution> = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            val result = runCatching {
                val service = ServiceList.YouTube
                val videoId = if (trackUrl.contains("v=")) {
                    trackUrl.substringAfter("v=").substringBefore("&")
                } else if (trackUrl.contains("youtu.be/")) {
                    trackUrl.substringAfter("youtu.be/").substringBefore("?")
                } else {
                    trackUrl.trim().removePrefix("/")
                }
                val cleanUrl = "https://www.youtube.com/watch?v=$videoId"
                val streamExtractor = service.getStreamExtractor(cleanUrl)
                streamExtractor.fetchPage()

                val views = try { streamExtractor.viewCount } catch (_: Exception) { 0L }
                val likes = try { streamExtractor.likeCount } catch (_: Exception) { 0L }

                val audioStreams = streamExtractor.audioStreams
                if (audioStreams.isNotEmpty()) {
                    val sortedStreams = audioStreams.sortedWith(
                        compareByDescending<AudioStream> { stream ->
                            val isOpus = stream.codec?.contains("opus", ignoreCase = true) == true ||
                                         stream.format?.name?.contains("WEB", ignoreCase = true) == true
                            val bitrate = stream.averageBitrate.takeIf { it > 0 } ?: stream.bitrate
                            if (isOpus) 1_000_000 + bitrate else bitrate
                        }
                    )
                    val bestAudio = sortedStreams.firstOrNull {
                        !it.url.isNullOrBlank() || !it.content.isNullOrBlank()
                    }
                    if (bestAudio != null) {
                        val url = if (!bestAudio.url.isNullOrBlank()) bestAudio.url else bestAudio.content
                        return@runCatching StreamResolution(
                            streamUrl = url ?: throw IllegalStateException("Audio URL kosong"),
                            viewCount = views,
                            likeCount = likes
                        )
                    }
                }

                val videoStreams = streamExtractor.videoStreams
                if (videoStreams.isNotEmpty()) {
                    val fallbackStream = videoStreams.firstOrNull { it.url != null || it.content != null }
                    if (fallbackStream != null) {
                        val url = if (!fallbackStream.url.isNullOrBlank()) fallbackStream.url else fallbackStream.content
                        return@runCatching StreamResolution(
                            streamUrl = url ?: throw IllegalStateException("Fallback URL kosong"),
                            viewCount = views,
                            likeCount = likes
                        )
                    }
                }

                throw IllegalStateException("Stream CDN tidak ditemukan untuk track ini")
            }

            if (result.isSuccess) {
                return@withContext result
            }
            lastError = result.exceptionOrNull()
            if (attempt < maxRetries) {
                kotlinx.coroutines.delay(attempt * 600L)
            }
        }
        Result.failure(lastError ?: IllegalStateException("Gagal memuat stream setelah $maxRetries percobaan"))
    }

    suspend fun resolveAudioStream(trackUrl: String): Result<String> {
        return resolveAudioStreamDetailed(trackUrl).map { it.streamUrl }
    }
}
