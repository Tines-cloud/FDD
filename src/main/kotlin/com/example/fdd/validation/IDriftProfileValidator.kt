package com.example.fdd.validation

import org.hl7.fhir.r4.model.StructureDefinition

interface IDriftProfileValidator {
    fun validateCompatibility(source: StructureDefinition, target: StructureDefinition)
}
