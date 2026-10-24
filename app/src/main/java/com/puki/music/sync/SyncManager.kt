package com.puki.music.sync

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import com.puki.music.data.LocalMusicStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class SyncConnectionState {
    DISCONNECTED,
    CONNECTING,
    SYNCED,
    ERROR
}

data class SyncStatus(
    val state: SyncConnectionState = SyncConnectionState.DISCONNECTED,
    val lastSyncTime: Long = 0L,
    val pendingOutboxCount: Int = 0,
    val errorMessage: String? = null,
    val isEnabled: Boolean = false
)

class SyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "PukiSyncManager"
        private const val PREFS_NAME = "puki_sync_prefs"
        private const val KEY_RELAY_URL = "sync_relay_url"
        private const val KEY_VAULT_ID = "sync_vault_id"
        private const val KEY_SYNC_SECRET = "sync_secret"
        private const val KEY_LAST_SYNC_SEQ = "sync_last_seq"
        private const val KEY_OUTBOX = "sync_outbox"
        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val KEY_SYNC_ENABLED = "sync_is_enabled"

        const val DEFAULT_RELAY_URL = "https://puki-sync.diylabs.top"

        @Volatile
        private var instance: SyncManager? = null

        fun getInstance(context: Context): SyncManager {
            return instance ?: synchronized(this) {
                instance ?: SyncManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var pollingJob: Job? = null

    private val _statusFlow = MutableStateFlow(SyncStatus())
    val statusFlow: StateFlow<SyncStatus> = _statusFlow.asStateFlow()

    init {
        ensureDeviceId()
        updateStatus()
        if (isSyncEnabled()) {
            startPolling()
        }
    }

    // ── Credentials & Pairing ──────────────────────────────────────────────

    fun getRelayUrl(): String {
        return prefs.getString(KEY_RELAY_URL, DEFAULT_RELAY_URL) ?: DEFAULT_RELAY_URL
    }

    fun setRelayUrl(url: String) {
        val clean = url.trim().trimEnd('/')
        prefs.edit().putString(KEY_RELAY_URL, clean).apply()
    }

    fun getVaultId(): String? {
        return prefs.getString(KEY_VAULT_ID, null)
    }

    fun getSyncSecret(): String? {
        return prefs.getString(KEY_SYNC_SECRET, null)
    }

    fun isSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_SYNC_ENABLED, false) && !getVaultId().isNullOrBlank() && !getSyncSecret().isNullOrBlank()
    }

    fun getDeviceId(): String {
        return ensureDeviceId()
    }

    private fun ensureDeviceId(): String {
        var devId = prefs.getString(KEY_DEVICE_ID, null)
        if (devId.isNullOrBlank()) {
            devId = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, devId).apply()
        }
        return devId
    }

    fun initializeNewVault(relayUrl: String = DEFAULT_RELAY_URL): String {
        val vaultId = UUID.randomUUID().toString()
        val secret = SyncCrypto.generateRandomSecret()
        saveCredentials(relayUrl, vaultId, secret)
        return getPairingUri()
    }

    fun saveCredentials(relayUrl: String, vaultId: String, syncSecret: String) {
        prefs.edit()
            .putString(KEY_RELAY_URL, relayUrl.trim().trimEnd('/'))
            .putString(KEY_VAULT_ID, vaultId.trim())
            .putString(KEY_SYNC_SECRET, syncSecret.trim())
            .putBoolean(KEY_SYNC_ENABLED, true)
            .putLong(KEY_LAST_SYNC_SEQ, 0L)
            .apply()

        updateStatus()
        startPolling()
        triggerSyncNow()
    }

    fun disableSync() {
        stopPolling()
        prefs.edit()
            .putBoolean(KEY_SYNC_ENABLED, false)
            .remove(KEY_VAULT_ID)
            .remove(KEY_SYNC_SECRET)
            .remove(KEY_LAST_SYNC_SEQ)
            .remove(KEY_OUTBOX)
            .apply()
        updateStatus()
    }

    fun getPairingUri(): String {
        val url = getRelayUrl()
        val vault = getVaultId() ?: ""
        val secret = getSyncSecret() ?: ""
        return "puki://sync?url=${Uri.encode(url)}&vault=${Uri.encode(vault)}&key=${Uri.encode(secret)}"
    }

    fun parseAndApplyPairingUri(uriString: String): Boolean {
        try {
            val uri = Uri.parse(uriString.trim())
            if (uri.scheme != "puki" || uri.host != "sync") return false

            val url = uri.getQueryParameter("url") ?: DEFAULT_RELAY_URL
            val vault = uri.getQueryParameter("vault")
            val key = uri.getQueryParameter("key")

            if (vault.isNullOrBlank() || key.isNullOrBlank()) return false

            saveCredentials(url, vault, key)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Gagal memproses kode pairing URI", e)
            return false
        }
    }

    // ── Outbox & Event Queuing ─────────────────────────────────────────────

    fun queueEvent(action: String, data: JSONObject) {
        if (!isSyncEnabled()) return

        val event = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("device_id", getDeviceId())
            put("device_type", "android")
            put("timestamp", System.currentTimeMillis())
            put("action", action)
            put("data", data)
        }

        synchronized(this) {
            val outboxStr = prefs.getString(KEY_OUTBOX, "[]") ?: "[]"
            val outboxArr = JSONArray(outboxStr)
            outboxArr.put(event)
            prefs.edit().putString(KEY_OUTBOX, outboxArr.toString()).apply()
        }

        updateStatus()
        scope.launch {
            syncCycle()
        }
    }

    private fun getOutboxEvents(): List<JSONObject> {
        val outboxStr = prefs.getString(KEY_OUTBOX, "[]") ?: "[]"
        val outboxArr = JSONArray(outboxStr)
        val list = mutableListOf<JSONObject>()
        for (i in 0 until outboxArr.length()) {
            list.add(outboxArr.getJSONObject(i))
        }
        return list
    }

    private fun removeSentEvents(sentIds: Set<String>) {
        if (sentIds.isEmpty()) return
        synchronized(this) {
            val outboxStr = prefs.getString(KEY_OUTBOX, "[]") ?: "[]"
            val outboxArr = JSONArray(outboxStr)
            val newArr = JSONArray()
            for (i in 0 until outboxArr.length()) {
                val obj = outboxArr.getJSONObject(i)
                if (!sentIds.contains(obj.optString("id"))) {
                    newArr.put(obj)
                }
            }
            prefs.edit().putString(KEY_OUTBOX, newArr.toString()).apply()
        }
        updateStatus()
    }

    // ── Polling & Lifecycle ────────────────────────────────────────────────

    fun onAppForegrounded() {
        if (isSyncEnabled()) {
            startPolling()
            triggerSyncNow()
        }
    }

    fun triggerSyncNow() {
        scope.launch {
            syncCycle()
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && isSyncEnabled()) {
                syncCycle()
                delay(30_000L) // 30 seconds interval
            }
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── Synchronization Pipeline ───────────────────────────────────────────

    private suspend fun syncCycle() {
        if (!isSyncEnabled()) return

        val vaultId = getVaultId() ?: return
        val secret = getSyncSecret() ?: return
        val relayUrl = getRelayUrl()

        val (kEnc, kAuth, authVerifier) = SyncCrypto.deriveKeys(secret)

        try {
            _statusFlow.value = _statusFlow.value.copy(state = SyncConnectionState.CONNECTING)

            // 1. Ensure vault registered on relay
            ensureVaultRegistered(relayUrl, vaultId, authVerifier)

            // 2. Push Outbox
            val pending = getOutboxEvents()
            if (pending.isNotEmpty()) {
                val batch = pending.take(50)
                val eventsPayload = JSONArray()
                val sentIds = mutableSetOf<String>()

                for (item in batch) {
                    val id = item.getString("id")
                    val plain = item.toString()
                    val cipher = SyncCrypto.encryptPayload(plain, kEnc)
                    eventsPayload.put(JSONObject().apply {
                        put("id", id)
                        put("payload_encrypted", cipher)
                    })
                    sentIds.add(id)
                }

                val pushBody = JSONObject().apply {
                    put("events", eventsPayload)
                }.toString()

                val timestamp = System.currentTimeMillis()
                val message = "$vaultId:$timestamp:$pushBody"
                val signature = SyncCrypto.computeHmacHex(kAuth, message)

                val request = Request.Builder()
                    .url("$relayUrl/api/v1/sync/push")
                    .post(pushBody.toRequestBody("application/json".toMediaType()))
                    .header("X-Vault-ID", vaultId)
                    .header("X-Timestamp", timestamp.toString())
                    .header("X-Signature", signature)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        removeSentEvents(sentIds)
                    } else {
                        Log.w(TAG, "Sync push gagal: HTTP ${resp.code}")
                    }
                }
            }

            // 3. Pull Delta
            val lastSeq = prefs.getLong(KEY_LAST_SYNC_SEQ, 0L)
            val pullTimestamp = System.currentTimeMillis()
            val pullMsg = "$vaultId:$pullTimestamp:"
            val pullSig = SyncCrypto.computeHmacHex(kAuth, pullMsg)

            val pullRequest = Request.Builder()
                .url("$relayUrl/api/v1/sync/pull?since_seq=$lastSeq")
                .get()
                .header("X-Vault-ID", vaultId)
                .header("X-Timestamp", pullTimestamp.toString())
                .header("X-Signature", pullSig)
                .build()

            httpClient.newCall(pullRequest).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bodyStr = resp.body?.string() ?: "{}"
                    val json = JSONObject(bodyStr)
                    val latestSeq = json.optLong("latest_seq", lastSeq)
                    val eventsArr = json.optJSONArray("events") ?: JSONArray()

                    val store = LocalMusicStore(context)
                    var appliedCount = 0

                    for (i in 0 until eventsArr.length()) {
                        val evtObj = eventsArr.getJSONObject(i)
                        val cipher = evtObj.getString("payload_encrypted")
                        try {
                            val decrypted = SyncCrypto.decryptPayload(cipher, kEnc)
                            val eventData = JSONObject(decrypted)
                            val originDevId = eventData.optString("device_id")

                            // Jangan proses balik event yang dibuat oleh device ini sendiri
                            if (originDevId != getDeviceId()) {
                                store.applyRemoteSyncEvent(eventData)
                                appliedCount++
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Gagal dekripsi payload remote event", e)
                        }
                    }

                    prefs.edit().putLong(KEY_LAST_SYNC_SEQ, latestSeq).apply()
                    _statusFlow.value = SyncStatus(
                        state = SyncConnectionState.SYNCED,
                        lastSyncTime = System.currentTimeMillis(),
                        pendingOutboxCount = getOutboxEvents().size,
                        errorMessage = null,
                        isEnabled = true
                    )
                } else {
                    _statusFlow.value = _statusFlow.value.copy(
                        state = SyncConnectionState.ERROR,
                        errorMessage = "Pull gagal: HTTP ${resp.code}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Kesalahan siklus sinkronisasi", e)
            _statusFlow.value = _statusFlow.value.copy(
                state = SyncConnectionState.ERROR,
                errorMessage = e.message ?: "Koneksi relay gagal"
            )
        }
    }

    private fun ensureVaultRegistered(relayUrl: String, vaultId: String, authVerifier: String) {
        // Minta PoW challenge ke server
        val chalReq = Request.Builder()
            .url("$relayUrl/api/v1/vault/challenge")
            .get()
            .build()

        val challengeJson: JSONObject = httpClient.newCall(chalReq).execute().use { resp ->
            if (!resp.isSuccessful) return
            JSONObject(resp.body?.string() ?: "{}")
        }

        val challengeStr = challengeJson.optString("challenge")
        val difficulty = challengeJson.optInt("difficulty", 4)
        if (challengeStr.isBlank()) return

        val nonce = SyncCrypto.solvePoW(challengeStr, difficulty)

        val initBody = JSONObject().apply {
            put("vault_id", vaultId)
            put("auth_verifier", authVerifier)
            put("challenge", challengeStr)
            put("nonce", nonce)
        }.toString()

        val initReq = Request.Builder()
            .url("$relayUrl/api/v1/vault/init")
            .post(initBody.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(initReq).execute().use { resp ->
            // HTTP 201 Created atau HTTP 409 Conflict (sudah terdaftar) keduanya valid
            if (resp.code == 201 || resp.code == 409) {
                // Siap
            } else {
                Log.w(TAG, "Registrasi vault relay status: ${resp.code}")
            }
        }
    }

    private fun updateStatus() {
        val outboxCount = getOutboxEvents().size
        _statusFlow.value = _statusFlow.value.copy(
            pendingOutboxCount = outboxCount,
            isEnabled = isSyncEnabled()
        )
    }
}
