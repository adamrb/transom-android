package org.plaudbridge.app.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.common.JwtUtils
import org.plaudbridge.app.storage.RecordingStore

/**
 * Manages the Plaud user access token (JWT) that the SDK needs for the device handshake.
 *
 * Unlike Plaud's template app (build-time USER_ACCESS_TOKEN), this app fetches the token at
 * runtime from the self-hosted plaud-bridge-server (POST /api/v1/plaud/user-token), caches it,
 * and refreshes it when the JWT is close to expiry or when the SDK reports an auth failure.
 *
 * The Android SDK facade has no setUserAccessToken (iOS parity gap); the live-refresh path is
 * sdk.NiceBuildSdk.setPartnerToken, which the SDK also uses internally after initSDK.
 */
object TokenManager {

    private const val TAG = "TokenManager"

    /** Refresh when less than this much lifetime remains (tokens live 86400s). */
    private const val EXPIRY_MARGIN_SEC = 30 * 60L

    private val refreshMutex = Mutex()

    /** Cached token if it parses and is not near expiry, else null. */
    fun cachedTokenIfValid(): String? {
        val token = RecordingStore.cachedPlaudToken ?: return null
        val info = JwtUtils.parse(token) ?: return null
        val now = System.currentTimeMillis() / 1000
        return if (info.expSeconds - now > EXPIRY_MARGIN_SEC) token else null
    }

    /** Best-effort current token for synchronous callers (may be expired; empty if none yet). */
    val currentToken: String
        get() = RecordingStore.cachedPlaudToken ?: ""

    /**
     * Return a valid token, fetching a fresh one from the bridge server when the cache is
     * missing/near expiry or [force] is set. Blocking network I/O — call from Dispatchers.IO.
     */
    suspend fun getValidToken(force: Boolean = false): String = refreshMutex.withLock {
        if (!force) cachedTokenIfValid()?.let { return it }
        val userId = RecordingStore.getOrCreateUserId()
        val token = ApiClient.fetchUserToken(userId)
        RecordingStore.cachedPlaudToken = token
        AppLog.i(TAG, "Fetched Plaud user token for $userId (${JwtUtils.mask(token)})")
        token
    }

    /**
     * Refresh the token and hand it to the (already initialized) SDK. Used when the JWT nears
     * expiry mid-session or the SDK reports an auth failure (e.g. 401 from the partner API).
     * Returns the new token, or null if the fetch failed.
     */
    suspend fun refreshAndApply(): String? = try {
        val token = getValidToken(force = true)
        try {
            sdk.NiceBuildSdk.setPartnerToken(token)
            AppLog.i(TAG, "Applied refreshed token to SDK")
        } catch (e: Exception) {
            AppLog.w(TAG, "setPartnerToken failed", e)
        }
        token
    } catch (e: Exception) {
        AppLog.w(TAG, "Token refresh failed", e)
        null
    }
}
