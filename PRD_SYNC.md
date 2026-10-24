# PRD: Zero-Knowledge Cross-Device Sync (Puki Music)

## 1. Executive Summary
- **Tujuan**: Sinkronisasi riwayat putar (*play history*), pencarian (*recent searches*), lagu favorit (*favorites/likes*), dan daftar putar (*custom playlists*) antar-perangkat secara otomatis, real-time, dan dua arah (Android ⇄ iOS ⇄ Desktop).
- **Filosofi Arsitektur**: **Zero-Knowledge & Client-Side End-to-End Encryption (E2EE)**. Server relay bertindak murni sebagai *untrusted blind ledger*. Data didekripsi hanya di perangkat pengguna.
- **Infrastruktur Relay**: Cloudflare Workers + Cloudflare D1 (Serverless SQLite), 100% free-tier permanen, multi-tenant aman terisolasi per `vault_id`.
- **User Experience**: Pairing tanpa input manual rumit via Scan QR Code 2 arah atau import file cadangan profil.

---

## 2. Threat Model & Security Architecture

### 2.1. Dual-Key Cryptographic Derivation
Kunci rahasia `sync_secret` (256-bit CSPRNG hex) yang digenerate oleh client dipecah menjadi dua fungsi menggunakan SHA-256:
1. **`K_enc` (Encryption Key)**:
   $$\text{K\_enc} = \text{HMAC-SHA256}(\text{"puki_encryption_v1"}, \text{sync\_secret})$$
   - *Status*: Disimpan lokal di perangkat (SharedPreferences / Keychain). **Dilarang dikirim ke jaringan**.
   - *Penggunaan*: Kunci simetris AES-256-GCM untuk encrypt/decrypt event payload.
2. **`K_auth` (Authentication Key)**:
   $$\text{K\_auth} = \text{HMAC-SHA256}(\text{"puki_auth_v1"}, \text{sync\_secret})$$
   - *Status*: Digunakan untuk signing HMAC request ke Relay Server.
   - *Verifier Server*: Server hanya menyimpan $\text{auth\_verifier} = \text{SHA-256}(\text{K\_auth})$.

### 2.2. Payload Enkripsi (AES-256-GCM)
Setiap event diserialisasi ke JSON lalu dienkripsi:
- **Cipher**: AES-256-GCM.
- **Nonce/IV**: 12 bytes unik acak per event.
- **Auth Tag**: 16 bytes authentication tag.
- **Format Transfer**: Base64 dari gabungan `[IV (12B)] + [Ciphertext] + [Auth Tag (16B)]`.

### 2.3. Anti-Replay & Request Authentication
Header wajib pada setiap HTTP request:
- `X-Vault-ID`: UUID string identifikasi vault.
- `X-Timestamp`: Epoch UTC (milliseconds). Server menolak request jika $|\text{server\_time} - \text{timestamp}| > 300\,000\text{ ms}$ (5 menit).
- `X-Signature`: Hex dari $\text{HMAC-SHA256}(\text{vault\_id} + \text{timestamp} + \text{body}, \text{K\_auth})$.

---

## 3. Spesifikasi Cloudflare Worker & D1 Database

### 3.1. D1 Database Schema
```sql
-- Registrasi Vault
CREATE TABLE IF NOT EXISTS vaults (
    vault_id TEXT PRIMARY KEY,
    auth_verifier TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

-- Event Ledger (Append-Only Delta)
CREATE TABLE IF NOT EXISTS sync_events (
    id TEXT PRIMARY KEY,
    vault_id TEXT NOT NULL,
    seq INTEGER NOT NULL,
    payload_encrypted TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    FOREIGN KEY(vault_id) REFERENCES vaults(vault_id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_vault_seq ON sync_events(vault_id, seq);
CREATE INDEX IF NOT EXISTS idx_vault_created ON sync_events(vault_id, created_at);
```

### 3.2. REST API Endpoints

#### 1. Inisialisasi Vault: `POST /api/v1/vault/init`
- **Request Body**:
  ```json
  {
    "vault_id": "a1b2c3d4-...",
    "auth_verifier": "hex_sha256_of_k_auth"
  }
  ```
- **Responses**:
  - `201 Created`: Vault berhasil didaftarkan.
  - `409 Conflict`: Vault ID sudah digunakan (client regenerate UUID).

#### 2. Push Delta Events: `POST /api/v1/sync/push`
- **Headers**: `X-Vault-ID`, `X-Timestamp`, `X-Signature`
- **Request Body**:
  ```json
  {
    "events": [
      {
        "id": "event_uuid_v4",
        "payload_encrypted": "base64_blob"
      }
    ]
  }
  ```
- **Logika Server**:
  - Validasi signature via `auth_verifier`.
  - Hitung `next_seq = MAX(seq) + 1` untuk `vault_id` terkait.
  - Insert batch event ke `sync_events`.
