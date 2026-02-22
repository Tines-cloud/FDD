package com.example.fdd.model

/**
 * A gold-standard profile pair used for experiment evaluation.
 *
 * @property pairId           Unique identifier for this pair (e.g. "r4-vs-us-core-patient").
 * @property sourceCanonical  Canonical URL of the source StructureDefinition.
 * @property targetCanonical  Canonical URL of the target StructureDefinition.
 * @property drifts           Manually curated list of expected [DriftItem]s.
 */
data class GoldStandardPair(
    val pairId: String,
    val sourceCanonical: String,
    val targetCanonical: String,
    val drifts: List<DriftItem> = emptyList()
)
