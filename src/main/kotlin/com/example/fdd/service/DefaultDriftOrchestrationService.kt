package com.example.fdd.service

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.exception.FddException
import com.example.fdd.fhir.ProfileLoader
import com.example.fdd.model.CoverageReport
import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.validation.DriftProfileValidator
import com.example.fdd.validation.MapValidator
import io.micrometer.core.annotation.Timed
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Default implementation of [DriftOrchestrationService].
 *
 * Wires together [ProfileLoader], [DriftAnalyzer], [MapGenerator], and [MapValidator]
 * into a single, cohesive pipeline.
 */
@Service
class DefaultDriftOrchestrationService(
    private val profileLoader: ProfileLoader,
    private val driftValidator: DriftProfileValidator,
    private val driftAnalyzer: DriftAnalyzer,
    private val mapGenerator: MapGenerator,
    private val mapValidator: MapValidator,
    private val coverageAnalyzer: CoverageAnalyzer
) : DriftOrchestrationService {

    private val log = LoggerFactory.getLogger(javaClass)

    @Timed(value = "fdd.orchestration.analyze.duration", description = "End-to-end drift analysis duration")
    override fun analyzeDrift(source: ProfileInput, target: ProfileInput): DriftReport {
        val (sourceSd, targetSd) = resolveProfiles(source, target)
        driftValidator.validateCompatibility(sourceSd, targetSd)
        return driftAnalyzer.analyzeDrift(sourceSd, targetSd)
    }

    @Timed(value = "fdd.orchestration.repair.duration", description = "End-to-end analyze-and-repair duration")
    override fun analyzeAndRepair(
        source: ProfileInput,
        target: ProfileInput
    ): Triple<DriftReport, MapGenerationResult, CoverageReport> {
        val (sourceSd, targetSd) = resolveProfiles(source, target)
        driftValidator.validateCompatibility(sourceSd, targetSd)

        // --- Stage 1 - Drift detection (profiles -> DriftReport) ---
        // The LLM receives the full profile elements to detect all differences.
        val driftReport = driftAnalyzer.analyzeDrift(sourceSd, targetSd)
        log.info(
            "Drift analysis produced {} items for {} -> {}",
            driftReport.totalDrifts,
            driftReport.sourceProfileCanonical,
            driftReport.targetProfileCanonical
        )

        // --- Stage 2 - Map generation (DriftReport -> FML) ---
        // The drift report is the PRIMARY input. Source/target are passed only
        // so the generator can extract abbreviated element snapshots for the
        // paths mentioned in the drift items - NOT to re-send full profiles.
        val rawResult = mapGenerator.generateMap(sourceSd, targetSd, driftReport)

        // --- Stage 3 - Trust-but-Verify (FML -> validated FML) ---
        // HAPI-FHIR compiles the FML; on failure the Reflexion loop asks the
        // LLM to self-correct using only the error message and canonical URLs.
        val validatedResult = mapValidator.validateAndRepair(
            fmlCode = rawResult.structureMapFml,
            source = sourceSd,
            target = targetSd,
            driftReport = driftReport
        )

        log.info(
            "Repair pipeline complete - syntactically valid: {}",
            validatedResult.syntacticallyValid
        )

        // --- Stage 4 - Coverage analysis (drift report + FML -> coverage report) ---
        // Deterministic, no LLM cost. Classifies every drift item so the user
        // knows exactly what is mapped, what is dropped, and why.
        val coverageReport = coverageAnalyzer.analyze(driftReport, validatedResult.structureMapFml)
        log.info(
            "Coverage analysis: {:.1f}% data shareability across {} drift items",
            coverageReport.dataShareabilityPercent,
            coverageReport.totalDriftItems
        )

        return Triple(driftReport, validatedResult, coverageReport)
    }

    /* ---------------- Profile resolution ---------------- */

    private fun resolveProfiles(
        source: ProfileInput,
        target: ProfileInput
    ): Pair<StructureDefinition, StructureDefinition> {
        val sourceSd = resolveProfile(source, "source")
        val targetSd = resolveProfile(target, "target")
        return sourceSd to targetSd
    }

    /**
     * Resolve a single [ProfileInput] into a [StructureDefinition].
     *
     * Priority order: json -> url -> canonical -> classpath -> file.
     */
    private fun resolveProfile(input: ProfileInput, label: String): StructureDefinition = when {
        !input.json.isNullOrBlank() -> {
            log.debug("Resolving {} profile from inline JSON", label)
            profileLoader.loadProfileFromJson(input.json)
        }

        !input.url.isNullOrBlank() -> {
            log.debug("Resolving {} profile from URL: {}", label, input.url)
            profileLoader.loadProfileFromUrl(input.url)
        }

        !input.canonical.isNullOrBlank() -> {
            log.debug("Resolving {} profile by canonical: {}", label, input.canonical)
            profileLoader.loadProfileByCanonical(input.canonical)
        }

        !input.classpath.isNullOrBlank() -> {
            log.debug("Resolving {} profile from classpath: {}", label, input.classpath)
            profileLoader.loadProfileFromClasspath(input.classpath)
        }

        !input.file.isNullOrBlank() -> {
            log.debug("Resolving {} profile from local file: {}", label, input.file)
            profileLoader.loadProfileFromFile(input.file)
        }

        else -> throw FddException(
            "Either json, url, canonical, classpath, or file must be provided for the $label profile"
        )
    }
}
