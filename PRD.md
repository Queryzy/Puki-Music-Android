# PRD: Puki Music Android (Client Application & E2EE Sync)

## 1. Overview & Objective
- **Nama Proyek**: Puki Music Android (`puki-music-android`)
- **Tujuan**: Pemutar audio Android ringan, zero-ads, background playback layar mati via AndroidX Media3 (ExoPlayer), dilengkapi mesin sinkronisasi telemetri antar-perangkat berbasis Client-Side E2EE (AES-256-GCM).
- **Format Penyimpanan**: Local-First `SharedPreferences` (`puki_music_store`) dengan integrasi antrean sinkronisasi delta ke Relay Cloudflare D1.

---

## 2. Arsitektur Komponen Sync Android

### 2.1. Paket Baru: `com.puki.music.sync`
1. **`SyncCrypto.kt`**:
   - `deriveKeys(secret: String): Pair<ByteArray, ByteArray>` (menghasilkan `K_enc` dan `K_auth`).
   - `encryptPayload(plaintext: String, key: ByteArray): String` (AES-256-GCM dengan 12-byte random IV dan 16-byte auth tag).
   - `decryptPayload(cipherBase64: String, key: ByteArray): String`.
   - `computeHmac(data: String, key: ByteArray): String`.
2. **`SyncManager.kt`**:
   - Pengelola sinkronisasi singleton.
   - Manajemen konfigurasi: `vault_id`, `sync_secret`, `relay_url`, `last_synced_seq`.
   - Antrean `sync_outbox` persisten di SharedPreferences untuk menampung event saat offline.
   - Polling periodik 30 detik saat aplikasi di foreground.
   - Trigger instan saat `ProcessLifecycleOwner` kembali ke `ON_RESUME`.
3. **`QrHelper.kt`**:
   - Generator Bitmap QR (menggunakan ZXing Core).
   - Launcher Google Code Scanner (`GmsBarcodeScanning`) untuk memindai QR tanpa request manual izin kamera sistem.

---

## 3. Hooking Data di `LocalMusicStore.kt`
- **Outgoing Events**:
  - `recordPlay()` -> Push `RECORD_PLAY` (id, title, artist, duration, thumbnailUrl, timestamp).
  - `toggleFavorite()` -> Push `TOGGLE_FAVORITE` (track metadata, isLiked, timestamp).
  - `addRecentSearch()` -> Push `ADD_SEARCH` (query, timestamp).
  - `createPlaylist()` / `addTrackToPlaylist()` -> Push `UPDATE_PLAYLIST` (playlist ID, name, track info, timestamp).
- **Incoming Events (`applyRemoteSyncEvent`)**:
  - Menerima plaintext JSON hasil dekripsi, memetakan ke fungsi internal store, dan meng-commit ke `SharedPreferences` tanpa merusak state audio playback yang sedang berjalan.

---

## 4. Rincian Tiket Pengerjaan Android (Task Breakdown)

| Tiket | Deskripsi Pekerjaan | Kriteria Penerimaan (Acceptance Criteria) |
| :--- | :--- | :--- |
| **T-AND-1** | Penambahan dependensi `com.google.zxing:core:3.5.3` dan `com.google.android.gms:play-services-code-scanner:16.1.0` pada `app/build.gradle.kts`. | Build Gradle tersinkronisasi bersih tanpa konflik library atau duplikasi manifest. |
| **T-AND-2** | Pembuatan modul kriptografi `com.puki.music.sync.SyncCrypto.kt` (AES-256-GCM, SHA-256 key derivation, HMAC-SHA256). | Plaintext string berhasil dienkripsi dan didekripsi kembali menjadi nilai awal 100% identik. |
| **T-AND-3** | Implementasi engine `com.puki.music.sync.SyncManager.kt` (Outbox queue, OkHttp requests, polling coroutine, lifecycle observer). | Mampu mengirim batch event ke Relay dan menerima event baru secara berkala saat layar menyala. |
| **T-AND-4** | Patching `LocalMusicStore.kt` untuk memasang trigger pengiriman event ke `SyncManager` dan method penerima `applyRemoteSyncEvent()`. | Setiap kali user mencari lagu, memberi like, atau memutar lagu, event otomatis masuk antrean sync dan event remote dapat diserap. |
| **T-AND-5** | Integrasi antarmuka pada `MainActivity.kt` di Tab Settings: Tombol pairing "Sinkronisasi Antar-Perangkat", dialog QR code, dan tombol scanner kamera GMS. | User dapat memunculkan QR Code pairing (`puki://sync?...`) dan kamera scanner dapat membaca QR dari perangkat lain secara instan. |
| **T-AND-6** | Pengujian integrasi Android: uji ketahanan offline (event tersimpan di antrean dan terkirim otomatis saat internet pulih), dan pemutaran audio tetap mulus saat sinkronisasi berlangsung. | Tidak ada audio stuttering atau UI freeze (ANR) selama proses sinkronisasi background. |
