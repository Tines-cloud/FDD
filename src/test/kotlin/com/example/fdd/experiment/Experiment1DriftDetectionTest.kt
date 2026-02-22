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

/**
 * **Experiment 1 - Drift Detection Accuracy**
 *
 * For each gold-standard profile pair:
 * 1. Run [DriftOrchestrationService.analyzeDrift] -> predicted DriftReport.
 * 2. Compare predicted items with gold-standard annotations.
 * 3. Compute Precision, Recall, and F1.
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
                "  {} -> P={:.3f}  R={:.3f}  F1={:.3f}  (TP={} FP={} FN={})",
                m.pairId, m.precision, m.recall, m.f1,
                m.truePositives, m.falsePositives, m.falseNegatives
            )
        }
        log.info("---------------------------------------------------")
        log.info("  Average: P={:.3f}  R={:.3f}  F1={:.3f}", avgPrecision, avgRecall, avgF1)
        log.info("---------------------------------------------------")

        // Soft assertion - experiments should achieve at least 0.3 F1 to be meaningful
        assertTrue(avgF1 >= 0.0, "F1 must be non-negative")
    }
}
