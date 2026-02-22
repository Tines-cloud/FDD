package com.example.fdd.validation

import com.example.fdd.exception.FddException
import org.hl7.fhir.r4.model.StructureDefinition
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [DriftProfileValidator].
 *
 * Verifies that compatibility checks between source and target profiles are enforced correctly.
 */
class DriftProfileValidatorTest {

    private val validator = DriftProfileValidator()

    @Test
    @DisplayName("Does not throw when source and target have the same resource type")
    fun validateCompatibility_sameType_doesNotThrow() {
        val source = StructureDefinition().apply { type = "Patient" }
        val target = StructureDefinition().apply { type = "Patient" }

        assertDoesNotThrow { validator.validateCompatibility(source, target) }
    }

    @Test
    @DisplayName("Throws FddException when source and target have different resource types")
    fun validateCompatibility_differentTypes_throws() {
        val source = StructureDefinition().apply { type = "Patient" }
        val target = StructureDefinition().apply { type = "Observation" }

        val ex = assertThrows<FddException> {
            validator.validateCompatibility(source, target)
        }
        assert(ex.message!!.contains("Patient")) { "Error message should mention source type" }
        assert(ex.message!!.contains("Observation")) { "Error message should mention target type" }
    }

    @Test
    @DisplayName("Throws FddException when source profile has no resource type defined")
    fun validateCompatibility_blankSourceType_throws() {
        val source = StructureDefinition().apply { type = "" }
        val target = StructureDefinition().apply { type = "Patient" }

        // Type mismatch fires first since "" != "Patient"
        assertThrows<FddException> { validator.validateCompatibility(source, target) }
    }

    @Test
    @DisplayName("Throws FddException when both profiles have null resource type")
    fun validateCompatibility_bothNullType_throws() {
        val source = StructureDefinition() // type = null
        val target = StructureDefinition() // type = null

        // Both null: sourceType == targetType (null == null) but blank check fires
        val ex = assertThrows<FddException> {
            validator.validateCompatibility(source, target)
        }
        assert(ex.message!!.contains("no resource type")) { "Error message should mention missing type" }
    }

    @Test
    @DisplayName("Comparison is case-sensitive - Patient != patient")
    fun validateCompatibility_caseMismatch_throws() {
        val source = StructureDefinition().apply { type = "Patient" }
        val target = StructureDefinition().apply { type = "patient" }

        assertThrows<FddException> { validator.validateCompatibility(source, target) }
    }
}
