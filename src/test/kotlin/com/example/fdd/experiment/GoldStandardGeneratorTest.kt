package com.example.fdd.experiment

import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.fhir.ProfileLoader
import com.example.fdd.model.GoldStandardPair
import com.example.fdd.service.RuleBasedDriftDetector
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

/**
 * **Gold Standard Generator Utility**
 *
 * This is NOT an experiment test. It is a one-time utility that generates
 * accurate gold-standard JSON files by running the deterministic
 * [RuleBasedDriftDetector] against each profile pair.
 *
 * **Purpose**: The rule-based detector reads actual profile StructureDefinitions
 * from the classpath and computes exact structural drifts. Its output is used
 * as the ground-truth baseline for Experiment 1 (drift detection accuracy).
 *
 * **When to run**: Run this whenever profiles change or new pairs are added.
 * Review the output, then copy to `src/test/resources/gold-standard/`.
 *
 * **Usage**:
 * ```
 * ./gradlew integrationTest --tests "*.GoldStandardGeneratorTest.generateAllGoldStandards"
 * ```
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("integration")
class GoldStandardGeneratorTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var profileLoader: ProfileLoader

    @Autowired
    private lateinit var profileContextBuilder: ProfileContextBuilder

    @Autowired
    private lateinit var ruleBasedDetector: RuleBasedDriftDetector

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    companion object {
        // ── Classpath path helpers ──
        private const val R4 = "standard-profiles/r4"
        private const val US = "standard-profiles/us-core"
        private const val AU = "standard-profiles/au-core"
        private const val HM = "custom-profiles/hemas"
        private const val IP = "custom-profiles/iit-proj"
        private const val TK = "custom-profiles/tk-soft"

        private fun r4(type: String) = "$R4/${type.lowercase()}.profile.json"
        private fun us(name: String) = "$US/StructureDefinition-$name.json"
        private fun au(name: String) = "$AU/StructureDefinition-$name.json"
        private fun hm(type: String) = "$HM/hemas-${type.lowercase()}.json"
        private fun ip(type: String) = "$IP/iit-proj-${type.lowercase()}.json"
        private fun tk(type: String) = "$TK/tk-soft-${type.lowercase()}.json"
    }

    /**
     * All 60 profile pairs, organized by category.
     * Each pair references classpath-relative paths to actual profile JSON files.
     */
    private val allPairs = listOf(
        // ── Category 1: R4 Base → US Core (14 pairs) ──
        PairDef("r4-vs-us-core-patient", r4("patient"), us("us-core-patient")),
        PairDef("r4-allergyintolerance-vs-us-core-allergyintolerance", r4("allergyintolerance"), us("us-core-allergyintolerance")),
        PairDef("r4-careplan-vs-us-core-careplan", r4("careplan"), us("us-core-careplan")),
        PairDef("r4-condition-vs-us-core-condition", r4("condition"), us("us-core-condition-encounter-diagnosis")),
        PairDef("r4-diagnosticreport-vs-us-core-diagnosticreport-lab", r4("diagnosticreport"), us("us-core-diagnosticreport-lab")),
        PairDef("r4-encounter-vs-us-core-encounter", r4("encounter"), us("us-core-encounter")),
        PairDef("r4-immunization-vs-us-core-immunization", r4("immunization"), us("us-core-immunization")),
        PairDef("r4-location-vs-us-core-location", r4("location"), us("us-core-location")),
        PairDef("r4-medication-vs-us-core-medication", r4("medication"), us("us-core-medication")),
        PairDef("r4-medicationrequest-vs-us-core-medicationrequest", r4("medicationrequest"), us("us-core-medicationrequest")),
        PairDef("r4-observation-vs-us-core-observation", r4("observation"), us("us-core-observation-lab")),
        PairDef("r4-organization-vs-us-core-organization", r4("organization"), us("us-core-organization")),
        PairDef("r4-practitioner-vs-us-core-practitioner", r4("practitioner"), us("us-core-practitioner")),
        PairDef("r4-procedure-vs-us-core-procedure", r4("procedure"), us("us-core-procedure")),

        // ── Category 2: AU Core → US Core — cross-national (11 pairs) ──
        PairDef("au-core-patient-vs-us-core-patient", au("au-core-patient"), us("us-core-patient")),
        PairDef("au-core-allergyintolerance-vs-us-core-allergyintolerance", au("au-core-allergyintolerance"), us("us-core-allergyintolerance")),
        PairDef("au-core-condition-vs-us-core-condition", au("au-core-condition"), us("us-core-condition-encounter-diagnosis")),
        PairDef("au-core-encounter-vs-us-core-encounter", au("au-core-encounter"), us("us-core-encounter")),
        PairDef("au-core-immunization-vs-us-core-immunization", au("au-core-immunization"), us("us-core-immunization")),
        PairDef("au-core-location-vs-us-core-location", au("au-core-location"), us("us-core-location")),
        PairDef("au-core-medication-vs-us-core-medication", au("au-core-medication"), us("us-core-medication")),
        PairDef("au-core-medicationrequest-vs-us-core-medicationrequest", au("au-core-medicationrequest"), us("us-core-medicationrequest")),
        PairDef("au-core-organization-vs-us-core-organization", au("au-core-organization"), us("us-core-organization")),
        PairDef("au-core-practitioner-vs-us-core-practitioner", au("au-core-practitioner"), us("us-core-practitioner")),
        PairDef("au-core-procedure-vs-us-core-procedure", au("au-core-procedure"), us("us-core-procedure")),

        // ── Category 3: TK-Soft → IIT-Proj — custom vs custom (7 pairs) ──
        PairDef("tk-soft-patient-vs-iit-proj-patient", tk("patient"), ip("patient")),
        PairDef("tk-soft-observation-vs-iit-proj-observation", tk("observation"), ip("observation")),
        PairDef("tk-soft-condition-vs-iit-proj-condition", tk("condition"), ip("condition")),
        PairDef("tk-soft-practitioner-vs-iit-proj-practitioner", tk("practitioner"), ip("practitioner")),
        PairDef("tk-soft-encounter-vs-iit-proj-encounter", tk("encounter"), ip("encounter")),
        PairDef("tk-soft-medication-vs-iit-proj-medication", tk("medication"), ip("medication")),
        PairDef("tk-soft-allergyintolerance-vs-iit-proj-allergyintolerance", tk("allergyintolerance"), ip("allergyintolerance")),

        // ── Category 4: TK-Soft → Hemas — custom vs custom (5 pairs) ──
        PairDef("tk-soft-patient-vs-hemas-patient", tk("patient"), hm("patient")),
        PairDef("tk-soft-observation-vs-hemas-observation", tk("observation"), hm("observation")),
        PairDef("tk-soft-condition-vs-hemas-condition", tk("condition"), hm("condition")),
        PairDef("tk-soft-encounter-vs-hemas-encounter", tk("encounter"), hm("encounter")),
        PairDef("tk-soft-medication-vs-hemas-medication", tk("medication"), hm("medication")),

        // ── Category 5: IIT-Proj → Hemas — custom vs custom (5 pairs) ──
        PairDef("iit-proj-patient-vs-hemas-patient", ip("patient"), hm("patient")),
        PairDef("iit-proj-observation-vs-hemas-observation", ip("observation"), hm("observation")),
        PairDef("iit-proj-condition-vs-hemas-condition", ip("condition"), hm("condition")),
        PairDef("iit-proj-encounter-vs-hemas-encounter", ip("encounter"), hm("encounter")),
        PairDef("iit-proj-medication-vs-hemas-medication", ip("medication"), hm("medication")),

        // ── Category 6: Custom → US Core — custom vs standard (7 pairs) ──
        PairDef("tk-soft-patient-vs-us-core-patient", tk("patient"), us("us-core-patient")),
        PairDef("hemas-patient-vs-us-core-patient", hm("patient"), us("us-core-patient")),
        PairDef("iit-proj-patient-vs-us-core-patient", ip("patient"), us("us-core-patient")),
        PairDef("tk-soft-observation-vs-us-core-observation", tk("observation"), us("us-core-observation-lab")),
        PairDef("hemas-condition-vs-us-core-condition", hm("condition"), us("us-core-condition-encounter-diagnosis")),
        PairDef("hemas-encounter-vs-us-core-encounter", hm("encounter"), us("us-core-encounter")),
        PairDef("hemas-organization-vs-us-core-organization", hm("organization"), us("us-core-organization")),

        // ── Category 7: Custom → R4 Base — custom vs standard (7 pairs) ──
        PairDef("tk-soft-patient-vs-r4-patient", tk("patient"), r4("patient")),
        PairDef("tk-soft-condition-vs-r4-condition", tk("condition"), r4("condition")),
        PairDef("iit-proj-observation-vs-r4-observation", ip("observation"), r4("observation")),
        PairDef("iit-proj-patient-vs-r4-patient", ip("patient"), r4("patient")),
        PairDef("hemas-patient-vs-r4-patient", hm("patient"), r4("patient")),
        PairDef("hemas-organization-vs-r4-organization", hm("organization"), r4("organization")),
        PairDef("hemas-encounter-vs-r4-encounter", hm("encounter"), r4("encounter")),

        // ── Category 8: AU Core → R4 Base + Custom cross (4 pairs) ──
        PairDef("au-core-patient-vs-r4-patient", au("au-core-patient"), r4("patient")),
        PairDef("au-core-condition-vs-r4-condition", au("au-core-condition"), r4("condition")),
        PairDef("au-core-encounter-vs-r4-encounter", au("au-core-encounter"), r4("encounter")),
        PairDef("hemas-immunization-vs-us-core-immunization", hm("immunization"), us("us-core-immunization")),
    )

    @Test
    @DisplayName("Generate gold-standard JSONs from rule-based detection for all 60 profile pairs")
    fun generateAllGoldStandards() {
        val outputDir = Paths.get("output", "generated-gold-standards")
        Files.createDirectories(outputDir)

        var successCount = 0
        var failCount = 0

        for (pair in allPairs) {
            try {
                val sourceSd = profileLoader.loadProfileFromClasspath(pair.sourceClasspath)
                val targetSd = profileLoader.loadProfileFromClasspath(pair.targetClasspath)

                val context = profileContextBuilder.buildContext(sourceSd, targetSd)
                val driftItems = ruleBasedDetector.detect(context)

                val goldPair = GoldStandardPair(
                    pairId = pair.pairId,
                    sourceClasspath = pair.sourceClasspath,
                    targetClasspath = pair.targetClasspath,
                    drifts = driftItems
                )

                val outputFile = outputDir.resolve("${pair.pairId}.json")
                Files.writeString(
                    outputFile,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(goldPair)
                )

                log.info("Generated: {} ({} drifts)", pair.pairId, driftItems.size)
                successCount++
            } catch (ex: Exception) {
                log.error("FAILED to generate {}: {}", pair.pairId, ex.message, ex)
                failCount++
            }
        }

        log.info("============================================")
        log.info("  Gold Standard Generation Complete")
        log.info("  Total pairs: {}  Success: {}  Failed: {}", allPairs.size, successCount, failCount)
        log.info("  Output: {}", outputDir.toAbsolutePath())
        log.info("============================================")
    }

    private data class PairDef(
        val pairId: String,
        val sourceClasspath: String,
        val targetClasspath: String
    )
}
