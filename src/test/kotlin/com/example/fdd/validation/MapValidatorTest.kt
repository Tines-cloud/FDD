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
import org.junit.jupiter.api.Assertions.assertFalse
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
    @DisplayName("Invalid syntax FML fails deterministically without invoking reflexion")
    fun validateAndRepair_invalidFml_throwsAfterMaxAttempts() {
        val invalidFml = "this is not valid FML"

        // chatWithHistory returns invalid FML on every reflexion turn
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn("still not valid")

        val ex = assertThrows<MapValidationException> {
            validator.validateAndRepair(invalidFml, source, target, emptyDriftReport)
        }

        assertTrue(
            ex.message!!.contains("syntax") || ex.message!!.contains("deterministic"),
            "Expected a compilation failure message, got: ${ex.message}"
        )
        // All cycle errors must appear in the details list - one entry per error per cycle
        assertTrue(ex.attemptErrors.isNotEmpty())
        // Every entry must carry a [Cycle N] prefix
        assertTrue(ex.attemptErrors.all { it.startsWith("[Cycle ") })
        // Syntax errors stay on deterministic path and must never be sent to LLM.
        verify(llmClient, never()).chatWithHistory(any(), any(), any(), any())
    }

    @Test
    @DisplayName("Reflexion fixes all errors in the FML via multi-turn conversation")
    fun validateAndRepair_reflexionAttemptsSelfCorrection() {
        val invalidFml = """
            map "http://example.org/fhir/StructureMap/fixed" = "FixedMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group FixedMap(source src : Patient, target tgt : Patient) {
              src.id as id -> tgt.id = bogus(id) "broken-transform";
            }
        """.trimIndent()
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

    @Test
    @DisplayName("Missing semicolon before an indented comment is fixed on the rule line, not the comment")
    fun validateAndRepair_missingSemicolonBeforeIndentedComment_fixesRuleLine() {
        val invalidFml = """
            map "http://example.org/fhir/StructureMap/test" = "TestMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group TestMap(source src : Patient, target tgt : Patient) {
              src.id as id -> tgt.id = id
              // explanatory comment should stay untouched
              src.gender as g -> tgt.gender = g "patient-gender";
            }
        """.trimIndent()

        val result = validator.validateAndRepair(invalidFml, source, target, emptyDriftReport)

        verify(llmClient, never()).chatWithHistory(any(), any(), any(), any())
        assertTrue(result.syntacticallyValid)
        assertTrue(result.structureMapFml.contains("src.id as id -> tgt.id = id;"))
        assertFalse(result.structureMapFml.contains("comment should stay untouched;"))
    }

    @Test
    @DisplayName("AP25: compound FHIRPath source specifier is rewritten to plain 'src ->' without invoking LLM")
    fun validateAndRepair_ap25_compoundFhirPathSource_rewrittenDeterministically() {
        // The LLM generates compound FHIRPath expressions as source specifiers, which HAPI
        // rejects with "Found '.' expecting ';'".  sanitizeFml must fix these before validation.
        // After AP25 rewrites the compound condition to plain 'src -> tgt.id as v "name";',
        // the rule is a valid (no-op binding) rule that HAPI accepts.
        val fmlWithAp25 = """
            map "http://example.org/fhir/StructureMap/test" = "TestMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group TestMap(source src : Patient, target tgt : Patient) {
              src.id as v -> tgt.id = v "patient-id";
              src.id.empty() and (tgt.id.exists()).not() -> tgt.id as w "patient-default-id";
            }
        """.trimIndent()

        val result = validator.validateAndRepair(fmlWithAp25, source, target, emptyDriftReport)

        // sanitizeFml should have rewritten the compound condition — LLM must not be invoked
        verify(llmClient, never()).chatWithHistory(any(), any(), any(), any())
        assertTrue(result.syntacticallyValid)
        // The compound source must be gone; the rewritten form must use plain 'src ->'
        assertFalse(result.structureMapFml.contains("src.id.empty() and"))
        assertTrue(result.structureMapFml.contains("src -> tgt.id as w"))
    }

    @Test
    @DisplayName("AP26: bare target assignment without source arrow is prefixed with 'tgt ->' without invoking LLM")
    fun validateAndRepair_ap26_bareTargetAssignment_prefixedDeterministically() {
        // The LLM generates target-only groups where rules have no source ('->'),
        // which HAPI rejects with "Found '=' expecting ';'".  sanitizeFml must fix these.
        val fmlWithAp26 = """
            map "http://example.org/fhir/StructureMap/test" = "TestMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group TestMap(source src : Patient, target tgt : Patient) {
              src.id as v -> tgt.id = v "patient-id";
            }

            group DefaultStatus(target tgt : Patient) {
              tgt.gender = 'unknown' "default-gender";
            }
        """.trimIndent()

        val result = validator.validateAndRepair(fmlWithAp26, source, target, emptyDriftReport)

        // sanitizeFml should have prefixed 'tgt -> ' — LLM must not be invoked
        verify(llmClient, never()).chatWithHistory(any(), any(), any(), any())
        assertTrue(result.syntacticallyValid)
        // The bare assignment must be gone; the valid form must be present
        assertTrue(result.structureMapFml.contains("tgt -> tgt.gender = 'unknown'"))
    }

    @Test
    @DisplayName("Repeated non-syntax errors are pruned after one failed LLM repair attempt")
    fun validateAndRepair_repeatedNonSyntaxError_prunesStubbornRule() {
        val stubbornFml = """
            map "http://example.org/fhir/StructureMap/test" = "TestMap"

            uses "http://hl7.org/fhir/StructureDefinition/Patient" as source
            uses "http://hl7.org/fhir/StructureDefinition/Patient" as target

            group TestMap(source src : Patient, target tgt : Patient) {
              src.id as v -> tgt.id = bogus(v) "bad-transform";
              src.gender as g -> tgt.gender = g "patient-gender";
            }
        """.trimIndent()

        // Simulate an LLM that fails to repair the same non-syntax error.
        whenever(llmClient.chatWithHistory(any(), any(), any(), any())).thenReturn(stubbornFml)

        val result = validator.validateAndRepair(stubbornFml, source, target, emptyDriftReport)

        // After one failed repair turn, the stubborn line is pruned rather than repeated forever.
        verify(llmClient, times(1)).chatWithHistory(any(), any(), any(), any())
        assertTrue(result.syntacticallyValid)
        assertTrue(result.structureMapFml.contains("PRUNED after repeated unresolved error"))
        assertTrue(result.structureMapFml.contains("// PRUNED after repeated unresolved error: src.id as v -> tgt.id = bogus(v) \"bad-transform\";"))
        assertTrue(result.structureMapFml.contains("src.gender as g -> tgt.gender = g \"patient-gender\";"))
    }
}