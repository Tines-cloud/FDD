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

            // Auto-fix syntax-level errors deterministically - missing semicolons, unquoted rule
            // names, unicode quotes, etc.  These never need AI.
            currentFml = autoFixSyntaxErrors(currentFml)

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
            if (errorsByCycle.size >= 2 &&
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
            // apply anti-pattern sanitization: the AI can reintroduce the same bad patterns
            // (multi-line then, not() wrapping, etc.) that were fixed before the loop started.
            currentFml = sanitizeFml(currentFml)
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
     * HAPI stops at the first error it finds. To surface all errors:
     * 1. Parse the FML → record the error.
     * 2. **Replace** the bad line with a blank line (do NOT remove it - removing shifts
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
                    "collectAllErrors: same error repeated 3x - parser stuck, stopping. Error: {}",
                    errorMsg.take(100)
                )
                return errors
            }

            errors.add(errorMsg)

            // Find the bad line by its line number (e.g. "at 15, 7:") and blank it.
            // Blanking keeps all other line numbers identical to the original FML.
            val lineNum = Regex("""\bat\s+(\d+)\s*,\s*\d+\s*:""").find(errorMsg)
                ?.groupValues?.get(1)?.toIntOrNull()

            if (lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
                lines[lineNum - 1] = ""   // blank - do NOT removeAt
                return@repeat
            }

            // No line number in message - try to locate bad token in the text.
            // Skip "null" - that is a HAPI internal state token, not an FML keyword.
            val badToken = Regex("""['"](\w+)['"]""")
                .findAll(errorMsg).lastOrNull()?.groupValues?.get(1)

            if (badToken != null && badToken != "null") {
                val idx = lines.indexOfFirst { it.contains(badToken) }
                if (idx >= 0) {
                    lines[idx] = ""  // blank - do NOT removeAt
                    return@repeat
                }
            }

            // Cannot locate the bad line - stop scanning
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
        val aliasPattern =
            Regex("""^(\s*uses\s+"[^"]+")(\s+alias\s+\w+)(\s+as\s+(?:source|target)\s*)$""", RegexOption.MULTILINE)
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
        val notWithArgs = Regex("""\bnot\(([^()]*(?:\([^()]*\)[^()]*)*)\)""")
        result = notWithArgs.replace(result) { m ->
            rewrites++
            "(${m.groupValues[1]}).not()"
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
            // Base case: single segment - direct assignment is valid
            val innerRule = "  src -> ${parentAlias}.${segments[0]} = ${literal} ${ruleName.dropLast(1)}-inner\";"
            return "group ${groupName}(source src : Resource, target ${parentAlias} : Element) {\n${innerRule}\n}"
        }
        // Still nested: recurse one more level
        val nextSuffix = suffix * 1000 + segments.size
        val nextAlias = "_fdd${nextSuffix}_"
        val nextGroup = "_SetFdd${nextSuffix}_"
        val firstSeg = segments[0]
        val rest = segments.drop(1)
        val delegateRule = "  src -> ${parentAlias}.${firstSeg} as ${nextAlias} then ${nextGroup}(src, ${nextAlias}) ${
            ruleName.dropLast(1)
        }-mid\";"
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
     * Deterministically fix syntax-level FML errors - zero LLM cost.
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

    /**
     * Try to fix a single known syntax error deterministically.
     * Returns the corrected FML, or null if this is not a fixable syntax error.
     *
     * Fixable patterns:
     *   A. "Found X expecting ;" - missing semicolon on the preceding rule line
     *   B. "Found X expecting ;" on a line that ends with an unquoted rule name
     *   C. "Found X expecting a token name" - rule missing a name string
     */
    private fun trySyntaxFix(fml: String, error: String): String? {
        val lines = fml.lines().toMutableList()
        val lineNum = extractLineNumber(error)

        val expectingSemi = error.contains("expecting \";\"") || error.contains("expecting ';'")
        val expectingTokenName = error.contains("expecting a token name")

        // Fix A: Unquoted rule name on the error line itself.
        // HAPI says "Found 'word' expecting ';'" because the word is not a quoted string.
        // Pattern: the rule line ends with  )  unquoted-word  ;
        // Example: src -> tgt.x as v then CreateFoo(src, v) create-foo-name;
        if (expectingSemi && lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
            val line = lines[lineNum - 1]
            // A rule line containing -> that ends with ) then bare_identifier ;
            val unquotedNameAfterParen = Regex("""^(.*\))\s+([\w][\w-]*)\s*;\s*$""")
            val m = unquotedNameAfterParen.find(line)
            if (m != null && line.contains("->")) {
                val before = m.groupValues[1]
                val name = m.groupValues[2]
                val fmlKeywords = setOf(
                    "src", "tgt", "source", "target", "group", "uses", "map", "then", "as", "where"
                )
                if (name !in fmlKeywords && name.length > 1) {
                    lines[lineNum - 1] = "$before \"$name\";"
                    log.debug("autoFixSyntaxErrors: quoted unquoted rule name '{}' on line {}", name, lineNum)
                    return lines.joinToString("\n")
                }
            }
        }

        // Fix B: Missing semicolon on the line BEFORE the error.
        // HAPI reports "Found X expecting ;" at the START of the next rule (line N).
        // The rule that ended before line N is missing its terminating ';'.
        if (expectingSemi && lineNum != null) {
            for (i in (lineNum - 2) downTo maxOf(0, lineNum - 6)) {
                if (i >= lines.size) continue
                val trimmed = lines[i].trimEnd()
                if (trimmed.isEmpty() || trimmed.startsWith("//")) continue
                // Stop at a properly-terminated or structural line
                if (trimmed.endsWith(";") || trimmed.endsWith("{") || trimmed.endsWith("}")) break
                // This is a rule line with no terminating semicolon - add it
                if (trimmed.isNotEmpty()) {
                    lines[i] = "$trimmed;"
                    log.debug("autoFixSyntaxErrors: added missing ';' to line {}", i + 1)
                    return lines.joinToString("\n")
                }
                break
            }
        }

        // Fix C: "Found X expecting a token name" - rule missing its name string.
        // autoFixRuleNames() only catches "Complex rules must have an explicit name".
        // HAPI also emits this token-name variant for the same missing-name situation.
        if (expectingTokenName && lineNum != null && lineNum >= 1 && lineNum <= lines.size) {
            val line = lines[lineNum - 1]
            val trimmed = line.trimEnd()
            if (trimmed.endsWith(";") && trimmed.contains("->") &&
                !Regex(""""[^"]*"\s*;$""").containsMatchIn(trimmed)
            ) {
                val indent = line.length - line.trimStart().length
                val ruleName = "rule_line$lineNum"
                lines[lineNum - 1] = " ".repeat(indent) + trimmed.dropLast(1).trimEnd() + " \"$ruleName\";"
                log.debug("autoFixSyntaxErrors: added generated rule name to line {}", lineNum)
                return lines.joinToString("\n")
            }
        }

        return null  // semantic error - let AI handle it
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
        // Bump temperature aggressively when stuck so the LLM explores a different syntax
        // instead of returning a near-identical FML draft.
        val temperature = when {
            isOscillating && hasRegressions -> 0.7
            isOscillating || hasRegressions -> 0.5
            else -> 0.1
        }
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