package com.example.fdd.fhir

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import com.example.fdd.exception.ProfileNotFoundException
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [DefaultProfileLoader].
 *
 * Tests canonical URL resolution using HAPI-FHIR's built-in R4 definitions.
 */
class ProfileLoaderTest {

    private val fhirContext = FhirContext.forR4()
    private val parser = fhirContext.newJsonParser().setPrettyPrint(false)
    private val fhirValidator = fhirContext.newValidator().apply {
        registerValidatorModule(
            FhirInstanceValidator(
                ValidationSupportChain(
                    DefaultProfileValidationSupport(fhirContext),
                    InMemoryTerminologyServerValidationSupport(fhirContext),
                    CommonCodeSystemsTerminologyService(fhirContext)
                )
            )
        )
    }

    private val loader = DefaultProfileLoader(fhirContext, parser, fhirValidator)

    @Test
    @DisplayName("Loads base R4 Patient by canonical URL from HAPI built-in definitions")
    fun loadProfileByCanonical_basePatient_returnsStructureDefinition() {
        val sd = loader.loadProfileByCanonical("http://hl7.org/fhir/StructureDefinition/Patient")

        assertNotNull(sd)
        assertEquals("Patient", sd.type)
        assertEquals("http://hl7.org/fhir/StructureDefinition/Patient", sd.url)
    }

    @Test
    @DisplayName("Loads base R4 Observation by canonical URL")
    fun loadProfileByCanonical_baseObservation_returnsStructureDefinition() {
        val sd = loader.loadProfileByCanonical("http://hl7.org/fhir/StructureDefinition/Observation")

        assertNotNull(sd)
        assertEquals("Observation", sd.type)
    }

    @Test
    @DisplayName("Throws ProfileNotFoundException for unknown canonical URL")
    fun loadProfileByCanonical_unknownUrl_throwsException() {
        assertThrows(ProfileNotFoundException::class.java) {
            loader.loadProfileByCanonical("http://example.org/not-a-real-profile")
        }
    }

    @Test
    @DisplayName("Parses StructureDefinition from raw JSON")
    fun loadProfileFromJson_validJson_parsesSuccessfully() {
        val json = """
        {
          "resourceType": "StructureDefinition",
          "url": "http://test.org/StructureDefinition/TestProfile",
          "name": "TestProfile",
          "status": "active",
          "kind": "resource",
          "abstract": false,
          "type": "Patient",
          "baseDefinition": "http://hl7.org/fhir/StructureDefinition/Patient",
          "derivation": "constraint",
          "differential": {
            "element": [
              {
                "id": "Patient",
                "path": "Patient"
              }
            ]
          }
        }
        """.trimIndent()

        val sd = loader.loadProfileFromJson(json)

        assertEquals("http://test.org/StructureDefinition/TestProfile", sd.url)
        assertEquals("Patient", sd.type)
    }
}
