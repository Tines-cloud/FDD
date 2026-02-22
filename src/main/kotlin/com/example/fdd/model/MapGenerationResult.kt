package com.example.fdd.model

/**
 * Result of a StructureMap generation + validation cycle.
 *
 * @property structureMapFml      The FHIR Mapping Language (FML) code as a raw string.
 * @property syntacticallyValid   `true` if the FML compiled successfully via HAPI-FHIR.
 * @property validationMessages   Ordered log of messages from each validation attempt.
 */
data class MapGenerationResult(
    val structureMapFml: String,
    val syntacticallyValid: Boolean,
    val validationMessages: List<String> = emptyList()
)
