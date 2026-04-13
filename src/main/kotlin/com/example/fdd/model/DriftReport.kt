package com.example.fdd.model

/**
 * Aggregated report of all semantic drift items detected between two FHIR profiles.
 *
 * @property sourceProfileCanonical Canonical URL of the source StructureDefinition.
 * @property targetProfileCanonical Canonical URL of the target StructureDefinition.
 * @property items                  Ordered list of detected [DriftItem]s.
 */
data class DriftReport(
    val sourceProfileCanonical: String,
    val targetProfileCanonical: String,
    val items: List<DriftItem> = emptyList()
) {
    /** Total number of drift items detected. */
    val totalDrifts: Int get() = items.size
}
