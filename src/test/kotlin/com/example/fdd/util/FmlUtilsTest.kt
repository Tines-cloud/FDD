package com.example.fdd.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Unit tests for [FmlUtils].
 *
 * Verifies code-fence stripping for all LLM response formats:
 * tagged fences (`fml`, `map`, `fhir`), untagged fences, and plain text.
 */
class FmlUtilsTest {

    private val sampleFml = """
        map "http://example.org/fhir/StructureMap/test" = "TestMap"

        group TestMap(source src : Patient, target tgt : Patient) {
          src.id as id -> tgt.id = id;
        }
    """.trimIndent()

    @Test
    @DisplayName("Returns plain FML unchanged")
    fun extractFml_plainText_returnsUnchanged() {
        val result = FmlUtils.extractFml(sampleFml)
        assertEquals(sampleFml, result)
    }

    @ParameterizedTest(name = "Strips ```{0} code fence")
    @ValueSource(strings = ["fml", "map", "fhir", ""])
    fun extractFml_codeFenceVariants_stripsFence(tag: String) {
        val wrapped = "```$tag\n$sampleFml\n```"
        val result = FmlUtils.extractFml(wrapped)
        assertEquals(sampleFml, result)
    }

    @Test
    @DisplayName("Trims leading and trailing whitespace")
    fun extractFml_whitespaceAround_trims() {
        val padded = "   \n  $sampleFml  \n   "
        val result = FmlUtils.extractFml(padded)
        assertEquals(sampleFml, result)
    }

    @Test
    @DisplayName("Handles empty string")
    fun extractFml_emptyString_returnsEmpty() {
        val result = FmlUtils.extractFml("")
        assertEquals("", result)
    }

    @Test
    @DisplayName("Handles whitespace-only string")
    fun extractFml_whitespaceOnly_returnsEmpty() {
        val result = FmlUtils.extractFml("   \n  \t  ")
        assertEquals("", result)
    }

    @Test
    @DisplayName("Does not strip partial code fences")
    fun extractFml_partialFence_returnsAsIs() {
        val partial = "```fml\n$sampleFml"
        val result = FmlUtils.extractFml(partial)
        // Partial fence (missing closing ```) should not be stripped
        assertTrue(result.contains("```fml"))
    }

    @Test
    @DisplayName("Strips fence with extra newlines around content")
    fun extractFml_extraNewlines_stripsCleanly() {
        val wrapped = "```fml\n\n$sampleFml\n\n```"
        val result = FmlUtils.extractFml(wrapped)
        assertEquals(sampleFml, result)
    }
}
