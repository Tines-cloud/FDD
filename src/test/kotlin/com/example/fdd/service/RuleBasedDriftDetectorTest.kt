package com.example.fdd.service

import com.example.fdd.model.BindingSummary
import com.example.fdd.model.ConstraintSummary
import com.example.fdd.model.DriftType
import com.example.fdd.model.ElementSummary
import com.example.fdd.model.ProfileContext
import com.example.fdd.model.ProfileSummary
import com.example.fdd.model.SlicingSummary
import com.example.fdd.model.TypeSummary
import com.example.fdd.service.impl.DefaultRuleBasedDriftDetector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [com.example.fdd.service.impl.DefaultRuleBasedDriftDetector].
 *
 * Verifies that every [DriftType] category is detected correctly from
 * deterministic structural comparison of [ProfileContext] data.
 */
class RuleBasedDriftDetectorTest {

    private val detector = DefaultRuleBasedDriftDetector()

    /* --------------------------------------------------------
     * Helpers to build minimal ProfileContext instances quickly
     * -------------------------------------------------------- */

    private fun context(
        sourceElements: List<ElementSummary> = emptyList(),
        targetElements: List<ElementSummary> = emptyList(),
        sourceFhirVersion: String? = "4.0.1",
        targetFhirVersion: String? = "4.0.1"
    ) = ProfileContext(
        sourceProfile = ProfileSummary(
            canonical = "http://source",
            type = "Patient",
            fhirVersion = sourceFhirVersion,
            elements = sourceElements
        ),
        targetProfile = ProfileSummary(
            canonical = "http://target",
            type = "Patient",
            fhirVersion = targetFhirVersion,
            elements = targetElements
        )
    )

    private fun element(
        path: String,
        min: Int = 0,
        max: String = "*",
        mustSupport: Boolean = false,
        types: List<TypeSummary> = emptyList(),
        binding: BindingSummary? = null,
        fixedValue: String? = null,
        patternValue: String? = null,
        sliceName: String? = null,
        slicing: SlicingSummary? = null,
        isModifier: Boolean = false,
        isSummary: Boolean = false,
        constraints: List<ConstraintSummary> = emptyList(),
        contentReference: String? = null,
        extensions: List<String> = emptyList(),
        modifierExtensions: List<String> = emptyList()
    ) = ElementSummary(
        path = path, min = min, max = max, mustSupport = mustSupport,
        types = types, binding = binding, fixedValue = fixedValue,
        patternValue = patternValue, sliceName = sliceName, slicing = slicing,
        isModifier = isModifier, isSummary = isSummary, constraints = constraints,
        contentReference = contentReference, extensions = extensions,
        modifierExtensions = modifierExtensions
    )

    /* ------------------------------------
     * VERSION
     * ------------------------------------ */
    @Nested
    @DisplayName("VERSION drift")
    inner class VersionDrift {

        @Test
        @DisplayName("Detects FHIR version mismatch")
        fun detectsFhirVersionMismatch() {
            val items = detector.detect(
                context(sourceFhirVersion = "4.0.1", targetFhirVersion = "5.0.0")
            )
            val versionItems = items.filter { it.type == DriftType.VERSION }
            assertEquals(1, versionItems.size)
            assertTrue(versionItems[0].description.contains("4.0.1"))
            assertTrue(versionItems[0].description.contains("5.0.0"))
            assertEquals("ERROR", versionItems[0].severity)
        }

        @Test
        @DisplayName("No VERSION drift when versions match")
        fun noVersionDriftWhenSame() {
            val items = detector.detect(
                context(sourceFhirVersion = "4.0.1", targetFhirVersion = "4.0.1")
            )
            assertTrue(items.none { it.type == DriftType.VERSION })
        }
    }

