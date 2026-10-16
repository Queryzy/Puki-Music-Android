package com.puki.nuclear.model

data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val trackCount: Long,
    val thumbnailUrl: String,
    val url: String
)
