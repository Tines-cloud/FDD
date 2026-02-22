package com.example.fdd.validation

import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Validates FML code by compiling it with HAPI-FHIR.
 * If compilation fails, uses an LLM to fix the errors automatically.
 */
interface MapValidator {

    /**
     * Compile the FML. If it fails, try to fix it with the LLM and re-compile.
     *
     * @param fmlCode     The raw FML text to validate.
     * @param source      Source profile (used for context when asking the LLM to fix errors).
     * @param target      Target profile (used for context when asking the LLM to fix errors).
     * @param driftReport The drift report that the FML is based on.
     * @return The final FML (possibly repaired), validity flag, and log of all attempts.
     * @throws MapValidationException if all repair attempts fail.
     */
    fun validateAndRepair(
        fmlCode: String,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): MapGenerationResult
}
