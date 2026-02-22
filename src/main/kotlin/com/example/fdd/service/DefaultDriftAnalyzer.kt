package com.example.fdd.service

import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.exception.DriftAnalysisException
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.model.DriftType
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Default [DriftAnalyzer] implementation using a hybrid approach.
 *
 * Steps:
 * 1. Build a [com.example.fdd.model.ProfileContext] from both profiles
 *    via [ProfileContextBuilder].
 * 2. Run [RuleBasedDriftDetector] for fast, deterministic checks.
 * 3. Pass the rule-based results to the LLM as seed items.
 * 4. Call [LlmClient] and parse the JSON response into a [DriftReport].
 * 5. Merge both result sets, removing duplicates by path + type.
 */
@Service
class DefaultDriftAnalyzer(
    private val llmClient: LlmClient,
    private val promptTemplateService: PromptTemplateService,
    private val profileContextBuilder: ProfileContextBuilder,
    private val objectMapper: ObjectMapper,
    private val ruleBasedDetector: RuleBasedDriftDetector
) : DriftAnalyzer {

    private val log = LoggerFactory.getLogger(javaClass)

    @Timed(value = "fdd.drift.analysis.duration", description = "Drift analysis duration")
    override fun analyzeDrift(
        source: StructureDefinition,
        target: StructureDefinition
    ): DriftReport {
        val sourceUrl = source.url ?: "unknown-source"
        val targetUrl = target.url ?: "unknown-target"
        log.info("Analysing drift: {} -> {}", sourceUrl, targetUrl)

        // 1. Build normalised profile context (single extraction path for both analyses)
        val context = profileContextBuilder.buildContext(source, target)
        val contextJson = objectMapper.writeValueAsString(context)

        // 2. Rule-based pre-analysis (uses the same normalised elements as the LLM)
        val ruleItems = ruleBasedDetector.detect(context)
        log.info("Rule-based detector found {} seed items", ruleItems.size)

        // 3. Include rule-based seeds in the prompt
        val seedJson = if (ruleItems.isNotEmpty()) {
            objectMapper.writeValueAsString(ruleItems)
        } else {
            "[]"
        }

        // 4. Compose prompts
        val systemPrompt = promptTemplateService.loadTemplate("drift-analysis-system.txt")
        val userPrompt = promptTemplateService.loadTemplate(
            "drift-analysis-user.txt",
            mapOf(
                "profileContext" to contextJson,
                "ruleBasedSeeds" to seedJson
            )
        )

        // 5. LLM call (with fallback to rule-based only if AI is unavailable)
        return try {
            val response = llmClient.chat(systemPrompt, userPrompt, temperature = 0.1)

            // 6. Parse and merge results
            val llmReport = parseDriftReport(response, sourceUrl, targetUrl)
            mergeResults(llmReport, ruleItems, sourceUrl, targetUrl)
        } catch (ex: Exception) {
            log.warn(
                "LLM call failed ({}), falling back to rule-based drift detection only ({} items)",
                ex.message, ruleItems.size
            )
            DriftReport(
                sourceProfileCanonical = sourceUrl,
                targetProfileCanonical = targetUrl,
                items = ruleItems
            )
        }
    }

    /**
     * Merge rule-based items with LLM-detected items, de-duplicating by
     * (type, sourcePath, targetPath) triple.
     *
     * **Merge strategy:** Rule items are prioritised — they are deterministic and always
     * correct for structural comparison. LLM items are appended only when they add a
     * drift observation not already covered by the rule detector (e.g. semantic renames,
     * cross-version behavioural differences). This prevents the LLM from overriding a
     * precise rule finding with a less accurate paraphrase.
     */
    private fun mergeResults(
        llmReport: DriftReport,
        ruleItems: List<DriftItem>,
        sourceCanonical: String,
        targetCanonical: String
    ): DriftReport {
        // Rule items are the ground truth — include all of them unconditionally
        val ruleKeys = ruleItems.map { Triple(it.type, it.sourcePath, it.targetPath) }.toSet()

        // LLM items are included only when they report something the rules did not catch
        val uniqueLlmItems = llmReport.items.filter {
            Triple(it.type, it.sourcePath, it.targetPath) !in ruleKeys
        }

        val mergedItems = ruleItems + uniqueLlmItems
        log.info(
            "Merged {} rule items + {} unique LLM items = {} total",
            ruleItems.size, uniqueLlmItems.size, mergedItems.size
        )

        return DriftReport(
            sourceProfileCanonical = sourceCanonical,
            targetProfileCanonical = targetCanonical,
            items = mergedItems
        )
    }

    /* ---------------- Response parsing ---------------- */

    private fun parseDriftReport(
        llmResponse: String,
        sourceCanonical: String,
        targetCanonical: String
    ): DriftReport {
        return try {
            val jsonArray = extractJsonArray(llmResponse)
            val rawItems = objectMapper.readValue(
                jsonArray,
                objectMapper.typeFactory.constructCollectionType(
                    List::class.java,
                    RawDriftItem::class.java
                )
            ) as List<RawDriftItem>

            val items = rawItems.mapIndexed { idx, raw ->
                DriftItem(
                    id = raw.id ?: "drift-${idx + 1}",
                    type = parseDriftType(raw.type),
                    sourcePath = raw.sourcePath ?: "",
                    targetPath = raw.targetPath ?: "",
                    description = raw.description ?: "",
                    severity = raw.severity ?: "WARNING"
                )
            }

            DriftReport(
                sourceProfileCanonical = sourceCanonical,
                targetProfileCanonical = targetCanonical,
                items = items
            ).also {
                log.info("Drift analysis complete: {} items detected", it.totalDrifts)
            }
        } catch (ex: Exception) {
            log.error("Failed to parse drift report from LLM response", ex)
            throw DriftAnalysisException(
                "Could not parse LLM drift-analysis response: ${ex.message}", ex
            )
        }
    }

    /**
     * Extract the outermost JSON array from the LLM response.
     * Handles cases where the model wraps the array in markdown code fences.
     */
    private fun extractJsonArray(response: String): String {
        val stripped = response
            .replace(Regex("```json\\s*"), "")
            .replace(Regex("```\\s*"), "")
            .trim()

        val start = stripped.indexOf('[')
        val end = stripped.lastIndexOf(']')
        if (start >= 0 && end > start) {
            return stripped.substring(start, end + 1)
        }
        return stripped
    }

    private fun parseDriftType(raw: String?): DriftType = when (raw?.uppercase()) {
        "TERMINOLOGY" -> DriftType.TERMINOLOGY
        "EXTENSION" -> DriftType.EXTENSION
        "STRUCTURAL" -> DriftType.STRUCTURAL
        "CARDINALITY" -> DriftType.CARDINALITY
        "VERSION" -> DriftType.VERSION
        else -> DriftType.STRUCTURAL // safe default
    }

    /**
     * Lenient DTO for deserialising LLM output - all fields nullable to tolerate
     * minor schema deviations.
     */
    private data class RawDriftItem(
        val id: String? = null,
        val type: String? = null,
        val sourcePath: String? = null,
        val targetPath: String? = null,
        val description: String? = null,
        val severity: String? = null
    )
}
