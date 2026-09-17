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
    private fun effectiveExpirySec(token: String, serverExp: Long = RecordingStore.cachedPlaudTokenExpiry): Long {
        val jwtExp = JwtUtils.parse(token)?.expSeconds ?: 0L
        return when {
            serverExp > 0 && jwtExp > 0 -> minOf(serverExp, jwtExp)
            serverExp > 0 -> serverExp
            else -> jwtExp
        }
    }

    /** Cached token if it is not near expiry and was minted for the current user id, else null. */
    fun cachedTokenIfValid(): String? {
        // Token, owner and expiry come from one synchronized snapshot (owner already checked).
        val cached = RecordingStore.cachedPlaudTokenSnapshot() ?: return null
        val exp = effectiveExpirySec(cached.token, cached.expirySec)
        if (exp <= 0) return null
        val now = System.currentTimeMillis() / 1000
        return if (exp - now > EXPIRY_MARGIN_SEC) cached.token else null
    }

    /** Best-effort current token for synchronous callers (may be expired; empty if none yet). */
    val currentToken: String
        get() = RecordingStore.cachedPlaudToken ?: ""

    /**
     * Persist a token + its server-declared lifetime (used by onboarding and refresh), tagged
     * with the user id it was minted for so [cachedTokenIfValid] can refuse it after the user
     * adopts a different id.
     */
    fun store(token: ApiClient.UserToken, forUserId: String = RecordingStore.getOrCreateUserId()): Boolean {
        val expiry = if (token.expiresInSec > 0) System.currentTimeMillis() / 1000 + token.expiresInSec else 0L
        // One transaction (token + owner + expiry); refused if the install's id changed meanwhile.
        return RecordingStore.setCachedPlaudToken(token.accessToken, forUserId, expiry)
    }

    /**
     * Return a valid token, fetching a fresh one from the bridge server when the cache is
     * missing/near expiry or [force] is set. Blocking network I/O — call from Dispatchers.IO.
     */
    suspend fun getValidToken(force: Boolean = false): String = refreshMutex.withLock {
        if (!force) cachedTokenIfValid()?.let { return it }
        while (true) {
            val userId = RecordingStore.getOrCreateUserId()
            val token = ApiClient.fetchUserToken(userId)
            // The user may adopt a previous install's id (RecordingStore.adoptUserId) while this
            // request is in flight; the store refuses a token minted for the old id, so fetch
            // again for the new one instead of handing out the stale token.
            if (store(token, forUserId = userId)) {
                AppLog.i(TAG, "Fetched Plaud user token (expires_in=${token.expiresInSec}s)")
                return@withLock token.accessToken
            }
            AppLog.i(TAG, "User id changed during token fetch; fetching again for the new id")
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
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
