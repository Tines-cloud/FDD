package com.example.fdd.service

import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.util.FmlUtils
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Default [MapGenerator] implementation - asks the LLM to produce FML code.
 *
 * The drift report is the main input. Source and target profiles are used only
 * to extract a short element list for the paths mentioned in the drift items,
 * so the LLM has enough context to write correct FML without re-receiving the
 * full profiles.
 *
 * Steps:
 * 1. Build a short element list covering only the drifted paths.
 * 2. Compose system + user prompts.
 * 3. Call [LlmClient] to get FML.
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

        // 2. Serialise drift report (the primary input for repair generation)
        val driftReportJson = objectMapper.writeValueAsString(driftReport)

        // 3. Compose prompts - drift report is the centerpiece
        val systemPrompt = promptTemplateService.loadTemplate("map-generation-system.txt")
        val userPrompt = promptTemplateService.loadTemplate(
            "map-generation-user.txt",
            mapOf(
                "sourceCanonical" to sourceUrl,
                "targetCanonical" to targetUrl,
                "driftReport" to driftReportJson,
                "abbreviatedElements" to abbreviatedElementsJson
            )
        )

        // 4. LLM call
        val response = llmClient.chat(systemPrompt, userPrompt, temperature = 0.15)

        // 5. Clean FML output
        val fml = FmlUtils.extractFml(response)
        log.info("StructureMap generated ({} chars)", fml.length)

        return MapGenerationResult(
            structureMapFml = fml,
            syntacticallyValid = false,         // validation is deferred to MapValidator
            validationMessages = emptyList()
        )
    }
}
