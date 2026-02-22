package com.example.fdd.model

/**
 * Categories of semantic drift between two FHIR profiles.
 *
 * Each category represents a distinct class of difference that may exist
 * between a source and target [org.hl7.fhir.r4.model.StructureDefinition].
 */
enum class DriftType {

    /** Differences in value-set bindings, code systems, or binding strength. */
    TERMINOLOGY,

    /** Extensions present in one profile but absent or structurally different in the other. */
    EXTENSION,

    /** Structural differences: renamed paths, reorganised elements, slicing changes, type changes. */
    STRUCTURAL,

    /** Changes in minimum / maximum cardinality (e.g. 0..1 -> 1..1). */
    CARDINALITY,

    /** Cross-version differences (e.g. R4 vs R5 element renames or restructuring). */
    VERSION
}
