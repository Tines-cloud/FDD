package com.example.fdd.service

import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.DriftType
import com.example.fdd.model.ProfileContext
import com.example.fdd.model.ProfileSummary
import com.example.fdd.service.impl.DefaultDriftAnalyzer
import org.hl7.fhir.r4.model.StructureDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Unit tests for [DefaultDriftAnalyzer].
 *
 * Verifies that the analyser correctly parses LLM responses into [com.example.fdd.model.DriftReport].
 */
class DriftAnalyzerTest {

    private lateinit var llmClient: LlmClient
    private lateinit var promptTemplateService: PromptTemplateService
    private lateinit var profileContextBuilder: ProfileContextBuilder
    private lateinit var objectMapper: ObjectMapper
    private lateinit var ruleBasedDetector: RuleBasedDriftDetector
    private lateinit var analyzer: DriftAnalyzer

    @BeforeEach
    fun setUp() {
        llmClient = mock()
        promptTemplateService = mock()
        profileContextBuilder = mock()
        ruleBasedDetector = mock()
        objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        analyzer = DefaultDriftAnalyzer(
            llmClient, promptTemplateService, profileContextBuilder, objectMapper, ruleBasedDetector
        )

        // Stub template loading (2-arg stub covers both single and double arg calls)
        whenever(promptTemplateService.loadTemplate(any(), any())).thenReturn("stub prompt")

        // Stub profile context builder
        whenever(profileContextBuilder.buildContext(any(), any())).thenReturn(
            ProfileContext(
                sourceProfile = ProfileSummary(canonical = "source", type = "Patient"),
                targetProfile = ProfileSummary(canonical = "target", type = "Patient")
            )
        )

        // Stub rule-based detector returns empty by default
        whenever(ruleBasedDetector.detect(any<ProfileContext>())).thenReturn(emptyList())
    }

    @Test
    @DisplayName("Parses a well-formed JSON array response from the LLM")
    fun analyzeDrift_parsesValidJsonResponse() {
        val llmResponse = """
            [
              {
                "id": "identifier-cardinality",
                "type": "CARDINALITY",
                "sourcePath": "Patient.identifier",
                "targetPath": "Patient.identifier",
                "description": "US Core requires at least one identifier",
                "severity": "ERROR"
              },
              {
                "id": "race-extension",
                "type": "EXTENSION",
                "sourcePath": "Patient",
                "targetPath": "Patient.extension:race",
                "description": "US Core adds race extension",
                "severity": "WARNING"
              }
            ]
        """.trimIndent()

        whenever(llmClient.chat(any(), any(), any())).thenReturn(llmResponse)

        val source = StructureDefinition().apply { url = "http://source" }
        val target = StructureDefinition().apply { url = "http://target" }

        val report = analyzer.analyzeDrift(source, target)

        assertEquals(2, report.totalDrifts)
        assertEquals(DriftType.CARDINALITY, report.items[0].type)
        assertEquals(DriftType.EXTENSION, report.items[1].type)
        assertEquals("http://source", report.sourceProfileCanonical)
    }

    @Test
    @DisplayName("Strips markdown code fences from LLM response before parsing")
    fun analyzeDrift_handlesMarkdownWrappedResponse() {
        val llmResponse = """
            ```json
            [
              {
                "id": "gender-binding",
                "type": "TERMINOLOGY",
                "sourcePath": "Patient.gender",
                "targetPath": "Patient.gender",
                "description": "Different binding strengths",
                "severity": "INFO"
              }
            ]
            ```
        """.trimIndent()

        whenever(llmClient.chat(any(), any(), any())).thenReturn(llmResponse)

        val source = StructureDefinition().apply { url = "http://src" }
        val target = StructureDefinition().apply { url = "http://tgt" }

        val report = analyzer.analyzeDrift(source, target)

        assertEquals(1, report.totalDrifts)
        assertEquals(DriftType.TERMINOLOGY, report.items[0].type)
    }

    @Test
    @DisplayName("Returns empty drift report when LLM returns empty array")
    fun analyzeDrift_handlesEmptyResponse() {
        whenever(llmClient.chat(any(), any(), any())).thenReturn("[]")

        val source = StructureDefinition().apply { url = "http://src" }
        val target = StructureDefinition().apply { url = "http://tgt" }

        val report = analyzer.analyzeDrift(source, target)

        assertTrue(report.items.isEmpty())
        assertEquals(0, report.totalDrifts)
    }

    @Test
    @DisplayName("Falls back to rule-based results when LLM call throws an exception")
    fun analyzeDrift_llmFailure_fallsBackToRuleBasedOnly() {
        // Stub LLM to throw - simulates network failure, quota exceeded, etc.
        whenever(llmClient.chat(any(), any(), any()))
            .thenThrow(RuntimeException("Simulated LLM unavailability"))

        // Stub rule-based detector to return one item
        val ruleItem = com.example.fdd.model.DriftItem(
            id = "rule-item-1",
            type = DriftType.CARDINALITY,
            sourcePath = "Patient.identifier",
            targetPath = "Patient.identifier",
            description = "Rule-detected cardinality delta",
            severity = "WARNING"
        )
        whenever(ruleBasedDetector.detect(any<ProfileContext>())).thenReturn(listOf(ruleItem))

        val source = StructureDefinition().apply { url = "http://src" }
        val target = StructureDefinition().apply { url = "http://tgt" }

        val report = analyzer.analyzeDrift(source, target)

        // Must contain exactly the rule-based item; no LLM items
        assertEquals(1, report.totalDrifts, "Expected fallback to rule-based items only")
        assertEquals(ruleItem.id, report.items[0].id)
        assertEquals("http://src", report.sourceProfileCanonical)
        assertEquals("http://tgt", report.targetProfileCanonical)
    }
}
