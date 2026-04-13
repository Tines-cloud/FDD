package com.example.fdd.fhir

import ca.uhn.fhir.context.FhirContext
import com.example.fdd.fhir.impl.DefaultProfileContextBuilder
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [com.example.fdd.fhir.impl.DefaultProfileContextBuilder].
 *
 * Verifies that profile element extraction works correctly with
 * HAPI-FHIR's built-in R4 StructureDefinitions.
 */
class ProfileContextBuilderTest {

    private val fhirContext = FhirContext.forR4()
    private val fhirContextR5 = FhirContext.forR5()
    private val builder = DefaultProfileContextBuilder(fhirContext, fhirContextR5)

    @Test
    @DisplayName("Builds context from two base R4 StructureDefinitions")
    fun buildContext_baseR4Profiles_returnsValidContext() {
        // Load base Patient from HAPI built-in definitions
        val patient = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Patient")
                as org.hl7.fhir.r4.model.StructureDefinition

        val observation = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Observation")
                as org.hl7.fhir.r4.model.StructureDefinition

        val context = builder.buildContext(patient, observation)

        assertNotNull(context.sourceProfile)
        assertNotNull(context.targetProfile)
        assertEquals("Patient", context.sourceProfile.type)
        assertEquals("Observation", context.targetProfile.type)
        assertTrue(context.sourceProfile.elements.isNotEmpty())
        assertTrue(context.targetProfile.elements.isNotEmpty())
    }

    @Test
    @DisplayName("Element summaries include expected fields for Patient")
    fun buildContext_patientProfile_extractsElements() {
        val patient = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Patient")
                as org.hl7.fhir.r4.model.StructureDefinition

        // Use same profile for both sides (testing extraction, not drift)
        val context = builder.buildContext(patient, patient)
        val elements = context.sourceProfile.elements

        // Patient should have identifier, name, gender, birthDate elements
        val paths = elements.map { it.path }
        assertTrue(paths.contains("Patient.identifier"), "Should have Patient.identifier")
        assertTrue(paths.contains("Patient.name"), "Should have Patient.name")
        assertTrue(paths.contains("Patient.gender"), "Should have Patient.gender")

        // Gender should have a binding
        val genderElement = elements.first { it.path == "Patient.gender" }
        assertNotNull(genderElement.binding, "Patient.gender should have a terminology binding")
    }

    @Test
    @DisplayName("Element summaries capture all semantically meaningful fields")
    fun buildContext_capturesAllFields() {
        val patient = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Patient")
                as org.hl7.fhir.r4.model.StructureDefinition

        val context = builder.buildContext(patient, patient)
        val elements = context.sourceProfile.elements

        // Type information: identifier should have type Reference or Identifier
        val identifierElem = elements.first { it.path == "Patient.identifier" }
        assertTrue(identifierElem.types.isNotEmpty(), "Types should be populated")
        assertTrue(identifierElem.types.any { it.code == "Identifier" }, "Identifier type code expected")

        // Constraints: Patient root should have constraints (e.g. dom-2, etc.)
        val rootElem = elements.first { it.path == "Patient" }
        assertTrue(rootElem.constraints.isNotEmpty(), "Root element should have constraints")
        assertTrue(rootElem.constraints.any { it.key.isNotBlank() }, "Constraints should have keys")

        // isModifier: Patient.active is a modifier element
        val activeElem = elements.firstOrNull { it.path == "Patient.active" }
        if (activeElem != null) {
            assertTrue(activeElem.isModifier, "Patient.active should be a modifier element")
        }

        // Mappings: elements should have cross-standard mappings
        val elementsWithMappings = elements.filter { it.mappings.isNotEmpty() }
        assertTrue(elementsWithMappings.isNotEmpty(), "Some elements should have mappings")

        // Short descriptions: elements should have short labels
        val elementsWithShort = elements.filter { it.short != null }
        assertTrue(elementsWithShort.isNotEmpty(), "Some elements should have short descriptions")

        // Full definition: elements should have definition text (no truncation)
        val elementsWithDef = elements.filter { it.definition != null }
        assertTrue(elementsWithDef.isNotEmpty(), "Some elements should have definitions")
    }

    @Test
    @DisplayName("Drift-focused context includes only drift-referenced elements and ancestors")
    fun buildDriftFocusedContext_filtersToReferencedPaths() {
        val patient = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Patient")
                as org.hl7.fhir.r4.model.StructureDefinition

        val observation = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Observation")
                as org.hl7.fhir.r4.model.StructureDefinition

        val driftItems = listOf(
            DriftItem(
                id = "drift-1",
                type = DriftType.CARDINALITY,
                sourcePath = "Patient.identifier",
                targetPath = "Observation.status",
                description = "Test drift"
            )
        )

        val context = builder.buildDriftFocusedContext(patient, observation, driftItems)

        // Source should contain Patient.identifier and the root Patient element
        val sourcePaths = context.sourceProfile.elements.map { it.path }
        assertTrue(sourcePaths.contains("Patient.identifier"), "Should include drift-referenced Patient.identifier")
        assertTrue(sourcePaths.contains("Patient"), "Should include ancestor root Patient")
        assertTrue(sourcePaths.none { it == "Patient.name" }, "Should NOT include unreferenced Patient.name")

        // Target should contain Observation.status and the root Observation element
        val targetPaths = context.targetProfile.elements.map { it.path }
        assertTrue(targetPaths.contains("Observation.status"), "Should include drift-referenced Observation.status")
        assertTrue(targetPaths.contains("Observation"), "Should include ancestor root Observation")
        assertTrue(targetPaths.none { it == "Observation.code" }, "Should NOT include unreferenced Observation.code")
    }

    @Test
    @DisplayName("Drift-focused context with empty drift items returns empty elements")
    fun buildDriftFocusedContext_emptyDriftItems_returnsEmptyElements() {
        val patient = fhirContext.validationSupport
            .fetchStructureDefinition("http://hl7.org/fhir/StructureDefinition/Patient")
                as org.hl7.fhir.r4.model.StructureDefinition

        val context = builder.buildDriftFocusedContext(patient, patient, emptyList())

        assertTrue(context.sourceProfile.elements.isEmpty(), "No drift items -> no elements")
        assertTrue(context.targetProfile.elements.isEmpty(), "No drift items -> no elements")
    }
}
