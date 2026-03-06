package com.example.fdd.validation

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.config.FddProperties
import com.example.fdd.exception.MapValidationException
import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.util.FmlUtils
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.r4.context.IWorkerContext
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.StructureDefinition
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Default [MapValidator] implementing the **Batch-Repair** pattern.
 *
 * Algorithm per repair cycle:
 * 1. Run [collectAllErrors] - a free loop of HAPI re-parse passes that discovers
 *    EVERY syntax error in the FML without any LLM calls.
 * 2. If no errors -> return success immediately.
 * 3. If errors found -> send ONE LLM call with the complete error list so the model
 *    can fix all problems simultaneously.
 * 4. Repeat up to [FddProperties.ValidationProperties.maxAttempts] cycles.
 * 5. If all cycles fail -> throw [MapValidationException] with the full error trace.
 *
 * Cost profile: N errors in one cycle = 1 LLM call (not N calls).
 */
@Component
class DefaultMapValidator(
    private val fhirContext: FhirContext,
    private val llmClient: LlmClient,
    private val promptTemplateService: PromptTemplateService,
    private val properties: FddProperties
) : MapValidator {

    private val log = LoggerFactory.getLogger(javaClass)
    private val maxAttempts: Int get() = properties.validation.maxAttempts

    @Timed(value = "fdd.map.validation.duration", description = "Trust-but-Verify validation duration")
    override fun validateAndRepair(
        fmlCode: String,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): MapGenerationResult {
        var currentFml = fmlCode
        // Flat list of "[Cycle N] <error>" entries covering every error from every cycle.
        // One entry per individual error found, prefixed with its cycle number so the
        // output store can group them for readable reporting.
        val failureLog = mutableListOf<String>()
        // Conversation history for multi-turn reflexion: (userMessage, rawLlmResponse) pairs.
        val conversationHistory = mutableListOf<Pair<String, String>>()

        for (cycle in 1..maxAttempts) {
            log.info("Repair cycle {}/{}: scanning FML for ALL errors (no LLM cost)", cycle, maxAttempts)

            // ── Phase 1: collect ALL errors via free HAPI re-parse passes ──────────────
            val errors = collectAllErrors(currentFml)

            if (errors.isEmpty()) {
                log.info("FML compiled successfully in cycle {}/{}", cycle, maxAttempts)
                if (cycle > 1) {
                    log.warn("FML required {} repair cycle(s). Error trace:", cycle - 1)
                    failureLog.forEach { entry -> log.warn("  {}", entry) }
                }
                val allMessages = failureLog.toMutableList().also {
                    it.add("[Cycle $cycle] Compilation successful")
                }
                return MapGenerationResult(
                    structureMapFml = currentFml,
                    syntacticallyValid = true,
                    validationMessages = allMessages
                )
            }

            // Record every individual error for this cycle
            errors.forEach { err -> failureLog.add("[Cycle $cycle] $err") }
            log.warn("Cycle {}: found {} error(s) in total:", cycle, errors.size)
            errors.forEachIndexed { idx, err -> log.warn("  [{}/{}] {}", idx + 1, errors.size, err) }

            if (cycle == maxAttempts) break

            // ── Phase 2: ONE LLM call to fix ALL errors found this cycle ─────────────
            val turnNum = conversationHistory.size + 1
            log.info("Reflexion turn {}: sending ALL {} error(s) in one LLM call", turnNum, errors.size)
            currentFml = reflexion(currentFml, errors, source, target, driftReport, conversationHistory)
        }

        // All cycles exhausted - log the full trace
        log.error("FML validation exhausted all {} repair cycles. Full error trace:", maxAttempts)
        failureLog.forEach { entry -> log.error("  {}", entry) }
        throw MapValidationException(
            message = "StructureMap compilation failed after $maxAttempts attempts",
            attemptErrors = failureLog.toList()
        )
    }

    /* ---------------- FML Compilation + Full-Error Scan ---------------- */

    /**
     * Attempt to parse/compile FML text using HAPI-FHIR's [StructureMapUtilities].
     */
    private fun tryCompile(fml: String): CompilationResult {
        return try {
            val workerContext = createWorkerContext()
            val utilities = StructureMapUtilities(workerContext)
            utilities.parse(fml, "GeneratedMap")
            CompilationResult(success = true)
        } catch (ex: Exception) {
            CompilationResult(success = false, error = ex.message ?: ex.javaClass.simpleName)
        }
    }

    /**
     * Run repeated HAPI parse passes to discover **every** syntax error in [fml].
     *
     * HAPI's parser is fail-fast - it throws on the first error it encounters and stops.
     * This method works around that limitation at zero LLM cost:
     *
     * 1. Parse the current working copy of the FML.
     * 2. On error: record the message, extract the 1-based line number from the
     *    HAPI error text (`"Error in GeneratedMap at LINE, COL: ..."`), remove
     *    that line from the working copy, and re-parse.
     * 3. Repeat until either the working copy compiles cleanly or the error does
     *    not carry a parseable line number (safety exit to avoid infinite loops).
     *
     * The returned list contains the raw HAPI error text for every error found
     * in the **original** FML.  The working copy used for scanning is discarded;
     * only the error messages are returned.
     *
     * @return Ordered list of all error messages found. Empty = no errors.
     */
    private fun collectAllErrors(fml: String): List<String> {
        val errors = mutableListOf<String>()
        val lines = fml.lines().toMutableList()
        val maxPasses = 50  // safety cap against pathological input

        repeat(maxPasses) {
            val currentText = lines.joinToString("\n")
            val result = tryCompile(currentText)
            if (result.success) return errors

            val errorMsg = result.error ?: return errors
            errors.add(errorMsg)

            // HAPI error format: "Error in GeneratedMap at LINE, COL: <message>"
            val lineNum = Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(errorMsg)
                ?.groupValues?.get(1)?.toIntOrNull()

            if (lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
                lines.removeAt(lineNum - 1)
            } else {
                // Cannot identify the offending line - stop scanning
                return errors
            }
        }
        return errors
    }

    /**
     * Create a minimal [IWorkerContext] backed by HAPI-FHIR's default R4
     * validation support. This is sufficient for FML syntax parsing/compilation.
     * A full context (with loaded IG profiles) would be required for executing transforms.
     */
    private fun createWorkerContext(): IWorkerContext {
        val validationSupport = ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContext)
        )
        return HapiWorkerContext(fhirContext, validationSupport)
    }

    /* ---------------- Reflexion ---------------- */

    /**
     * Ask the LLM to repair broken FML using a **stateful conversation thread**.
     *
     * Unlike the old single-error approach, each call passes the **complete list** of all
     * errors found in the current cycle so the model can fix everything simultaneously.
     *
     * - **Turn 1**: sends full context - the broken FML, all N errors, profile canonicals,
     *   and the drift report as semantic ground truth.
     * - **Turn 2+**: sends only the new error list. The LLM already sees its own previous
     *   FML output as an `AssistantMessage` so no token cost for re-transmitting the code.
     */
    private fun reflexion(
        fml: String,
        errors: List<String>,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport,
        conversationHistory: MutableList<Pair<String, String>>
    ): String {
        val systemPrompt = promptTemplateService.loadTemplate("reflexion-system.txt")
        val errorList = errors.mapIndexed { idx, err -> "  ${idx + 1}. $err" }.joinToString("\n")

        val userMessage: String
        val rawResponse: String

        if (conversationHistory.isEmpty()) {
            log.info("Reflexion turn 1: presenting {} error(s) to LLM with full context", errors.size)
            userMessage = promptTemplateService.loadTemplate(
                "reflexion-user.txt",
                mapOf(
                    "fmlCode" to fml,
                    "errorCount" to errors.size.toString(),
                    "errorList" to errorList,
                    "sourceCanonical" to (source.url ?: ""),
                    "targetCanonical" to (target.url ?: ""),
                    "driftReport" to driftReport.toReflexionContext()
                )
            )
            rawResponse = llmClient.chatWithHistory(systemPrompt, emptyList(), userMessage, 0.1)
        } else {
            val turnNum = conversationHistory.size + 1
            log.info("Reflexion turn {}: presenting {} new error(s) to LLM", turnNum, errors.size)
            userMessage = buildFollowUpMessage(errors)
            rawResponse = llmClient.chatWithHistory(systemPrompt, conversationHistory.toList(), userMessage, 0.1)
        }

        conversationHistory.add(Pair(userMessage, rawResponse))
        return FmlUtils.extractFml(rawResponse)
    }

    /**
     * Short follow-up message for reflexion turn 2+.
     * The full FML is already in the conversation as the LLM's own AssistantMessage.
     * We only send the new error list - the LLM fixes all of them in one pass.
     */
    private fun buildFollowUpMessage(errors: List<String>): String {
        val errorList = errors.mapIndexed { idx, err -> "  ${idx + 1}. $err" }.joinToString("\n")
        return """
            The FML you generated above still has ${errors.size} compilation error(s).

            Fix ALL of the following errors in a single complete rewrite:
$errorList

            Rules:
            - Fix every error listed above simultaneously - do not fix one and break another.
            - Do NOT re-introduce any error type from a previous turn.
            - Every rule must end with a semicolon.
            - Do NOT use unsupported keywords (e.g. `default`, `alias` in a `uses` clause).
            - All braces and parentheses must be balanced.
            Output ONLY the corrected FML - no markdown, no explanations.
        """.trimIndent()
    }

    /**
     * Converts the drift report into a compact, readable summary for inclusion in the
     * reflexion prompt. The LLM uses this as semantic ground truth when rewriting broken FML.
     */
    private fun DriftReport.toReflexionContext(): String {
        if (items.isEmpty()) return "No drift items detected."
        val lines = mutableListOf(
            "Source: $sourceProfileCanonical",
            "Target: $targetProfileCanonical",
            "$totalDrifts drift item(s) the FML must express:"
        )
        items.forEachIndexed { i, item ->
            lines += "${i + 1}. [${item.type}] [${item.severity}] ${item.sourcePath} -> ${item.targetPath}: ${item.description}"
        }
        return lines.joinToString("\n")
    }

    /* ---------------- Internal DTOs ---------------- */

    private data class CompilationResult(
        val success: Boolean,
        val error: String? = null
    )
}
