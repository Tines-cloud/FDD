package com.example.fdd.service.impl

import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.exception.DriftAnalysisException
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.model.DriftType
import com.example.fdd.service.DriftAnalyzer
import com.example.fdd.service.RuleBasedDriftDetector
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

/**
 * Default [com.example.fdd.service.DriftAnalyzer] implementation - **AI is the primary detector**.
 *
 * Pipeline role:
 * - The LLM is the authoritative semantic drift detector. It receives the full normalised
 *   profile context plus the rule-based items as **seed hints** (not gospel). The LLM is
 *   expected to confirm, refine, expand, or reject seeds and to find drift the rules missed.
 * - The deterministic [com.example.fdd.service.RuleBasedDriftDetector] is a safety net only:
 *   it catches structural fundamentals (cardinality, version mismatch) that must never be
 *   missed if the LLM hallucinates.
 *
 * Steps:
 * 1. Build a [com.example.fdd.model.ProfileContext] from both profiles.
 * 2. Run [com.example.fdd.service.RuleBasedDriftDetector] to produce SEED items.
 * 3. Send full profile context + seeds to the LLM.
 * 4. Parse LLM JSON response - these items are the FINAL drift report
 *    (`source = "ai"` for new discoveries, `source = "hybrid"` when a seed triple matched).
 * 5. Rule seeds the LLM confirmed are promoted to `source = "hybrid"`.
 *    Rule seeds the LLM did NOT re-emit are backfilled as `source = "rule"` (safety-net).
 *    This prevents false negatives from rule-only structural drifts the LLM may omit.
 *    `source` provenance lets evaluation code distinguish each type of finding.
 *
 * Fallback: if the LLM call fails entirely, return rule-only items with a clear warning.
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
            .map { it.copy(source = "rule") }
        log.info("Rule-based detector produced {} SEED items (hints for the LLM)", ruleItems.size)

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

            // 6. Parse LLM response - these are the PRIMARY drift items
            val llmReport = parseDriftReport(response, sourceUrl, targetUrl)
            mergeResults(llmReport, ruleItems, sourceUrl, targetUrl)
        } catch (ex: Exception) {
            log.warn(
                "LLM call failed ({}), falling back to rule-based seeds only ({} items). " +
                        "Output will contain rule items only - re-run when the LLM is available.",
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
     * Merge LLM-detected items (PRIMARY, gatekeeper) with rule-based seeds.
     *
     * **Strategy - the LLM is the AUTHORITATIVE filter:**
     *
     * 1. The LLM has already been instructed (drift-analysis prompts) to confirm,
     *    refine, or REJECT each rule-based seed and to add its own discoveries.
     * 2. The output is therefore the LLM's emitted set, NOTHING ELSE. Rule seeds
     *    that the LLM omitted are treated as REJECTED (not silently re-added),
     *    because the rule-based detector is known to over-report (`MustSupport`,
     *    `uscdi-requirement` markers, slice-name noise) and the user wants only
     *    the AI-curated set as the final report.
     * 3. Provenance: each LLM item whose `(type, sourcePath, targetPath)` triple
     *    matches a rule seed is marked `source = "hybrid"` (rule-confirmed).
     *    Pure LLM discoveries are marked `source = "ai"`.
     *
     * The fallback when the LLM call fails entirely is handled in [analyzeDrift]
     * - in that case the rule items are returned with a clear warning log.
     *
     * Path normalisation: the LLM and the rule detector sometimes differ between
     * `null` and `""` for one-sided drifts (e.g. extension added in target only).
     * Triple matching normalises both to "" so a hybrid match is not missed.
     */
    private fun mergeResults(
        llmReport: DriftReport,
        ruleItems: List<DriftItem>,
        sourceCanonical: String,
        targetCanonical: String
    ): DriftReport {
        fun key(item: DriftItem) = Triple(item.type, item.sourcePath.trim(), item.targetPath.trim())

        // Index rule seeds by normalised triple for fast lookup
        val ruleByTriple = ruleItems.associateBy { key(it) }
        val matchedSeedTriples = mutableSetOf<Triple<DriftType, String, String>>()

        // Step 1: Emit LLM items, marking provenance based on whether a rule seed corroborated it.
        val llmEmitted = llmReport.items.map { llmItem ->
            val triple = key(llmItem)
            if (triple in ruleByTriple) {
                matchedSeedTriples.add(triple)
                llmItem.copy(source = "hybrid")
            } else {
                llmItem.copy(source = "ai")
            }
        }

        // Step 2: Backfill unmatched rule seeds as source="rule" (safety net).
        // Rule seeds whose triple was NOT confirmed/overridden by the LLM are appended.
        // This preserves the deterministic baseline and prevents silent data loss.
        val unmatchedSeeds = ruleItems.filter { key(it) !in matchedSeedTriples }

        val finalItems = llmEmitted + unmatchedSeeds

        val aiOnly = llmEmitted.count { it.source == "ai" }
        val hybrid = llmEmitted.count { it.source == "hybrid" }
        val ruleOnly = unmatchedSeeds.size
        log.info(
            "Drift analysis complete - {} item(s) in final report: {} AI-discovered, {} hybrid (LLM-confirmed seed), {} rule-only (safety net backfill).",
            finalItems.size, aiOnly, hybrid, ruleOnly
        )

        return DriftReport(
            sourceProfileCanonical = sourceCanonical,
            targetProfileCanonical = targetCanonical,
            items = finalItems
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
                    id = raw.id ?: "ai-${idx + 1}",
                    type = parseDriftType(raw.type),
                    sourcePath = raw.sourcePath ?: "",
                    targetPath = raw.targetPath ?: "",
                    description = raw.description ?: "",
                    severity = raw.severity ?: "WARNING",
                    source = "ai"
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
        if (start in 0..<end) {
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