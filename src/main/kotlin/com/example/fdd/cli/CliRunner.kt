package com.example.fdd.cli

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.api.dto.ValidationSummary
import com.example.fdd.output.impl.OutputStore
import com.example.fdd.service.DriftOrchestrationService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.io.File

/**
 * CLI runner activated with `--spring.profiles.active=cli`.
 *
 * When this profile is active the embedded web server is disabled
 * (see `application-cli.yaml`), so the process runs the command and exits.
 *
 * ## Usage
 *
 * ```
 * java -jar fdd.jar --spring.profiles.active=cli \
 *   --mode=analyze \
 *   --source-canonical=http://hl7.org/fhir/StructureDefinition/Patient \
 *   --target-canonical=http://hl7.org/fhir/StructureDefinition/Observation
 * ```
 *
 * ### Arguments
 *
 * | Argument              | Required | Description |
 * |-----------------------|----------|-------------|
 * | `--mode`              | yes      | `analyze` or `repair` |
 * | `--source-canonical`  | one of   | Source profile canonical URL |
 * | `--source-url`        | one of   | Source profile HTTP URL |
 * | `--source-file`       | one of   | Source profile local .json file path |
 * | `--source-classpath`  | one of   | Source profile classpath resource |
 * | `--target-canonical`  | one of   | Target profile canonical URL |
 * | `--target-url`        | one of   | Target profile HTTP URL |
 * | `--target-file`       | one of   | Target profile local .json file path |
 * | `--target-classpath`  | one of   | Target profile classpath resource |
 * | `--output`            | no       | File path to write JSON output |
 */
@Component
@Profile("cli")
class CliRunner(
    private val orchestrationService: DriftOrchestrationService,
    private val objectMapper: ObjectMapper,
    private val outputStore: OutputStore
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        try {
            val mode = args.requiredOption("mode")
            val source = buildProfileInput(args, "source")
            val target = buildProfileInput(args, "target")
            val outputFile = args.optionalOption("output")

            log.info("CLI mode={}, source={}, target={}", mode, source.label(), target.label())

            val outputContext = outputStore.createContext(
                requestType = "cli-${mode.lowercase()}",
                sourceLabel = source.label(),
                targetLabel = target.label(),
                requestPayload = mapOf("mode" to mode, "source" to source, "target" to target)
            )

            val json = when (mode.lowercase()) {
                "analyze" -> runAnalyze(source, target, outputContext)
                "repair" -> runRepair(source, target, outputContext)
                else -> {
                    System.err.println("ERROR: --mode must be 'analyze' or 'repair' (got '$mode')")
                    printUsage()
                    return
                }
            }

            if (outputFile != null) {
                File(outputFile).writeText(json)
                println("Output written to $outputFile")
            } else {
                println(json)
            }

        } catch (ex: IllegalArgumentException) {
            System.err.println("ERROR: ${ex.message}")
            printUsage()
        } catch (ex: Exception) {
            System.err.println("ERROR: ${ex.javaClass.simpleName}: ${ex.message}")
            log.error("CLI execution failed", ex)
        }
    }

    private fun runAnalyze(
        source: ProfileInput,
        target: ProfileInput,
        outputContext: OutputStore.OutputContext?
    ): String {
        val driftReport = orchestrationService.analyzeDrift(source, target)
        val responsePayload = mapOf("driftReport" to driftReport)
        outputStore.writeAnalyzeResult(outputContext, driftReport, responsePayload)
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responsePayload)
    }

    private fun runRepair(
        source: ProfileInput,
        target: ProfileInput,
        outputContext: OutputStore.OutputContext?
    ): String {
        val (driftReport, mapResult, coverageReport) = orchestrationService.analyzeAndRepair(source, target)
        val response = RepairResponse(
            driftReport = driftReport,
            structureMap = mapResult.structureMapFml,
            validation = ValidationSummary(
                syntacticallyValid = mapResult.syntacticallyValid,
                messages = mapResult.validationMessages
            ),
            coverage = coverageReport,
            outputDirectory = outputContext?.directory?.toString()
        )
        outputStore.writeRepairResult(outputContext, response)
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response)
    }

    private fun buildProfileInput(args: ApplicationArguments, prefix: String): ProfileInput {
        val canonical = args.optionalOption("$prefix-canonical")
        val url = args.optionalOption("$prefix-url")
        val classpath = args.optionalOption("$prefix-classpath")
        val filePath = args.optionalOption("$prefix-file")

        // --source-file / --target-file -> read file contents and pass as inline JSON
        val json: String? = filePath?.let {
            val file = File(it)
            require(file.exists()) { "File not found: $it" }
            require(file.extension.equals("json", ignoreCase = true)) {
                "Only .json files are supported (got: ${file.name})"
            }
            file.readText()
        }

        require(canonical != null || url != null || classpath != null || json != null) {
            "At least one of --$prefix-canonical, --$prefix-url, --$prefix-classpath, or --$prefix-file must be provided"
        }

        return ProfileInput(
            json = json,
            canonical = canonical,
            url = url,
            classpath = classpath
        )
    }

    private fun ApplicationArguments.requiredOption(name: String): String =
        optionalOption(name)
            ?: throw IllegalArgumentException("Required argument --$name is missing")

    private fun ApplicationArguments.optionalOption(name: String): String? =
        if (containsOption(name)) getOptionValues(name)?.firstOrNull() else null

    private fun printUsage() {
        System.err.println(
            """
            |
            |Usage:
            |  java -jar fdd.jar --spring.profiles.active=cli [OPTIONS]
            |
            |Required:
            |  --mode=analyze|repair        Analysis mode
            |
            |Source profile (at least one):
            |  --source-canonical=URL       Canonical URL (e.g. http://hl7.org/fhir/StructureDefinition/Patient)
            |  --source-url=URL             HTTP URL to a StructureDefinition JSON
            |  --source-file=PATH           Local .json file path (absolute or relative)
            |  --source-classpath=PATH      Classpath resource path
            |
            |Target profile (at least one):
            |  --target-canonical=URL       Canonical URL
            |  --target-url=URL             HTTP URL
            |  --target-file=PATH           Local .json file path (absolute or relative)
            |  --target-classpath=PATH      Classpath resource path
            |
            |Optional:
            |  --output=FILE                Write JSON output to file
            |
            |Examples:
            |  java -jar fdd.jar --spring.profiles.active=cli \
            |    --mode=analyze \
            |    --source-canonical=http://hl7.org/fhir/StructureDefinition/Patient \
            |    --target-canonical=http://hl7.org/fhir/StructureDefinition/Observation
            |
            |  java -jar fdd.jar --spring.profiles.active=cli \
            |    --mode=repair \
            |    --source-url=https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json \
            |    --target-canonical=http://hl7.org/fhir/StructureDefinition/Patient \
            |    --output=result.json
            |
            |  java -jar fdd.jar --spring.profiles.active=cli \
            |    --mode=analyze \
            |    --source-file=C:\profiles\source-patient.json \
            |    --target-file=C:\profiles\target-patient.json
            """.trimMargin()
        )
    }
}
