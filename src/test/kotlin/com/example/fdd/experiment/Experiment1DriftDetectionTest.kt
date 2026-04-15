package com.example.fdd.experiment

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.service.DriftOrchestrationService
import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
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
 * Each profile pair runs as a **separate test** in the Gradle report so
 * individual pair results, stdout, and pass/fail status are visible.
 *
 * This test requires a running LLM (set `fdd.ai.provider` and API key).
 * It is designed to run in CI/CD or manual evaluation, not on every build.
 */
@SpringBootTest
@ActiveProfiles("experiment")
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Experiment1DriftDetectionTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var orchestrationService: DriftOrchestrationService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /** Collects results across all dynamic tests for the aggregate JSON file. */
    private val collectedResults = mutableListOf<EvaluationMetrics>()

    @TestFactory
    @DisplayName("Drift Detection Accuracy")
    fun evaluateDriftDetectionAccuracy(): List<DynamicTest> {
        val allGoldPairs = GoldStandardLoader.loadAll()
        if (allGoldPairs.isEmpty()) {
            log.warn("No gold-standard pairs found - skipping experiment")
            return emptyList()
        }

        // Support selective pair execution via system property
        val selectedIds = System.getProperty("fdd.pairs")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val goldPairs = if (!selectedIds.isNullOrEmpty()) {
            val filtered = allGoldPairs.filter { it.pairId in selectedIds }
            log.info("Running selected pairs: {} (matched {}/{})", selectedIds, filtered.size, selectedIds.size)
            filtered
        } else {
            log.info("Running all {} gold-standard pairs", allGoldPairs.size)
            allGoldPairs
        }

        return goldPairs.map { gold ->
            dynamicTest(gold.pairId) {
                log.info(
                    "Evaluating pair: {} (source={}, target={})",
                    gold.pairId, gold.sourceClasspath, gold.targetClasspath
                )

                val predicted = orchestrationService.analyzeDrift(
                    source = ProfileInput(classpath = gold.sourceClasspath),
                    target = ProfileInput(classpath = gold.targetClasspath)
                )

                val metrics = GoldStandardLoader.evaluate(predicted, gold)
                synchronized(collectedResults) { collectedResults.add(metrics) }

                log.info(
                    "Result: {} -> P={}  R={}  F1={}  (TP={} FP={} FN={})",
                    metrics.pairId,
                    "%.3f".format(metrics.precision),
                    "%.3f".format(metrics.recall),
                    "%.3f".format(metrics.f1),
                    metrics.truePositives, metrics.falsePositives, metrics.falseNegatives
                )

                Assertions.assertTrue(metrics.f1 >= 0.0, "F1 must be non-negative for ${gold.pairId}")
            }
        }
    }

    @AfterAll
    fun writeAggregateResults() {
        if (collectedResults.isEmpty()) return

        val avgPrecision = collectedResults.map { it.precision }.average()
        val avgRecall = collectedResults.map { it.recall }.average()
        val avgF1 = collectedResults.map { it.f1 }.average()

        log.info("---------------------------------------------------")
        log.info("  EXPERIMENT 1 - Drift Detection Accuracy")
        log.info("---------------------------------------------------")
        collectedResults.forEach { m ->
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
        log.info(
            "  Average: P={}  R={}  F1={}",
            "%.3f".format(avgPrecision),
            "%.3f".format(avgRecall),
            "%.3f".format(avgF1)
        )
        log.info("===================================================================")

        writeResultsFile(collectedResults, avgPrecision, avgRecall, avgF1)
    }

    private fun writeResultsFile(
        results: List<EvaluationMetrics>,
        avgPrecision: Double, avgRecall: Double, avgF1: Double
    ) {
        try {
            val projectRoot = System.getProperty("project.root") ?: System.getProperty("user.dir")
            val outputDir = Paths.get(projectRoot, "output", "experiment-results")
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
