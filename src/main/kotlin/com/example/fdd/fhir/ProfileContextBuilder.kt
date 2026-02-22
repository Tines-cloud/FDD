package com.example.fdd.fhir

import com.example.fdd.model.DriftItem
import com.example.fdd.model.ProfileContext
import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Builds compact, JSON-serialisable profile contexts from two FHIR [StructureDefinition]s.
 *
 * Two modes:
 * - Full context - every element from both profiles; used by [DriftAnalyzer].
 * - Drift-focused context - only the elements whose paths appear in the drift
 *   report; used by [MapGenerator] so it does not re-receive the full profiles.
 */
interface ProfileContextBuilder {

    /**
     * Full element context - used for drift analysis.
     */
    fun buildContext(source: StructureDefinition, target: StructureDefinition): ProfileContext

    /**
     * Element context limited to paths mentioned in [driftItems].
     *
     * Used for map generation so the drift report drives repair and the full
     * profiles are not sent to the LLM again.
     */
    fun buildDriftFocusedContext(
        source: StructureDefinition,
        target: StructureDefinition,
        driftItems: List<DriftItem>
    ): ProfileContext
}
