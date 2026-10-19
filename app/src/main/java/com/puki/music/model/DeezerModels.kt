package com.puki.music.model

data class DeezerTrack(
    val id: Long,
    val title: String,
    val artistName: String,
    val artistId: Long,
    val albumTitle: String,
    val albumId: Long,
    val durationSeconds: Long,
    val coverUrl: String,
    val isrc: String? = null
)

data class DeezerAlbum(
    val id: Long,
    val title: String,
    val coverUrl: String,
    val artistName: String,
    val artistId: Long,
    val releaseDate: String? = null,
    val trackCount: Int = 0,
    val recordType: String = "album" // album, single, ep, compile
)

data class DeezerArtist(
    val id: Long,
    val name: String,
    val pictureUrl: String,
    val fanCount: Int = 0,
    val albumCount: Int = 0
)

data class DeezerDashboardData(
    val topTracks: List<DeezerTrack> = emptyList(),
    val topAlbums: List<DeezerAlbum> = emptyList(),
    val topArtists: List<DeezerArtist> = emptyList()
)
