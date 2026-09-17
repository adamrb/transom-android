package io.github.adamrb.transom.net

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import io.github.adamrb.transom.common.AppLog
import io.github.adamrb.transom.common.JwtUtils
import io.github.adamrb.transom.storage.RecordingStore

/**
 * Manages the Plaud user access token (JWT) that the SDK needs for the device handshake.
 *
 * Unlike Plaud's template app (build-time USER_ACCESS_TOKEN), this app fetches the token at
 * runtime from the self-hosted transom-server (POST /api/v1/plaud/user-token), caches it,
 * and refreshes it when it is close to expiry or when the SDK reports an auth failure.
 *
 * Expiry is tracked from the server's `expires_in` (persisted as an absolute timestamp);
 * the JWT's own `exp` claim is used only as a consistency check (whichever is sooner wins).
 *
 * The Android SDK facade has no setUserAccessToken (iOS parity gap); the live-refresh path is
 * sdk.NiceBuildSdk.setPartnerToken, which the SDK also uses internally after initSDK.
 */
object TokenManager {

    private const val TAG = "TokenManager"

    /** Refresh when less than this much lifetime remains (tokens live 86400s). */
    private const val EXPIRY_MARGIN_SEC = 30 * 60L

    private val refreshMutex = Mutex()

    /** Effective expiry (epoch seconds): server-provided expiry, tightened by the JWT exp if sooner. */
    private fun effectiveExpirySec(token: String): Long {
        val serverExp = RecordingStore.cachedPlaudTokenExpiry
        val jwtExp = JwtUtils.parse(token)?.expSeconds ?: 0L
        return when {
            serverExp > 0 && jwtExp > 0 -> minOf(serverExp, jwtExp)
            serverExp > 0 -> serverExp
            else -> jwtExp
        }
    }

    /** Cached token if it is not near expiry, else null. */
    fun cachedTokenIfValid(): String? {
        val token = RecordingStore.cachedPlaudToken ?: return null
        val exp = effectiveExpirySec(token)
        if (exp <= 0) return null
        val now = System.currentTimeMillis() / 1000
        return if (exp - now > EXPIRY_MARGIN_SEC) token else null
    }

    /** Best-effort current token for synchronous callers (may be expired; empty if none yet). */
    val currentToken: String
        get() = RecordingStore.cachedPlaudToken ?: ""

    /** Persist a token + its server-declared lifetime (used by onboarding and refresh). */
    fun store(token: ApiClient.UserToken) {
        RecordingStore.cachedPlaudToken = token.accessToken
        RecordingStore.cachedPlaudTokenExpiry =
            if (token.expiresInSec > 0) System.currentTimeMillis() / 1000 + token.expiresInSec
            else 0L
    }

    /**
     * Return a valid token, fetching a fresh one from the bridge server when the cache is
     * missing/near expiry or [force] is set. Blocking network I/O — call from Dispatchers.IO.
     */
    suspend fun getValidToken(force: Boolean = false): String = refreshMutex.withLock {
        if (!force) cachedTokenIfValid()?.let { return it }
        val userId = RecordingStore.getOrCreateUserId()
        val token = ApiClient.fetchUserToken(userId)
        store(token)
        AppLog.i(TAG, "Fetched Plaud user token (expires_in=${token.expiresInSec}s)")
        token.accessToken
    }

    /**
     * Refresh the token and hand it to the (already initialized) SDK. Used when the JWT nears
     * expiry mid-session or the SDK reports an auth failure (e.g. 401 from the partner API).
     * Returns the new token, or null if the fetch OR the SDK apply failed — callers must treat
     * null as "do not proceed with stale credentials".
     */
    suspend fun refreshAndApply(): String? = try {
        val token = getValidToken(force = true)
        sdk.NiceBuildSdk.setPartnerToken(token)
        AppLog.i(TAG, "Applied refreshed token to SDK")
        token
    } catch (e: Exception) {
        AppLog.w(TAG, "Token refresh failed", e)
        null
    }
}
