package com.example.fdd.model

/**
 * A single drift observation between a source and target FHIR profile.
 *
 * Each item captures one semantic difference and the FHIRPaths where
 * it manifests in both the source and target [org.hl7.fhir.r4.model.StructureDefinition].
 *
 * @property id        Stable, kebab-case identifier unique within a dataset.
 * @property type      Category of the drift (terminology, extension, structural, cardinality, version).
 * @property sourcePath FHIRPath expression identifying the element in the source profile.
 * @property targetPath FHIRPath expression identifying the element in the target profile.
 * @property description Human-readable explanation of the drift.
 * @property severity   Severity level: `INFO`, `WARNING`, or `ERROR`.
 */
data class DriftItem(
    val id: String,
    val type: DriftType,
    val sourcePath: String,
    val targetPath: String,
    val description: String,
    val severity: String = "WARNING"
)
