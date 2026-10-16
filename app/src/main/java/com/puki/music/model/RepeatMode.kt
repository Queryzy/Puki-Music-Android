package com.puki.music.model

enum class RepeatMode {
    OFF,    // Putar antrean berurutan, berhenti saat lagu terakhir selesai
    ALL,    // Putar antrean berurutan, ulang kembali ke awal saat selesai (looping playlist/album/liked)
    ONE     // Ulangi 1 lagu yang sedang aktif terus-menerus
}

enum class NetworkDownloadPreference {
    WIFI_ONLY,      // Hanya unduh saat tersambung Wi-Fi
    ANY_NETWORK     // Unduh di jaringan apa pun (Wi-Fi & Kuota/Data Seluler)
}
