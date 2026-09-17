package cloud.adamrb.transom.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * QR onboarding payload: {"v":1,"url":"https://...","token":"..."} — strict version and type
 * checks. Robolectric supplies the real org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
class QrSetupPayloadTest {

    private fun success(raw: String): QrSetupPayload {
        val result = QrSetupPayload.parse(raw)
        assertTrue("expected Success, got $result", result is QrSetupPayload.Result.Success)
        return (result as QrSetupPayload.Result.Success).payload
    }

    private fun failure(raw: String?): QrSetupPayload.Error {
        val result = QrSetupPayload.parse(raw)
        assertTrue("expected Failure, got $result", result is QrSetupPayload.Result.Failure)
        return (result as QrSetupPayload.Result.Failure).error
    }

    // MARK: - Valid payloads

    @Test
    fun validPayloadParses() {
        val p = success("""{"v":1,"url":"https://bridge.example.com","token":"secret-123"}""")
        assertEquals("https://bridge.example.com", p.url)
        assertEquals("secret-123", p.token)
    }

    @Test
    fun trailingSlashIsTrimmedFromUrl() {
        val p = success("""{"v":1,"url":"https://bridge.example.com/","token":"t"}""")
        assertEquals("https://bridge.example.com", p.url)
    }

    @Test
    fun extraFieldsAreIgnored() {
        val p = success("""{"v":1,"url":"https://s.example","token":"t","name":"My server"}""")
        assertEquals("https://s.example", p.url)
    }

    @Test
    fun urlWithPortAndPathIsAccepted() {
        val p = success("""{"v":1,"url":"https://h.example:8443/bridge","token":"t"}""")
        assertEquals("https://h.example:8443/bridge", p.url)
        assertEquals("h.example", p.host)
        assertEquals(8443, p.port)
    }

    @Test
    fun defaultPortIsCanonicalizedAndExposed() {
        val implicit = success("""{"v":1,"url":"https://h.example","token":"t"}""")
        assertEquals(443, implicit.port)
        assertEquals("https://h.example", implicit.url)
        // Explicit :443 canonicalizes to the same origin (port dropped from the URL)
        val explicit = success("""{"v":1,"url":"https://h.example:443","token":"t"}""")
        assertEquals("https://h.example", explicit.url)
        assertEquals(443, explicit.port)
    }

    @Test
    fun hostIsLowercasedInCanonicalForm() {
        val p = success("""{"v":1,"url":"https://BRIDGE.Example.COM","token":"t"}""")
        assertEquals("bridge.example.com", p.host)
        assertEquals("https://bridge.example.com", p.url)
    }

    @Test
    fun unicodeHomographHostIsShownAsPunycode() {
        // Cyrillic "е" (U+0435) in "bridgе" — must surface as its ASCII punycode form so the
        // confirmation dialog exposes the lookalike instead of rendering it invisibly.
        val p = success("""{"v":1,"url":"https://bridgе.example.com","token":"t"}""")
        assertTrue("expected punycode host, got ${p.host}", p.host.startsWith("xn--"))
        assertFalse(p.host.contains('е'))
        assertTrue(p.url.contains(p.host))
    }

    // MARK: - Wrong version

    @Test
    fun wrongVersionIsRejected() {
        assertEquals(
            QrSetupPayload.Error.UNSUPPORTED_VERSION,
            failure("""{"v":2,"url":"https://s.example","token":"t"}""")
        )
    }

    @Test
    fun stringVersionIsRejected() {
        // "1" (a JSON string) is not the integer 1 — strict types
        assertEquals(
            QrSetupPayload.Error.MISSING_FIELD,
            failure("""{"v":"1","url":"https://s.example","token":"t"}""")
        )
    }

    @Test
    fun missingVersionIsRejected() {
        assertEquals(
            QrSetupPayload.Error.MISSING_FIELD,
            failure("""{"url":"https://s.example","token":"t"}""")
        )
    }

    // MARK: - Missing / mistyped fields

    @Test
    fun missingUrlIsRejected() {
        assertEquals(QrSetupPayload.Error.MISSING_FIELD, failure("""{"v":1,"token":"t"}"""))
    }

    @Test
    fun missingTokenIsRejected() {
        assertEquals(
            QrSetupPayload.Error.MISSING_FIELD,
            failure("""{"v":1,"url":"https://s.example"}""")
        )
    }

    @Test
    fun blankTokenIsRejected() {
        assertEquals(
            QrSetupPayload.Error.MISSING_FIELD,
            failure("""{"v":1,"url":"https://s.example","token":"   "}""")
        )
    }

    @Test
    fun numericUrlIsRejected() {
        // JSONObject.optString would coerce 42 to "42"; the parser must not
        assertEquals(
            QrSetupPayload.Error.MISSING_FIELD,
            failure("""{"v":1,"url":42,"token":"t"}""")
        )
    }

    // MARK: - Not JSON

    @Test
    fun nonJsonIsRejected() {
        assertEquals(QrSetupPayload.Error.NOT_JSON, failure("https://a-random-url.example"))
    }

    @Test
    fun emptyAndNullAreRejected() {
        assertEquals(QrSetupPayload.Error.NOT_JSON, failure(""))
        assertEquals(QrSetupPayload.Error.NOT_JSON, failure(null))
    }

    @Test
    fun jsonArrayIsRejected() {
        assertEquals(QrSetupPayload.Error.NOT_JSON, failure("""[1,2,3]"""))
    }

    // MARK: - URL scheme

    @Test
    fun httpUrlIsRejected() {
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"http://s.example","token":"t"}""")
        )
    }

    @Test
    fun userinfoTrickIsRejected() {
        // The real host here is evil.com — prefix matching would have been fooled
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"https://good.com@evil.com/","token":"t"}""")
        )
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"https://user:pass@evil.com/","token":"t"}""")
        )
    }

    @Test
    fun fragmentAndQueryAreRejected() {
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"https://s.example/#frag","token":"t"}""")
        )
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"https://s.example/?q=1","token":"t"}""")
        )
    }

    @Test
    fun malformedAuthorityIsRejected() {
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"https://","token":"t"}""")
        )
        assertEquals(
            QrSetupPayload.Error.BAD_URL,
            failure("""{"v":1,"url":"https://h .example","token":"t"}""")
        )
    }
}
