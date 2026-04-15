package com.example.fdd.experiment

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.service.DriftOrchestrationService
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **Experiment 2 - Repair Syntactic Validity**
 *
 * For each gold-standard pair:
 * 1. Run full [DriftOrchestrationService.analyzeAndRepair].
 * 2. Record whether the generated StructureMap compiled successfully.
 * 3. Compute Syntactic Validity Rate = (# compiled) / (# generated).
 * 4. Write per-pair and aggregate results to a JSON file.
 *
 * This test requires a running LLM. Designed for evaluation, not routine CI.
 */
@SpringBootTest
@ActiveProfiles("experiment")
@Tag("integration")
class Experiment2SyntacticValidityTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var orchestrationService: DriftOrchestrationService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("Evaluate syntactic validity rate of generated StructureMaps")
    fun evaluateSyntacticValidity() {
        val allGoldPairs = GoldStandardLoader.loadAll()
        if (allGoldPairs.isEmpty()) {
            log.warn("No gold-standard pairs found - skipping experiment")
            return
        }

        // Support selective pair execution via system property
        val selectedIds = System.getProperty("fdd.pairs")?.split(",")?.map { it.trim() }
        val goldPairs = if (!selectedIds.isNullOrEmpty()) {
            val filtered = allGoldPairs.filter { it.pairId in selectedIds }
            log.info("Running selected pairs: {} (matched {}/{})", selectedIds, filtered.size, selectedIds.size)
            filtered
        } else {
            log.info("Running all {} gold-standard pairs", allGoldPairs.size)
            allGoldPairs
        }

        if (goldPairs.isEmpty()) {
            log.warn("No matching gold-standard pairs found for: {}", selectedIds)
            return
        }

        var totalGenerated = 0
        var totalValid = 0
        val results = mutableListOf<PairResult>()

        for (gold in goldPairs) {
            log.info("Generating repair map for: {}", gold.pairId)

            try {
                val (driftReport, mapResult, coverageReport) = orchestrationService.analyzeAndRepair(
                    source = ProfileInput(classpath = gold.sourceClasspath),
                    target = ProfileInput(classpath = gold.targetClasspath)
                )

                totalGenerated++
                if (mapResult.syntacticallyValid) totalValid++

                results.add(
                    PairResult(
                        pairId = gold.pairId,
                        valid = mapResult.syntacticallyValid,
                        messages = mapResult.validationMessages,
                        driftItemCount = driftReport.totalDrifts,
                        dataShareabilityPercent = coverageReport.dataShareabilityPercent,
                        repairCycles = mapResult.validationMessages.size
                    )
                )
            } catch (ex: Exception) {
                log.error("Failed for pair {}: {}", gold.pairId, ex.message)
                totalGenerated++
                results.add(
                    PairResult(
                        pairId = gold.pairId,
                        valid = false,
                        messages = listOf("Exception: ${ex.message}"),
                        driftItemCount = 0,
                        dataShareabilityPercent = 0.0,
                        repairCycles = 0
                    )
                )
            }
        }

        val validityRate = if (totalGenerated > 0) totalValid.toDouble() / totalGenerated else 0.0

        log.info("---------------------------------------------------")
        log.info("  EXPERIMENT 2 - Syntactic Validity Rate")
        log.info("---------------------------------------------------")
        results.forEach { r ->
            log.info(
                "  {} -> valid: {}  drifts: {}  shareability: {}%  messages: {}",
                r.pairId, r.valid, r.driftItemCount,
                "%.1f".format(r.dataShareabilityPercent), r.messages.size
            )
        }
        log.info("---------------------------------------------------")
        log.info("  Validity Rate: {}% ({}/{})", "%.1f".format(validityRate * 100), totalValid, totalGenerated)
        log.info("---------------------------------------------------")

        // Write results to JSON file
        writeResultsFile(results, validityRate, totalValid, totalGenerated)

        assertTrue(validityRate >= 0.0, "Validity rate must be non-negative")
    }

    private data class PairResult(
        val pairId: String,
        val valid: Boolean,
        val messages: List<String>,
        val driftItemCount: Int,
        val dataShareabilityPercent: Double,
        val repairCycles: Int
    )

    private fun writeResultsFile(
        results: List<PairResult>,
        validityRate: Double,
        totalValid: Int,
        totalGenerated: Int
    ) {
        try {
            val outputDir = Paths.get("output", "experiment-results")
            Files.createDirectories(outputDir)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val outputFile = outputDir.resolve("experiment2-syntactic-validity-$timestamp.json")

            val report = mapOf(
                "experiment" to "Experiment 2 - Syntactic Validity",
                "timestamp" to LocalDateTime.now().toString(),
                "totalGenerated" to totalGenerated,
                "totalValid" to totalValid,
                "syntacticValidityRate" to "%.1f".format(validityRate * 100),
                "perPairResults" to results.map { r ->
                    mapOf(
                        "pairId" to r.pairId,
                        "valid" to r.valid,
                        "driftItemCount" to r.driftItemCount,
                        "dataShareabilityPercent" to "%.1f".format(r.dataShareabilityPercent),
                        "repairCycles" to r.repairCycles,
                        "validationMessages" to r.messages
                    )
                },
                "failedPairs" to results.filter { !it.valid }.map { it.pairId }
            )

            Files.writeString(outputFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
            log.info("Experiment 2 results written to: {}", outputFile)
        } catch (ex: Exception) {
            log.warn("Failed to write experiment results file: {}", ex.message)
        }
    }
}
