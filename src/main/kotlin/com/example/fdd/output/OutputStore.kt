package com.example.fdd.output

import com.example.fdd.api.dto.ErrorResponse
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.config.FddProperties
import com.example.fdd.exception.MapValidationException
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
     * Builds a human-readable error report for error.txt.
     *
     * For [MapValidationException] the report includes every per-attempt FML compilation
     * failure with its reason, so it is immediately clear why each reflexion turn failed.
     * The full Java stack trace is appended at the bottom for debugging.
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

            // Group entries by "[Cycle N]" prefix - one cycle = one LLM repair call
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
