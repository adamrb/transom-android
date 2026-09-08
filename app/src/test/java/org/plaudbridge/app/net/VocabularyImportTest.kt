package org.plaudbridge.app.net

import androidx.test.core.app.ApplicationProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.plaudbridge.app.models.VocabEntry
import org.plaudbridge.app.storage.RecordingStore
import org.robolectric.RobolectricTestRunner

/**
 * The vault import: request shape of POST /api/v1/vocabulary/import (the body the server's own
 * contrib script sends) and result mapping in ApiClient.importVocabulary, and the gazetteer
 * parser ported from that script.
 */
@RunWith(RobolectricTestRunner::class)
class VocabularyImportTest {

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

    private val entries = listOf(
        VocabEntry("Dana Whitlock", listOf("Dana Whitlok"), "obsidian", 10_000),
        VocabEntry("Morgan", emptyList(), "obsidian", 10_000)
    )

    @Test
    fun postsEntriesToTheImportEndpointWithBearerAuth() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"entries":[{"term":"Dana Whitlock","aliases":["Dana Whitlok"],"source":"obsidian"},
                    {"term":"Morgan","aliases":[],"source":"obsidian"}],"added":2}"""
            )
        )
        val result = ApiClient.importVocabulary(entries)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/v1/vocabulary/import", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
        assertTrue(recorded.getHeader("Content-Type")!!.startsWith("application/json"))
        val body = JSONObject(recorded.body.readUtf8())
        val sent = body.getJSONArray("entries")
        assertEquals(2, sent.length())
        assertEquals("Dana Whitlock", sent.getJSONObject(0).getString("term"))
        assertEquals("Dana Whitlok", sent.getJSONObject(0).getJSONArray("aliases").getString(0))
        assertEquals("obsidian", sent.getJSONObject(0).getString("source"))
        assertEquals(10_000, sent.getJSONObject(0).getInt("weight"))

        val ok = result as ApiClient.VocabularyImportResult.Ok
        assertEquals(2, ok.added)
        assertEquals(listOf("Dana Whitlock", "Morgan"), ok.entries.map { it.term })
    }

    @Test
    fun editorEntriesWithoutAWeightDoNotSendOne() {
        val json = VocabEntry("Plaud", listOf("plot")).toJson()
        assertFalse(json.has("weight"))
    }

    @Test
    fun mapsServerAnswersToTypedResults() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(ApiClient.VocabularyImportResult.Unsupported, ApiClient.importVocabulary(entries))

        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiClient.VocabularyImportResult.AuthError(401), ApiClient.importVocabulary(entries))

        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"too many entries"}"""))
        val rejected = ApiClient.importVocabulary(entries) as ApiClient.VocabularyImportResult.Error
        assertEquals("Too many entries.", rejected.detail)

        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))
        val failed = ApiClient.importVocabulary(entries) as ApiClient.VocabularyImportResult.Error
        assertEquals("HTTP 500", failed.message)
        assertEquals(null, failed.detail)
    }

    @Test
    fun connectionFailureIsAnErrorNotAnException() {
        server.shutdown()
        assertTrue(ApiClient.importVocabulary(entries) is ApiClient.VocabularyImportResult.Error)
    }

    // MARK: - Gazetteer parsing

    @Test
    fun parsesCanonicalNamesAndPlausibleMisHearingsOnly() {
        val parsed = VocabularyImport.parseGazetteer(
            """
            # Names gazetteer
            One line per name: Canonical | type | alias, alias | notes
            Dana Whitlock | person | Dana Whitlok, Dana, Mr Whitlock | coworker
            Morgan | person | Morgen, Morgin |
            the cleaner | role | cleaner
            Alex | person | alix
            Plaud Bridge | project | Plogged Bridge, Plod Bridge, Bridge
            not a gazetteer line
            Dana Whitlock | duplicate | Dana Whitloks
            """.trimIndent()
        )
        assertEquals(listOf("Dana Whitlock", "Morgan", "Alex", "Plaud Bridge"), parsed.map { it.term })
        // "Dana" alone is a prefix, "Mr Whitlock" starts with a generic word: neither is a
        // mis-hearing. The repeated Dana Whitlock line contributed its extra alias.
        assertEquals(listOf("Dana Whitlok", "Dana Whitloks"), parsed[0].aliases)
        assertEquals(listOf("Morgen", "Morgin"), parsed[1].aliases)
        // A lowercase alias is never a correction (it could be an ordinary word).
        assertEquals(emptyList<String>(), parsed[2].aliases)
        // "Plogged" is too far from "Plaud" (edit distance 4) and "Bridge" alone is one token of a
        // two-token name: the gazetteer only carries close mis-hearings, the editor takes the rest.
        assertEquals(listOf("Plod Bridge"), parsed[3].aliases)
        assertTrue(parsed.all { it.source == VocabEntry.SOURCE_OBSIDIAN && it.weight == VocabularyImport.GAZETTEER_WEIGHT })
    }

    @Test
    fun singleWordAliasOfAMultiWordNameBecomesItsOwnCorrectionEntry() {
        val parsed = VocabularyImport.parseGazetteer(
            """
            Morgan Ashford | person | Morgen, Morgan Ashfurd
            Dana Whitlock | person | Whitlok
            """.trimIndent()
        )
        assertEquals(listOf("Morgan Ashford", "Dana Whitlock", "Morgan", "Whitlock"), parsed.map { it.term })
        // The multi-word mis-hearing stays on the full name...
        assertEquals(listOf("Morgan Ashfurd"), parsed[0].aliases)
        assertEquals(emptyList<String>(), parsed[1].aliases)
        // ...while "Morgen" corrects to "Morgan" only, never expanding to the full name, as a
        // correction-only entry (weight 0) that keeps the gazetteer source.
        val morgan = parsed[2]
        assertEquals(listOf("Morgen"), morgan.aliases)
        assertEquals(0, morgan.weight)
        assertEquals(VocabEntry.SOURCE_OBSIDIAN, morgan.source)
        assertEquals(listOf("Whitlok"), parsed[3].aliases)
    }

    @Test
    fun linesWithoutThreeColumnsOrANameAreSkipped() {
        assertTrue(VocabularyImport.parseGazetteer("Morgan | person").isEmpty())
        assertTrue(VocabularyImport.parseGazetteer("| person | x").isEmpty())
        assertTrue(VocabularyImport.parseGazetteer("lowercase | person | x").isEmpty())
        assertTrue(VocabularyImport.parseGazetteer("").isEmpty())
    }

    @Test
    fun misspellingRuleMatchesTheServerScript() {
        assertTrue(VocabularyImport.isMisspelling("Dana Whitlok", "Dana Whitlock"))
        assertFalse("identical", VocabularyImport.isMisspelling("Dana Whitlock", "Dana Whitlock"))
        assertFalse("bare first name is a prefix", VocabularyImport.isMisspelling("Dana", "Dana Whitlock"))
        assertFalse("too far", VocabularyImport.isMisspelling("Bob", "Dana Whitlock"))
        assertTrue("single token close variant", VocabularyImport.isMisspelling("Morgen", "Morgan"))
        assertTrue(VocabularyImport.looksLikeName("Plaud Bridge"))
        assertFalse(VocabularyImport.looksLikeName("the cleaner"))
        assertFalse(VocabularyImport.looksLikeName("Dr Who"))
        assertFalse(VocabularyImport.looksLikeName("A"))
    }
}