- **Response**: `200 OK` `{"synced": count, "latest_seq": 105}`.

#### 3. Pull Delta Events: `GET /api/v1/sync/pull?since_seq=100`
- **Headers**: `X-Vault-ID`, `X-Timestamp`, `X-Signature`
- **Logika Server**:
  - Query: `SELECT id, seq, payload_encrypted, created_at FROM sync_events WHERE vault_id = ? AND seq > ? ORDER BY seq ASC LIMIT 200`
- **Response**: `200 OK`
  ```json
  {
    "latest_seq": 105,
    "events": [
      {
        "id": "uuid",
        "seq": 101,
        "payload_encrypted": "base64_blob",
        "created_at": 1729688400000
      }
    ]
  }
  ```

#### 4. Health Check: `GET /api/v1/health`
- **Response**: `200 OK` `{"status": "ok", "service": "puki-sync-relay", "time": 1729688400}`.

#### 5. Scheduled Pruning (Cloudflare Cron Trigger)
- Dijalankan 1x per hari: `DELETE FROM sync_events WHERE created_at < ?` (event > 30 hari).

---

## 4. Format Data Event & Protokol Pairing

### 4.1. Event Payload Plaintext (Sebelum Dienkripsi)
```json
{
  "event_id": "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "device_id": "c9284fa0-82a1-4322-9011-aa9b02319dfa",
  "device_type": "android",
  "timestamp": 1729688400000,
  "action": "RECORD_PLAY | TOGGLE_FAVORITE | ADD_SEARCH | UPDATE_PLAYLIST",
  "data": { ... }
}
```

### 4.2. Resolusi Konflik per Tipe Data
1. **Search History (`ADD_SEARCH`)**:
   - `data`: `{"query": "Yoasobi"}`
   - *Conflict Resolution*: Union / Deduplikasi string case-insensitive, simpan 30 query terbaru.
2. **Play History (`RECORD_PLAY`)**:
   - `data`: `{"id": "...", "title": "...", "artist": "...", "thumbnailUrl": "...", "duration": 210, "playedAt": 1729688400000}`
   - *Conflict Resolution*: Append & recalculate `playCount = playCount + 1`, sort by `lastPlayed DESC`.
3. **Favorites / Likes (`TOGGLE_FAVORITE`)**:
   - `data`: `{"track": { ... }, "isLiked": true, "updatedAt": 1729688400000}`
   - *Conflict Resolution*: Last-Write-Wins (LWW) berdasarkan `updatedAt`.
4. **Playlists (`UPDATE_PLAYLIST`)**:
   - `data`: `{"playlistId": "...", "name": "...", "operation": "ADD_TRACK | REMOVE_TRACK | RENAME", "track": { ... }, "updatedAt": 1729688400000}`
   - *Conflict Resolution*: Event-based item modification (idempotent, tidak menimpa seluruh list).

### 4.3. Skema URI Pairing (QR Code)
```text
puki://sync?url=https%3A%2F%2Fsync.pukimusic.top&vault=a1b2c3d4-xxxx-xxxx-xxxx-xxxxxxxxxxxx&key=9f8e7d6c5b4a3f2e1d0c9b8a7f6e5d4c3b2a1f0e9d8c7b6a5f4e3d2c1b0a9f8e
```
- Menampung seluruh konfigurasi dalam < 140 karakter teks UTF-8, rendering QR sangat jernih dan mudah discan kamera mobile.

---

## 5. Implementasi Client Android

### 5.1. Struktur Modul
- `com.puki.music.sync.SyncCrypto`: Utility AES-256-GCM + HMAC-SHA256 (Android SDK `javax.crypto`).
- `com.puki.music.sync.SyncManager`:
  - Singleton pengelola lifecycle sync.
  - OkHttp client dengan retry policy & rate limiter.
  - Outbox persistent queue di SharedPreferences.
  - Polling interval 30 detik saat foreground (`CoroutineScope(Dispatchers.IO)`).
  - Pull instan saat `ProcessLifecycleOwner` status `ON_RESUME`.
- `com.puki.music.sync.QrHelper`:
  - `generateQrBitmap(content: String, size: Int): Bitmap` (ZXing core).
  - Launcher `GmsBarcodeScanning.getClient(context).startScan()`.

### 5.2. Hooking Titik Data di `LocalMusicStore.kt`
- Hook pengiriman event keluar:
  - `recordPlay()` -> `SyncManager.queueEvent("RECORD_PLAY", trackJson)`
  - `toggleFavorite()` -> `SyncManager.queueEvent("TOGGLE_FAVORITE", favJson)`
  - `addRecentSearch()` -> `SyncManager.queueEvent("ADD_SEARCH", queryJson)`
  - `addTrackToPlaylist()` -> `SyncManager.queueEvent("UPDATE_PLAYLIST", playlistJson)`
