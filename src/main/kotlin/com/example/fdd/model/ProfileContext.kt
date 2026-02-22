package com.example.fdd.model

/**
 * Two FHIR profiles converted into a plain data structure for the LLM.
 *
 * Wraps the source and target [ProfileSummary] objects that are serialised to
 * JSON and included in the LLM prompt.
 */
data class ProfileContext(
    val sourceProfile: ProfileSummary,
    val targetProfile: ProfileSummary
)

/**
 * Normalised summary of a single FHIR [org.hl7.fhir.r4.model.StructureDefinition].
 *
 * @property canonical   Canonical URL of the profile.
 * @property type        Base resource type (e.g. "Patient", "Observation").
 * @property version     Profile version string, if declared.
 * @property fhirVersion FHIR version (e.g. "4.0.1", "5.0.0").
 * @property elements    Flattened list of element summaries from the snapshot.
 */
data class ProfileSummary(
    val canonical: String,
    val type: String,
    val version: String? = null,
    val fhirVersion: String? = null,
    val elements: List<ElementSummary> = emptyList()
)

/**
 * A single FHIR element, flattened for use in LLM prompts.
 *
 * Includes cardinality, types, terminology bindings, constraints, fixed values,
 * slicing rules, flags, and mappings.
 *
 * @property path              FHIR element path (e.g. "Patient.identifier").
 * @property sliceName         Slice name, if this element is a slice entry.
 * @property slicing           Slicing rules, if this element introduces slicing.
 * @property types             Type information including profiles and target profiles.
 * @property min               Minimum cardinality.
 * @property max               Maximum cardinality ("*" for unbounded).
 * @property mustSupport       Whether the element is marked mustSupport.
 * @property isModifier        Whether the element is a modifier element.
 * @property isSummary         Whether the element appears in summary views.
 * @property binding           Terminology binding, if any.
 * @property constraints       Element-level invariants / constraints.
 * @property fixedValue        String representation of a fixed[x] value, if declared.
 * @property patternValue      String representation of a pattern[x] value, if declared.
 * @property defaultValue      String representation of a defaultValue[x], if declared.
 * @property meaningWhenMissing Explanation of what a missing value means.
 * @property contentReference  Reference to another element's definition.
 * @property extensions        URLs of extensions on this element.
 * @property modifierExtensions URLs of modifier extensions on this element.
 * @property mappings          Cross-standard mappings declared on this element.
 * @property short             Short human-readable label.
 * @property definition        Full element definition text.
 */
data class ElementSummary(
    val path: String,
    val sliceName: String? = null,
    val slicing: SlicingSummary? = null,
    val types: List<TypeSummary> = emptyList(),
    val min: Int = 0,
    val max: String = "*",
    val mustSupport: Boolean = false,
    val isModifier: Boolean = false,
    val isSummary: Boolean = false,
    val binding: BindingSummary? = null,
    val constraints: List<ConstraintSummary> = emptyList(),
    val fixedValue: String? = null,
    val patternValue: String? = null,
    val defaultValue: String? = null,
    val meaningWhenMissing: String? = null,
    val contentReference: String? = null,
    val extensions: List<String> = emptyList(),
    val modifierExtensions: List<String> = emptyList(),
    val mappings: List<MappingSummary> = emptyList(),
    val short: String? = null,
    val definition: String? = null
)

/**
 * Normalised type information for an element.
 *
 * @property code           Type code (e.g. "Reference", "CodeableConcept").
 * @property profiles       Canonical URLs of profiles this type is constrained to.
 * @property targetProfiles  For Reference types, the profiles the target must conform to.
 */
data class TypeSummary(
    val code: String,
    val profiles: List<String> = emptyList(),
    val targetProfiles: List<String> = emptyList()
)

/**
 * Normalised slicing definition.
 *
 * @property discriminators  Slicing discriminator paths and types (e.g. "value:url").
 * @property ordered         Whether slices must appear in order.
 * @property rules           Slicing rules: "open", "closed", or "openAtEnd".
 */
data class SlicingSummary(
    val discriminators: List<String> = emptyList(),
    val ordered: Boolean = false,
    val rules: String? = null
)

/**
 * Normalised element constraint / invariant.
 *
 * @property key        Constraint identifier (e.g. "ele-1").
 * @property severity   Constraint severity: "error" or "warning".
 * @property human      Human-readable description.
 * @property expression FHIRPath expression, if any.
 */
data class ConstraintSummary(
    val key: String,
    val severity: String? = null,
    val human: String? = null,
    val expression: String? = null
)

/**
 * Normalised cross-standard mapping on an element.
 *
 * @property identity  Mapping identity (e.g. "rim", "v2").
 * @property map       Mapping expression or target.
 */
data class MappingSummary(
    val identity: String,
    val map: String? = null
)

/**
 * Normalised terminology binding on an element.
 *
 * @property strength Binding strength: "required", "extensible", "preferred", or "example".
 * @property valueSet Canonical URL of the bound ValueSet.
 */
data class BindingSummary(
    val strength: String,
    val valueSet: String? = null
)
