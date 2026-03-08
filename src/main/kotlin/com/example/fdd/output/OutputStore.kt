package com.example.fdd.output

import com.example.fdd.api.dto.ErrorResponse
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.config.FddProperties
import com.example.fdd.exception.MapValidationException
import com.example.fdd.model.CoverageReport
import com.example.fdd.model.CoverageStatus
import com.example.fdd.model.DriftReport
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Component
class OutputStore(
    private val props: FddProperties,
    private val objectMapper: ObjectMapper
) {

    companion object {
        const val OUTPUT_CONTEXT_ATTRIBUTE = "fdd.output.context"
    }

    data class OutputContext(
        val directory: Path,
        val requestType: String
    )

    private val log = LoggerFactory.getLogger(javaClass)
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    fun createContext(
        requestType: String,
        sourceLabel: String,
        targetLabel: String,
        requestPayload: Any
    ): OutputContext? {
        if (!props.output.enabled) {
            return null
        }

        val directory = createRequestDirectory(requestType)
        writeJson(
            directory, "metadata.json", mapOf(
                "requestType" to requestType,
                "source" to sourceLabel,
                "target" to targetLabel,
                "timestamp" to LocalDateTime.now().toString()
            )
        )
        writeJson(directory, "request.json", requestPayload)
        return OutputContext(directory, requestType)
    }

    fun attachToRequest(httpRequest: HttpServletRequest, context: OutputContext?) {
        if (context == null) {
            return
        }
        httpRequest.setAttribute(OUTPUT_CONTEXT_ATTRIBUTE, context)
    }

    fun writeAnalyzeResult(context: OutputContext?, driftReport: DriftReport, responsePayload: Any) {
        if (context == null) {
            return
        }
        writeJson(context.directory, "drift-report.json", driftReport)
        writeJson(context.directory, "response.json", responsePayload)
    }

    fun writeRepairResult(context: OutputContext?, response: RepairResponse) {
        if (context == null) {
            return
        }
        writeJson(context.directory, "drift-report.json", response.driftReport)
        writeJson(context.directory, "validation.json", response.validation)
        writeJson(context.directory, "coverage-report.json", response.coverage)
        writeText(context.directory, "coverage-report.txt", response.coverage.summary + buildCoverageDetails(response.coverage))
        writeJson(context.directory, "response.json", response)
        writeText(context.directory, "structure-map.fml", response.structureMap)
    }

    fun writeError(httpRequest: HttpServletRequest, error: ErrorResponse, ex: Exception) {
        val context = httpRequest.getAttribute(OUTPUT_CONTEXT_ATTRIBUTE) as? OutputContext
            ?: return

        writeJson(context.directory, "error.json", error)
        writeText(context.directory, "error.txt", buildErrorReport(error, ex))
    }

    /**
     * Build a readable error report for error.txt.
     * For FML validation failures, it groups errors by cycle so you can see
     * what went wrong in each repair attempt.
     */
    private fun buildErrorReport(error: ErrorResponse, ex: Exception): String {
        val sb = StringBuilder()
        val sep = "=".repeat(72)
        val thin = "-".repeat(72)

        sb.appendLine(sep)
        sb.appendLine("ERROR CODE    : ${error.code}")
        sb.appendLine("ERROR MESSAGE : ${error.message}")
        sb.appendLine("TIMESTAMP     : ${LocalDateTime.now()}")
        sb.appendLine(sep)

        // Per-attempt FML compilation failure details (MapValidationException only)
        if (ex is MapValidationException && ex.attemptErrors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("FML COMPILATION FAILURE TRACE")
            sb.appendLine(thin)

            // Group errors by cycle number from the "[Cycle N]" prefix
            val byCycle = LinkedHashMap<Int, MutableList<String>>()
            ex.attemptErrors.forEach { entry ->
                val cycleNum = Regex("""^\[Cycle (\d+)\]""").find(entry)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                byCycle.getOrPut(cycleNum) { mutableListOf() }
                    .add(entry.removePrefix("[Cycle $cycleNum] "))
            }

            val totalCycles = byCycle.keys.maxOrNull() ?: 1
            val totalErrors = ex.attemptErrors.size
            sb.appendLine("Repair cycles attempted : $totalCycles")
            sb.appendLine("Total errors collected  : $totalErrors")
            sb.appendLine("Note: each cycle = 1 LLM call fixing ALL errors found in that cycle")
            sb.appendLine()

            byCycle.forEach { (cycleNum, errors) ->
                val isLast = cycleNum == totalCycles
                sb.appendLine("  Cycle $cycleNum${if (isLast) " (final - exhausted)" else ""}: ${errors.size} error(s) found")
                errors.forEach { err -> sb.appendLine("    - $err") }
                if (!isLast) {
                    sb.appendLine("    -> Action: sent all ${errors.size} error(s) to LLM in ONE reflexion call")
                } else {
                    sb.appendLine("    -> Action: no more repair cycles remaining - validation failed")
                }
                sb.appendLine()
            }
            sb.appendLine(thin)
        } else if (error.details.isNotEmpty()) {
            // Fallback: any other exception type that has details (e.g. ProfileValidationException)
            sb.appendLine()
            sb.appendLine("DETAILS")
            sb.appendLine(thin)
            error.details.forEachIndexed { idx, detail ->
                sb.appendLine("  ${idx + 1}. $detail")
            }
            sb.appendLine(thin)
        }

        sb.appendLine()
        sb.appendLine("FULL STACK TRACE")
        sb.appendLine(thin)
        sb.appendLine(stackTrace(ex))

        return sb.toString()
    }

    /**
     * Build a detailed per-item breakdown for the coverage report text file.
     * Groups items by coverage status with table-like formatting for each category.
     */
    private fun buildCoverageDetails(report: CoverageReport): String {
        val sb = StringBuilder()
        val sep = "=".repeat(72)
        val thin = "-".repeat(72)

        val grouped = report.items.groupBy { it.coverageStatus }

        // Category 1: Actively Mapped
        val mapped = grouped[CoverageStatus.MAPPED].orEmpty()
        if (mapped.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("CATEGORY 1: ACTIVELY MAPPED IN FML (${mapped.size} items) -- COVERED")
            sb.appendLine(sep)
            sb.appendLine()
            sb.appendLine("%-12s %-50s %s".format("Rule(s)", "What", "FML Handling"))
            sb.appendLine("%-12s %-50s %s".format("-".repeat(12), "-".repeat(50), "-".repeat(40)))
            for (item in mapped) {
                sb.appendLine("%-12s %-50s %s".format(
                    item.driftItemId,
                    truncate(item.description, 50),
                    item.fmlHandling
                ))
            }
            sb.appendLine()
        }

        // Category 2: No Rule Needed (metadata)
        val metadata = grouped[CoverageStatus.NO_RULE_NEEDED].orEmpty()
        if (metadata.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("CATEGORY 2: PROFILE METADATA -- NO FML RULE NEEDED (${metadata.size} items)")
            sb.appendLine(sep)
            sb.appendLine()
            sb.appendLine("These are profile-level metadata that DON'T affect instance data transformation:")
            sb.appendLine()
            sb.appendLine("%-12s %-50s %s".format("Rule(s)", "What", "Why no FML needed"))
            sb.appendLine("%-12s %-50s %s".format("-".repeat(12), "-".repeat(50), "-".repeat(40)))
            for (item in metadata) {
                sb.appendLine("%-12s %-50s %s".format(
                    item.driftItemId,
                    truncate(item.description, 50),
                    item.explanation
                ))
            }
            sb.appendLine()
        }

        // Category 3: Covered by Parent
        val byParent = grouped[CoverageStatus.COVERED_BY_PARENT].orEmpty()
        if (byParent.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("CATEGORY 3: COVERED BY PARENT/GROUP MAPPING (${byParent.size} items)")
            sb.appendLine(sep)
            sb.appendLine()
            sb.appendLine("%-12s %-50s %s".format("Rule(s)", "What", "FML Handling"))
            sb.appendLine("%-12s %-50s %s".format("-".repeat(12), "-".repeat(50), "-".repeat(40)))
            for (item in byParent) {
                sb.appendLine("%-12s %-50s %s".format(
                    item.driftItemId,
                    truncate(item.description, 50),
                    item.fmlHandling
                ))
            }
            sb.appendLine()
        }

        // Category 4: Source Data Loss
        val sourceLoss = grouped[CoverageStatus.SOURCE_DATA_LOSS].orEmpty()
        if (sourceLoss.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("CATEGORY 4: SOURCE DATA LOSS -- DROPPED IN TRANSFORMATION (${sourceLoss.size} items)")
            sb.appendLine(sep)
            sb.appendLine()
            sb.appendLine("%-12s %-45s %s".format("Rule(s)", "What", "Why"))
            sb.appendLine("%-12s %-45s %s".format("-".repeat(12), "-".repeat(45), "-".repeat(45)))
            for (item in sourceLoss) {
                sb.appendLine("%-12s %-45s %s".format(
                    item.driftItemId,
                    truncate("${item.sourcePath}: ${item.description}", 45),
                    item.fmlHandling
                ))
            }
            sb.appendLine()
        }

        // Category 5: Unmappable
        val unmappable = grouped[CoverageStatus.UNMAPPABLE_NO_SOURCE].orEmpty()
        if (unmappable.isNotEmpty()) {
            sb.appendLine(sep)
            sb.appendLine("CATEGORY 5: UNMAPPABLE -- NO SOURCE DATA (${unmappable.size} items)")
            sb.appendLine(sep)
            sb.appendLine()
            sb.appendLine("%-12s %-45s %s".format("Rule(s)", "What", "Why unmappable"))
            sb.appendLine("%-12s %-45s %s".format("-".repeat(12), "-".repeat(45), "-".repeat(45)))
            for (item in unmappable) {
                sb.appendLine("%-12s %-45s %s".format(
                    item.driftItemId,
                    truncate("${item.targetPath}: ${item.description}", 45),
                    item.explanation
                ))
            }
            sb.appendLine()
        }

        // Verdict
        sb.appendLine(sep)
        sb.appendLine("VERDICT: ${report.verdict}")
        sb.appendLine(sep)
        sb.appendLine()
        sb.appendLine("  ${report.mapped} actively mapped with FML rules")
        sb.appendLine("  ${report.coveredByParent} covered by parent/group mapping")
        sb.appendLine("  ${report.noRuleNeeded} correctly handled (profile metadata, no data impact)")
        if (report.sourceDataLoss > 0) {
            sb.appendLine("  ${report.sourceDataLoss} source data loss (source-only elements not in target)")
        }
        if (report.unmappableNoSource > 0) {
            sb.appendLine("  ${report.unmappableNoSource} unmappable (target-only elements with no source data)")
        }
        sb.appendLine()

        return sb.toString()
    }

    private fun truncate(text: String, maxLen: Int): String {
        val singleLine = text.replace("\n", " ").trim()
        return if (singleLine.length <= maxLen) singleLine
        else singleLine.take(maxLen - 3) + "..."
    }

    private fun createRequestDirectory(requestType: String): Path {
        val root = Paths.get(props.output.directory)
        val timestamp = LocalDateTime.now().format(timestampFormat)
        val suffix = UUID.randomUUID().toString().take(8)
        val directory = root.resolve("$timestamp-$requestType-$suffix")
        Files.createDirectories(directory)
        return directory
    }

    private fun writeJson(directory: Path, fileName: String, value: Any) {
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(fileName).toFile(), value)
        } catch (ex: Exception) {
            log.warn("Failed to write output JSON {}: {}", fileName, ex.message)
        }
    }

    private fun writeText(directory: Path, fileName: String, content: String) {
        try {
            Files.writeString(directory.resolve(fileName), content)
        } catch (ex: Exception) {
            log.warn("Failed to write output text {}: {}", fileName, ex.message)
        }
    }

    private fun stackTrace(ex: Exception): String {
        val writer = StringWriter()
        ex.printStackTrace(PrintWriter(writer))
        return writer.toString()
    }
}
