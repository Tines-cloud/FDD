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
 * **Experiment 2 - Repair Syntactic Validity**
 *
 * For each gold-standard pair:
 * 1. Run full [DriftOrchestrationService.analyzeAndRepair].
 * 2. Record whether the generated StructureMap compiled successfully.
 * 3. Compute Syntactic Validity Rate = (# compiled) / (# generated).
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

    @Test
    @DisplayName("Evaluate syntactic validity rate of generated StructureMaps")
    fun evaluateSyntacticValidity() {
        val goldPairs = GoldStandardLoader.loadAll()
        if (goldPairs.isEmpty()) {
            log.warn("No gold-standard pairs found - skipping experiment")
            return
        }

        var totalGenerated = 0
        var totalValid = 0
        val results = mutableListOf<PairResult>()

        for (gold in goldPairs) {
            log.info("Generating repair map for: {}", gold.pairId)

            try {
                val (_, mapResult) = orchestrationService.analyzeAndRepair(
                    source = ProfileInput(canonical = gold.sourceCanonical),
                    target = ProfileInput(canonical = gold.targetCanonical)
                )

                totalGenerated++
                if (mapResult.syntacticallyValid) totalValid++

                results.add(
                    PairResult(gold.pairId, mapResult.syntacticallyValid, mapResult.validationMessages)
                )
            } catch (ex: Exception) {
                log.error("Failed for pair {}: {}", gold.pairId, ex.message)
                totalGenerated++
                results.add(PairResult(gold.pairId, false, listOf("Exception: ${ex.message}")))
            }
        }

        val validityRate = if (totalGenerated > 0) totalValid.toDouble() / totalGenerated else 0.0

        log.info("---------------------------------------------------")
        log.info("  EXPERIMENT 2 - Syntactic Validity Rate")
        log.info("---------------------------------------------------")
        results.forEach { r ->
            log.info("  {} -> valid: {}  messages: {}", r.pairId, r.valid, r.messages.size)
        }
        log.info("---------------------------------------------------")
        log.info("  Validity Rate: {}% ({}/{})", "%.1f".format(validityRate * 100), totalValid, totalGenerated)
        log.info("---------------------------------------------------")

        assertTrue(validityRate >= 0.0, "Validity rate must be non-negative")
    }

    private data class PairResult(
        val pairId: String,
        val valid: Boolean,
        val messages: List<String>
    )
}
