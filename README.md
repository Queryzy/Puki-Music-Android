# 🎵 Puki Music (Android Native)

Aplikasi pemutar musik Android mandiri berbasis arsitektur streaming YouTube Innertube CDN via `NewPipeExtractor` dan audio player engine `androidx.media3`. 100% bebas iklan, background playback layar mati, synchronized lyrics karaoke, discography artis, dan download offline.

## ✨ Fitur Utama
- **Zero Ads & Continuous Audio Playback**: Direct audio stream (Googlevideo Opus 160kbps) tanpa jeda iklan sponsor maupun video player overhead.
- **Background Playback**: Berjalan di latar belakang dengan notifikasi kontrol media sistem (`MediaSessionService`) dan `WAKE_LOCK`.
- **Sequential Queue Autoplay**: Otomatis memutar lagu berikutnya di antrean saat lagu selesai.
- **Synchronized Lyrics (Karaoke)**: Integrasi real-time LRCLIB dengan auto-scroll dan tap-to-seek.
- **Discography Artis & Batch Download**: Jelajahi album dan playlist artis, unduh seluruh track dalam satu klik.
- **Offline Storage & Cleaner**: Manajemen penyimpanan audio lokal langsung di menu Settings.
- **Adaptive Launcher Icon**: Icon piringan vinyl merah kontras di atas latar putih bersih dengan padding simetris.

## 🚀 Download APK Langsung
- **Gitea Releases**: [Puki Music Releases](https://gitea.tsolusi.com/nanda/puki-music/releases)
- **Local Server**: `http://10.10.9.22:8088/Puki-Music.apk`

## 🛠️ Stack & Toolchain
- **Language**: Kotlin 1.9.x
- **UI Toolkit**: Jetpack Compose (Material 3)
- **Audio Engine**: `androidx.media3:media3-exoplayer:1.3.1`
- **Stream Scraper**: `com.github.TeamNewPipe:NewPipeExtractor:v0.26.5`
- **Build System**: Gradle 8.7, Android SDK 34 (minSdk 26)
