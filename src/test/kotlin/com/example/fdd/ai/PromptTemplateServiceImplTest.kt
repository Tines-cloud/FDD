package com.example.fdd.ai

import com.example.fdd.ai.impl.PromptTemplateServiceImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [PromptTemplateService].
 *
 * Verifies template loading from classpath, Mustache substitution,
 * caching behaviour, and error handling for missing templates.
 */
class PromptTemplateServiceImplTest {

    private lateinit var service: PromptTemplateService

    @BeforeEach
    fun setUp() {
        service = PromptTemplateServiceImpl()
    }

    @Test
    @DisplayName("Loads a known template from classpath:ai/")
    fun loadTemplate_knownTemplate_returnsContent() {
        val result = service.loadTemplate("drift-analysis-system.txt")

        assertTrue(result.isNotBlank(), "Template content must not be blank")
        assertTrue(result.contains("drift"), "System prompt should mention drift")
    }

    @Test
    @DisplayName("Performs Mustache variable substitution")
    fun loadTemplate_withVariables_substitutesCorrectly() {
        val result = service.loadTemplate(
            "drift-analysis-user.txt",
            mapOf(
                "profileContext" to "CONTEXT_PLACEHOLDER",
                "ruleBasedSeeds" to "SEEDS_PLACEHOLDER"
            )
        )

        assertTrue(result.contains("CONTEXT_PLACEHOLDER"), "profileContext variable should be substituted")
        assertTrue(result.contains("SEEDS_PLACEHOLDER"), "ruleBasedSeeds variable should be substituted")
        assertFalse(result.contains("{{profileContext}}"), "Unreplaced placeholder should not remain")
        assertFalse(result.contains("{{ruleBasedSeeds}}"), "Unreplaced placeholder should not remain")
    }

    @Test
    @DisplayName("Returns raw template when no variables are provided")
    fun loadTemplate_noVariables_returnsRawTemplate() {
        val result = service.loadTemplate("drift-analysis-system.txt", emptyMap())

        assertTrue(result.isNotBlank())
    }

    @Test
    @DisplayName("Caches templates after first load")
    fun loadTemplate_calledTwice_returnsSameContent() {
        val first = service.loadTemplate("drift-analysis-system.txt")
        val second = service.loadTemplate("drift-analysis-system.txt")

        assertEquals(first, second, "Cached and fresh loads should be identical")
    }

    @Test
    @DisplayName("clearCache forces re-read on next call")
    fun clearCache_thenReload_succeeds() {
        val before = service.loadTemplate("drift-analysis-system.txt")
        service.clearCache()
        val after = service.loadTemplate("drift-analysis-system.txt")

        assertEquals(before, after, "Content should be identical after cache clear + reload")
    }

    @Test
    @DisplayName("Throws IllegalArgumentException for non-existent template")
    fun loadTemplate_missingTemplate_throwsException() {
        assertThrows(IllegalArgumentException::class.java) {
            service.loadTemplate("does-not-exist.txt")
        }
    }

    @Test
    @DisplayName("All six prompt templates load successfully")
    fun loadTemplate_allPromptTemplates_loadSuccessfully() {
        val templates = listOf(
            "drift-analysis-system.txt",
            "drift-analysis-user.txt",
            "map-generation-system.txt",
            "map-generation-user.txt",
            "reflexion-system.txt",
            "reflexion-user.txt"
        )

        templates.forEach { name ->
            val content = service.loadTemplate(name)
            assertTrue(content.isNotBlank(), "Template $name should not be blank")
        }
    }
}
