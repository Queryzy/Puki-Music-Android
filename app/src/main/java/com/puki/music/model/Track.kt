package com.puki.music.model

import java.util.Locale

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val streamUrl: String? = null,
    val localFilePath: String? = null,
    val viewCount: Long = 0L,
    val likeCount: Long = 0L
) {
    val isOffline: Boolean
        get() = !localFilePath.isNullOrBlank()

    val formattedDuration: String
        get() {
            val minutes = durationSeconds / 60
            val seconds = durationSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }

    val formattedViews: String?
        get() {
            if (viewCount <= 0) return null
            return when {
                viewCount >= 1_000_000_000 -> String.format(Locale.US, "%.1fB", viewCount / 1_000_000_000.0)
                viewCount >= 1_000_000 -> String.format(Locale.US, "%.1fM", viewCount / 1_000_000.0)
                viewCount >= 1_000 -> String.format(Locale.US, "%.1fK", viewCount / 1_000.0)
                else -> viewCount.toString()
            }
        }

    val formattedLikes: String?
        get() {
            if (likeCount <= 0) return null
            return when {
                likeCount >= 1_000_000 -> String.format(Locale.US, "%.1fM", likeCount / 1_000_000.0)
                likeCount >= 1_000 -> String.format(Locale.US, "%.1fK", likeCount / 1_000.0)
                else -> likeCount.toString()
            }
        }

    val isPopular: Boolean
        get() = viewCount >= 1_000_000L
}
