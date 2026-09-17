package cloud.adamrb.transom.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import cloud.adamrb.transom.net.ApiClient
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.net.UnknownHostException

/** Every user-visible failure is one sentence: the server's own detail when it has one, never a code. */
@RunWith(RobolectricTestRunner::class)
class ServerErrorTextTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun detailIsTakenFromFastApiBodiesAndTurnedIntoASentence() {
        assertEquals("Automations are turned off on the server.", ServerErrorText.detailFrom("""{"detail":"Automations are turned off on the server"}"""))
        assertEquals("Not found.", ServerErrorText.detailFrom("""{"detail":"not found"}"""))
        assertEquals("Title must not be blank!", ServerErrorText.detailFrom("""{"detail":"title must not be blank!"}"""))
    }

    @Test
    fun detailIsIgnoredWhenItIsNotAPlainString() {
        assertNull(ServerErrorText.detailFrom("""{"detail":[{"loc":["body","title"],"msg":"field required"}]}"""))
        assertNull(ServerErrorText.detailFrom("""{"error":"x"}"""))
        assertNull(ServerErrorText.detailFrom("<html>502 Bad Gateway</html>"))
        assertNull(ServerErrorText.detailFrom(""))
        assertNull(ServerErrorText.detailFrom(null))
        assertNull(ServerErrorText.detailFrom("""{"detail":"${"x".repeat(400)}"}"""))
    }

    @Test
    fun resultMessagesBecomeSentences() {
        val unexpected = "Your server answered in an unexpected way. Try again."
        val unreachable = "Couldn't reach your server. Check that it's running and that you're online."
        assertEquals(unexpected, ServerErrorText.fromResultMessage(context, "HTTP 500"))
        assertEquals(unexpected, ServerErrorText.fromResultMessage(context, "HTTP 409"))
        assertEquals("Your server rejected that.", ServerErrorText.fromResultMessage(context, "rejected by the server (422)"))
        assertEquals(unexpected, ServerErrorText.fromResultMessage(context, "fetch vocabulary response is not valid JSON"))
        assertEquals(unreachable, ServerErrorText.fromResultMessage(context, "timeout"))
        assertEquals(unreachable, ServerErrorText.fromResultMessage(context, "Failed to connect to /10.0.0.5:8090"))
        assertEquals(unreachable, ServerErrorText.fromResultMessage(context, null))
    }

    @Test
    fun serverSetupErrorsAreTheThreeOnboardingSentences() {
        assertEquals(
            "That access token was not accepted.",
            ServerErrorText.forServerSetup(context, ApiClient.ApiException(401, "user-token request rejected"))
        )
        assertEquals(
            "That access token was not accepted.",
            ServerErrorText.forServerSetup(context, ApiClient.ApiException(403, "forbidden"))
        )
        assertEquals(
            "Couldn't reach that address. Check the URL and that your server is on.",
            ServerErrorText.forServerSetup(context, UnknownHostException("bridge.example.com"))
        )
        assertEquals(
            "Couldn't reach that address. Check the URL and that your server is on.",
            ServerErrorText.forServerSetup(context, IOException("Cleartext HTTP traffic not permitted"))
        )
        assertEquals(
            "Couldn't connect to your server. Your server answered in an unexpected way. Try again.",
            ServerErrorText.forServerSetup(context, ApiClient.ApiException(500, "no access_token in response"))
        )
        assertEquals(
            "Couldn't connect to your server. Plaud region mismatch.",
            ServerErrorText.forServerSetup(context, ApiClient.ApiException(409, "x"), detail = "Plaud region mismatch.")
        )
    }
}