    /* ---------------------------------
     * CARDINALITY
     * ---------------------------------*/
    @Nested
    @DisplayName("CARDINALITY drift")
    inner class CardinalityDrift {

        @Test
        @DisplayName("Detects min cardinality increase")
        fun detectsMinIncrease() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.identifier", min = 0, max = "*")),
                    targetElements = listOf(element("Patient.identifier", min = 1, max = "*"))
                )
            )
            val card = items.filter { it.type == DriftType.CARDINALITY }
            assertEquals(1, card.size)
            assertEquals("ERROR", card[0].severity)
            assertTrue(card[0].description.contains("[0..*]"))
            assertTrue(card[0].description.contains("[1..*]"))
        }

        @Test
        @DisplayName("Detects max cardinality restriction")
        fun detectsMaxRestriction() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.name", min = 0, max = "*")),
                    targetElements = listOf(element("Patient.name", min = 0, max = "1"))
                )
            )
            val card = items.filter { it.type == DriftType.CARDINALITY }
            assertEquals(1, card.size)
            assertEquals("WARNING", card[0].severity)
        }

        @Test
        @DisplayName("No cardinality drift when min/max identical")
        fun noCardinalityDriftWhenSame() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.name", min = 1, max = "1")),
                    targetElements = listOf(element("Patient.name", min = 1, max = "1"))
                )
            )
            assertTrue(items.none { it.type == DriftType.CARDINALITY })
        }
    }

    /* --------------------------------
     * TERMINOLOGY
     * --------------------------------*/
    @Nested
    @DisplayName("TERMINOLOGY drift")
    inner class TerminologyDrift {

        @Test
        @DisplayName("Detects binding strength change")
        fun detectsBindingStrengthChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient.gender", binding = BindingSummary("preferred", "http://vs/old"))
                    ),
                    targetElements = listOf(
                        element("Patient.gender", binding = BindingSummary("required", "http://vs/old"))
                    )
                )
            )
            val term = items.filter { it.type == DriftType.TERMINOLOGY }
            assertEquals(1, term.size)
            assertEquals("ERROR", term[0].severity)
            assertTrue(term[0].description.contains("preferred"))
            assertTrue(term[0].description.contains("required"))
        }

        @Test
        @DisplayName("Detects value-set URL change")
        fun detectsValueSetChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient.gender", binding = BindingSummary("required", "http://vs/old"))
                    ),
                    targetElements = listOf(
                        element("Patient.gender", binding = BindingSummary("required", "http://vs/new"))
                    )
                )
            )
            val term = items.filter { it.type == DriftType.TERMINOLOGY }
            assertEquals(1, term.size)
            assertTrue(term[0].description.contains("http://vs/old"))
            assertTrue(term[0].description.contains("http://vs/new"))
        }

        @Test
        @DisplayName("Detects fixed value change")
        fun detectsFixedValueChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.active", fixedValue = "true")),
                    targetElements = listOf(element("Patient.active", fixedValue = "false"))
                )
            )
            val term = items.filter { it.type == DriftType.TERMINOLOGY && it.description.contains("Fixed") }
            assertEquals(1, term.size)
            assertEquals("ERROR", term[0].severity)
        }

        @Test
        @DisplayName("Detects pattern value change")
        fun detectsPatternValueChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.code", patternValue = "CodeA")),
                    targetElements = listOf(element("Patient.code", patternValue = "CodeB"))
                )
            )
            val term = items.filter { it.type == DriftType.TERMINOLOGY && it.description.contains("Pattern") }
            assertEquals(1, term.size)
            assertEquals("WARNING", term[0].severity)
        }
    }

    /* --------------------------------
     * STRUCTURAL
     * -------------------------------- */
    @Nested
    @DisplayName("STRUCTURAL drift")
    inner class StructuralDrift {

        @Test
        @DisplayName("Detects type code change")
        fun detectsTypeCodeChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient.value", types = listOf(TypeSummary("string")))
                    ),
                    targetElements = listOf(
                        element("Patient.value", types = listOf(TypeSummary("CodeableConcept")))
                    )
                )
            )
            val structural = items.filter { it.type == DriftType.STRUCTURAL && it.description.contains("Type changed") }
            assertEquals(1, structural.size)
        }

        @Test
        @DisplayName("Detects type profile constraint change")
        fun detectsTypeProfileChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element(
                            "Patient.contact",
                            types = listOf(
                                TypeSummary(
                                    "Reference",
                                    profiles = listOf("http://hl7.org/fhir/StructureDefinition/Organization")
                                )
                            )
                        )
                    ),
                    targetElements = listOf(
                        element(
                            "Patient.contact",
                            types = listOf(TypeSummary("Reference", profiles = listOf("http://us-core/Organization")))
                        )
                    )
                )
            )
            val structural = items.filter { it.description.contains("Type profile constraint") }
            assertEquals(1, structural.size)
        }

        @Test
        @DisplayName("Detects Reference target-profile change")
        fun detectsTargetProfileChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element(
                            "Patient.generalPractitioner",
                            types = listOf(
                                TypeSummary(
                                    "Reference",
                                    targetProfiles = listOf("http://hl7.org/fhir/StructureDefinition/Practitioner")
                                )
                            )
                        )
                    ),
                    targetElements = listOf(
                        element(
                            "Patient.generalPractitioner",
                            types = listOf(
                                TypeSummary(
                                    "Reference",
                                    targetProfiles = listOf("http://us-core/Practitioner")
                                )
                            )
                        )
                    )
                )
            )
            val structural = items.filter { it.description.contains("Reference target profile") }
            assertEquals(1, structural.size)
        }

        @Test
        @DisplayName("Detects mustSupport added and removed")
        fun detectsMustSupportChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient.name", mustSupport = false),
                        element("Patient.active", mustSupport = true)
                    ),
                    targetElements = listOf(
                        element("Patient.name", mustSupport = true),
                        element("Patient.active", mustSupport = false)
                    )
                )
            )
            val ms = items.filter { it.description.contains("MustSupport") }
            assertEquals(2, ms.size)
            assertTrue(ms.any { it.description.contains("added") })
            assertTrue(ms.any { it.description.contains("removed") })
        }

        @Test
        @DisplayName("Detects isModifier change")
        fun detectsIsModifierChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.status", isModifier = false)),
                    targetElements = listOf(element("Patient.status", isModifier = true))
                )
            )
            val mod = items.filter { it.description.contains("IsModifier") }
            assertEquals(1, mod.size)
            assertEquals("ERROR", mod[0].severity)
        }

        @Test
        @DisplayName("Detects slicing rules change")
        fun detectsSlicingChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element(
                            "Patient.identifier",
                            slicing = SlicingSummary(discriminators = listOf("value:system"), rules = "open")
                        )
                    ),
                    targetElements = listOf(
                        element(
                            "Patient.identifier",
                            slicing = SlicingSummary(discriminators = listOf("value:system"), rules = "closed")
                        )
                    )
                )
            )
            val slicing = items.filter { it.description.contains("Slicing changed") }
            assertEquals(1, slicing.size)
        }

        @Test
        @DisplayName("Detects constraints added and removed")
        fun detectsConstraintChanges() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element(
                            "Patient", constraints = listOf(
                                ConstraintSummary(key = "pat-1", severity = "error", human = "Name or id required")
                            )
                        )
                    ),
                    targetElements = listOf(
                        element(
                            "Patient", constraints = listOf(
                                ConstraintSummary(key = "pat-2", severity = "warning", human = "Phone required")
                            )
                        )
                    )
                )
            )
            val constItems = items.filter { it.description.contains("Constraint") }
            assertEquals(2, constItems.size)
            assertTrue(constItems.any { it.description.contains("added") && it.description.contains("pat-2") })
            assertTrue(constItems.any { it.description.contains("removed") && it.description.contains("pat-1") })
        }

        @Test
        @DisplayName("Detects contentReference change")
        fun detectsContentReferenceChange() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.contact.name", contentReference = "#Patient.name")),
                    targetElements = listOf(element("Patient.contact.name", contentReference = "#Patient.humanName"))
                )
            )
            val cr = items.filter { it.description.contains("Content reference") }
            assertEquals(1, cr.size)
        }

        @Test
        @DisplayName("Detects element added in target")
        fun detectsElementAdded() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.name")),
                    targetElements = listOf(element("Patient.name"), element("Patient.birthDate", min = 1))
                )
            )
            val added = items.filter { it.description.contains("Element added") }
            assertEquals(1, added.size)
            assertEquals("ERROR", added[0].severity) // min > 0 -> ERROR
            assertEquals("Patient.birthDate", added[0].targetPath)
        }

        @Test
        @DisplayName("Detects element removed from target")
        fun detectsElementRemoved() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient.name"),
                        element("Patient.active", mustSupport = true)
                    ),
                    targetElements = listOf(element("Patient.name"))
                )
            )
            val removed = items.filter { it.description.contains("Element removed") }
            assertEquals(1, removed.size)
            assertEquals("WARNING", removed[0].severity) // mustSupport -> WARNING
            assertEquals("Patient.active", removed[0].sourcePath)
        }

        @Test
        @DisplayName("Detects new slice added in target")
        fun detectsSliceAdded() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient.identifier")),
                    targetElements = listOf(
                        element("Patient.identifier"),
                        element("Patient.identifier:ssn", sliceName = "ssn")
                    )
                )
            )
            val slices = items.filter { it.description.contains("slice") || it.description.contains("Slice") }
            assertEquals(1, slices.size)
            assertTrue(slices[0].description.contains("ssn"))
        }
    }

    /* --------------------------------
     * EXTENSION
     * -------------------------------- */
    @Nested
    @DisplayName("EXTENSION drift")
    inner class ExtensionDrift {

        @Test
        @DisplayName("Detects element-level extension added and removed")
        fun detectsExtensionAddedAndRemoved() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient", extensions = listOf("http://ext/a", "http://ext/b"))
                    ),
                    targetElements = listOf(
                        element("Patient", extensions = listOf("http://ext/b", "http://ext/c"))
                    )
                )
            )
            val extItems = items.filter { it.type == DriftType.EXTENSION }
            assertEquals(2, extItems.size)
            assertTrue(extItems.any { it.description.contains("added") && it.description.contains("http://ext/c") })
            assertTrue(extItems.any { it.description.contains("removed") && it.description.contains("http://ext/a") })
        }

        @Test
        @DisplayName("Detects modifier extensions added")
        fun detectsModifierExtensionAdded() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(element("Patient")),
                    targetElements = listOf(
                        element("Patient", modifierExtensions = listOf("http://mod-ext/critical"))
                    )
                )
            )
            val modExts = items.filter { it.description.contains("Modifier extension") }
            assertEquals(1, modExts.size)
            assertEquals("ERROR", modExts[0].severity)
        }
    }

    /* ---------------------------------
     * Composite / Edge Cases
     * --------------------------------- */
    @Nested
    @DisplayName("Composite scenarios")
    inner class CompositeScenarios {

        @Test
        @DisplayName("Identical profiles produce no drift items")
        fun identicalProfilesNoDrift() {
            val elements = listOf(
                element("Patient", min = 0, max = "*"),
                element(
                    "Patient.name", min = 1, max = "*", mustSupport = true,
                    types = listOf(TypeSummary("HumanName"))
                )
            )
            val items = detector.detect(
                context(sourceElements = elements, targetElements = elements)
            )
            assertTrue(items.isEmpty(), "Expected no drift but found: $items")
        }

        @Test
        @DisplayName("Multiple drift types on same element")
        fun multipleDriftTypesOnSameElement() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element(
                            "Patient.identifier",
                            min = 0, max = "*",
                            binding = BindingSummary("preferred", "http://vs/old"),
                            mustSupport = false
                        )
                    ),
                    targetElements = listOf(
                        element(
                            "Patient.identifier",
                            min = 1, max = "1",
                            binding = BindingSummary("required", "http://vs/new"),
                            mustSupport = true
                        )
                    )
                )
            )
            val types = items.map { it.type }.toSet()
            assertTrue(DriftType.CARDINALITY in types, "Expected CARDINALITY drift")
            assertTrue(DriftType.TERMINOLOGY in types, "Expected TERMINOLOGY drift")
            assertTrue(DriftType.STRUCTURAL in types, "Expected STRUCTURAL drift (mustSupport)")
        }

        @Test
        @DisplayName("All drift item IDs are unique")
        fun allIdsUnique() {
            val items = detector.detect(
                context(
                    sourceElements = listOf(
                        element("Patient.name", min = 0, mustSupport = false),
                        element(
                            "Patient.gender",
                            binding = BindingSummary("preferred", "http://old"),
                            types = listOf(TypeSummary("code"))
                        )
                    ),
                    targetElements = listOf(
                        element("Patient.name", min = 1, mustSupport = true),
                        element(
                            "Patient.gender",
                            binding = BindingSummary("required", "http://new"),
                            types = listOf(TypeSummary("CodeableConcept"))
                        ),
                        element("Patient.birthDate")
                    )
                )
            )
            val ids = items.map { it.id }
            assertEquals(ids.size, ids.toSet().size, "Expected all IDs to be unique: $ids")
        }
    }
}
