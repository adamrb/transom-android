package io.github.adamrb.transom.net

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import io.github.adamrb.transom.storage.RecordingStore
import org.robolectric.RobolectricTestRunner

/**
 * TokenManager: absolute expiry persisted from the server's expires_in (finding 14),
 * refresh-when-near-expiry, and failure propagation.
 */
@RunWith(RobolectricTestRunner::class)
class TokenManagerTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        RecordingStore.init(ApplicationProvider.getApplicationContext())
        RecordingStore.clearAll()
        server = MockWebServer()
        server.start()
        RecordingStore.serverBaseUrl = server.url("/").toString().trimEnd('/')
        RecordingStore.serverAuthToken = "test-token"
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun storePersistsTokenAndAbsoluteExpiry() {
        val before = System.currentTimeMillis() / 1000
        TokenManager.store(ApiClient.UserToken("tok-abc", 3600L))
        val after = System.currentTimeMillis() / 1000

        assertEquals("tok-abc", RecordingStore.cachedPlaudToken)
        val expiry = RecordingStore.cachedPlaudTokenExpiry
        assertTrue("expiry should be ~now+3600", expiry in (before + 3600)..(after + 3600))
    }

    @Test
    fun storeWithoutExpiresInLeavesExpiryUnknown() {
        TokenManager.store(ApiClient.UserToken("tok-abc", 0L))
        assertEquals(0L, RecordingStore.cachedPlaudTokenExpiry)
    }

    @Test
    fun cachedTokenValidWhenFarFromExpiry() {
        TokenManager.store(ApiClient.UserToken("tok-abc", 7200L)) // margin is 1800s
        assertEquals("tok-abc", TokenManager.cachedTokenIfValid())
    }

    @Test
    fun cachedTokenMintedForAnotherUserIdIsNotHandedOut() {
        val original = RecordingStore.getOrCreateUserId()
        TokenManager.store(ApiClient.UserToken("tok-old-id", 7200L), forUserId = original)
        assertEquals("tok-old-id", TokenManager.cachedTokenIfValid())

        // A fetch that started before the user adopted a previous install's id arrives late: the
        // store refuses it, and the token cached for the old id is not handed out either.
        assertTrue(RecordingStore.adoptUserId("pb_previous-phone-id"))
        assertFalse(TokenManager.store(ApiClient.UserToken("tok-late", 7200L), forUserId = original))
        assertNull(RecordingStore.cachedPlaudToken)
        assertNull(TokenManager.cachedTokenIfValid())
        // ...while a token minted for the adopted id is accepted.
        assertTrue(TokenManager.store(ApiClient.UserToken("tok-new", 7200L), forUserId = "pb_previous-phone-id"))
        assertEquals("tok-new", TokenManager.cachedTokenIfValid())

        // Tokens cached by builds without the tag (null) are still trusted.
        RecordingStore.cachedPlaudToken = "tok-untagged"
        RecordingStore.cachedPlaudTokenUserId = null
        RecordingStore.cachedPlaudTokenExpiry = System.currentTimeMillis() / 1000 + 7200
        assertEquals("tok-untagged", TokenManager.cachedTokenIfValid())
    }

    @Test
    fun cachedTokenNullWhenNearExpiry() {
        TokenManager.store(ApiClient.UserToken("tok-abc", 60L)) // < 1800s margin
        assertNull(TokenManager.cachedTokenIfValid())
    }

    @Test
    fun cachedOpaqueTokenWithoutAnyExpiryInfoIsNotTrusted() {
        // Opaque (non-JWT) token and no server expiry: cannot judge freshness -> treat as invalid.
        RecordingStore.cachedPlaudToken = "opaque-token"
        RecordingStore.cachedPlaudTokenExpiry = 0L
        assertNull(TokenManager.cachedTokenIfValid())
    }

    @Test
    fun getValidTokenFetchesOnceAndThenServesCache() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"fresh","expires_in":86400}""")
        )
        assertEquals("fresh", TokenManager.getValidToken())
        assertEquals(1, server.requestCount)

        // Second call: cache is fresh, must not hit the server again.
        assertEquals("fresh", TokenManager.getValidToken())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun getValidTokenRefreshesWhenCachedTokenIsNearExpiry() = runBlocking {
        TokenManager.store(ApiClient.UserToken("stale", 60L))
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"access_token":"renewed","expires_in":86400}""")
        )
        assertEquals("renewed", TokenManager.getValidToken())
        assertEquals(1, server.requestCount)
        assertEquals("renewed", RecordingStore.cachedPlaudToken)
    }

    @Test
    fun getValidTokenPropagatesServerFailure() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertThrows(ApiClient.ApiException::class.java) {
            runBlocking { TokenManager.getValidToken(force = true) }
        }
        // The stale cache must not have been overwritten by the failed fetch.
        assertNull(RecordingStore.cachedPlaudToken)
    }

    @Test
    fun refreshAndApplyReturnsNullWhenFetchFails() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        assertNull(TokenManager.refreshAndApply())
    }
}
