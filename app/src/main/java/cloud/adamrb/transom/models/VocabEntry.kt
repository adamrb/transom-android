package cloud.adamrb.transom.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * One custom-vocabulary entry as the bridge server stores it (GET/PUT /api/v1/vocabulary).
 *
 * [term] is the spelling the transcriber should produce; [aliases] are the mis-hearings the
 * server replaces with it after transcription. [source] is "manual" for entries typed by the
 * user and "obsidian" for terms imported from the vault's names gazetteer; the server keeps them
 * apart so a re-import can merge without clobbering hand edits, which is why the app must send
 * each existing term's source back unchanged (see [VocabularyEditorText.parse]).
 *
 * Pure Kotlin plus org.json so the mapping is unit-testable; no android.* imports.
 */
data class VocabEntry(
    val term: String,
    val aliases: List<String> = emptyList(),
    val source: String = SOURCE_MANUAL,
    /**
     * Hotword ranking weight. Null (the editors' case) tells the server to keep whatever weight
     * the term already has; the vault import sets it so gazetteer names outrank the rest.
     */
    val weight: Int? = null
) {
    fun toJson(): JSONObject = JSONObject()
        .put("term", term)
        .put("aliases", JSONArray().apply { aliases.forEach { put(it) } })
        .put("source", source)
        .apply { if (weight != null) put("weight", weight) }

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_OBSIDIAN = "obsidian"

        fun fromJson(o: JSONObject): VocabEntry {
            val aliases = o.optJSONArray("aliases")
            return VocabEntry(
                term = o.optString("term"),
                aliases = if (aliases == null) emptyList()
                else (0 until aliases.length()).map { aliases.optString(it) }.filter { it.isNotEmpty() },
                source = o.optString("source").ifBlank { SOURCE_MANUAL }
            )
        }

        fun listFromJson(arr: JSONArray?): List<VocabEntry> =
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { fromJson(it) } }

        fun listToJson(entries: List<VocabEntry>): JSONArray =
            JSONArray().apply { entries.forEach { put(it.toJson()) } }
    }
}

/**
 * The plain-text editor format shared with the server (`app/vocabulary.py`) and the web
 * dashboard (`parseVocabEditor`): one entry per line, `Term = alias, alias`, `#` comments and
 * blank lines ignored. The app parses exactly like the dashboard so a list edited on the phone
 * round-trips through the dashboard unchanged and vice versa.
 */
object VocabularyEditorText {

    /**
     * Parse editor text into entries. The line is split on the FIRST `=` only, so an alias may
     * itself contain `=`-free commas but a term never can. Terms that already exist on the
     * server keep their [VocabEntry.source] (matched case-insensitively, the server's own key);
     * everything else is "manual". Without that, saving from the phone would relabel every
     * imported term as hand-typed and the next vault import could not tell them apart.
     */
    fun parse(text: String, existing: List<VocabEntry> = emptyList()): List<VocabEntry> {
        val sourceByTerm = existing.associate { it.term.lowercase() to it.source }
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val eq = line.indexOf('=')
                val term = (if (eq < 0) line else line.substring(0, eq)).trim()
                if (term.isEmpty()) return@mapNotNull null
                val aliases = if (eq < 0) emptyList()
                else line.substring(eq + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }
                VocabEntry(term, aliases, sourceByTerm[term.lowercase()] ?: VocabEntry.SOURCE_MANUAL)
            }
    }

    /** Server `to_editor_text`: sorted case-insensitively by term, aliases after " = ". */
    fun format(entries: List<VocabEntry>): String =
        entries.sortedBy { it.term.lowercase() }.joinToString("\n") { e ->
            if (e.aliases.isEmpty()) e.term else "${e.term} = ${e.aliases.joinToString(", ")}"
        }
}
