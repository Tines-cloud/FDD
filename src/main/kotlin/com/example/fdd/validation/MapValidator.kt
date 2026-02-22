package com.example.fdd.validation

import com.example.fdd.model.DriftReport
import com.example.fdd.model.MapGenerationResult
import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Validates a FHIR StructureMap (FML) by attempting to compile it with
 * HAPI-FHIR's StructureMapUtilities.
 *
 * If compilation fails, the validator invokes an LLM-powered **Reflexion** loop:
 * the error message AND the original drift report are fed back to the model so it
 * can self-correct the FML while preserving semantic intent.
 */
interface MapValidator {

    /**
     * Validate (and optionally repair) the given FML code.
     *
     * @param fmlCode     Raw FHIR Mapping Language text produced by the [com.example.fdd.service.MapGenerator].
     * @param source      Source [StructureDefinition] (provided for reflexion context).
     * @param target      Target [StructureDefinition] (provided for reflexion context).
     * @param driftReport The drift report that drove FML generation. Included in the reflexion
     *                    prompt so the LLM can verify semantic intent while fixing syntax errors.
     * @return A [MapGenerationResult] with the final FML, validity flag, and log of all attempts.
     * @throws com.example.fdd.exception.MapValidationException if all reflexion attempts fail.
     */
    fun validateAndRepair(
        fmlCode: String,
        source: StructureDefinition,
        target: StructureDefinition,
        driftReport: DriftReport
    ): MapGenerationResult
}
