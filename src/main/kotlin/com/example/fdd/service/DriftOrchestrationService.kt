package com.example.fdd.service

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult

/**
 * Top-level orchestration service for the FHIR Drift Doctor pipeline.
 *
 * Coordinates the full workflow:
 * 1. Resolve profiles (by inline JSON, HTTP URL, or canonical URL).
 * 2. Detect drift via [DriftAnalyzer].
 * 3. Generate and validate a repair StructureMap via [MapGenerator]
 *    and [com.example.fdd.validation.MapValidator].
 */
interface DriftOrchestrationService {

    /**
     * Analyse drift only - no map generation.
     *
     * @param source Identifies the source FHIR profile (JSON, URL, or canonical).
     * @param target Identifies the target FHIR profile (JSON, URL, or canonical).
     * @return A [DriftReport]
     */
    fun analyzeDrift(source: ProfileInput, target: ProfileInput): DriftReport

    /**
     * Analyse drift **and** generate + validate a repair StructureMap.
     *
     * @param source Identifies the source FHIR profile (JSON, URL, or canonical).
     * @param target Identifies the target FHIR profile (JSON, URL, or canonical).
     * @return A pair of the [DriftReport] and the validated [MapGenerationResult].
     */
    fun analyzeAndRepair(source: ProfileInput, target: ProfileInput): Pair<DriftReport, MapGenerationResult>
}
