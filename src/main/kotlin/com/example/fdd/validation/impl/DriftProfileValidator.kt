package com.example.fdd.validation.impl

import com.example.fdd.exception.FddException
import com.example.fdd.validation.IDriftProfileValidator
import org.hl7.fhir.r4.model.StructureDefinition
import org.springframework.stereotype.Component

/**
 * Validates that source and target profiles are compatible for drift analysis.
 *
 * Rules:
 * 1. Both profiles must be for the SAME resource type (e.g., Patient vs Patient).
 * 2. Drift analysis across different resource types (e.g., Patient vs Observation)
 *    is not supported and would require custom StructureMaps for translation,
 *    not for repair/alignment.
 */
@Component
class DriftProfileValidator : IDriftProfileValidator {

    override fun validateCompatibility(source: StructureDefinition, target: StructureDefinition) {
        val sourceType = source.type
        val targetType = target.type

        if (sourceType != targetType) {
            throw FddException(
                "Profile resource type mismatch: source is '$sourceType' but target is '$targetType'. " +
                        "Drift analysis requires both profiles to describe the SAME resource type " +
                        "(e.g., US Core Patient vs AU Patient). " +
                        "Cross-resource comparisons (e.g., Patient vs Observation) are not supported and typically require full data transformations rather than drift repair."
            )
        }

        if (sourceType.isNullOrBlank()) {
            throw FddException("Source profile has no resource type defined")
        }

        if (targetType.isNullOrBlank()) {
            throw FddException("Target profile has no resource type defined")
        }
    }
}