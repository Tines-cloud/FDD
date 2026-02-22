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

    /**
     * Convenience accessor - items grouped by [DriftType].
     *
     * Not called in the core pipeline but reserved for experiment metrics
     * (e.g. per-type precision/recall) and future API response enrichment.
     */
    @Suppress("unused")
    fun groupedByType(): Map<DriftType, List<DriftItem>> = items.groupBy { it.type }
}
