package com.example.fdd.service

import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.model.DriftType
import com.example.fdd.model.ProfileContext
import com.example.fdd.model.ProfileSummary
import com.example.fdd.service.impl.DefaultMapGenerator
import org.hl7.fhir.r4.model.StructureDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * Unit tests for [DefaultMapGenerator].
 *
 * Verifies prompt composition, FML extraction, and code-fence stripping.
 */
class MapGeneratorTest {

    private lateinit var llmClient: LlmClient
    private lateinit var promptTemplateService: PromptTemplateService
    private lateinit var profileContextBuilder: ProfileContextBuilder
    private lateinit var objectMapper: ObjectMapper
    private lateinit var generator: DefaultMapGenerator

    private val source = StructureDefinition().apply { url = "http://source" }
    private val target = StructureDefinition().apply { url = "http://target" }

    private val sampleDriftReport = DriftReport(
        sourceProfileCanonical = "http://source",
        targetProfileCanonical = "http://target",
        items = listOf(
            DriftItem(
                id = "identifier-cardinality",
                type = DriftType.CARDINALITY,
                sourcePath = "Patient.identifier",
                targetPath = "Patient.identifier",
                description = "Optional vs required",
                severity = "ERROR"
            )
        )
    )

    @BeforeEach
    fun setUp() {
        llmClient = mock()
        promptTemplateService = mock()
        profileContextBuilder = mock()
        objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

        generator = DefaultMapGenerator(
            llmClient, promptTemplateService, profileContextBuilder, objectMapper
        )

        whenever(promptTemplateService.loadTemplate(any(), any())).thenReturn("stub prompt")
        whenever(profileContextBuilder.buildDriftFocusedContext(any(), any(), any())).thenReturn(
            ProfileContext(
                sourceProfile = ProfileSummary(canonical = "http://source", type = "Patient"),
                targetProfile = ProfileSummary(canonical = "http://target", type = "Patient")
            )
        )
    }

    @Test
    @DisplayName("Returns clean FML when LLM response has no code fences")
    fun generateMap_cleanFml_returnsFmlDirectly() {
        val fml = """
            map "http://example.org/fhir/StructureMap/test" = "TestMap"

            group TestMap(source src : Patient, target tgt : Patient) {
              src.id as id -> tgt.id = id;
            }
        """.trimIndent()

        whenever(llmClient.chat(any(), any(), any())).thenReturn(fml)

        val result = generator.generateMap(source, target, sampleDriftReport)

        assertEquals(fml, result.structureMapFml)
        assertFalse(result.syntacticallyValid) // validation is deferred to MapValidator
    }

    @Test
    @DisplayName("Strips markdown code fences from LLM response")
    fun generateMap_wrappedInCodeFence_stripsFences() {
        val fml = """map "http://example.org/test" = "TestMap"

group TestMap(source src : Patient, target tgt : Patient) {
  src.id as id -> tgt.id = id;
}"""

        val wrappedResponse = "```fml\n$fml\n```"

        whenever(llmClient.chat(any(), any(), any())).thenReturn(wrappedResponse)

        val result = generator.generateMap(source, target, sampleDriftReport)

        assertEquals(fml, result.structureMapFml)
    }

    @Test
    @DisplayName("Returns non-empty FML result for valid drift report")
    fun generateMap_validInput_returnsNonEmpty() {
        whenever(llmClient.chat(any(), any(), any()))
            .thenReturn("map \"test\" = \"test\" { }")

        val result = generator.generateMap(source, target, sampleDriftReport)

        assertTrue(result.structureMapFml.isNotBlank())
        assertTrue(result.validationMessages.isEmpty())
    }
}
