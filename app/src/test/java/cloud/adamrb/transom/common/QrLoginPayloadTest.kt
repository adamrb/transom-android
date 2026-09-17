package cloud.adamrb.transom.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Web sign-in QR payload: {"v":1,"kind":"login","url":"https://...","id":"..."} with strict
 * version, kind, url and id checks. Robolectric supplies the real org.json implementation.
 */
@RunWith(RobolectricTestRunner::class)
class QrLoginPayloadTest {

    private val id = "abcDEF123456_-abcDEF123456_-0123" // 32 url-safe chars, like the server issues

    private fun success(raw: String): QrLoginPayload {
        val result = QrLoginPayload.parse(raw)
        assertTrue("expected Success, got $result", result is QrLoginPayload.Result.Success)
        return (result as QrLoginPayload.Result.Success).payload
    }

    private fun failure(raw: String?): QrLoginPayload.Error {
        val result = QrLoginPayload.parse(raw)
        assertTrue("expected Failure, got $result", result is QrLoginPayload.Result.Failure)
        return (result as QrLoginPayload.Result.Failure).error
    }

    // MARK: - Valid payloads

    @Test
    fun validPayloadParses() {
        val p = success("""{"v":1,"kind":"login","url":"https://bridge.example.com","id":"$id"}""")
        assertEquals("https://bridge.example.com", p.url)
        assertEquals("bridge.example.com", p.host)
        assertEquals(443, p.port)
        assertEquals(id, p.id)
    }

    @Test
    fun urlIsCanonicalized() {
        val p = success("""{"v":1,"kind":"login","url":"https://BRIDGE.Example.COM:443/","id":"$id"}""")
        assertEquals("https://bridge.example.com", p.url)
        assertEquals("bridge.example.com", p.host)
        assertEquals(443, p.port)
    }

    @Test
    fun nonDefaultPortAndPathAreKept() {
        val p = success("""{"v":1,"kind":"login","url":"https://h.example:8443/bridge","id":"$id"}""")
        assertEquals("https://h.example:8443/bridge", p.url)
        assertEquals(8443, p.port)
    }

    @Test
    fun idBoundsAreInclusive() {
        assertEquals("a".repeat(16), success("""{"v":1,"kind":"login","url":"https://h.example","id":"${"a".repeat(16)}"}""").id)
        assertEquals("a".repeat(64), success("""{"v":1,"kind":"login","url":"https://h.example","id":"${"a".repeat(64)}"}""").id)
    }

    @Test
    fun extraFieldsAreIgnored() {
        val p = success("""{"v":1,"kind":"login","url":"https://h.example","id":"$id","expires_in":180}""")
        assertEquals(id, p.id)
    }

    @Test
    fun unicodeHomographHostIsShownAsPunycode() {
        // Cyrillic "е" (U+0435) in "bridgе": the dialog must show the ASCII form.
        val p = success("""{"v":1,"kind":"login","url":"https://bridgе.example.com","id":"$id"}""")
        assertTrue("expected punycode host, got ${p.host}", p.host.startsWith("xn--"))
        assertFalse(p.host.contains('е'))
    }

    // MARK: - Kind

    @Test
    fun setupCodeIsWrongKind() {
        // An onboarding QR has no kind; it must not be mistaken for a sign-in code.
        assertEquals(
            QrLoginPayload.Error.WRONG_KIND,
            failure("""{"v":1,"url":"https://h.example","token":"secret"}""")
        )
    }

