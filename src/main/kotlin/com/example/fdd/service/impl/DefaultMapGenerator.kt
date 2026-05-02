package com.example.fdd.service.impl

import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.service.MapGenerator
import com.example.fdd.util.FmlUtils
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Default [com.example.fdd.service.MapGenerator] implementation - asks the LLM to produce FML code.
 *
 * The drift report is the main input. Source and target profiles are used only
 * to extract a short element list for the paths mentioned in the drift items,
 * so the LLM has enough context to write correct FML without re-receiving the
 * full profiles.
 *
 * Steps:
 * 1. Build a short element list covering only the drifted paths.
 * 2. Compose system + user prompts.
 * 3. Call [com.example.fdd.ai.LlmClient] to get FML.
 * 4. Strip any markdown fences from the response.
 */
@Service
class DefaultMapGenerator(
    private val llmClient: LlmClient,
    private val promptTemplateService: PromptTemplateService,
    private val profileContextBuilder: ProfileContextBuilder,
    private val objectMapper: ObjectMapper
) : MapGenerator {

    private val log = LoggerFactory.getLogger(javaClass)

    @Timed(value = "fdd.map.generation.duration", description = "StructureMap generation duration")
    override fun generateMap(
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): MapGenerationResult {
        val sourceUrl = source.url ?: "unknown-source"
        val targetUrl = target.url ?: "unknown-target"
        log.info("Generating StructureMap for: {} -> {}", sourceUrl, targetUrl)

        // 1. Build ABBREVIATED context - only elements referenced in drift items
        val abbreviatedContext = profileContextBuilder.buildDriftFocusedContext(
            source, target, driftReport.items
        )
        val abbreviatedElementsJson = objectMapper.writeValueAsString(abbreviatedContext)

        // 2. Build PASS-THROUGH elements - required/mustSupport target fields that have
        //    matching source paths but are NOT in the drift report (identical in both profiles).
        //    These must be explicitly copied in the StructureMap or the transformed resource
        //    will be missing fields required by the base FHIR spec and target profile.
        val passThroughElements = profileContextBuilder.buildPassThroughElements(
            source, target, driftReport.items
        )
        val passThroughJson = objectMapper.writeValueAsString(
            passThroughElements.map { e ->
                mapOf(
                    "path" to e.path,
                    "min" to (e.min ?: 0),
                    "max" to e.max,
                    "mustSupport" to e.mustSupport,
                    "types" to e.types.map { t -> t.code }
                )
            }
        )
        log.info(
            "Pass-through elements: {} required/mustSupport common element(s) for {} -> {}",
            passThroughElements.size, sourceUrl, targetUrl
        )

        // 3. Serialise drift report (the primary input for repair generation)
        val driftReportJson = objectMapper.writeValueAsString(driftReport)

        // 4. Compose prompts - drift report is the centerpiece
        val systemPrompt = promptTemplateService.loadTemplate("map-generation-system.txt")
        val userPrompt = promptTemplateService.loadTemplate(
            "map-generation-user.txt",
            mapOf(
                "sourceCanonical" to sourceUrl,
                "targetCanonical" to targetUrl,
                "driftReport" to driftReportJson,
                "abbreviatedElements" to abbreviatedElementsJson,
                "passThroughElements" to passThroughJson
            )
        )

        // 5. LLM call
        val response = llmClient.chat(systemPrompt, userPrompt, temperature = 0.15)

        // 6. Clean FML output
        val fml = FmlUtils.extractFml(response)
        log.info("StructureMap generated ({} chars)", fml.length)

        return MapGenerationResult(
            structureMapFml = fml,
            syntacticallyValid = false,
            validationMessages = emptyList()
        )
    }
}