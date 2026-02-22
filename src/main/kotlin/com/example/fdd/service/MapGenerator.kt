package com.example.fdd.service

import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Generates a FHIR StructureMap (in FML syntax) that transforms instances
 * conforming to a source profile into instances intended to conform to a
 * target profile, guided by a [DriftReport].
 */
interface MapGenerator {

    /**
     * Generate FML code resolving the identified drift items.
     *
     * @param source      Source [StructureDefinition].
     * @param target      Target [StructureDefinition].
     * @param driftReport Previously computed drift report for the same profile pair.
     * @return A [MapGenerationResult] containing the raw FML and initial validation status.
     */
    fun generateMap(
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): MapGenerationResult
}