    @Test
    fun otherKindsAreRejected() {
        assertEquals(QrLoginPayload.Error.WRONG_KIND, failure("""{"v":1,"kind":"setup","url":"https://h.example","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.WRONG_KIND, failure("""{"v":1,"kind":"Login","url":"https://h.example","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.WRONG_KIND, failure("""{"v":1,"kind":1,"url":"https://h.example","id":"$id"}"""))
    }

    @Test
    fun isLoginKindOnlyLooksAtTheKind() {
        assertTrue(QrLoginPayload.isLoginKind("""{"v":1,"kind":"login","url":"http://h.example","id":"bad id"}"""))
        assertFalse(QrLoginPayload.isLoginKind("""{"v":1,"url":"https://h.example","token":"t"}"""))
        assertFalse(QrLoginPayload.isLoginKind("not json"))
        assertFalse(QrLoginPayload.isLoginKind(null))
    }

    // MARK: - Version

    @Test
    fun wrongVersionIsRejected() {
        assertEquals(QrLoginPayload.Error.UNSUPPORTED_VERSION, failure("""{"v":2,"kind":"login","url":"https://h.example","id":"$id"}"""))
    }

    @Test
    fun stringOrMissingVersionIsRejected() {
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"v":"1","kind":"login","url":"https://h.example","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"kind":"login","url":"https://h.example","id":"$id"}"""))
    }

    // MARK: - Url

    @Test
    fun httpUrlIsRejected() {
        assertEquals(QrLoginPayload.Error.BAD_URL, failure("""{"v":1,"kind":"login","url":"http://h.example","id":"$id"}"""))
    }

    @Test
    fun userinfoInUrlIsRejected() {
        // The real host here is evil.com
        assertEquals(QrLoginPayload.Error.BAD_URL, failure("""{"v":1,"kind":"login","url":"https://good.com@evil.com/","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.BAD_URL, failure("""{"v":1,"kind":"login","url":"https://user:pass@evil.com/","id":"$id"}"""))
    }

    @Test
    fun queryFragmentAndGarbageUrlsAreRejected() {
        assertEquals(QrLoginPayload.Error.BAD_URL, failure("""{"v":1,"kind":"login","url":"https://h.example/?x=1","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.BAD_URL, failure("""{"v":1,"kind":"login","url":"https://h.example/#f","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.BAD_URL, failure("""{"v":1,"kind":"login","url":"https://","id":"$id"}"""))
    }

    @Test
    fun missingOrNumericUrlIsRejected() {
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"v":1,"kind":"login","id":"$id"}"""))
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"v":1,"kind":"login","url":42,"id":"$id"}"""))
    }

    // MARK: - Id

    @Test
    fun missingOrBlankIdIsRejected() {
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"v":1,"kind":"login","url":"https://h.example"}"""))
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"v":1,"kind":"login","url":"https://h.example","id":"  "}"""))
        assertEquals(QrLoginPayload.Error.MISSING_FIELD, failure("""{"v":1,"kind":"login","url":"https://h.example","id":12345678901234567}"""))
    }

    @Test
    fun idWithForbiddenCharactersIsRejected() {
        // Anything that could change the request path or smuggle a query must fail.
        for (bad in listOf("abc/def1234567890123", "abcdefghijklmnop?x=1", "abcdefghijklmnop.json", "abcdefgh ijklmnopq", "abcdefghijklmno%2F", "abcdefghijklmnopé")) {
            assertEquals(bad, QrLoginPayload.Error.BAD_ID, failure("""{"v":1,"kind":"login","url":"https://h.example","id":"$bad"}"""))
        }
    }

    @Test
    fun idLengthOutOfBoundsIsRejected() {
        assertEquals(QrLoginPayload.Error.BAD_ID, failure("""{"v":1,"kind":"login","url":"https://h.example","id":"${"a".repeat(15)}"}"""))
        assertEquals(QrLoginPayload.Error.BAD_ID, failure("""{"v":1,"kind":"login","url":"https://h.example","id":"${"a".repeat(65)}"}"""))
    }

    // MARK: - Not JSON

    @Test
    fun nonJsonEmptyAndNullAreRejected() {
        assertEquals(QrLoginPayload.Error.NOT_JSON, failure("https://h.example/login?id=$id"))
        assertEquals(QrLoginPayload.Error.NOT_JSON, failure(""))
        assertEquals(QrLoginPayload.Error.NOT_JSON, failure(null))
        assertEquals(QrLoginPayload.Error.NOT_JSON, failure("[1,2]"))
    }
}
