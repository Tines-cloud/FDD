package com.example.fdd.validation.impl

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import com.example.fdd.ai.LlmClient
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.config.FddProperties
import com.example.fdd.exception.MapValidationException
import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.util.FmlUtils
import com.example.fdd.validation.MapValidator
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.r4.context.IWorkerContext
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.StructureDefinition
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Validates FML code by compiling it with HAPI-FHIR and auto-repairs errors using an LLM.
 *
 * How it works:
 * 1. Scan the FML for ALL errors using free HAPI re-parse passes (no LLM cost).
 * 2. If no errors, return success.
 * 3. If errors found, send them ALL to the LLM in ONE call to fix everything at once.
 * 4. Repeat up to maxAttempts cycles.
 * 5. If all cycles fail, throw an exception with the full error history.
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
        // All errors from every cycle, each prefixed with "[Cycle N]" for grouping in output.
        val failureLog = mutableListOf<String>()
        // Conversation thread with the LLM: list of (userMessage, llmResponse) pairs.
        val conversationHistory = mutableListOf<Pair<String, String>>()
        // Errors from each past cycle, used to warn the LLM not to revert to old broken code.
        val errorsByCycle = mutableListOf<List<String>>()
        // FML text before each LLM fix attempt, used to detect line-level regressions.
        val fmlByCycle = mutableListOf<String>()

        // Pre-process: rewrite known LLM syntax anti-patterns before first validation (no LLM cost)
        currentFml = sanitizeFml(currentFml)

        for (cycle in 1..maxAttempts) {
            log.info("Repair cycle {}/{}: scanning FML for ALL errors (no LLM cost)", cycle, maxAttempts)

            // Auto-fix "Complex rules must have an explicit name" errors (free, no LLM cost)
            currentFml = autoFixRuleNames(currentFml)

            // -- Scan for ALL errors (free, no LLM cost) --
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

            // Detect line-level regressions: LLM "fixed" a line but introduced a different error on the same line
            val regressions = if (errorsByCycle.isNotEmpty()) {
                detectRegressions(fmlByCycle.last(), currentFml, errorsByCycle.last(), errors)
            } else emptyList()
            if (regressions.isNotEmpty()) {
                log.warn("Regression detected: {} line(s) still have errors after LLM fix attempt", regressions.size)
                regressions.forEach { r ->
                    log.warn(
                        "  Line {}: was [{}] -> now [{}]",
                        r.lineNum,
                        r.previousError,
                        r.currentError
                    )
                }
            }

            if (cycle == maxAttempts) break

            // Save state before LLM fix for regression detection in the next cycle
            fmlByCycle.add(currentFml)

            // -- Send ALL errors to LLM in ONE call --
            val turnNum = conversationHistory.size + 1
            log.info("Reflexion turn {}: sending ALL {} error(s) in one LLM call", turnNum, errors.size)
            currentFml = reflexion(
                currentFml,
                errors,
                source,
                target,
                driftReport,
                conversationHistory,
                errorsByCycle.toList(),
                regressions
            )
            // Save this cycle's errors so the next follow-up can warn the LLM not to revert
            errorsByCycle.add(errors)
        }

        // All cycles exhausted - log the full trace
        log.error("FML validation exhausted all {} repair cycles. Full error trace:", maxAttempts)
        failureLog.forEach { entry -> log.error("  {}", entry) }
        throw MapValidationException(
            message = "StructureMap compilation failed after $maxAttempts attempts",
            attemptErrors = failureLog.toList()
        )
    }

    /* ---------- FML Compilation + Error Scanning ---------- */

    /** A line-level regression: the LLM's fix replaced one error with another on the same line. */
    private data class LineRegression(
        val lineNum: Int,
        val previousError: String,
        val currentError: String,
        val previousLineContent: String,
        val currentLineContent: String
    )

    /** Extract the 1-based line number from a HAPI error message like "at 17, 7:". */
    private fun extractLineNumber(error: String): Int? =
        Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(error)
            ?.groupValues?.get(1)?.toIntOrNull()

    /**
     * Compare errors from the previous cycle with errors from the current cycle.
     * If the same line number has errors in both cycles (even different errors),
     * it means the LLM's "fix" replaced one error with another - a regression.
     */
    private fun detectRegressions(
        previousFml: String,
        currentFml: String,
        previousErrors: List<String>,
        currentErrors: List<String>
    ): List<LineRegression> {
        val prevLines = previousFml.lines()
        val currLines = currentFml.lines()
        val prevByLine = previousErrors.mapNotNull { err -> extractLineNumber(err)?.let { it to err } }.toMap()
        val currByLine = currentErrors.mapNotNull { err -> extractLineNumber(err)?.let { it to err } }.toMap()

        return currByLine.mapNotNull { (lineNum, currErr) ->
            val prevErr = prevByLine[lineNum]
            if (prevErr != null && prevErr != currErr) {
                LineRegression(
                    lineNum = lineNum,
                    previousError = prevErr,
                    currentError = currErr,
                    previousLineContent = prevLines.getOrElse(lineNum - 1) { "" },
                    currentLineContent = currLines.getOrElse(lineNum - 1) { "" }
                )
            } else null
        }
    }

    /** Try to compile the FML. Returns success/failure with the error message if any. */
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
     * Find ALL syntax errors in the FML without any LLM calls.
     *
     * HAPI stops at the first error it finds. To get around this, we:
     * 1. Parse the FML.
     * 2. When it fails, record the error, remove the bad line, and parse again.
     * 3. Repeat until it compiles cleanly or we can't find the bad line.
     *
     * The working copy is thrown away. Only the error messages are returned.
     */
    private fun collectAllErrors(fml: String): List<String> {
        val errors = mutableListOf<String>()
        val lines = fml.lines().toMutableList()

        // Each pass removes one line, so we can never need more passes than lines.
        repeat(lines.size) {
            val currentText = lines.joinToString("\n")
            val result = tryCompile(currentText)
            if (result.success) return errors

            val errorMsg = result.error ?: return errors
            errors.add(errorMsg)

            // Try to find the bad line by its line number (e.g. "at 15, 7:")
            val lineNum = Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(errorMsg)
                ?.groupValues?.get(1)?.toIntOrNull()

            if (lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
                lines.removeAt(lineNum - 1)
                return@repeat
            }

            // If no line number, try to find the bad keyword in the FML text
            val badToken = Regex("""['"](\w+)['"]""")
                .findAll(errorMsg)
                .lastOrNull()?.groupValues?.get(1)

            if (badToken != null) {
                val idx = lines.indexOfFirst { it.contains(badToken) }
                if (idx >= 0) {
                    lines.removeAt(idx)
                    return@repeat
                }
            }

            // Could not find which line caused the error - stop scanning
            return errors
        }
        return errors
    }

    /**
     * Auto-fix "Complex rules must have an explicit name" errors.
     * HAPI requires every rule with -> to end with a quoted name before the semicolon.
     * This fixes them deterministically without any LLM cost.
     */
    /**
     * Rewrites known LLM-generated FML anti-patterns into valid HAPI FML syntax.
     * Runs once before the first validation cycle - zero LLM cost.
     *
     * Anti-pattern 1: FHIRPath-style `.where()` in source expressions.
     *   INVALID:  src.extension.where(url='http://...') as alias ->
     *   VALID:    src.extension as alias where alias.url = 'http://...' ->
     *
     * Anti-pattern 2: `.where()` with property shorthand (no explicit target field name).
     *   INVALID:  src.extension.where(url='http://...') ->
     */
    private fun sanitizeFml(fml: String): String {
        var result = fml
        var rewrites = 0

        // Pattern: <base>.where(<prop>='<value>') as <alias>
        // Rewrite:  <base> as <alias> where <alias>.<prop> = '<value>'
        val withAlias = Regex(
            """(\S+)\.where\((\w+)='([^']*)'\)\s+as\s+(\w+)"""
        )
        result = withAlias.replace(result) { m ->
            val base = m.groupValues[1]
            val prop = m.groupValues[2]
            val value = m.groupValues[3]
            val alias = m.groupValues[4]
            rewrites++
            "$base as $alias where $alias.$prop = '$value'"
        }

        // Pattern: <base>.where(<prop>="<value>") as <alias>  (double-quoted value)
        val withAliasDouble = Regex(
            """(\S+)\.where\((\w+)=\"([^\"]*)\"\)\s+as\s+(\w+)"""
        )
        result = withAliasDouble.replace(result) { m ->
            val base = m.groupValues[1]
            val prop = m.groupValues[2]
            val value = m.groupValues[3]
            val alias = m.groupValues[4]
            rewrites++
            "$base as $alias where $alias.$prop = '$value'"
        }

        if (rewrites > 0) {
            log.info("sanitizeFml: rewrote {} .where() anti-pattern(s) to valid FML", rewrites)
        }
        return result
    }

    private fun autoFixRuleNames(fml: String): String {
        var currentFml = fml
        var fixed = 0

        // Keep fixing until no more "Complex rules must have an explicit name" errors
        repeat(200) {
            val result = tryCompile(currentFml)
            if (result.success) {
                if (fixed > 0) log.info("Auto-fixed {} rule name error(s) without LLM cost", fixed)
                return currentFml
            }

            val error = result.error ?: return currentFml
            if (!error.contains("Complex rules must have an explicit name")) {
                if (fixed > 0) log.info("Auto-fixed {} rule name error(s) without LLM cost", fixed)
                return currentFml
            }

            val lineNum = Regex("""\bat\s+(\d+)\s*,""").find(error)
                ?.groupValues?.get(1)?.toIntOrNull() ?: return currentFml

            val lines = currentFml.lines().toMutableList()
            if (lineNum < 1 || lineNum > lines.size) return currentFml

            val line = lines[lineNum - 1]
            val trimmed = line.trimEnd()

            // Only fix lines ending with that don't already have a "name" before
            if (!trimmed.endsWith(";")) return currentFml
            if (Regex(""""[^"]*"\s*;$""").containsMatchIn(trimmed)) return currentFml

            val indent = line.length - line.trimStart().length
            val ruleName = "rule_line$lineNum"
            lines[lineNum - 1] = " ".repeat(indent) + trimmed.dropLast(1).trimEnd() + " \"$ruleName\";"
            currentFml = lines.joinToString("\n")
            fixed++
        }

        if (fixed > 0) log.info("Auto-fixed {} rule name error(s) without LLM cost", fixed)
        return currentFml
    }

    /** Create a basic HAPI-FHIR context for FML parsing. */
    private fun createWorkerContext(): IWorkerContext {
        val validationSupport = ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContext)
        )
        return HapiWorkerContext(fhirContext, validationSupport)
    }

    /* ---------- LLM Repair (Reflexion) ---------- */

    /**
     * Ask the LLM to fix the broken FML.
     *
     * Turn 1: sends the full FML, all errors, profile URLs, and drift report.
     * Turn 2+: sends all errors AND the current FML with error-line highlights,
     *          so the LLM can see exactly what is broken.
     *
     * All errors for a cycle are sent in a single call.
     */
    private fun reflexion(
        fml: String,
        errors: List<String>,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport,
        conversationHistory: MutableList<Pair<String, String>>,
        allPriorErrors: List<List<String>>,
        regressions: List<LineRegression> = emptyList()
    ): String {
        val systemPrompt = promptTemplateService.loadTemplate("reflexion-system.txt")
        val errorList = errors.mapIndexed { idx, err -> "  ${idx + 1}. $err" }.joinToString("\n")

        // Detect oscillation: current error already appeared in a prior cycle
        val oscillatingErrors = errors.filter { err ->
            allPriorErrors.any { priorCycle -> err in priorCycle }
        }
        val isOscillating = oscillatingErrors.isNotEmpty()
        val hasRegressions = regressions.isNotEmpty()
        val temperature = if (isOscillating || hasRegressions) 0.2 else 0.1
        if (isOscillating) {
            log.warn(
                "Oscillation detected - {} error(s) already seen in prior cycles, raising temperature to {}",
                oscillatingErrors.size,
                temperature
            )
        }
        if (hasRegressions) {
            log.warn(
                "Line-level regression detected on {} line(s), raising temperature to {}",
                regressions.size,
                temperature
            )
        }

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
            rawResponse = llmClient.chatWithHistory(systemPrompt, emptyList(), userMessage, temperature)
        } else {
            val turnNum = conversationHistory.size + 1
            log.info(
                "Reflexion turn {}: presenting {} error(s) to LLM with full FML context (oscillation={}, regressions={})",
                turnNum,
                errors.size,
                isOscillating,
                regressions.size
            )
            userMessage = buildFollowUpMessage(fml, errors, allPriorErrors, oscillatingErrors, regressions)
            rawResponse =
                llmClient.chatWithHistory(systemPrompt, conversationHistory.toList(), userMessage, temperature)
        }

        conversationHistory.add(Pair(userMessage, rawResponse))
        return FmlUtils.extractFml(rawResponse)
    }

    /**
     * Build a follow-up message that includes the CURRENT FML, highlights the
     * exact broken lines, lists current and prior errors, and (if oscillation
     * is detected) forces the LLM to take a completely different approach.
     */
    private fun buildFollowUpMessage(
        currentFml: String,
        errors: List<String>,
        allPriorErrors: List<List<String>>,
        oscillatingErrors: List<String>,
        regressions: List<LineRegression> = emptyList()
    ): String {
        val errorList = errors.mapIndexed { idx, err -> "  ${idx + 1}. $err" }.joinToString("\n")

        // Build line-level snippets around each error so the LLM sees the broken code
        val fmlLines = currentFml.lines()
        val snippets = errors.mapNotNull { err ->
            val lineNum = Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(err)
                ?.groupValues?.get(1)?.toIntOrNull()
            if (lineNum != null && lineNum in 1..fmlLines.size) {
                val start = maxOf(0, lineNum - 4)
                val end = minOf(fmlLines.size - 1, lineNum + 2)
                val snippet = (start..end).joinToString("\n") { idx ->
                    val marker = if (idx == lineNum - 1) ">>>" else "   "
                    "$marker ${idx + 1}: ${fmlLines[idx]}"
                }
                "Error at line $lineNum:\n$snippet"
            } else null
        }
        val snippetSection = if (snippets.isNotEmpty()) {
            "\n## ERROR LOCATION IN YOUR CURRENT FML\n" + snippets.joinToString("\n\n")
        } else ""

        val oscillationSection = if (oscillatingErrors.isNotEmpty()) {
            val errLines = oscillatingErrors.joinToString("\n") { "  - $it" }
            """

## OSCILLATION DETECTED - You are reverting to previously broken code!
These exact errors already appeared in an earlier cycle:
$errLines
Your previous approaches for these lines ALL failed. You MUST use a COMPLETELY
DIFFERENT syntax or structure for these lines. Do NOT reuse any code from your
earlier attempts. Try a fundamentally different way to express the same mapping."""
        } else ""

        val priorSection = if (allPriorErrors.isNotEmpty()) {
            val priorLines = buildString {
                allPriorErrors.forEachIndexed { cycleIdx, cycleErrors ->
                    appendLine("  Cycle ${cycleIdx + 1}:")
                    cycleErrors.forEach { err -> appendLine("    - $err") }
                }
            }
            "\n## PREVIOUSLY SEEN ERRORS (DO NOT reintroduce ANY of these)\n$priorLines"
        } else ""

        val regressionSection = if (regressions.isNotEmpty()) {
            val regLines = regressions.joinToString("\n\n") { r ->
                """  LINE ${r.lineNum} - YOUR FIX REPLACED ONE ERROR WITH ANOTHER:
    Before your fix: ${r.previousLineContent.trim()}
    After your fix:  ${r.currentLineContent.trim()}
    Previous error:  ${r.previousError}
    Current error:   ${r.currentError}
    BOTH versions are wrong. You need a COMPLETELY DIFFERENT approach for this line."""
            }
            """

## REGRESSION DETECTED - Your fix introduced NEW errors on the SAME lines!
Your previous fix attempt did NOT make progress. It replaced errors with
different errors on the same lines. This means both your old and new code
for these lines are syntactically wrong.

$regLines

You MUST write these lines using a fundamentally different FML construct.
Do NOT try minor variations of the same approach - use a different syntax entirely."""
        } else ""

        return """
Your last FML attempt has ${errors.size} compilation error(s).

## CURRENT ERRORS (fix ALL of these)
$errorList
$snippetSection
$regressionSection
$oscillationSection
$priorSection

## YOUR CURRENT FML (this is your latest code that has the errors above)
```fml
$currentFml
```

## Rules
- Fix every error listed under CURRENT ERRORS.
- Your fix MUST NOT introduce any new compilation errors on any line.
- Do NOT reintroduce any error from PREVIOUSLY SEEN ERRORS.
- If REGRESSION DETECTED is shown, your previous approach for those lines failed TWICE.
  Use a COMPLETELY DIFFERENT FML construct - not a minor variation.
- The lines marked with >>> in ERROR LOCATION show exactly where the error is.
- "Complex rules must have an explicit name" means: every rule that uses
  `then` or maps source->target MUST end with a quoted name string before
  the semicolon:  src.x as s -> tgt.y = s "rule-name";
- Every rule must end with a semicolon.
- Do NOT use unsupported keywords (`default`, `alias` in `uses` clauses).
- All braces and parentheses must be balanced.
Output ONLY the corrected FML - no markdown, no explanations.
        """.trimIndent()
    }

    /** Turn the drift report into a short summary for the LLM prompt. */
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

    /* ---------- Internal DTOs ---------- */

    private data class CompilationResult(
        val success: Boolean,
        val error: String? = null
    )
}