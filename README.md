# 🎵 Puki Music (Android Native)

<div align="center">

![Puki Music Android](https://raw.githubusercontent.com/Queryzy/Puki-Music-Android/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png)

**Native Android Music Player (Zero-Ads, Background Playback, Synchronized Lyrics & Client-Side E2EE Cross-Device Sync)**

[![Platform](https://img.shields.io/badge/Platform-Android_8.0+_(API_26+)-brightgreen.svg)](https://developer.android.com)
[![Language](https://img.shields.io/badge/Language-Kotlin_1.9-purple.svg)](https://kotlinlang.org)
[![UI](https://img.shields.io/badge/UI-Jetpack_Compose_Material3-blue.svg)](https://developer.android.com/jetpack/compose)
[![Audio Engine](https://img.shields.io/badge/Audio-AndroidX_Media3_ExoPlayer-orange.svg)](https://developer.android.com/media/media3)
[![License](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

</div>

---

## 🌐 Ekosistem Puki Music
Puki Music hadir dalam ekosistem multi-platform mandiri yang saling terhubung:
* 🍏 **iOS Client (SwiftUI / AVPlayer)**: [Queryzy/Puki-Music-IOs](https://github.com/Queryzy/Puki-Music-IOs)
* 🤖 **Android Client (Jetpack Compose / ExoPlayer)**: [Queryzy/Puki-Music-Android](https://github.com/Queryzy/Puki-Music-Android)
* ⚡ **Blind Ledger Sync Relay (Workers & Docker)**: [Queryzy/Puki-Sync-Relay](https://github.com/Queryzy/Puki-Sync-Relay)

---

## ✨ Fitur Utama
* **100% Zero-Ads Playback**: Streaming langsung stream audio YouTube Innertube CDN (Opus 160kbps) tanpa iklan sponsor maupun overhead video player.
* **Background & Lockscreen Playback**: Pemutaran mulus di latar belakang layar mati dengan integrasi `MediaSessionService` dan `androidx.media3.session`.
* **Karaoke Synchronized Lyrics**: Integrasi real-time LRCLIB dengan auto-scroll kata per kata dan tap-to-seek ke durasi lagu.
* **Offline Storage & Download**: Unduh lagu favorit untuk diputar offline tanpa kuota internet dengan fitur MP3/M4A storage cleaner.
* **Discography Artis & Related Tracks**: Eksplorasi album, top singles, dan rekomendasi lagu terkait tanpa login akun Google.
* **Zero-Knowledge E2EE Cross-Device Sync**: Sinkronisasi riwayat putar, pencarian, dan lagu favorit 1:1 antara Android & iOS tanpa pihak ketiga dapat membaca data Anda.

---

## 🔐 Mekanisme & Arsitektur Sinkronisasi E2EE
Puki Music menggunakan arsitektur **Zero-Knowledge Blind Ledger**:
1. **Derivasi Kunci Kriptografi**:
   - `Sync Secret` 256-bit dihasilkan secara lokal menggunakan `SecureRandom`.
   - Melalui HKDF / PBKDF2-SHA256, secret diturunkan menjadi `K_enc` (AES-256-GCM) untuk enkripsi data dan `K_auth` (HMAC-SHA256) untuk tanda tangan request ke relay.
2. **Pairing Antar-Perangkat via QR Code**:
   - Secret dipindahkan antar-perangkat secara visual melalui QR Code (`pukisync://pair?vault=...&key=...`). Kunci **tidak pernah dikirim ke internet**.
3. **Blind Ledger Relay**:
   - Server relay hanya menerima dan menyimpan `payload_encrypted` (ciphertext). Server, Cloudflare, dan admin relay **mustahil membaca judul lagu, nama artis, atau riwayat putar**.
4. **Non-Destructive Set Union (1:1 Sync)**:
   - Penggabungan data lama dan baru menggunakan skema Set Union & LWW (Last-Write-Wins), memastikan lagu favorit dan histori dari kedua perangkat menyatu tanpa saling menghapus.

---

## 📲 Cara Install APK

### Cara 1: Unduh APK Langsung (Release)
1. Buka halaman **[Releases](https://github.com/Queryzy/Puki-Music-Android/releases)** atau mirror internal [Gitea Releases](https://gitea.tsolusi.com/nanda/puki-music/releases).
2. Unduh file `Puki-Music.apk` versi terbaru.
3. Buka file APK di perangkat Android Anda dan izinkan instalasi dari sumber tidak dikenal (*Install Unknown Apps*).
4. Buka aplikasi dan nikmati musik bebas iklan.

### In-App Auto Update
Aplikasi dilengkapi sistem pembaruan otomatis bawaan:
* Buka **Pengaturan** > **Periksa Pembaruan**.
* Aplikasi akan mengecek rilis terbaru secara otomatis dan memperbarui APK langsung tanpa perlu download manual lewat browser.

---

## 🛠️ Build dari Source Code

### Prasyarat
* Android Studio Hedgehog (2023.1.1) atau versi lebih baru
* JDK 17
* Android SDK 34 (minSdk 26 - Android 8.0 Oreo ke atas)

### Langkah Kompilasi
```bash
# Clone repository
git clone https://github.com/Queryzy/Puki-Music-Android.git
cd Puki-Music-Android

# Build Debug APK
./gradlew assembleDevDebug

# Jalankan Unit Tests
./gradlew testDevDebugUnitTest

# Build Release APK
./gradlew assembleProdRelease
```
File APK biner hasil build akan berada di direktori `app/build/outputs/apk/prod/release/app-prod-release.apk`.

---

## ⚙️ Cara Menghubungkan Sinkronisasi (Pairing)
1. Buka tab **Pengaturan** di Puki Music Android.
2. Pada kartu **Sinkronisasi Antar-Perangkat**, masukkan URL Relay (contoh: `https://puki-sync.diylabs.top` atau self-hosted Anda).
3. Jika menghubungkan dari iPhone/perangkat lain:
   * Tekan tombol **Pindai QR**.
   * Arahkan kamera ke QR Code yang ditampilkan di layar iPhone Anda.
4. Atau tekan **Buat Kunci Baru** untuk menjadikan Android sebagai perangkat pembuat vault, lalu scan QR Android menggunakan iPhone.
5. Tekan **Sinkronkan Sekarang** untuk memicu penggabungan data 1:1 seketika.

---

## 📄 Lisensi
Didistribusikan di bawah lisensi MIT. Lihat file `LICENSE` untuk rincian lengkap.
