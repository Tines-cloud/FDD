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
        val messages = mutableListOf<String>()

        for (attempt in 1..maxAttempts) {
            log.info("Validation attempt {}/{}", attempt, maxAttempts)

            val compilationResult = tryCompile(currentFml)

            if (compilationResult.success) {
                // Compiled successfully - return immediately, no further attempts needed
                messages.add("Attempt $attempt: Compilation successful")
                log.info("FML compiled successfully on attempt {}", attempt)
                return MapGenerationResult(
                    structureMapFml = currentFml,
                    syntacticallyValid = true,
                    validationMessages = messages
                )
            }

            // Compilation failed
            val errorMsg = compilationResult.error ?: "Unknown compilation error"
            messages.add("Attempt $attempt: $errorMsg")
            log.warn("Compilation failed on attempt {}: {}", attempt, errorMsg)

            if (attempt == maxAttempts) {
                // No more attempts left - exit loop and throw below
                break
            }

            // More attempts remaining - ask the LLM to self-correct before the next attempt
            log.info("Invoking reflexion after attempt {}/{}", attempt, maxAttempts)
            currentFml = reflexion(currentFml, errorMsg, source, target, driftReport)
        }

        // All attempts exhausted without a successful compile
        log.error("FML validation failed after {} attempts", maxAttempts)
        throw MapValidationException(
            "StructureMap compilation failed after $maxAttempts attempts. " +
                    "Last error: ${messages.lastOrNull() ?: "unknown"}"
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
     * Feed the failed FML, its compilation error, and the original drift report back to the LLM
     * for self-correction.
     *
     * Including the drift report means the LLM can verify that any fix it applies still covers
     * the semantic differences it was originally asked to map. This closes the gap where a
     * purely syntax-focused fix silently drops or alters a mapping rule.
     */
    private fun reflexion(
        fml: String,
        error: String,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): String {
        log.info("Invoking LLM reflexion for error correction")

        val systemPrompt = promptTemplateService.loadTemplate("reflexion-system.txt")
        val userPrompt = promptTemplateService.loadTemplate(
            "reflexion-user.txt",
            mapOf(
                "fmlCode" to fml,
                "errorMessage" to error,
                "sourceCanonical" to (source.url ?: ""),
                "targetCanonical" to (target.url ?: ""),
                "driftReport" to driftReport.toReflexionContext()
            )
        )

        val response = llmClient.chat(systemPrompt, userPrompt, temperature = 0.1)
        return FmlUtils.extractFml(response)
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
