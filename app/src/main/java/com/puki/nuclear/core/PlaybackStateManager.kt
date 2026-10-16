package com.puki.nuclear.core

import com.puki.nuclear.model.Track

object PlaybackStateManager {
    @Volatile
    var activeTrack: Track? = null

    @Volatile
    var currentQueue: List<Track> = emptyList()

    @Volatile
    var onPlayNext: (() -> Unit)? = null

    @Volatile
    var onPlayPrevious: (() -> Unit)? = null
}
