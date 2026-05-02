package com.example.fdd.fhir

import com.example.fdd.model.DriftItem
import com.example.fdd.model.ElementSummary
import com.example.fdd.model.ProfileContext
import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Builds compact, JSON-serialisable profile contexts from two FHIR [StructureDefinition]s.
 *
 * Two modes:
 * - Full context - every element from both profiles used by [DriftAnalyzer].
 * - Drift-focused context - only the elements whose paths appear in the drift
 *   report used by [MapGenerator] so it does not re-receive the full profiles.
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

    /**
     * Returns the subset of target elements that are required (min >= 1) or mustSupport,
     * exist under the same path in the source profile, but are NOT covered by any drift item.
     *
     * The map generator uses this list to produce pass-through copy rules for fields that
     * must appear in the transformed resource but are absent from the drift report (because
     * they are identical in both profiles and therefore generate no drift).
     *
     * A default empty-list implementation is provided so that test mocks of this interface
     * do not need to be updated.
     */
    fun buildPassThroughElements(
        source: StructureDefinition,
        target: StructureDefinition,
        driftItems: List<DriftItem>
    ): List<ElementSummary> = emptyList()
}
