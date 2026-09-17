package cloud.adamrb.transom.net

import cloud.adamrb.transom.models.VocabEntry

/**
 * "Import from your vault" for the custom vocabulary: parse the Obsidian names gazetteer the
 * phone's vault copy holds (`_names.md`) into entries for [ApiClient.importVocabulary],
 * which MERGES them into the server's list. A port of the server's own
 * `contrib/vocab_from_obsidian.py` so both paths produce the same entries.
 */
object VocabularyImport {

    /** Weight the vault script gives gazetteer names so they outrank other imported hotwords. */
    const val GAZETTEER_WEIGHT = 10_000

    /** Largest file the picker is allowed to hand us; the gazetteer is a few hundred lines. */
    const val MAX_FILE_BYTES = 2L * 1024 * 1024

    // MARK: - Gazetteer parsing (port of contrib/vocab_from_obsidian.py parse_gazetteer)

    private val GENERIC = setOf(
        "the", "a", "an", "my", "our", "her", "his", "dr", "mr", "mrs", "ms", "mom", "dad", "nurse"
    )

    /**
     * Entries from the vault's `_names.md`: one name per line as
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
