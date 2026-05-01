package com.example.fdd.model

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls

/**
 * A single drift observation between a source and target FHIR profile.
 *
 * Each item captures one semantic difference and the FHIRPaths where
 * it manifests in both the source and target [org.hl7.fhir.r4.model.StructureDefinition].
 *
 * @property id        Stable, kebab-case identifier unique within a dataset.
 * @property type      Category of the drift (terminology, extension, structural, cardinality, version).
 * @property sourcePath FHIRPath expression identifying the element in the source profile.
 *                     Empty string when the element only exists in the target (e.g. an extension
 *                     slice added in the target). Gold-standard JSON files may serialise this as
 *                     `null`; the [JsonSetter] coerces null to "" so the model stays non-nullable
 *                     for the rest of the codebase. Uses `tools.jackson.annotation.JsonSetter` (Jackson 3.x).
 * @property targetPath FHIRPath expression identifying the element in the target profile.
 *                     Empty string when the element only exists in the source.
 * @property description Human-readable explanation of the drift.
 * @property severity   Severity level: `INFO`, `WARNING`, or `ERROR`.
 * @property source    Origin of the item: `"ai"` (LLM-detected, primary), `"rule"` (rule-based seed),
 *                     or `"hybrid"` (rule seed confirmed/refined by the LLM).
 *                     The LLM is the authoritative detector; rule items are seeds only.
 */
data class DriftItem(
    val id: String,
    val type: DriftType,
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    val sourcePath: String = "",
    @JsonSetter(nulls = Nulls.AS_EMPTY)
    val targetPath: String = "",
    val description: String = "",
    val severity: String = "WARNING",
    val source: String = "ai"
)