- Handler event masuk:
  - `applyRemoteSyncEvent(eventJson)` -> Parsing tipe aksi -> update memory & commit ke SharedPreferences tanpa restart app.

---

## 6. Implementasi Client iOS

### 6.1. Struktur Modul
- `PukiMusic/Services/SyncCrypto.swift`: Enkripsi AES.GCM dan hashing HMAC-SHA256 menggunakan native Apple `CryptoKit`.
- `PukiMusic/Services/SyncManager.swift`:
  - Manager sync ObservableObject.
  - Polling timer 30 detik selama scene `.active`.
  - Push event queue via `URLSession`.
- `PukiMusic/Views/QrViews.swift`:
  - `QRCodeView`: Menggunakan `CIFilter(name: "CIQRCodeGenerator")` & `Image(uiImage:)`.
  - `QRScannerView`: SwiftUI UIViewControllerRepresentable membungkus `AVCaptureMetadataOutput`.

### 6.2. Hooking Titik Data di `LocalMusicStore.swift`
- Hook pengiriman event keluar di method penyimpanan lokal.
- Handler event masuk otomatis mengupdate array `@Published` (`favorites`, `playlists`, `recentSearches`, `playHistory`) sehingga SwiftUI langsung re-render secara reaktif.

---

## 7. Rincian Tiket Tugas (Task Breakdown T1 - T12)

| Tiket | Komponen | Deskripsi Pekerjaan | Kriteria Selesai (Acceptance Criteria) |
| :--- | :--- | :--- | :--- |
| **T1** | Backend / Relay | Setup Cloudflare Worker, binding D1 database, dan migrasi DDL schema tabel `vaults` dan `sync_events`. | Endpoint `/api/v1/health` merespons HTTP 200, tabel D1 berhasil dibuat. |
| **T2** | Backend / Relay | Implementasi handler `/api/v1/vault/init`, `/api/v1/sync/push`, dan `/api/v1/sync/pull` dengan validasi HMAC signature dan timestamp guard. | Test via curl: push event berhasil menyimpan ciphertext dan pull mengembalikan delta event sesuai urutan `seq`. |
| **T3** | Android / Core | Tambah lib `com.google.zxing:core:3.5.3` & `play-services-code-scanner` di `app/build.gradle.kts`. | Project Android berhasil sinkronisasi Gradle dan clean compile tanpa error dependensi. |
| **T4** | Android / Kripto | Buat kelas `com.puki.music.sync.SyncCrypto.kt` (AES-256-GCM enkripsi, dekripsi, key derivation, dan HMAC signing). | Unit test kripto: plaintext -> encrypt -> decrypt menghasilkan string asli identik. |
| **T5** | Android / Engine | Buat `com.puki.music.sync.SyncManager.kt` dengan background push queue, polling loop 30s, dan instant pull lifecycle hook. | Event dapat dikirim ke Cloudflare D1 dan event baru berhasil diunduh secara berkala. |
| **T6** | Android / Store | Patch `LocalMusicStore.kt` dengan hook push event pada play, like, search, playlist, serta method `applyRemoteSyncEvent()`. | Aksi lokal men-trigger antrean sync dan event remote berhasil memperbarui SharedPreferences. |
| **T7** | Android / UI | Tambah sub-card "Sinkronisasi Antar-Perangkat" di Settings tab: tombol Tampilkan QR (pairing dialog) & tombol Scan QR (GMS scanner). | Pengguna dapat melihat QR pairing dan kamera scanner dapat membaca URI `puki://sync?...`. |
| **T8** | iOS / Kripto & Core| Buat `SyncCrypto.swift` dan `SyncManager.swift` menggunakan `CryptoKit` dan `URLSession`. | Kripto kompatibel 100% dengan Android (data dienkripsi di Android dapat didekripsi di iOS dan sebaliknya). |
| **T9** | iOS / Store | Patch `LocalMusicStore.swift` dengan trigger push event dan listener data remote ke array `@Published`. | Riwayat play, search, dan like masuk ke store iOS dan memperbarui UI secara reaktif. |
| **T10** | iOS / UI | Tambah permission `NSCameraUsageDescription` di Info.plist, buat QR Generator view dan AVCapture scanner view di `SettingsTabView`. | iPhone dapat menampilkan QR pairing dan memindai QR pairing dari Android. |
| **T11** | QA / E2E Sync | Uji pairing dua arah: Android scan iOS dan iOS scan Android. | Kunci sinkronisasi tersalin sempurna antar kedua perangkat tanpa input manual. |
| **T12** | QA / Verifikasi | Uji skenario nyata: pencarian di iOS muncul di Android, pemutaran di Android muncul di iOS, perubahan playlist ter-sync aman tanpa timpa data. | Seluruh data sinkron dalam waktu < 30 detik tanpa konflik data atau crash aplikasi. |
