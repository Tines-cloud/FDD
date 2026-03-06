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
 * Default [MapValidator] implementing the **Trust-but-Verify** pattern.
 *
 * Algorithm:
 * 1. Attempt to compile the FML using HAPI's [StructureMapUtilities.parse].
 * 2. On success -> return immediately with `syntacticallyValid = true`.
 * 3. On failure -> feed the error back to the LLM (Reflexion) and retry.
 * 4. Repeat up to [FddProperties.ValidationProperties.maxAttempts] times.
 * 5. If all attempts fail -> throw [MapValidationException].
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
        // Raw compilation error per failed attempt - no "Attempt X:" prefix stored here
        // so the same text can be used in logs, prompts, and formatted API responses cleanly.
        val failureLog = mutableListOf<String>()
        // Conversation history for multi-turn reflexion: (userMessage, rawLlmResponse) pairs.
        // Each turn the LLM sees its own previous FML as an AssistantMessage, so follow-up
        // turns only need to send the new error - no need to re-transmit the full FML.
        val conversationHistory = mutableListOf<Pair<String, String>>()

        for (attempt in 1..maxAttempts) {
            log.info("Validation attempt {}/{}", attempt, maxAttempts)

            val compilationResult = tryCompile(currentFml)

            if (compilationResult.success) {
                log.info("FML compiled successfully on attempt {}/{}", attempt, maxAttempts)

                // Always log the prior failures so they remain visible even on success.
                if (attempt > 1) {
                    log.warn(
                        "FML required {} reflexion turn(s) before succeeding. Prior failure(s):",
                        attempt - 1
                    )
                    failureLog.forEachIndexed { idx, err -> log.warn("  [Attempt {}] {}", idx + 1, err) }
                }

                val allMessages = failureLog.mapIndexed { idx, err -> "Attempt ${idx + 1}: $err" } +
                        listOf("Attempt $attempt: Compilation successful")
                return MapGenerationResult(
                    structureMapFml = currentFml,
                    syntacticallyValid = true,
                    validationMessages = allMessages
                )
            }

            // Compilation failed - record the raw error
            val errorMsg = compilationResult.error ?: "Unknown compilation error"
            failureLog.add(errorMsg)
            log.warn("Compilation failed on attempt {}: {}", attempt, errorMsg)

            if (attempt == maxAttempts) {
                // No more attempts left - exit loop and throw below
                break
            }

            // More attempts remaining - continue the reflexion conversation.
            // Turn 1 sends full context; subsequent turns send only the new error since the
            // LLM already sees its own prior FML outputs as AssistantMessages.
            val turnNum = conversationHistory.size + 1
            log.info("Invoking reflexion conversation turn {} after attempt {}/{}", turnNum, attempt, maxAttempts)
            currentFml = reflexion(currentFml, errorMsg, source, target, driftReport, conversationHistory)
        }

        // All attempts exhausted - log each per-attempt failure clearly
        log.error("FML validation exhausted all {} attempts. Per-attempt failure trace:", maxAttempts)
        failureLog.forEachIndexed { idx, err -> log.error("  [Attempt {}] {}", idx + 1, err) }
        val formattedMessages = failureLog.mapIndexed { idx, err -> "Attempt ${idx + 1}: $err" }
        throw MapValidationException(
            message = "StructureMap compilation failed after $maxAttempts attempts",
            attemptErrors = formattedMessages
        )
    }

    /* ---------------- FML Compilation ---------------- */

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
     * - **Turn 1** (empty [conversationHistory]): sends full context - broken FML, current
     *   parse error, profile canonicals, and the drift report as semantic ground truth.
     * - **Turn 2+**: sends only the new error. The LLM already sees its own previous FML
     *   output as an `AssistantMessage`, so there is no need to re-transmit the full code.
     *   This is both more token-efficient and more effective: the model has explicit memory
     *   of what it already tried and can reason about why its previous fix was still wrong.
     *
     * The raw LLM response (not just the extracted FML) is stored back into
     * [conversationHistory] so subsequent turns receive the complete assistant turn.
     */
    private fun reflexion(
        fml: String,
        error: String,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport,
        conversationHistory: MutableList<Pair<String, String>>
    ): String {
        val systemPrompt = promptTemplateService.loadTemplate("reflexion-system.txt")

        val userMessage: String
        val rawResponse: String

        if (conversationHistory.isEmpty()) {
            // ── Turn 1: start a new conversation with the full context ──
            log.info("Reflexion turn 1: starting conversation with full context")
            userMessage = promptTemplateService.loadTemplate(
                "reflexion-user.txt",
                mapOf(
                    "fmlCode" to fml,
                    "errorMessage" to error,
                    "sourceCanonical" to (source.url ?: ""),
                    "targetCanonical" to (target.url ?: ""),
                    "driftReport" to driftReport.toReflexionContext()
                )
            )
            rawResponse = llmClient.chatWithHistory(systemPrompt, emptyList(), userMessage, 0.1)
        } else {
            // ── Turn 2+: follow-up - LLM sees its own prior FML as AssistantMessage ──
            val turnNum = conversationHistory.size + 1
            log.info("Reflexion turn {}: continuing conversation with new error only", turnNum)
            userMessage = buildFollowUpMessage(error)
            rawResponse = llmClient.chatWithHistory(systemPrompt, conversationHistory.toList(), userMessage, 0.1)
        }

        // Append this turn to history so the next call can build on it
        conversationHistory.add(Pair(userMessage, rawResponse))
        return FmlUtils.extractFml(rawResponse)
    }

    /**
     * Short follow-up message for reflexion turn 2+.
     * The full FML is already in the conversation context as the LLM's own AssistantMessage.
     */
    private fun buildFollowUpMessage(error: String): String = """
        The FML you generated above is still not compiling.

        New compilation error:
        $error

        Fix this error. Rules:
        - Do NOT re-introduce any error type from a previous turn.
        - Every rule must end with a semicolon.
        - Do NOT use unsupported keywords (e.g. `default`, `alias` in a `uses` clause).
        - All braces and parentheses must be balanced.
        Output ONLY the corrected FML - no markdown, no explanations.
    """.trimIndent()

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
