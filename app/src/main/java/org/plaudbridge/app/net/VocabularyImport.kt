package org.plaudbridge.app.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.plaudbridge.app.common.AppLog
import org.plaudbridge.app.common.ServerErrorText
import org.plaudbridge.app.models.VocabEntry
import java.util.concurrent.TimeUnit

/**
 * "Import from your vault" for the custom vocabulary: parse the Obsidian names gazetteer the
 * phone's vault copy holds (`Life/_names.md`) and MERGE it into the server's list with
 * `POST /api/v1/vocabulary/import {"entries": [...]}` (the endpoint the server's own
 * `contrib/vocab_from_obsidian.py` uses). A merge never removes anything: existing terms and
 * aliases stay, new ones are added, and the response is the merged list plus how many were new.
 *
 * Kept out of [ApiClient] only so the vocabulary screen's work does not collide with the file
 * detail work happening there; the request/response handling mirrors ApiClient's style.
 */
object VocabularyImport {

    private const val TAG = "VocabularyImport"

    /** Weight the vault script gives gazetteer names so they outrank other imported hotwords. */
    const val GAZETTEER_WEIGHT = 10_000

    /** Largest file the picker is allowed to hand us; the gazetteer is a few hundred lines. */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    private val jsonType = "application/json".toMediaType()

    /** Typed result of the import. Never throws once the server is configured. */
    sealed class Result {
        /** The server's merged list and how many entries were new. */
        data class Ok(val entries: List<VocabEntry>, val added: Int) : Result()
        /** 404: the server predates the vocabulary feature. */
        object Unsupported : Result()
        data class AuthError(val code: Int) : Result()
        /** [message] is already a user sentence (the server's `detail` when it sent one). */
        data class Error(val message: String, val detail: String? = null) : Result()
    }

    /** POST /api/v1/vocabulary/import: merge [entries] into the server's list. */
    fun importEntries(entries: List<VocabEntry>): Result {
        val body = JSONObject().put("entries", VocabEntry.listToJson(entries)).toString().toRequestBody(jsonType)
        val req = Request.Builder()
            .url("${ApiClient.baseUrl()}/api/v1/vocabulary/import")
            .header("Authorization", ApiClient.authHeader())
            .post(body)
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: ""
                when {
                    resp.code == 404 -> Result.Unsupported
                    resp.code == 401 || resp.code == 403 -> Result.AuthError(resp.code)
                    !resp.isSuccessful -> {
                        AppLog.w(TAG, "import failed: HTTP ${resp.code} (${text.length} bytes)")
                        Result.Error("HTTP ${resp.code}", ServerErrorText.detailFrom(text))
                    }
                    else -> try {
                        val json = JSONObject(text)
                        Result.Ok(
                            VocabEntry.listFromJson(json.optJSONArray("entries")),
                            json.optInt("added", 0)
                        )
                    } catch (e: Exception) {
                        Result.Error("import response is not valid JSON")
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "import request failed", e)
            Result.Error(e.message ?: "network error")
        }
    }

    // MARK: - Gazetteer parsing (port of contrib/vocab_from_obsidian.py parse_gazetteer)

    private val GENERIC = setOf(
        "the", "a", "an", "my", "our", "her", "his", "dr", "mr", "mrs", "ms", "mom", "dad", "nurse"
    )

    /**
     * Entries from the vault's `Life/_names.md`: one name per line as
     * `Canonical | type | alias, alias | ...`. Header, comment and prose lines are skipped;
     * aliases are kept only when they are plausible mis-hearings of the name itself (a nickname
     * or bare first name must never be rewritten into a full name). Every entry is
     * source "obsidian" with the gazetteer weight, exactly like the server-side script, and the
     * same two post-passes run: [splitSingleTokenAliases] and a merge of repeated terms.
     */
    fun parseGazetteer(text: String): List<VocabEntry> {
        val raw = mutableListOf<VocabEntry>()
        for (line in text.lineSequence().map { it.trim() }) {
            if (!line.contains('|') || line.startsWith("#") || line.startsWith("One line")) continue
            val parts = line.split('|').map { it.trim() }
            if (parts.size < 3 || !looksLikeName(parts[0])) continue
            val canonical = parts[0]
            val aliases = parts[2].split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() && looksLikeName(it) && isMisspelling(it, canonical) }
                .distinctBy { it.lowercase() }
            raw += VocabEntry(canonical, aliases, VocabEntry.SOURCE_OBSIDIAN, GAZETTEER_WEIGHT)
        }
        // collect(): the same term listed twice keeps the first entry and gains the new aliases.
        val seen = LinkedHashMap<String, VocabEntry>()
        for (e in splitSingleTokenAliases(raw)) {
            val key = e.term.lowercase()
            val have = seen[key]
            if (have == null) {
                seen[key] = e
            } else {
                val known = have.aliases.map { it.lowercase() }.toSet()
                seen[key] = have.copy(aliases = have.aliases + e.aliases.filter { it.lowercase() !in known })
            }
        }
        return seen.values.toList()
    }

    /**
     * "Morgen" is a mis-hearing of "Morgan", not of "Morgan Ashford": correcting it to the full
     * name would expand every first-name mention in a transcript. A single-word alias of a
     * multi-word name therefore becomes its own entry on the closest name token
     * (term "Morgan", alias "Morgen", weight 0: a correction only, the full name already carries
     * the hotword budget). Multi-word aliases stay on the full name.
     */
    internal fun splitSingleTokenAliases(entries: List<VocabEntry>): List<VocabEntry> {
        val out = mutableListOf<VocabEntry>()
        val extra = LinkedHashMap<String, VocabEntry>()
        for (e in entries) {
            val ctoks = e.term.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val keep = mutableListOf<String>()
            for (a in e.aliases) {
                val single = a.split(Regex("\\s+")).filter { it.isNotEmpty() }.size == 1
                if (single && ctoks.size > 1) {
                    val target = ctoks.minByOrNull { editDistance(a.lowercase(), it.lowercase()) }!!
                    val x = extra.getOrPut(target.lowercase()) { VocabEntry(target, emptyList(), e.source, 0) }
                    val known = x.aliases.map { it.lowercase() }
                    if (a.lowercase() !in known && !a.equals(target, ignoreCase = true)) {
                        extra[target.lowercase()] = x.copy(aliases = x.aliases + a)
                    }
                } else {
                    keep += a
                }
            }
            out += e.copy(aliases = keep)
        }
        return out + extra.values
    }

    internal fun looksLikeName(s: String): Boolean {
        val t = s.trim()
        if (t.isEmpty() || t.length > 64 || t.length < 2) return false
        val first = t.split(Regex("\\s+")).first().trim('\'', '"')
        if (first.isEmpty()) return false
        return first.first().isUpperCase() && first.lowercase() !in GENERIC
    }

    /**
     * Every token of the alias is within edit distance 2 of some token of the canonical name and
     * at least one token differs; an alias with no differing token only counts when it has as
     * many tokens as the name (so "Priya" alone is a prefix of "Priya Smith", not a mis-hearing).
     */
    internal fun isMisspelling(alias: String, canonical: String): Boolean {
        val ctoks = canonical.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
        val atoks = alias.split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.lowercase() }
        if (atoks.isEmpty() || ctoks.isEmpty() || atoks == ctoks) return false
        var differs = false
        for (at in atoks) {
            val best = ctoks.minOf { editDistance(at, it) }
            if (best > 2) return false
            if (best > 0) differs = true
        }
        return if (differs) true else atoks.size == ctoks.size
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
