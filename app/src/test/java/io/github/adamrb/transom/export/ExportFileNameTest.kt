package io.github.adamrb.transom.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ExportFileName guards the only user- or page-controlled string that becomes a path under
 * cacheDir/exports/, so the rules are pinned here.
 */
class ExportFileNameTest {

    @Test
    fun plainTitleGetsMdExtension() {
        assertEquals("Budget planning call.md", ExportFileName.sanitize("Budget planning call"))
    }

    @Test
    fun existingMarkdownExtensionIsNotDoubled() {
        assertEquals("notes.md", ExportFileName.sanitize("notes.md"))
        assertEquals("notes.md", ExportFileName.sanitize("notes.MD"))
        assertEquals("notes.md", ExportFileName.sanitize("notes.markdown"))
        // Other extensions are kept as part of the name and still forced to .md
        assertEquals("notes.txt.md", ExportFileName.sanitize("notes.txt"))
    }

    @Test
    fun pathSeparatorsAndTraversalCannotEscapeTheDirectory() {
        val name = ExportFileName.sanitize("../../etc/passwd")
        assertFalse(name.contains('/'))
        assertFalse(name.contains('\\'))
        assertFalse(name.startsWith("."))
        assertEquals("-..-etc-passwd.md", name)
        assertEquals("a-b-c.md", ExportFileName.sanitize("a/b\\c"))
        assertEquals("transcript.md", ExportFileName.sanitize(".."))
        assertEquals("transcript.md", ExportFileName.sanitize("..."))
    }

    @Test
    fun controlCharactersAreStripped() {
        assertEquals("ab.md", ExportFileName.sanitize("a\u0007b\u0000"))
        assertEquals("ab.md", ExportFileName.sanitize("a\u007Fb"))
        // Line breaks and tabs become word separators instead of gluing words together
        assertEquals("a b.md", ExportFileName.sanitize("a\nb"))
        assertEquals("a b.md", ExportFileName.sanitize("a\r\n\tb"))
    }

    @Test
    fun windowsUnsafeCharactersAreReplaced() {
        assertEquals("re- -Q3- -plan-.md", ExportFileName.sanitize("""re: "Q3" |plan?"""))
        assertEquals("a-b-c-d-e.md", ExportFileName.sanitize("a*b<c>d|e"))
    }

    @Test
    fun whitespaceIsCollapsedAndTrimmed() {
        assertEquals("a b c.md", ExportFileName.sanitize("  a \t b\n\n c  "))
    }

    @Test
    fun emptyAndNullFallBack() {
        assertEquals("transcript.md", ExportFileName.sanitize(""))
        assertEquals("transcript.md", ExportFileName.sanitize(null))
        assertEquals("transcript.md", ExportFileName.sanitize("   "))
        assertEquals("transcript.md", ExportFileName.sanitize(".md"))
        assertEquals("transcript.md", ExportFileName.sanitize("///"))
    }

    @Test
    fun longNamesAreCapped() {
        val name = ExportFileName.sanitize("x".repeat(500))
        assertEquals(ExportFileName.MAX_BASE_LENGTH + ".md".length, name.length)
        assertTrue(name.endsWith(".md"))
        // Capping never leaves a trailing dot or space before the extension
        val dotted = ExportFileName.sanitize("y".repeat(ExportFileName.MAX_BASE_LENGTH - 1) + ". tail")
        assertFalse(dotted.endsWith(" .md"))
        assertFalse(dotted.endsWith("..md"))
    }

    @Test
    fun unicodeTitlesSurvive() {
        assertEquals("Réunion d'équipe 会議.md", ExportFileName.sanitize("Réunion d'équipe 会議"))
    }
}
