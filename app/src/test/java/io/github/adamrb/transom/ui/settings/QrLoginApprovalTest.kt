package io.github.adamrb.transom.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The origin rule for sign-in codes: a code is approved only when its (scheme, host, effective
 * port) equals the configured server's; everything else is refused before any request is made.
 */
@RunWith(RobolectricTestRunner::class)
class QrLoginApprovalTest {

    private val id = "abcDEF123456_-abcDEF123456_-0123"

    private fun code(url: String) = """{"v":1,"kind":"login","url":"$url","id":"$id"}"""

    private fun approve(server: String?, url: String): QrLoginApproval.Decision.Approve {
        val d = QrLoginApproval.decide(server, code(url))
        assertTrue("expected Approve, got $d", d is QrLoginApproval.Decision.Approve)
        return d as QrLoginApproval.Decision.Approve
    }

    private fun wrongServer(server: String?, url: String): QrLoginApproval.Decision.WrongServer {
        val d = QrLoginApproval.decide(server, code(url))
        assertTrue("expected WrongServer, got $d", d is QrLoginApproval.Decision.WrongServer)
        return d as QrLoginApproval.Decision.WrongServer
    }

    // MARK: - Same origin

    @Test
    fun sameOriginIsApprovedWithTheRequestId() {
        val d = approve("https://bridge.example.com", "https://bridge.example.com")
        assertEquals(id, d.payload.id)
        assertEquals("bridge.example.com", d.payload.host)
    }

    @Test
    fun explicitDefaultPortEqualsImplicit() {
        approve("https://bridge.example.com", "https://bridge.example.com:443/")
        approve("https://bridge.example.com:443", "https://bridge.example.com")
    }

    @Test
    fun hostCaseAndTrailingSlashDoNotMatter() {
        approve("https://bridge.example.com", "https://BRIDGE.Example.com/")
        approve("https://bridge.example.com/", "https://bridge.example.com")
    }

    @Test
    fun configuredNonDefaultPortMatchesItself() {
        approve("https://h.example:8443", "https://h.example:8443")
    }

    @Test
    fun pathDifferencesAreStillTheSameOrigin() {
        // The server may be configured under a sub-path; the origin is what matters.
        approve("https://h.example/bridge", "https://h.example")
    }

    // MARK: - Different origin

    @Test
    fun differentHostIsRefusedWithBothSidesNamed() {
        val d = wrongServer("https://bridge.example.com", "https://evil.example.com")
        assertEquals("evil.example.com:443", d.codeHostPort)
        assertEquals("bridge.example.com:443", d.serverHostPort)
    }

    @Test
    fun subdomainAndSuffixLookalikesAreRefused() {
        wrongServer("https://bridge.example.com", "https://api.bridge.example.com")
        wrongServer("https://bridge.example.com", "https://bridge.example.com.evil.net")
    }

    @Test
    fun differentPortIsRefused() {
        val d = wrongServer("https://bridge.example.com", "https://bridge.example.com:8443")
        assertEquals("bridge.example.com:8443", d.codeHostPort)
        wrongServer("https://h.example:8443", "https://h.example")
        wrongServer("https://h.example:8443", "https://h.example:9443")
    }

    @Test
    fun homographHostIsRefusedAndShownAsPunycode() {
        val d = wrongServer("https://bridge.example.com", "https://bridgе.example.com") // Cyrillic е
        assertTrue(d.codeHostPort, d.codeHostPort.startsWith("xn--"))
    }

    // MARK: - Not approvable at all

    @Test
    fun noConfiguredServerIsNotConfigured() {
        assertEquals(QrLoginApproval.Decision.NotConfigured, QrLoginApproval.decide(null, code("https://h.example")))
        assertEquals(QrLoginApproval.Decision.NotConfigured, QrLoginApproval.decide("", code("https://h.example")))
        assertEquals(QrLoginApproval.Decision.NotConfigured, QrLoginApproval.decide("nonsense", code("https://h.example")))
    }

    @Test
    fun invalidCodeIsInvalidBeforeAnyOriginCheck() {
        val d = QrLoginApproval.decide("https://h.example", "hello")
        assertTrue(d is QrLoginApproval.Decision.Invalid)
        assertEquals(false, (d as QrLoginApproval.Decision.Invalid).isSetupCode)
    }

    @Test
    fun setupCodeIsFlaggedSoTheMessageCanSaySo() {
        val d = QrLoginApproval.decide("https://h.example", """{"v":1,"url":"https://h.example","token":"t"}""")
        assertTrue(d is QrLoginApproval.Decision.Invalid)
        assertEquals(true, (d as QrLoginApproval.Decision.Invalid).isSetupCode)
    }

    @Test
    fun hostPortMakesTheDefaultPortExplicit() {
        assertEquals("bridge.example.com:443", QrLoginApproval.hostPort("https://bridge.example.com"))
        assertEquals("h.example:8443", QrLoginApproval.hostPort("https://h.example:8443/base"))
        assertEquals("127.0.0.1:80", QrLoginApproval.hostPort("http://127.0.0.1"))
        assertEquals(null, QrLoginApproval.hostPort(null))
        assertEquals(null, QrLoginApproval.hostPort("nonsense"))
    }
}
