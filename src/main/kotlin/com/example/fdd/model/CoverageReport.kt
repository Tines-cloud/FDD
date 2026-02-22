package com.example.fdd.model

/**
 * What happened to a drift item in the generated FML.
 */
enum class CoverageStatus {
    /** FML has an explicit mapping rule for this drift. */
    MAPPED,

    /** A parent element's mapping group handles this sub-element. */
    COVERED_BY_PARENT,

    /** Profile-level metadata (MustSupport, constraints, conformance extensions) - not instance data. */
    NO_RULE_NEEDED,

    /** Source element does not exist in the target profile - data will be lost in transformation. */
    SOURCE_DATA_LOSS,

    /** Target element has no equivalent in the source - cannot be populated automatically. */
    UNMAPPABLE_NO_SOURCE,

    /**
     * Target element has no source equivalent AND is mandatory (min >= 1) or mustSupport in the
     * target profile. Leaving it empty will cause the transformed resource to fail validation.
     * A default-value FML rule or a post-processing enrichment step is REQUIRED.
     */
    UNMAPPABLE_REQUIRED
}

/**
 * Coverage classification for a single drift item.
 */
data class CoverageItem(
    val driftItemId: String,
    val driftType: String,
    val sourcePath: String,
    val targetPath: String,
    val description: String,
    val severity: String,
    val coverageStatus: CoverageStatus,
    val explanation: String,
    /** How the FML handles this drift (e.g. "MapIdentifier group copies all sub-elements"). */
    val fmlHandling: String = "",
    /** For UNMAPPABLE_REQUIRED: the target element's min cardinality (>=1 means required). */
    val targetMin: Int = 0
)

/**
 * Full coverage analysis comparing the drift report against the generated FML.
 * Every drift item is accounted for - nothing is silently dropped.
 */
data class CoverageReport(
    val totalDriftItems: Int,
    val mapped: Int,
    val coveredByParent: Int,
    val noRuleNeeded: Int,
    val sourceDataLoss: Int,
    val unmappableNoSource: Int,
    val dataShareabilityPercent: Double,
    val items: List<CoverageItem>,
    val summary: String,
    /** One-line verdict: e.g. "104/104 drifts are correctly handled" */
    val verdict: String = "",
    /**
     * Count of UNMAPPABLE_REQUIRED items: target fields that are mandatory (min>=1) or
     * mustSupport but have no source equivalent. These will cause validation failures
     * unless addressed with default-value rules or post-processing.
     */
    val criticalUnmappable: Int = 0
)
