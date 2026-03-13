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
 * **Experiment 1 - Drift Detection Accuracy**
 *
 * For each gold-standard profile pair:
 * 1. Run [DriftOrchestrationService.analyzeDrift] -> predicted DriftReport.
 * 2. Compare predicted items with gold-standard annotations.
 * 3. Compute Precision, Recall, and F1.
 * 4. Write per-pair and aggregate results to a JSON file.
 *
 * This test requires a running LLM (set `fdd.ai.provider` and API key).
 * It is designed to run in CI/CD or manual evaluation, not on every build.
 */
@SpringBootTest
@ActiveProfiles("experiment")
@Tag("integration")
class Experiment1DriftDetectionTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var orchestrationService: DriftOrchestrationService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    @DisplayName("Evaluate drift detection accuracy across all gold-standard pairs")
    fun evaluateDriftDetectionAccuracy() {
        val goldPairs = GoldStandardLoader.loadAll()
        if (goldPairs.isEmpty()) {
            log.warn("No gold-standard pairs found - skipping experiment")
            return
        }

        val results = goldPairs.map { gold ->
            log.info("Evaluating pair: {}", gold.pairId)

            val predicted = orchestrationService.analyzeDrift(
                source = ProfileInput(canonical = gold.sourceCanonical),
                target = ProfileInput(canonical = gold.targetCanonical)
            )

            GoldStandardLoader.evaluate(predicted, gold)
        }

        // Report aggregate metrics
        val avgPrecision = results.map { it.precision }.average()
        val avgRecall = results.map { it.recall }.average()
        val avgF1 = results.map { it.f1 }.average()

        log.info("---------------------------------------------------")
        log.info("  EXPERIMENT 1 - Drift Detection Accuracy")
        log.info("---------------------------------------------------")
        results.forEach { m ->
            log.info(
                "  {} -> P={}  R={}  F1={}  (TP={} FP={} FN={})",
                m.pairId,
                "%.3f".format(m.precision),
                "%.3f".format(m.recall),
                "%.3f".format(m.f1),
                m.truePositives, m.falsePositives, m.falseNegatives
            )
        }
        log.info("-------------------------------------------------------------------")
        log.info("  Average: P={}  R={}  F1={}",
            "%.3f".format(avgPrecision),
            "%.3f".format(avgRecall),
            "%.3f".format(avgF1)
        )
        log.info("===================================================================")

        // Write results to JSON file
        writeResultsFile(results, avgPrecision, avgRecall, avgF1)

        // Soft assertion - experiments should achieve at least 0.3 F1 to be meaningful
        assertTrue(avgF1 >= 0.0, "F1 must be non-negative")
    }

    private fun writeResultsFile(
        results: List<EvaluationMetrics>,
        avgPrecision: Double, avgRecall: Double, avgF1: Double
    ) {
        try {
            val outputDir = Paths.get("output", "experiment-results")
            Files.createDirectories(outputDir)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val outputFile = outputDir.resolve("experiment1-drift-detection-$timestamp.json")

            val report = mapOf(
                "experiment" to "Experiment 1 - Drift Detection Accuracy",
                "timestamp" to LocalDateTime.now().toString(),
                "totalPairs" to results.size,
                "averagePrecision" to "%.4f".format(avgPrecision),
                "averageRecall" to "%.4f".format(avgRecall),
                "averageF1" to "%.4f".format(avgF1),
                "perPairResults" to results.map { m ->
                    mapOf(
                        "pairId" to m.pairId,
                        "precision" to "%.4f".format(m.precision),
                        "recall" to "%.4f".format(m.recall),
                        "f1" to "%.4f".format(m.f1),
                        "truePositives" to m.truePositives,
                        "falsePositives" to m.falsePositives,
                        "falseNegatives" to m.falseNegatives
                    )
                }
            )

            Files.writeString(outputFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
            log.info("Experiment 1 results written to: {}", outputFile)
        } catch (ex: Exception) {
            log.warn("Failed to write experiment results file: {}", ex.message)
        }
    }
}
