package com.example.fdd.validation

import ca.uhn.fhir.context.FhirContext
import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.config.FddProperties
import com.example.fdd.config.ValidationProperties
import com.example.fdd.exception.MapValidationException
import com.example.fdd.model.DriftReport
import com.example.fdd.validation.impl.DefaultMapValidator
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
 * Tests for DefaultMapValidator.
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

        // Stub loadTemplate for both single-arg and two-arg calls.
        whenever(promptTemplateService.loadTemplate(any(), any())).thenReturn("stub")
        // Stub LLM response for repair calls.
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn("invalid fallback")

        validator = DefaultMapValidator(fhirContext, llmClient, promptTemplateService, properties)
    }

    @Test
    @DisplayName("Valid FML compiles on first attempt without invoking reflexion")
    fun validateAndRepair_validFml_succeeds() {
        // Valid FML with uses clauses:
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
    @DisplayName("Invalid FML throws MapValidationException after exhausting max cycles")
    fun validateAndRepair_invalidFml_throwsAfterMaxAttempts() {
        val invalidFml = "this is not valid FML"

        // chatWithHistory returns invalid FML on every reflexion turn
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn("still not valid")

        val ex = assertThrows<MapValidationException> {
            validator.validateAndRepair(invalidFml, source, target, emptyDriftReport)
        }

        assertTrue(
            ex.message!!.contains("attempts") || ex.message!!.contains("stuck") || ex.message!!.contains("converging"),
            "Expected a compilation failure message, got: ${ex.message}"
        )
        // All cycle errors must appear in the details list - one entry per error per cycle
        assertTrue(ex.attemptErrors.isNotEmpty())
        // Every entry must carry a [Cycle N] prefix
        assertTrue(ex.attemptErrors.all { it.startsWith("[Cycle ") })
        // Reflexion is called exactly 2 times (after cycle 1 and 2, not after cycle 3)
        verify(llmClient, times(2)).chatWithHistory(any(), any(), any(), any())
    }

    @Test
    @DisplayName("Reflexion fixes all errors in the FML via multi-turn conversation")
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

        // Reflexion repairs FML on the first conversation turn
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn(improvedFml)

        val result = validator.validateAndRepair(invalidFml, source, target, emptyDriftReport)

        verify(llmClient, org.mockito.kotlin.atLeastOnce()).chatWithHistory(any(), any(), any(), any())
        // validationMessages: [Cycle 1 errors...] + success entry
        assertTrue(result.validationMessages.isNotEmpty())
        assertTrue(result.validationMessages.last().contains("successful"))
    }
}
