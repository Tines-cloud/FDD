package com.example.fdd.service

import com.example.fdd.model.DriftReport
import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Detects semantic drift between a source and target FHIR profile.
 *
 * Implementations should analyse differences across five drift categories:
 * [com.example.fdd.model.DriftType.TERMINOLOGY],
 * [com.example.fdd.model.DriftType.EXTENSION],
 * [com.example.fdd.model.DriftType.STRUCTURAL],
 * [com.example.fdd.model.DriftType.CARDINALITY], and
 * [com.example.fdd.model.DriftType.VERSION].
 */
interface DriftAnalyzer {

    /**
     * Analyse drift between the [source] and [target] profiles.
     *
     * @return A [DriftReport] listing all detected drift items.
     * @throws com.example.fdd.exception.DriftAnalysisException if analysis fails.
     */
    fun analyzeDrift(
        source: StructureDefinition,
        target: StructureDefinition
    ): DriftReport
}
