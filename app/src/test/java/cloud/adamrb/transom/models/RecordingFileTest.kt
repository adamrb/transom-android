package cloud.adamrb.transom.models

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecordingFile: the display-name rule (manual rename > server AI title > stored name) and Gson
 * backward compatibility for the two fields added for server titles. Legacy recordings.json
 * entries predate both fields, so a missing or null value must decode to "no title, not edited".
 */
class RecordingFileTest {

    private val gson = Gson()

    private fun file(
        name: String = "Untitled Recording",
        serverTitle: String? = null,
        edited: Boolean = false
    ) = RecordingFile(
        sessionId = 1L, deviceSN = "SN-A", name = name, duration = 10, createdAt = 1000L,
        serverTitle = serverTitle, nameEditedByUser = edited
    )

    // MARK: - displayName precedence

    @Test
    fun displayNameFallsBackToNameWithoutServerTitle() {
        assertEquals("Untitled Recording", file().displayName)
    }

    @Test
    fun displayNamePrefersServerTitleOverDefaultName() {
        assertEquals("Budget planning call", file(serverTitle = "Budget planning call").displayName)
    }

    @Test
    fun displayNameIgnoresBlankServerTitle() {
        assertEquals("Untitled Recording", file(serverTitle = "   ").displayName)
        assertEquals("Untitled Recording", file(serverTitle = "").displayName)
    }

    @Test
    fun displayNameTrimsServerTitle() {
        assertEquals("Standup", file(serverTitle = "  Standup \n").displayName)
    }

    @Test
    fun manualRenameBeatsServerTitle() {
        val f = file(name = "My meeting", serverTitle = "Budget planning call", edited = true)
        assertEquals("My meeting", f.displayName)
    }

    // MARK: - JSON backward compatibility

    private val legacyJson = """
        [{"id":"abc","sessionId":42,"deviceSN":"SN-A","name":"Untitled Recording","duration":7,
          "createdAt":1700000000000,"uploaded":true,"serverId":"srv-1"}]
    """.trimIndent()

    private fun parse(json: String): List<RecordingFile> {
        val type = object : TypeToken<List<RecordingFile>>() {}.type
        return gson.fromJson(json, type)
    }

    @Test
    fun legacyJsonWithoutNewFieldsParsesWithDefaults() {
        val f = parse(legacyJson).single()
        assertEquals("abc", f.id)
        assertEquals("srv-1", f.serverId)
        assertNull(f.serverTitle)
        assertFalse(f.nameEditedByUser)
        assertEquals("Untitled Recording", f.displayName)
    }

    @Test
    fun explicitNullsForNewFieldsParseAsDefaults() {
        // Gson skips null values for primitive fields and assigns null to the nullable one.
        val json = """[{"id":"abc","sessionId":1,"deviceSN":"SN-A","name":"n","duration":1,
            "createdAt":1,"serverTitle":null,"nameEditedByUser":null}]"""
        val f = parse(json).single()
        assertNull(f.serverTitle)
        assertFalse(f.nameEditedByUser)
    }

    @Test
    fun legacyJsonWithoutMarksFieldsReadsAsNotReadAndNotSynced() {
        val f = parse(legacyJson).single()
        // null (not read yet) is the state that makes MarksSyncManager ask the device; an empty
        // list would wrongly mean "read, no button presses".
        assertNull(f.marks)
        assertFalse(f.marksSynced)
    }

    @Test
    fun marksRoundTripThroughGson() {
        val original = file().apply { marks = listOf(6.0, 125.5); marksSynced = true }
        val json = gson.toJson(listOf(original))
        assertTrue(json.contains("\"marks\":[6.0,125.5]"))
        assertTrue(json.contains("\"marksSynced\":true"))
        val back = parse(json).single()
        assertEquals(listOf(6.0, 125.5), back.marks)
        assertTrue(back.marksSynced)

        // An empty list survives as empty (distinct from null).
        val none = file().apply { marks = emptyList() }
        assertEquals(emptyList<Double>(), parse(gson.toJson(listOf(none))).single().marks)
    }

    @Test
    fun newFieldsRoundTripThroughGson() {
        val original = file(name = "Renamed", serverTitle = "AI title", edited = true)
        val json = gson.toJson(listOf(original))
        assertTrue(json.contains("\"serverTitle\":\"AI title\""))
        assertTrue(json.contains("\"nameEditedByUser\":true"))
        // displayName is computed, never persisted.
        assertFalse(json.contains("displayName"))

        val back = parse(json).single()
        assertEquals("AI title", back.serverTitle)
        assertTrue(back.nameEditedByUser)
        assertEquals("Renamed", back.displayName)
    }
}
