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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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
    private val repairTraceDir: Path = Paths.get("output", "repair-trace")

    @Timed(value = "fdd.map.validation.duration", description = "Trust-but-Verify validation duration")
    override fun validateAndRepair(
        fmlCode: String,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): MapGenerationResult {
        var currentFml = fmlCode
        val runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
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
        persistFmlSnapshot(runId, 0, "initial-llm", currentFml)

        for (cycle in 1..maxAttempts) {
            log.info("Repair cycle {}/{}: scanning FML for ALL errors (no LLM cost)", cycle, maxAttempts)

            // Auto-fix syntax-level errors deterministically — missing semicolons, unquoted rule
            // names, unicode quotes, etc.  These never need AI.
            // MUST run BEFORE autoFixRuleNames: if a rule is missing both its semicolon AND its
            // quoted name, autoFixRuleNames exits early (line doesn't end with ";"). Running
            // autoFixSyntaxErrors first adds the semicolon, then autoFixRuleNames can add the name.
            currentFml = autoFixSyntaxErrors(currentFml)

            // Auto-fix "Complex rules must have an explicit name" errors (free, no LLM cost)
            currentFml = autoFixRuleNames(currentFml)
            persistFmlSnapshot(runId, cycle, "post-deterministic-fixes", currentFml)

            // -- Scan for ALL errors (free, no LLM cost) --
            var errors = collectAllErrors(currentFml)

            // Never route syntax/parsing errors to LLM. Keep them on the deterministic path.
            var syntaxErrors = errors.filter { isDeterministicSyntaxError(it) }
            if (syntaxErrors.isNotEmpty()) {
                val syntaxFixed = autoFixRuleNames(
                    autoFixSyntaxErrors(
                        applyDeterministicFixesForCollectedSyntaxErrors(currentFml, syntaxErrors)
                    )
                )
                if (syntaxFixed != currentFml) {
                    currentFml = syntaxFixed
                    persistFmlSnapshot(runId, cycle, "post-syntax-deterministic", currentFml)
                    errors = collectAllErrors(currentFml)
                    syntaxErrors = errors.filter { isDeterministicSyntaxError(it) }
                }

                // If syntax still remains, deterministically prune those lines rather than looping.
                if (syntaxErrors.isNotEmpty()) {
                    val prunedSyntax = pruneUnresolvedSyntaxRules(currentFml, syntaxErrors)
                    if (prunedSyntax != currentFml) {
                        currentFml = autoFixRuleNames(autoFixSyntaxErrors(prunedSyntax))
                        persistFmlSnapshot(runId, cycle, "post-syntax-prune", currentFml)
                        errors = collectAllErrors(currentFml)
                        syntaxErrors = errors.filter { isDeterministicSyntaxError(it) }
                    }
                }
            }

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

            // If the same NON-syntax error survived one full LLM repair attempt, do not let
            // it loop forever. Prune the stubborn rule line from the map and re-validate.
            // This keeps the map progressing instead of wasting further LLM calls on a line
            // that is not converging.
            if (errorsByCycle.isNotEmpty()) {
                val prunedFml = pruneRepeatedUnresolvedRules(currentFml, errorsByCycle.last(), errors)
                if (prunedFml != currentFml) {
                    currentFml = autoFixRuleNames(autoFixSyntaxErrors(prunedFml))
                    persistFmlSnapshot(runId, cycle, "post-stubborn-prune", currentFml)
                    errors = collectAllErrors(currentFml)

                    if (errors.isEmpty()) {
                        log.info("FML compiled successfully in cycle {}/{} after pruning repeated non-syntax rule(s)", cycle, maxAttempts)
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
                }
            }

            // Record every individual error for this cycle
            errors.forEach { err -> failureLog.add("[Cycle $cycle] $err") }
            log.warn("Cycle {}: found {} error(s) in total:", cycle, errors.size)
            errors.forEachIndexed { idx, err -> log.warn("  [{}/{}] {}", idx + 1, errors.size, err) }

            // Strict routing policy:
            //   - syntax errors => deterministic code path only
            //   - non-syntax errors => eligible for LLM repair
            val nonSyntaxErrors = errors.filterNot { isDeterministicSyntaxError(it) }
            if (nonSyntaxErrors.isEmpty()) {
                // All remaining errors are syntax-level and deterministic fixes made no progress.
                // Do not send these to LLM; fail fast instead of repeating reflexion loops.
                throw MapValidationException(
                    message = "Unresolved syntax errors remained after deterministic repair; refusing to send syntax errors to LLM",
                    attemptErrors = failureLog.toList()
                )
            }

            val priorNormErrors = errorsByCycle.flatten().map { normaliseError(it) }.toSet()
            val deltaErrors = nonSyntaxErrors.filter { normaliseError(it) !in priorNormErrors }
            val errorsForTurn = if (deltaErrors.isNotEmpty()) deltaErrors else nonSyntaxErrors
            if (deltaErrors.isNotEmpty()) {
                log.info(
                    "Cycle {}: sending {} NEW error(s) to reflexion ({} total this cycle)",
                    cycle,
                    deltaErrors.size,
                    nonSyntaxErrors.size
                )
            } else {
                log.info(
                    "Cycle {}: no new errors vs prior cycles, sending all {} current error(s)",
                    cycle,
                    nonSyntaxErrors.size
                )
            }

            // Hard oscillation guard: if the LLM keeps producing the same logical error set
            // across CONSECUTIVE cycles, it is clearly stuck and further LLM calls are wasteful.
            //
            // Threshold: require the same normalised error set on TWO consecutive cycles, meaning
            // the LLM has had at least 2 repair attempts and neither made progress.
            // Rationale for >= 2 (not 1):
            //   - Cycle 1 uses reflexion-user.txt (basic prompt with full context).
            //   - Cycle 2 uses buildFollowUpMessage (richer: line snippets, oscillation warning,
            //     prior errors list).  The richer prompt deserves one fair chance.
            //   - Only if BOTH the basic and enriched prompts fail on the same error do we abort.
            //
            // Column normalisation: HAPI embeds column numbers ("at 20, 13:") that drift by ±1
            // when the LLM tweaks indentation.  Strip column so "at 20, 13: msg" == "at 20, 12: msg".
            val normErrors = errors.map { normaliseError(it) }.toSet()
            if (errorsByCycle.size >= maxAttempts - 1 &&
                errorsByCycle.last().map { normaliseError(it) }.toSet() == normErrors
            ) {
                log.error(
                    "Hard oscillation: cycle {} and {} both produced the SAME logical error set " +
                            "after {} LLM repair attempts. Aborting.",
                    cycle - 1, cycle, errorsByCycle.size
                )
                throw MapValidationException(
                    message = "StructureMap compilation stuck on same errors (modulo column) across " +
                            "cycles ${cycle - 1} and $cycle after ${errorsByCycle.size} LLM repair attempt(s) " +
                            "- LLM not converging, aborting early",
                    attemptErrors = failureLog.toList()
                )
            }

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
            log.info("Reflexion turn {}: sending {} error(s) in one LLM call", turnNum, errorsForTurn.size)
            currentFml = reflexion(
                currentFml,
                errorsForTurn,
                source,
                target,
                driftReport,
                conversationHistory,
                errorsByCycle.toList(),
                regressions
            )
            // Re-apply anti-pattern sanitization: the AI can reintroduce the same bad patterns
            // (multi-line then, not() wrapping, etc.) that were fixed before the loop started.
            currentFml = sanitizeFml(currentFml)
            persistFmlSnapshot(runId, cycle, "post-reflexion-patch", currentFml)
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

    /**
     * Normalise a HAPI error string for oscillation comparison by stripping the column number.
     * "Error in GeneratedMap at 20, 13: msg" -> "Error in GeneratedMap at 20: msg"
     * This prevents tiny LLM indentation changes from bypassing the oscillation guard.
     */
    private fun normaliseError(error: String): String =
        error.replace(Regex("""(\bat\s+\d+)\s*,\s*\d+\s*:"""), "$1:")

    /** Extract the 1-based line number from a HAPI error message like "at 17, 7:". */
    private fun extractLineNumber(error: String): Int? =
        Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(error)
            ?.groupValues?.get(1)?.toIntOrNull()

    /** Extract the 1-based column number from a HAPI error message like "at 17, 7:". */
    private fun extractColumnNumber(error: String): Int? =
        Regex("""\bat\s+\d+\s*,\s*(\d+)\s*:""").find(error)
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

    /**
     * If a NON-syntax error comes back unchanged after one full LLM repair turn, drop the
     * offending rule line from the FML instead of allowing it to repeat indefinitely.
     *
     * We preserve line numbers by replacing the line with a comment rather than removing it.
     */
    private fun pruneRepeatedUnresolvedRules(
        fml: String,
        previousErrors: List<String>,
        currentErrors: List<String>
    ): String {
        if (previousErrors.isEmpty() || currentErrors.isEmpty()) return fml

        val previousNormErrors = previousErrors.map { normaliseError(it) }.toSet()
        val lines = fml.lines().toMutableList()
        val prunedLines = mutableSetOf<Int>()

        currentErrors.forEach { error ->
            val normalised = normaliseError(error)
            if (normalised !in previousNormErrors) return@forEach

            // Syntax errors stay on the deterministic fix path. This fallback is for semantic /
            // structural errors that the LLM failed to repair on the prior turn.
            if (error.contains(" expecting ")) return@forEach

            val lineNum = locatePrunableRuleLine(lines, error) ?: return@forEach
            if (lineNum !in 1..lines.size || !prunedLines.add(lineNum)) return@forEach

            val line = lines[lineNum - 1]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//")) return@forEach

            val looksLikeRuleLine = line.contains("->") || (line.contains('=') && trimmed.endsWith(";"))
            if (!looksLikeRuleLine) return@forEach

            val indent = line.length - line.trimStart().length
            lines[lineNum - 1] = " ".repeat(indent) + "// PRUNED after repeated unresolved error: $trimmed"
            log.warn("Pruning stubborn non-syntax rule at line {} after repeated error: {}", lineNum, error)
        }

        if (prunedLines.isEmpty()) return fml

        log.warn("Pruned {} repeated non-syntax rule(s) after failed LLM repair attempt", prunedLines.size)
        return lines.joinToString("\n")
    }

    /** True when an error is purely parser/syntax-level and must never be routed to LLM. */
    private fun isDeterministicSyntaxError(error: String): Boolean {
        val lower = error.lowercase()
        return lower.contains("expecting") ||
                lower.contains("complex rules must have an explicit name") ||
                lower.contains("unknown structuremapinputmode code")
    }

    /**
     * Apply deterministic, line-targeted fixes for a batch of collected syntax errors.
     * This complements autoFixSyntaxErrors, which works from single-pass compile failures.
     */
    private fun applyDeterministicFixesForCollectedSyntaxErrors(fml: String, errors: List<String>): String {
        var current = fml
        errors
            .asSequence()
            .filter { isDeterministicSyntaxError(it) }
            .distinctBy { normaliseError(it) }
            .forEach { err ->
                val fixed = trySyntaxFix(current, err)
                if (fixed != null && fixed != current) {
                    current = fixed
                }
            }
        return current
    }

    /**
     * Final deterministic fallback for syntax lines that could not be rewritten.
     * Keeps progress by pruning the exact offending line rather than looping/LLM retries.
     */
    private fun pruneUnresolvedSyntaxRules(fml: String, syntaxErrors: List<String>): String {
        if (syntaxErrors.isEmpty()) return fml

        val lines = fml.lines().toMutableList()
        val pruned = mutableSetOf<Int>()
        val declarationLine = Regex("""^\s*(map\s+|uses\s+\"|imports\s+\"|group\s+\w+)""")

        syntaxErrors.forEach { error ->
            val lineNum = extractLineNumber(error) ?: return@forEach
            if (lineNum !in 1..lines.size || !pruned.add(lineNum)) return@forEach

            val line = lines[lineNum - 1]
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("//") || declarationLine.containsMatchIn(trimmed)) return@forEach

            val looksLikePrunableSyntaxLine =
                trimmed.contains("->") ||
                        trimmed.startsWith(".") ||
                        trimmed.startsWith("=") ||
                        trimmed.startsWith("src") ||
                        trimmed.startsWith("tgt") ||
                        trimmed == "}"

            if (!looksLikePrunableSyntaxLine) return@forEach

            val indent = line.length - line.trimStart().length
            lines[lineNum - 1] = " ".repeat(indent) + "// PRUNED unresolved syntax: $trimmed"
            log.warn("Pruning unresolved syntax line {} after deterministic repair failure: {}", lineNum, error)
        }

        if (pruned.isEmpty()) return fml
        log.warn("Pruned {} unresolved syntax line(s) to avoid reflexion loops", pruned.size)
        return lines.joinToString("\n")
    }

    /**
     * Prefer HAPI's explicit line number. If absent, fall back to a token-based scan for the
     * transform / identifier mentioned in the error message so stubborn semantic errors can still
     * be pruned deterministically.
     */
    private fun locatePrunableRuleLine(lines: List<String>, error: String): Int? {
        extractLineNumber(error)?.let { return it }

        val token = Regex("""(?:code|named?)\s+'([^']+)'""")
            .find(error)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val candidateIndex = lines.indexOfFirst { line ->
            val trimmed = line.trim()
            trimmed.contains("$token(") ||
                    trimmed.contains(" $token(") ||
                    trimmed.contains("'$token'") ||
                    trimmed.contains("\"$token\"")
        }

        return if (candidateIndex >= 0) candidateIndex + 1 else null
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
     * HAPI stops at the first error it finds. To surface all errors:
     * 1. Parse the FML → record the error.
     * 2. **Replace** the bad line with a blank line (do NOT remove it — removing shifts
     *    all subsequent line numbers, so error messages no longer match the original FML
     *    that the LLM will see).
     * 3. Parse again → repeat.
     *
     * Because we only blank lines (never remove them), every error message's line number
     * corresponds exactly to the same line in the ORIGINAL unmodified FML that is sent
     * to the LLM. The LLM can therefore look up each error by line number and fix it
     * correctly in a single repair call.
     */
    private fun collectAllErrors(fml: String): List<String> {
        val errors = mutableListOf<String>()
        val lines = fml.lines().toMutableList()

        repeat(lines.size) {
            val currentText = lines.joinToString("\n")
            val result = tryCompile(currentText)
            if (result.success) return errors

            val errorMsg = result.error ?: return errors

            // Stuck-parser guard: HAPI can enter a null-state after too many lines are
            // blanked, emitting a stream of identical errors with no location.
            // Stop as soon as the same normalised error appears 3 consecutive times.
            if (errors.size >= 3 &&
                errors.takeLast(3).map { normaliseError(it) }.all { it == normaliseError(errorMsg) }
            ) {
                log.warn(
                    "collectAllErrors: same error repeated 3x — parser stuck, stopping. Error: {}",
                    errorMsg.take(100)
                )
                return errors
            }

            // HAPI parser null-state guard: once the parser gets confused it emits
            // hundreds of "Found \"null\" expecting ..." errors at the last line.
            // These are not real FML errors — stop collecting immediately.
            if (errorMsg.contains("Found \"null\"")) {
                log.warn("collectAllErrors: parser emitted null-token error — stopping early")
                return errors
            }

            errors.add(errorMsg)

            // Find the bad line by its line number (e.g. "at 15, 7:") and blank it.
            // Blanking keeps all other line numbers identical to the original FML.
            val lineNum = Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(errorMsg)
                ?.groupValues?.get(1)?.toIntOrNull()

            if (lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
                lines[lineNum - 1] = ""   // blank — do NOT removeAt
                return@repeat
            }

            // No line number in message — try to locate bad token in the text.
            // Skip "null" — that is a HAPI internal state token, not an FML keyword.
            val badToken = Regex("""['"](\w+)['"]""")
                .findAll(errorMsg).lastOrNull()?.groupValues?.get(1)

            if (badToken != null && badToken != "null") {
                val idx = lines.indexOfFirst { it.contains(badToken) }
                if (idx >= 0) {
                    lines[idx] = ""  // blank — do NOT removeAt
                    return@repeat
                }
            }

            // Cannot locate the bad line — stop scanning
            return errors
        }
        return errors
    }

    /**
     * Rewrites known LLM-generated FML anti-patterns into valid HAPI FML syntax.
     * Runs once before the first validation cycle - zero LLM cost.
     *
     * Anti-pattern 1: FHIRPath-style `.where()` in source expressions.
     *   INVALID:  src.extension.where(url='http://...') as alias ->
     *   VALID:    src.extension as alias where alias.url = 'http://...' ->
     *
     * Anti-pattern 2: Multi-level dotted source paths that HAPI cannot parse.
     *   INVALID:  src.name.family as f -> tgt.name.family = f "...";
     *   These are rewritten as comments since they need sub-group refactoring
     *   which the LLM should handle in the generation prompt. If they slip through,
     *   we comment them out so the rest of the FML can compile.
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

        // Anti-pattern 3: strip markdown code fences that the LLM sometimes wraps FML in
        result = result.replace(Regex("""^```\w*\s*""", RegexOption.MULTILINE), "")
        result = result.replace(Regex("""^```\s*$""", RegexOption.MULTILINE), "")

        // Anti-pattern 4: remove 'alias' from uses declarations
        // INVALID:  uses "url" alias SomeName as source
        // VALID:    uses "url" as source
        val aliasPattern = Regex("""^(\s*uses\s+"[^"]+")(\s+alias\s+\w+)(\s+as\s+(?:source|target)\s*)$""", RegexOption.MULTILINE)
        var aliasFixes = 0
        result = aliasPattern.replace(result) { m ->
            aliasFixes++
            "${m.groupValues[1]}${m.groupValues[3]}"
        }
        rewrites += aliasFixes

        // Anti-pattern 5: not(expr) -> (expr).not()
        // FHIRPath not() is a 0-argument method.  Writing not(condition) causes
        // "The function 'not' requires 0 parameters".  Wrap the argument instead.
        // INVALID:  where not(src.status = 'registered')
        // VALID:    where (src.status = 'registered').not()
        // Use a pattern that handles nested parens like not(src.field.exists())
        // NOTE: require [^()]+ (one-or-more) NOT [^()]* so that .not() with empty
        // args (already-correct form) is never matched and corrupted.
        val notWithArgs = Regex("""\bnot\(([^()]+(?:\([^()]*\)[^()]*)*)\)""")
        result = notWithArgs.replace(result) { m ->
            rewrites++
            "(${m.groupValues[1]}).not()"
        }

        // Anti-pattern 6a: multi-line rule where a continuation line starts with "."
        // Handles both  ".path = value "rule";"  and  ".path as a -> tgt.path "rule";"
        // Excludes lines ending with "," — those are multi-parameter group call continuations.
        // Lines ending with ")" are allowed UNLESS the line is a group/uses declaration
        // (FHIRPath where-clause expressions end with ")" and their dot continuation
        // must be merged; group headers ending with ")" must NOT be merged).
        val multiLineDot = Regex(
            """^([ \t]*)(.*[^\s{};/,])\r?\n[ \t]+(\.[^\r\n]+)$""",
            setOf(RegexOption.MULTILINE)
        )
        val groupOrUsesDecl = Regex("""^\s*(group\s+\w+|uses\s+"|imports\s+")""")
        // AP-19 inline: convert "identifier.where(cond) ->" → "identifier where cond ->"
        // Applied only on lines that were just merged (dot continuation) so we never
        // touch pre-existing valid FML that happens to have .where() in it.
        val mergedInlineWhere = Regex("""(\w+)\.where\(([^)]+)\)(\s+(?:->|then\b))""")
        var dotChanged: Boolean
        do {
            dotChanged = false
            result = multiLineDot.replace(result) { m ->
                val fullLine = "${m.groupValues[1]}${m.groupValues[2]}"
                if (groupOrUsesDecl.containsMatchIn(fullLine)) {
                    m.value  // don't merge group/uses declarations
                } else {
                    rewrites++
                    dotChanged = true
                    val merged = "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[3]}"
                    // Apply AP-19 to the merged line only (not to the whole FML)
                    mergedInlineWhere.replace(merged) { mw ->
                        "${mw.groupValues[1]} where ${mw.groupValues[2]}${mw.groupValues[3]}"
                    }
                }
            }
        } while (dotChanged)

        // Anti-pattern 6b: multi-line rule where a continuation line starts with "=" —
        // the LLM splits  src -> tgt.field = "value" "rule";  across two lines, putting
        // the "= value" part on the next line (indented).  HAPI sees the first line as a
        // complete statement and then finds "=" where it expects ";".
        // Also covers split rules like:  src.field as v\n   = copy() "rule";
        // Excludes lines ending with "," or ")" for the same reason as 6a.
        val multiLineEquals = Regex(
            """^([ \t]*)(.*[^\s{};/,)])\r?\n[ \t]+(=[^\r\n]+)$""",
            setOf(RegexOption.MULTILINE)
        )
        var eqChanged: Boolean
        do {
            eqChanged = false
            result = multiLineEquals.replace(result) { m ->
                rewrites++
                eqChanged = true
                "${m.groupValues[1]}${m.groupValues[2]} ${m.groupValues[3]}"
            }
        } while (eqChanged)

        // Anti-pattern 6c: "Unknown StructureMapInputMode code '.'" — the LLM prefixes
        // the mode keyword with a dot in uses declarations or group parameters.
        // INVALID:  uses "url" as .source   /  uses "url" as .target
        // INVALID:  group X(source src : T, .target tgt : T2)
        // VALID:    uses "url" as source    /  group X(source src : T, target tgt : T2)
        val dotMode = Regex("""\bas\s+\.(source|target|independent)\b""")
        result = dotMode.replace(result) { m ->
            rewrites++
            "as ${m.groupValues[1]}"
        }

        // Anti-pattern 6: deep target paths in constant assignment rules.
        val fixed6 = fixDeepTargetPaths(result)
        if (fixed6 != result) {
            rewrites++
            result = fixed6
        }

        // Anti-pattern 7: multi-line "source then\n    src -> target" construct.
        // LLM writes conditional rules spanning two lines using 'then' as an if-then
        // separator, which is not valid FML.  'then' in FML means "invoke sub-group".
        //
        // INVALID (two lines):
        //   src.category as sc where CONDITION then
        //       src -> tgt.category as tc then CreateLabCategory(src, tc) "name";
        //
        // VALID (merged into one rule):
        //   src.category as sc where CONDITION -> tgt.category as tc then CreateLabCategory(src, tc) "name";
        //
        // Merging preserves the where condition and the target exactly as the LLM intended.
        val multiLineThen = Regex(
            """^([ \t]*)(.*?)\s+then[ \t]*\r?\n[ \t]+src\s+->\s+(.+)$""",
            setOf(RegexOption.MULTILINE)
        )
        result = multiLineThen.replace(result) { m ->
            rewrites++
            val indent = m.groupValues[1]
            val sourceExpr = m.groupValues[2].trim()
            val targetPart = m.groupValues[3].trim()
            "${indent}${sourceExpr} -> ${targetPart}"
        }

        // Anti-pattern 8: empty parentheses alias "as ()" — LLM wraps alias in empty parens.
        // INVALID:  src.code as () -> tgt.code = code "rule";
        // VALID:    src.code as code -> tgt.code = code "rule";
        val emptyAlias = Regex("""(\w+)\s+as\s*\(\s*\)""")
        result = emptyAlias.replace(result) { m ->
            rewrites++
            "${m.groupValues[1]} as ${m.groupValues[1]}"
        }

        // Anti-pattern 9: multi-line comma-chained transforms.
        // HAPI only supports single-line comma-separated transforms.  The LLM formats
        // them with each extra target on its own indented continuation line, e.g.:
        //   src.a as v -> tgt.fieldA = vA "rule-A",
        //                 tgt.fieldB = vB "rule-B";
        // HAPI sees line 1 as a complete statement and then finds "tgt" expecting ";".
        // Fix: merge each continuation line that follows a comma-terminated rule line.
        // Only merge when the continuation starts with an identifier+dot (variable.field)
        // and is NOT a FML keyword continuation (group/source/target/map/uses).
        val multiLineCommaTransform = Regex(
            """^([ \t]*.+,)[ \t]*\r?\n([ \t]+(?!group\b|map\b|uses\b|source\b|target\b|imports\b|//)\w+\..+)$""",
            setOf(RegexOption.MULTILINE)
        )
        var commaChanged: Boolean
        do {
            commaChanged = false
            result = multiLineCommaTransform.replace(result) { m ->
                rewrites++
                commaChanged = true
                "${m.groupValues[1]} ${m.groupValues[2].trimStart()}"
            }
        } while (commaChanged)

        // Anti-pattern 10a: polymorphic element subscript "[x]" or "[X]" in source/target paths.
        // INVALID:  src.medication[x] as m -> ...
        // VALID:    src.medication as m -> ...
        result = result.replace(Regex("""(\w+)\[x]""", RegexOption.IGNORE_CASE)) { m ->
            rewrites++
            m.groupValues[1]
        }

        // Anti-pattern 10b: leading "[...]" annotation on a rule line — LLM adds type-filter
        // annotations such as "[medication.ofType(CodeableConcept)]" before the rule source.
        // These have no meaning in FML and cause "Found '[' expecting ';'" at col 3.
        val leadingBracket = Regex("""^([ \t]*)\[[^\]]*][ \t]+(.+)$""", RegexOption.MULTILINE)
        result = leadingBracket.replace(result) { m ->
            rewrites++
            "${m.groupValues[1]}${m.groupValues[2]}"
        }

        // Anti-pattern 11: copy(varName) wrapping — LLM writes "tgt.field = copy(v)" but
        // HAPI FML has no copy() function in assignments.  Strip the wrapper: "= v".
        // "copy()" with no args is valid (pass-through rule body) and is NOT touched here.
        val copyWrap = Regex("""=\s*copy\((\w+)\)""")
        result = copyWrap.replace(result) { m ->
            rewrites++
            "= ${m.groupValues[1]}"
        }

        // Anti-pattern 12: invalid 'uses' mode keywords.
        // HAPI only accepts 'source' and 'target'. The LLM sometimes writes 'input',
        // 'output', 'conceptMapUrl', 'producer', 'consumer', or 'model'.
        val invalidUsesMode = Regex(
            """^([ \t]*uses[ \t]+"[^"]+"[ \t]+as[ \t]+)(input|output|conceptMapUrl|producer|consumer|model|abstract)[ \t]*$""",
            RegexOption.MULTILINE
        )
        result = invalidUsesMode.replace(result) { m ->
            rewrites++
            val mode = m.groupValues[2]
            when (mode) {
                "input", "producer", "model" -> "${m.groupValues[1]}source"
                "output", "consumer", "abstract" -> "${m.groupValues[1]}target"
                else -> "// [fdd-sanitized] invalid uses '${mode}' removed"
            }
        }

        // Anti-pattern 13: trailing comma inside group parameter list.
        // INVALID:  group Foo(source src : T, target tgt : T2,)
        // VALID:    group Foo(source src : T, target tgt : T2)
        result = result.lines().joinToString("\n") { line ->
            if (Regex("""^\s*group\s+\w+\s*\(""").containsMatchIn(line) &&
                Regex(""",\s*\)""").containsMatchIn(line)
            ) {
                rewrites++
                Regex(""",\s*\)""").replace(line, ")")
            } else line
        }

        // Anti-pattern 14: .ofType(TypeName) in source/target paths — FHIRPath function
        // that HAPI FML does not support in mapping expressions.
        // INVALID:  src.medication.ofType(CodeableConcept) as m -> ...
        // VALID:    src.medication as m -> ...
        // Strips the suffix; a where clause can be added manually if type filtering is needed.
        val ofTypeInPath = Regex("""\.(ofType|as)\(\w+\)""")
        result = ofTypeInPath.replace(result) { m ->
            // Only strip .ofType() / .as() — not plain method calls with full paths
            rewrites++
            ""
        }

        // Anti-pattern 15: extension("url") FHIRPath shorthand in source expressions.
        // HAPI FML does not support the compact extension("url") function call.
        // INVALID:  src.extension("http://example.org/ext") as e -> ...
        // VALID:    src.extension as e where e.url = 'http://example.org/ext' -> ...
        val extensionShorthand = Regex(
            """(\w+)\.extension\(\s*["']([^"']+)["']\s*\)\s+as\s+(\w+)"""
        )
        result = extensionShorthand.replace(result) { m ->
            rewrites++
            val base  = m.groupValues[1]
            val url   = m.groupValues[2]
            val alias = m.groupValues[3]
            "$base.extension as $alias where $alias.url = '$url'"
        }

        // Anti-pattern 16: extra "as alias" inside group parameter declarations.
        // HAPI FML group params have the form: (source|target) name : Type
        // The LLM sometimes adds an alias: (source src as srcAlias : Type)
        // INVALID:  group Foo(source src as srcAlias : Patient, target tgt as tgtAlias : Patient)
        // VALID:    group Foo(source src : Patient, target tgt : Patient)
        val groupParamAliasPattern = Regex("""\b(source|target)\s+(\w+)\s+as\s+\w+\s*:""")
        result = result.lines().joinToString("\n") { line ->
            if (Regex("""^\s*group\s+""").containsMatchIn(line) &&
                groupParamAliasPattern.containsMatchIn(line)
            ) {
                val fixed = groupParamAliasPattern.replace(line) { m ->
                    "${m.groupValues[1]} ${m.groupValues[2]} :"
                }
                if (fixed != line) rewrites++
                fixed
            } else line
        }

        // Anti-pattern 18: FML rules written at the top level of the map (outside any group body).
        // HAPI only accepts 'map', 'uses', 'imports', 'group' at depth 0.  The LLM sometimes
        // generates rules ("src -> tgt ...") directly at depth 0 after a group closes.
        // These cause "Found 'src' expecting 'group'" errors.  Comment them out so the rest
        // compiles; the reflexion loop will see the comments and can re-emit them in the right place.
        val topLevelDecl = Regex("""^\s*(map\s+["']|uses\s+["']|imports\s+["']|group\s+\w+|//|${'$'})""")
        val looksLikeRule = Regex("""->""")
        var apDepth18 = 0
        result = result.lines().joinToString("\n") { line ->
            val opened = line.count { it == '{' }
            val closed = line.count { it == '}' }
            val enteredAt = apDepth18
            apDepth18 += opened - closed
            if (enteredAt == 0 && !topLevelDecl.containsMatchIn(line) && looksLikeRule.containsMatchIn(line)) {
                rewrites++
                "// [fdd-sanitized] stray top-level rule moved here by LLM: $line"
            } else {
                line
            }
        }

        // Anti-pattern 17: dotted type names in group parameter declarations.        // HAPI rejects "Patient.Communication" as a type in group params, producing
        // "Found '.' expecting ')'".  Replace each "Outer.Inner" type with BackboneElement.
        // INVALID:  group Foo(source src : Patient.Communication, target tgt : Encounter.Location)
        // VALID:    group Foo(source src : BackboneElement, target tgt : BackboneElement)
        val dottedGroupType = Regex("""((?:source|target)\s+\w+\s*:\s*)\w+(?:\.\w+)+""")
        result = result.lines().joinToString("\n") { line ->
            if (Regex("""^\s*group\s+\w+\s*\(""").containsMatchIn(line)) {
                val fixed = dottedGroupType.replace(line) { m ->
                    rewrites++
                    "${m.groupValues[1]}BackboneElement"
                }
                fixed
            } else line
        }

        // Anti-pattern 20: FHIRPath boolean/aggregate functions used as rule source specifier.
        //
        // HAPI FML source must be a plain element path. When the LLM writes:
        //   src.f.empty() then tgt.f = 'default' "r";
        //   src.f.exists() -> tgt.f = ... "r";
        // HAPI emits "Found '(' expecting ';'" at the '(' of the function call.
        //
        // FIX: strip the .funcName(args) part from the source expression entirely.
        // The remaining source path (e.g. src.f) is valid FML.
        // trySyntaxFix(Fix P1) also handles this at the column level, but doing it
        // here in sanitizeFml means the validator never even sees the error.
        //
        //   src.identifier.empty() then tgt.identifier = 'x' "r";
        //   → src.identifier then tgt.identifier = 'x' "r";    (valid)
        val fhirPathSourceFn = Regex(
            """^([ \t]*)(\S.*?)\.(empty|exists|hasValue|hasExtension|count|resolve|matches)\s*\([^)]*\)(\s+(?:then|->).*)$""",
            setOf(RegexOption.MULTILINE)
        )
        result = fhirPathSourceFn.replace(result) { m ->
            rewrites++
            // m.groupValues[1] = indent, [2] = path before .func, [4] = rest of line
            "${m.groupValues[1]}${m.groupValues[2]}${m.groupValues[4]}"
        }

        // Anti-pattern 21: `then create tgt.field` / `then new tgt.field`.
        //
        // HAPI FML has no standalone `create` keyword in rule-body position.
        // The LLM writes:  src -> tgt.f then create tgt.f.child "r";
        // FIX: strip `then create tgt.f.child` — the rule body is complete without it.
        // `src -> tgt.f "r";` is valid (simple element delegation rule).
        val thenCreate = Regex(
            """\s+then\s+(?:create|new)\s+(?:tgt|src)\.\w+"""
        )
        result = result.lines().joinToString("\n") { line ->
            if (thenCreate.containsMatchIn(line)) {
                rewrites++
                thenCreate.replace(line, "")
            } else line
        }

        // Anti-pattern 22: iif() FHIRPath function in transform position.
        // HAPI FML does not support iif() as a transform type.
        // Error: "Unknown StructureMapTransformType code 'iif'"
        // The LLM writes:  src -> tgt.field = iif(cond, v1, v2) "r";
        // FIX: rewrite to always-assign the second argument (the "then" branch).
        // This is the safe-default: the mapping still copies the most relevant value.
        val iifTransform = Regex(
            """=\s*iif\s*\([^,]+,\s*([^,)]+)(?:,[^)]*)?\)"""
        )
        result = iifTransform.replace(result) { m ->
            rewrites++
            "= ${m.groupValues[1].trim()}"
        }


        // Anti-pattern 23: inline "then src ->" on a single line.
        // AP7 handles the multi-line variant (then at end-of-line, src -> on next line).
        // The LLM also emits both parts on one line:
        //   src.f as v where COND then src -> tgt.f as w then Group(v, w) "name";
        // Fix: remove "then src ->" and replace with just "->".
        val inlineThenSrcArrow = Regex(
            """(\S[^\n]*?)\s+then\s+src\s+->(\s+)""",
            setOf(RegexOption.MULTILINE)
        )
        result = inlineThenSrcArrow.replace(result) { m ->
            rewrites++
            "${m.groupValues[1]} ->${m.groupValues[2]}"
        }

        // Anti-pattern 24: numeric array indexers in source/target paths.
        // AP10a strips "[x]" polymorphic subscripts; this strips numeric ones.
        // INVALID:  src.coding[0].system as v -> ...
        // VALID:    src.coding.system as v -> ...
        result = result.replace(Regex("""(\w+)\[(\d+)\]""")) { m ->
            rewrites++
            m.groupValues[1]
        }

        // Anti-pattern 25: Compound FHIRPath source specifier.
        // The LLM generates "conditional default" rules using compound FHIRPath expressions
        // as the rule's source specifier, e.g.:
        //   src.identifier.empty() and (tgt.identifier.exists()).not() -> tgt.identifier as tId ...
        // HAPI FML does not support compound FHIRPath (with 'and', '.empty()', '.not()') in a
        // source specifier — only simple dotted paths are allowed before '->'.
        // Error produced: "Found '.' expecting ';'"
        // Fix: strip the compound condition entirely and replace with plain 'src ->'.
        //   src.X.empty() and (tgt.X.exists()).not() -> REST   →   src -> REST
        // NOTE: (tgt.FIELD.exists()).not() has nested parens — [^)]+ stops at the inner ')'
        // inside exists().  We must explicitly spell out exists() inside the outer parens:
        //   \(tgt\.\w+\.exists\(\)\)\.not\(\)
        val ap25 = Regex(
            """^(\s*)src(?:\.\w+)+\([^)]*\)\s+and\s+\(tgt(?:\.\w+)+\.exists\(\)\)\.not\(\)\s*->\s*(.+)$""",
            setOf(RegexOption.MULTILINE)
        )
        result = ap25.replace(result) { m ->
            rewrites++
            "${m.groupValues[1]}src -> ${m.groupValues[2].trimStart()}"
        }

        // Anti-pattern 26: Bare target assignment without source in a target-only group.
        // The LLM generates groups like:
        //   group DefaultIdentifier(target tgt : Identifier) {
        //     tgt.system = 'urn:ietf:rfc:3986' "default-identifier-system";
        //   }
        // HAPI FML requires every rule to have a source side and '->'.
        // Error produced: "Found '=' expecting ';'"
        // Fix: prepend 'tgt -> ' to make a valid FML rule.
        //   tgt.FIELD = 'VALUE' "NAME";   →   tgt -> tgt.FIELD = 'VALUE' "NAME";
        val ap26 = Regex(
            """^(\s*)(tgt(?:\.\w+)+\s*=\s*'[^']*'\s+"[^"]+"\s*;)$""",
            setOf(RegexOption.MULTILINE)
        )
        result = ap26.replace(result) { m ->
            rewrites++
            "${m.groupValues[1]}tgt -> ${m.groupValues[2].trimStart()}"
        }

        // Anti-pattern 27: unmatched top-level closing brace.
        // Stray '}' at depth 0 causes cascaded parser errors like:
        //   Found "src" expecting "group"
        //   Found "}" expecting "group"
        // Comment out only truly top-level unmatched braces.
        var depth27 = 0
        result = result.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (depth27 == 0 && trimmed == "}") {
                rewrites++
                "// [fdd-sanitized] unmatched top-level closing brace removed"
            } else {
                depth27 += line.count { it == '{' } - line.count { it == '}' }
                if (depth27 < 0) depth27 = 0
                line
            }
        }

        if (rewrites > 0) {
            log.info("sanitizeFml: rewrote {} anti-pattern(s) to valid FML", rewrites)
        }
        return result
    }

    /**
     * Detect and rewrite lines of the form:
     *   src -> tgt.A.B[.C...] = "literal" "name";
     * which HAPI rejects with "Found '.' expecting ';'".
     *
     * Rewrites each such line to:
     *   src -> tgt.A as _grp_<n> then _SetA_<n>(src, _grp_<n>) "name";
     * and appends a synthetic helper group at the end of the FML:
     *   group _SetA_<n>(source src : Element, target _grp_<n> : Element) {
     *     src -> _grp_<n>.B[.C...] = "literal" "<name>-inner";
     *   }
     * If B.C... is still more than one level, the same transform recurses.
     */
    private fun fixDeepTargetPaths(fml: String): String {
        // Match: (leading whitespace)(src expr) -> tgt.(two-or-more dotted segments) = "value" "name";
        // The src part may contain 'where ...' clauses - capture everything up to the ->
        val deepTargetPattern = Regex(
            """^(\s*)(.*?)\s*->\s*(tgt\.\w+(?:\.\w+)+)\s*=\s*("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*')\s*("(?:[^"\\]|\\.)+")\s*;$""",
            RegexOption.MULTILINE
        )
        if (!deepTargetPattern.containsMatchIn(fml)) return fml

        val extraGroups = mutableListOf<String>()
        var counter = 0
        var result = fml

        result = deepTargetPattern.replace(result) { m ->
            val indent = m.groupValues[1]
            val srcExpr = m.groupValues[2].trim()
            val fullTargetPath = m.groupValues[3]  // e.g. tgt.category.coding.system
            val literal = m.groupValues[4]
            val ruleName = m.groupValues[5]        // e.g. "set-category-system"

            // Split off first segment: tgt.A -> top alias; rest = B.C...
            val segments = fullTargetPath.removePrefix("tgt.").split(".")
            val firstSeg = segments[0]
            val restSegs = segments.drop(1)

            val groupSuffix = counter++
            val aliasName = "_fdd${groupSuffix}_"
            val groupName = "_SetFdd${groupSuffix}_"

            // Build the inner group body recursively (handles multiple nesting levels)
            val innerBody = buildDeepAssignGroup(aliasName, restSegs, literal, ruleName, groupSuffix)
            extraGroups.add(innerBody)

            // Rewrite the top-level rule.
            // The group call needs the source root variable. In constant-assignment rules the
            // source expression is typically just "src" or starts with "src"; extract the
            // first bare identifier (word before any dot or space).
            val srcRootVar = Regex("""^\w+""").find(srcExpr)?.value ?: "src"
            val innerRuleName = ruleName.dropLast(1) + "-outer\""  // e.g. "set-category-system-outer"
            "${indent}${srcExpr} -> tgt.${firstSeg} as ${aliasName} then ${groupName}(${srcRootVar}, ${aliasName}) ${innerRuleName};"
        }

        if (extraGroups.isNotEmpty()) {
            result = result.trimEnd() + "\n\n" + extraGroups.joinToString("\n\n")
            log.info("sanitizeFml: rewrote {} deep-target-path assignment(s) into sub-group(s)", extraGroups.size)
        }
        return result
    }

    /**
     * Recursively build a helper group that assigns a possibly-deep path to a literal.
     */
    private fun buildDeepAssignGroup(
        parentAlias: String,
        segments: List<String>,
        literal: String,
        ruleName: String,
        suffix: Int
    ): String {
        val groupName = "_SetFdd${suffix}_"
        if (segments.size == 1) {
            // Base case: single segment — direct assignment is valid
            val innerRule = "  src -> ${parentAlias}.${segments[0]} = ${literal} ${ruleName.dropLast(1)}-inner\";"
            return "group ${groupName}(source src : Resource, target ${parentAlias} : Element) {\n${innerRule}\n}"
        }
        // Still nested: recurse one more level
        val nextSuffix = suffix * 1000 + segments.size
        val nextAlias = "_fdd${nextSuffix}_"
        val nextGroup = "_SetFdd${nextSuffix}_"
        val firstSeg = segments[0]
        val rest = segments.drop(1)
        val delegateRule = "  src -> ${parentAlias}.${firstSeg} as ${nextAlias} then ${nextGroup}(src, ${nextAlias}) ${ruleName.dropLast(1)}-mid\";"
        val innerGroupBody = buildDeepAssignGroup(nextAlias, rest, literal, ruleName, nextSuffix)
        return "group ${groupName}(source src : Resource, target ${parentAlias} : Element) {\n${delegateRule}\n}\n\n${innerGroupBody}"
    }

    /**
     * Auto-fix "Complex rules must have an explicit name" errors.
     * HAPI requires every rule with -> to end with a quoted name before the semicolon.
     * This fixes them deterministically without any LLM cost.
     */
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

            // Only fix lines ending with ';' that don't already have a quoted rule name before the semicolon
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

    /**
     * Deterministically fix syntax-level FML errors — zero LLM cost.
     *
     * This is the key principle: if the error is SYNTAX (missing semicolon, unquoted rule name,
     * wrong quote characters, double semicolons), we fix it in code. The AI is only called for
     * SEMANTIC errors (wrong FHIR paths, wrong group signatures, wrong logic).
     *
     * Fixes handled:
     *   1. Unicode curly/smart quotes → ASCII straight quotes (global)
     *   2. Double semicolons ;; → ; (global)
     *   3. Missing semicolons: HAPI "Found X expecting ;" → add ; to preceding rule line
     *   4. Unquoted rule names: GroupCall(args) rulename; → GroupCall(args) "rulename";
     *   5. "Found X expecting a token name" beyond what autoFixRuleNames covers
     *
     * Runs in a loop: fixes one error per iteration until either the FML compiles or
     * the next error is a semantic error that requires AI.
     */
    private fun autoFixSyntaxErrors(fml: String): String {
        var current = fml
        var totalFixes = 0

        // Pass 1: global substitutions that apply regardless of error location
        val globalFixed = current
            .replace('\u201C', '"').replace('\u201D', '"')   // " " → "
            .replace('\u2018', '\'').replace('\u2019', '\'') // ' ' → '
            .replace('\u00B4', '\'')                          // ´ (acute accent) → '
            .replace(";;", ";")
        if (globalFixed != current) {
            log.info("autoFixSyntaxErrors: normalised unicode quotes / double semicolons")
            current = globalFixed
            totalFixes++
        }

        // Pass 1b: deterministically merge ALL continuation lines that begin with '.' or '='.
        // These are the dominant repeated syntax failures in Experiment 2/3 and do not need AI.
        val mergedContinuations = mergeBrokenContinuationLines(current)
        if (mergedContinuations != current) {
            log.info("autoFixSyntaxErrors: merged broken continuation lines before validation")
            current = mergedContinuations
            totalFixes++
        }

        // Pass 2: iterative error-driven fixes
        repeat(100) {
            val result = tryCompile(current)
            if (result.success) {
                if (totalFixes > 0) log.info("autoFixSyntaxErrors: resolved {} syntax issue(s) without LLM", totalFixes)
                return current
            }
            val error = result.error ?: return current

            val fixed = trySyntaxFix(current, error) ?: run {
                if (totalFixes > 0) {
                    log.info(
                        "autoFixSyntaxErrors: fixed {} issue(s); remaining error needs AI: {}",
                        totalFixes, error.take(120)
                    )
                }
                return current
            }
            if (fixed == current) return current
            current = fixed
            totalFixes++
        }
        return current
    }

    private fun mergeBrokenContinuationLines(fml: String): String {
        val lines = fml.lines().toMutableList()
        var changed = false
        val groupOrUsesDecl = Regex("""^\s*(group\s+\w+|uses\s+"|imports\s+")""")
        val inlineWhere = Regex("""(\w+)\.where\(([^)]+)\)(\s+(?:->|then\b))""")

        var idx = lines.size - 1
        while (idx > 0) {
            val current = lines[idx]
            val trimmed = current.trimStart()
            if (trimmed.startsWith(".") || trimmed.startsWith("=")) {
                val previous = lines[idx - 1].trimEnd()
                if (previous.isNotBlank() &&
                    !groupOrUsesDecl.containsMatchIn(previous) &&
                    !previous.endsWith("{")
                ) {
                    val separator = if (trimmed.startsWith("=")) " " else ""
                    var merged = previous + separator + trimmed
                    merged = inlineWhere.replace(merged) { m ->
                        "${m.groupValues[1]} where ${m.groupValues[2]}${m.groupValues[3]}"
                    }
                    lines[idx - 1] = merged
                    lines.removeAt(idx)
                    changed = true
                }
            }
            idx--
        }

        return if (changed) lines.joinToString("\n") else fml
    }

    /**
     * Try to fix a single known syntax error deterministically.
     * Returns the corrected FML, or null if this is not a fixable syntax error.
     *
     * Fixable patterns:
     *   A. "Found X expecting ;" — missing semicolon on the preceding rule line
     *   B. "Found X expecting ;" on a line that ends with an unquoted rule name
     *   C. "Found X expecting a token name" — rule missing a name string
     */
    private fun trySyntaxFix(fml: String, error: String): String? {
        val lines = fml.lines().toMutableList()
        val lineNum = extractLineNumber(error)
        val colNum  = extractColumnNumber(error)

        val expectingSemi     = error.contains("expecting \";\"") || error.contains("expecting ';'")
        val expectingTokenName = error.contains("expecting a token name")

        // ---------------------------------------------------------------
        // Fix P (POSITION-AWARE): "Found 'X' expecting ';'" where HAPI gives us
        // an exact line AND column.  We use the column to identify the offending
        // token and splice in a ';' right before it, preserving the rest of the line.
        //
        // Handles:
        //   • Found '(' expecting ';'  →  strip .funcName(args) at the col position
        //   • Found '==' / '!=' / '<' / '<=' / '>' / '>=' expecting ';'
        //                              →  truncate at the operator, terminate with ';'
        //   • Any other unexpected token at a known column
        //
        // The HAPI column is 1-based.
        // ---------------------------------------------------------------
        if (expectingSemi && lineNum != null && colNum != null &&
            lineNum >= 1 && lineNum <= lines.size
        ) {
            val line = lines[lineNum - 1]
            val col0 = colNum - 1  // convert to 0-based

            // ── Case P1: unexpected '(' ──────────────────────────────────
            // HAPI parsed a dotted path, hit '(' and expected ';'.
            // The offending token is '.funcName(' ending at col0.
            // Strip from the last '.' before col0 through the matching ')'.
            if (col0 < line.length && line[col0] == '(') {
                val dotPos = line.lastIndexOf('.', col0 - 1)
                val cutFrom = if (dotPos >= 0) dotPos else col0

                // Find the matching closing ')' for this '('
                var depth = 0
                var closePos = col0
                for (i in col0 until line.length) {
                    when (line[i]) {
                        '(' -> depth++
                        ')' -> { depth--; if (depth == 0) { closePos = i; break } }
                    }
                }
                // Everything before '.funcName' + everything after ')'
                val before = line.substring(0, cutFrom)
                val after  = if (closePos + 1 < line.length) line.substring(closePos + 1) else ""
                var fixed  = before + after

                // If nothing useful remains after stripping, end the statement
                if (fixed.trimEnd().let { !it.endsWith(";") && !it.endsWith("{") && !it.endsWith("}") }) {
                    fixed = fixed.trimEnd() + if (fixed.contains("->")) "" else ";"
                }
                if (fixed.trimEnd() != line.trimEnd()) {
                    lines[lineNum - 1] = fixed
                    log.debug("autoFixSyntaxErrors: stripped .func() call at col {} on line {}", colNum, lineNum)
                    return lines.joinToString("\n")
                }
            }

            // ── Case P2: unexpected comparison operator ( ==, !=, <, <=, >, >= ) ─
            // HAPI finished parsing a source path and hit a comparison where ';' was expected.
            // Truncate the line at the operator position and terminate with ';'.
            val compOp = Regex("""(==|!=|<=?|>=?)""")
            if (col0 < line.length) {
                val opMatch = compOp.find(line, col0.coerceAtLeast(0))
                if (opMatch != null && opMatch.range.first <= col0 + 2) {
                    val before = line.substring(0, opMatch.range.first).trimEnd()
                    val indent = line.length - line.trimStart().length
                    val termLine = " ".repeat(indent) + before.trimStart().let {
                        if (!it.endsWith(";") && !it.endsWith("{") && !it.endsWith("}")) "$it;" else it
                    }
                    lines[lineNum - 1] = termLine
                    log.debug("autoFixSyntaxErrors: truncated comparison op '{}' at line {}", opMatch.value, lineNum)
                    return lines.joinToString("\n")
                }
            }

            // ── Case P3: unexpected bare keyword at known col (e.g. 'then' mid-expression) ─
            // Generic fallback: truncate at col0 and add ';'
            if (col0 >= 1 && col0 < line.length) {
                val before = line.substring(0, col0).trimEnd()
                if (before.isNotBlank() && before.length < line.length - 1) {
                    val indent = line.length - line.trimStart().length
                    val termLine = " ".repeat(indent) + before.trimStart().let {
                        if (!it.endsWith(";") && !it.endsWith("{") && !it.endsWith("}")) "$it;" else it
                    }
                    lines[lineNum - 1] = termLine
                    log.debug("autoFixSyntaxErrors: truncated at col {} on line {}", colNum, lineNum)
                    return lines.joinToString("\n")
                }
            }
        }

        // ---------------------------------------------------------------
        // Fix A: Unquoted rule name — "Found 'word' expecting ';'"
        // Line ends with:  ) unquoted-word ;
        // ---------------------------------------------------------------
        if (expectingSemi && lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
            val line = lines[lineNum - 1]
            val unquotedNameAfterParen = Regex("""^(.*\))\s+([\w][\w-]*)\s*;\s*$""")
            val m = unquotedNameAfterParen.find(line)
            if (m != null && line.contains("->")) {
                val before = m.groupValues[1]
                val name   = m.groupValues[2]
                val fmlKeywords = setOf("src","tgt","source","target","group","uses","map","then","as","where")
                if (name !in fmlKeywords && name.length > 1) {
                    lines[lineNum - 1] = "$before \"$name\";"
                    log.debug("autoFixSyntaxErrors: quoted unquoted rule name '{}' on line {}", name, lineNum)
                    return lines.joinToString("\n")
                }
            }
        }

        // ---------------------------------------------------------------
        // Fix D: Dot-continuation line — "Found '.' expecting ';'"
        // The current line starts with '.'; join it onto the preceding line.
        // ---------------------------------------------------------------
        if (expectingSemi && lineNum != null && lineNum >= 2 && lineNum <= lines.size) {
            val dotLine = lines[lineNum - 1]
            if (dotLine.trimStart().startsWith(".")) {
                val precedingIdx = lineNum - 2
                val preceding    = lines[precedingIdx].trimEnd()
                var merged = "$preceding${dotLine.trim()}"
                val apInlineWhere = Regex("""(\w+)\.where\(([^)]+)\)(\s+(?:->|then\b))""")
                merged = apInlineWhere.replace(merged) { mm ->
                    "${mm.groupValues[1]} where ${mm.groupValues[2]}${mm.groupValues[3]}"
                }
                lines[precedingIdx] = merged
                lines.removeAt(lineNum - 1)
                log.debug("autoFixSyntaxErrors: merged dot-continuation line {} onto line {}", lineNum, lineNum - 1)
                return lines.joinToString("\n")
            }
        }

        // ---------------------------------------------------------------
        // Fix B: Missing semicolon on the preceding rule line
        // ---------------------------------------------------------------
        if (expectingSemi && lineNum != null) {
            for (i in (lineNum - 2) downTo maxOf(0, lineNum - 6)) {
                if (i >= lines.size) continue
                val rawLine    = lines[i]
                val trimmedEnd = rawLine.trimEnd()
                val trimmed    = rawLine.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("//")) continue
                if (trimmedEnd.endsWith(";") || trimmedEnd.endsWith("{") || trimmedEnd.endsWith("}")) break
                if (trimmedEnd.isNotEmpty()) {
                    lines[i] = "$trimmedEnd;"
                    log.debug("autoFixSyntaxErrors: added missing ';' to line {}", i + 1)
                    return lines.joinToString("\n")
                }
                break
            }
        }

        // ---------------------------------------------------------------
        // Fix C: Missing rule name — "Found X expecting a token name"
        // ---------------------------------------------------------------
        if (expectingTokenName && lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
            val line    = lines[lineNum - 1]
            val trimmed = line.trimEnd()
            // C1: ends with ';' but no quoted name
            if (trimmed.endsWith(";") && trimmed.contains("->") &&
                !Regex(""""[^"]*"\s*;$""").containsMatchIn(trimmed)
            ) {
                val indent   = line.length - line.trimStart().length
                val ruleName = "rule_line$lineNum"
                lines[lineNum - 1] = " ".repeat(indent) + trimmed.dropLast(1).trimEnd() + " \"$ruleName\";"
                log.debug("autoFixSyntaxErrors: added generated rule name to line {}", lineNum)
                return lines.joinToString("\n")
            }
            // C2: ends with ')' and has '->'; add name + ';'
            if (trimmed.endsWith(")") && trimmed.contains("->")) {
                val indent   = line.length - line.trimStart().length
                val ruleName = "rule_line$lineNum"
                lines[lineNum - 1] = " ".repeat(indent) + trimmed.trimStart() + " \"$ruleName\";"
                log.debug("autoFixSyntaxErrors: added rule name + ; to ) line {}", lineNum)
                return lines.joinToString("\n")
            }
            // C3: closing-paren-only line — merge onto preceding
            val trimmedFull = line.trim()
            if ((trimmedFull == ")" || trimmedFull == ");" ||
                        trimmedFull.matches(Regex("""^\)\s*\{?$"""))) && lineNum >= 2
            ) {
                val precedingIdx = lineNum - 2
                val preceding    = lines[precedingIdx].trimEnd()
                if (!preceding.endsWith(";") && !preceding.endsWith("{") && !preceding.endsWith("}")) {
                    lines[precedingIdx] = "$preceding$trimmedFull"
                    lines.removeAt(lineNum - 1)
                    log.debug("autoFixSyntaxErrors: merged closing-paren-only line {} onto line {}", lineNum, lineNum - 1)
                    return lines.joinToString("\n")
                }
            }
        }

        // ---------------------------------------------------------------
        // Fix F: "Found 'then' expecting ';'" — trailing bare 'then'
        // Strip the trailing 'then' and close the rule with ';'.
        // ---------------------------------------------------------------
        if (error.contains("Found 'then'") && lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
            val line     = lines[lineNum - 1]
            val stripped = line.trimEnd().removeSuffix("then").trimEnd()
            if (stripped != line.trimEnd() && stripped.contains("->")) {
                val indent   = line.length - line.trimStart().length
                val withName = if (!Regex(""""[^"]*"\s*$""").containsMatchIn(stripped))
                    "$stripped \"rule_line$lineNum\";" else "$stripped;"
                lines[lineNum - 1] = " ".repeat(indent) + withName.trimStart()
                log.debug("autoFixSyntaxErrors: removed trailing 'then' on line {}", lineNum)
                return lines.joinToString("\n")
            }
        }

        // ---------------------------------------------------------------
        // Fix G: "Found '->' expecting '('" — parser saw -> where it expected (
        // meaning a rule has "then <something> ->" instead of proper "->" syntax.
        // This happens when AP23 did not fire (e.g. post-LLM rewrite introduced
        // a bare "then src ->" again on the same line).
        // Fix: find the "then" immediately before "->" on the error line, remove it.
        // ---------------------------------------------------------------
        if (error.contains("Found '->'") && error.contains("expecting '('") &&
            lineNum != null && lineNum >= 1 && lineNum <= lines.size
        ) {
            val line = lines[lineNum - 1]
            // Remove any "then src ->" or bare "then ->" on the error line
            val fixedLine = line
                .replace(Regex("""\bthen\b\s+src\s+->"""), "->")
                .replace(Regex("""\bthen\b\s+->"""),       "->")
            if (fixedLine != line) {
                lines[lineNum - 1] = fixedLine
                log.debug("autoFixSyntaxErrors: removed spurious 'then' before '->' on line {}", lineNum)
                return lines.joinToString("\n")
            }
        }

        return null  // semantic error — let AI handle it
    }

    private fun createWorkerContext(): IWorkerContext {
        val validationSupport = ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContext)
        )
        return HapiWorkerContext(fhirContext, validationSupport)
    }

    /* ---------- LLM Repair (Reflexion) ---------- */

    /**
     * Find the zero-based [start end] line range of the FML `group` block that contains
     * the given 1-based [targetLine].  Returns `null` when no enclosing group is found.
     */
    private fun extractGroupContaining(fml: String, targetLine: Int): IntRange? {
        val lines = fml.lines()
        val zeroTarget = targetLine - 1
        val groupHeader = Regex("""^\s*group\s+\w+""")

        var groupStart = -1
        var braceDepth = 0

        for (i in lines.indices) {
            val line = lines[i]
            if (groupHeader.containsMatchIn(line)) {
                groupStart = i
                braceDepth = 0
            }
            if (groupStart >= 0) {
                braceDepth += line.count { it == '{' } - line.count { it == '}' }
                if (braceDepth <= 0 && i > groupStart) {
                    if (zeroTarget in groupStart..i) return groupStart..i
                    groupStart = -1
                }
            }
        }
        return null
    }

    /**
     * Extract the first `group Name(...) { ... }` block from an LLM response.
     * Handles both code-fenced and plain-text responses.
     */
    private fun extractGroupBlock(response: String): String? {
        val text = Regex("""```(?:fml|map|fhir)?\s*\n?([\s\S]*?)\n?```""")
            .find(response.trim())?.groupValues?.get(1)?.trim()
            ?: response.trim()

        val groupMatch = Regex("""^group\s+""", RegexOption.MULTILINE).find(text) ?: return null
        val fromGroup = text.substring(groupMatch.range.first)

        var depth = 0
        var endIdx = -1
        for (i in fromGroup.indices) {
            when (fromGroup[i]) {
                '{' -> depth++
                '}' -> { depth--; if (depth == 0) { endIdx = i; break } }
            }
        }
        return if (endIdx >= 0) fromGroup.substring(0, endIdx + 1) else null
    }

    /**
     * When the reflexion loop is stuck on a repeating error (oscillation), extract each
     * FML group that contains a broken line and ask the LLM to fix ONLY that group via a
     * fresh, focused `chat()` call (no conversation history).
     *
     * Returns the patched FML (with fixed groups spliced in), or `null` when surgical
     * repair cannot be applied (no group found, or the LLM response cannot be parsed).
     *
     * Groups are processed bottom-up so that splicing earlier groups does not shift the
     * line indices of later groups.
     */
    private fun surgicalRepairGroups(
        fml: String,
        oscillatingErrors: List<String>,
        systemPrompt: String
    ): String? {
        val groupRanges = oscillatingErrors
            .mapNotNull { extractLineNumber(it) }
            .mapNotNull { ln -> extractGroupContaining(fml, ln) }
            .distinctBy { it.first }
            .sortedByDescending { it.first }

        if (groupRanges.isEmpty()) return null

        var result = fml
        var anyRepaired = false

        for (range in groupRanges) {
            val fmlLines = result.lines()
            val brokenGroup = fmlLines
                .subList(range.first, minOf(range.last + 1, fmlLines.size))
                .joinToString("\n")

            val errorsInGroup = oscillatingErrors.filter { err ->
                extractLineNumber(err)?.let { ln -> ln - 1 in range } == true
            }
            if (errorsInGroup.isEmpty()) continue

            val errorList = errorsInGroup.mapIndexed { i, e -> "  ${i + 1}. $e" }.joinToString("\n")
            val surgicalPrompt = buildString {
                appendLine("Fix the compilation errors in this FML group.")
                appendLine("Return ONLY the corrected group code starting with 'group'. No explanations, no other text.")
                appendLine()
                appendLine("ERRORS:")
                appendLine(errorList)
                appendLine()
                appendLine("BROKEN GROUP:")
                appendLine(brokenGroup)
                appendLine()
                appendLine("Rules:")
                appendLine("- Fix every error listed above.")
                appendLine("- NEVER use dotted type names (e.g. Patient.Contact) in group parameters — use BackboneElement.")
                appendLine("- NEVER use [x] suffix on element names.")
                appendLine("- NEVER use .ofType() or .as() as FHIRPath function calls in paths.")
                appendLine("- NEVER write multi-level source paths (e.g. src.a.b) — use sub-groups instead.")
                appendLine("- Every rule must have a unique quoted name before the semicolon.")
                appendLine("- Return ONLY the fixed group starting with 'group' and ending with '}'.")
            }

            log.info(
                "Surgical repair: sending group at lines {}-{} ({} lines, {} error(s)) to LLM",
                range.first + 1, range.last + 1, range.last - range.first + 1, errorsInGroup.size
            )
            val rawResponse = llmClient.chat(systemPrompt, surgicalPrompt, 0.5)
            log.info("Surgical repair: LLM response length: {}", rawResponse.length)

            val fixedGroup = extractGroupBlock(rawResponse) ?: run {
                log.warn("Surgical repair: could not extract group block from response — skipping this group")
                continue
            }

            // Splice: remove old lines (bottom-up index is safe here since we sorted descending)
            val lines = result.lines().toMutableList()
            for (i in range.last downTo range.first) lines.removeAt(i)
            fixedGroup.lines().forEachIndexed { i, line -> lines.add(range.first + i, line) }
            result = lines.joinToString("\n")

            anyRepaired = true
            log.info(
                "Surgical repair: replaced {} line(s) with {} line(s) at position {}",
                range.last - range.first + 1, fixedGroup.lines().size, range.first + 1
            )
        }

        return if (anyRepaired) result else null
    }

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
        val priorNorm = allPriorErrors.flatten().map { normaliseError(it) }.toSet()
        val newErrors = errors.filter { normaliseError(it) !in priorNorm }
        val effectiveErrors = if (newErrors.isNotEmpty()) newErrors else errors

        val oscillatingErrors = effectiveErrors.filter { err ->
            allPriorErrors.any { priorCycle -> err in priorCycle }
        }
        val isOscillating = oscillatingErrors.isNotEmpty()
        val hasRegressions = regressions.isNotEmpty()
        val temperature = when {
            isOscillating && hasRegressions -> 0.7
            isOscillating || hasRegressions -> 0.5
            else -> 0.1
        }

        val turnNum = conversationHistory.size + 1
        val attemptId = "cycle-${allPriorErrors.size + 1}-turn-$turnNum-errors-${effectiveErrors.size}"
        val errorList = effectiveErrors.mapIndexed { idx, err ->
            val lineNum = extractLineNumber(err)
            val lineText = if (lineNum != null) fml.lines().getOrElse(lineNum - 1) { "" } else ""
            "${idx + 1}. $err\n   line=${lineNum ?: "?"}\n   code=$lineText"
        }.joinToString("\n")
        val regressionSection = if (regressions.isEmpty()) {
            "None"
        } else {
            regressions.joinToString("\n") {
                "- line ${it.lineNum}: was '${it.previousLineContent}' now '${it.currentLineContent}'"
            }
        }
        val userMessage = promptTemplateService.loadTemplate(
            "reflexion-user.txt",
            mapOf(
                "attemptId" to attemptId,
                "fmlCode" to fml,
                "errorCount" to effectiveErrors.size.toString(),
                "errorList" to errorList,
                "sourceCanonical" to (source.url ?: ""),
                "targetCanonical" to (target.url ?: ""),
                "driftReport" to driftReport.toReflexionContext(),
                "regressionSection" to regressionSection
            )
        )

        log.info(
            "Reflexion turn {}: patch-only repair with {} error(s) in ONE LLM call (oscillation={}, regressions={})",
            turnNum,
            effectiveErrors.size,
            isOscillating,
            regressions.size
        )

        val systemPrompt = promptTemplateService.loadTemplate("reflexion-system.txt")

        val rawResponse = llmClient.chatWithHistory(systemPrompt, conversationHistory.toList(), userMessage, temperature)
        val patched = applyPatchFixesFromLlm(fml, rawResponse)
        conversationHistory.add(Pair(userMessage, rawResponse))
        return patched
    }

    private fun applyPatchFixesFromLlm(fml: String, rawResponse: String): String {
        val json = extractPatchPayload(rawResponse)
        val regex = Regex(
            """\{[\s\S]*?"line"\s*:\s*(\d+)[\s\S]*?"(?:fixedLine|replacement|newLine)"\s*:\s*"((?:\\\\.|[^"\\\\])*)"[\s\S]*?}"""
        )
        val matches = regex.findAll(json).toList()
        if (matches.isEmpty()) {
            // Backward compatibility: if model returned full FML, accept it instead of dropping the turn.
            val extracted = FmlUtils.extractFml(rawResponse)
            if (extracted.contains("map \"") && extracted != fml) {
                log.info("Patch-only reflexion returned full FML; using full response as fallback")
                return extracted
            }
            log.warn("Patch-only reflexion returned no line fixes; keeping current FML")
            return fml
        }

        val lines = fml.lines().toMutableList()
        val fixes = matches.mapNotNull { m ->
            val lineNum = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val fixedLine = unescapeJsonString(m.groupValues[2])
            lineNum to fixedLine
        }.sortedByDescending { it.first }

        fixes.forEach { (lineNum, fixedLine) ->
            if (lineNum in 1..lines.size) {
                val idx = lineNum - 1
                val replacementLines = fixedLine.split("\n")
                lines.removeAt(idx)
                lines.addAll(idx, replacementLines)
            }
        }

        log.info("Patch-only reflexion applied {} line-level fix(es)", fixes.size)
        return lines.joinToString("\n")
    }

    private fun extractPatchPayload(rawResponse: String): String {
        val trimmed = rawResponse.trim()
        val fenced = Regex("""```(?:json)?\s*([\s\S]*?)\s*```""")
            .find(trimmed)
            ?.groupValues?.get(1)
        if (fenced != null) return fenced.trim()

        val arrayStart = trimmed.indexOf('[')
        val arrayEnd = trimmed.lastIndexOf(']')
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1)
        }
        return trimmed
    }

    private fun unescapeJsonString(value: String): String {
        return value
            .replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
    }

    private fun persistFmlSnapshot(runId: String, cycle: Int, stage: String, fml: String) {
        runCatching {
            val dir = repairTraceDir.resolve(runId)
            Files.createDirectories(dir)
            val file = dir.resolve("cycle-${cycle.toString().padStart(2, '0')}-$stage.fml")
            Files.writeString(file, fml)
        }.onFailure {
            log.debug("Could not persist FML snapshot: {}", it.message)
        }
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
