package com.example.fdd.validation

import ca.uhn.fhir.context.FhirContext
import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.config.FddProperties
import com.example.fdd.config.ValidationProperties
import com.example.fdd.exception.MapValidationException
import com.example.fdd.model.DriftReport
import org.hl7.fhir.r4.model.StructureDefinition
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [DefaultMapValidator].
 *
 * Tests the Trust-but-Verify + Reflexion loop logic.
 */
class MapValidatorTest {

    private lateinit var llmClient: LlmClient
    private lateinit var promptTemplateService: PromptTemplateService
    private lateinit var validator: DefaultMapValidator

    private val fhirContext = FhirContext.forR4()
    private val source = StructureDefinition().apply { url = "http://source" }
    private val target = StructureDefinition().apply { url = "http://target" }
    private val emptyDriftReport = DriftReport(
        sourceProfileCanonical = "http://source",
        targetProfileCanonical = "http://target"
    )

    @BeforeEach
    fun setUp() {
        llmClient = mock()
        promptTemplateService = mock()

        val properties = FddProperties(
            validation = ValidationProperties(maxAttempts = 3)
        )

        // loadTemplate always called as loadTemplate(name, map) in bytecode (Kotlin default arg),
        // so a single two-arg stub covers both the single-arg and two-arg call sites.
        whenever(promptTemplateService.loadTemplate(any(), any())).thenReturn("stub")
        // Default LLM stub for reflexion path - reflexion now uses chatWithHistory (multi-turn)
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn("invalid fallback")

        validator = DefaultMapValidator(fhirContext, llmClient, promptTemplateService, properties)
    }

    @Test
    @DisplayName("Valid FML compiles on first attempt without invoking reflexion")
    fun validateAndRepair_validFml_succeeds() {
        // A fully-formed FML structure with uses clauses for HAPI compliance:
        val validFml = """
            map "http://example.org/fhir/StructureMap/test" = "TestMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group TestMap(source src : Patient, target tgt : Patient) {
              src.id as id -> tgt.id = id;
            }
        """.trimIndent()

        val result = validator.validateAndRepair(validFml, source, target, emptyDriftReport)

        assertTrue(result.validationMessages.isNotEmpty())
        if (result.syntacticallyValid) {
            // No reflexion needed - chatWithHistory must not have been invoked
            verify(llmClient, never()).chatWithHistory(any(), any(), any(), any())
            assertTrue(result.validationMessages.last().contains("successful"))
        }
    }

    @Test
    @DisplayName("Invalid FML throws MapValidationException after exhausting max attempts")
    fun validateAndRepair_invalidFml_throwsAfterMaxAttempts() {
        val invalidFml = "this is not valid FML"

        // chatWithHistory returns invalid FML on every reflexion turn
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn("still not valid")

        val ex = assertThrows<MapValidationException> {
            validator.validateAndRepair(invalidFml, source, target, emptyDriftReport)
        }

        assertTrue(ex.message!!.contains("3 attempts"))
        // All 3 attempt errors must appear in the details list
        assertTrue(ex.attemptErrors.size == 3)
        // Reflexion is called exactly 2 times (after attempt 1 and 2, not after 3)
        verify(llmClient, times(2)).chatWithHistory(any(), any(), any(), any())
    }

    @Test
    @DisplayName("Reflexion attempts self-correction via multi-turn conversation")
    fun validateAndRepair_reflexionAttemptsSelfCorrection() {
        val invalidFml = "broken FML"
        val improvedFml = """
            map "http://example.org/fhir/StructureMap/fixed" = "FixedMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group FixedMap(source src : Patient, target tgt : Patient) {
              src.id as id -> tgt.id = id;
            }
        """.trimIndent()

        // Reflexion repairs the FML on the first conversation turn
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn(improvedFml)

        val result = validator.validateAndRepair(invalidFml, source, target, emptyDriftReport)

        verify(llmClient, org.mockito.kotlin.atLeastOnce()).chatWithHistory(any(), any(), any(), any())
        // validationMessages contains: [failure from attempt 1, success from attempt 2]
        assertTrue(result.validationMessages.size >= 2)
        assertTrue(result.validationMessages.last().contains("successful"))
    }
}
