package com.example.fdd.model

/**
 * A gold-standard profile pair used for experiment evaluation.
 *
 * @property pairId           Unique identifier for this pair (e.g. "r4-vs-us-core-patient").
 * @property sourceClasspath  Classpath-relative path to the source StructureDefinition JSON.
 * @property targetClasspath  Classpath-relative path to the target StructureDefinition JSON.
 * @property drifts           Manually curated list of expected [DriftItem]s.
 */
data class GoldStandardPair(
    val pairId: String,
    val sourceClasspath: String,
    val targetClasspath: String,
    val drifts: List<DriftItem> = emptyList()
)
