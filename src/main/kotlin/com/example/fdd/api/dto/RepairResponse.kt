package com.example.fdd.api.dto

import com.example.fdd.model.CoverageReport
import com.example.fdd.model.DriftReport

/**
 * Response body for `POST /api/drift/repair`.
 */
data class RepairResponse(
    val driftReport: DriftReport,
    val structureMap: String,
    val validation: ValidationSummary,
    val coverage: CoverageReport,
    val outputDirectory: String? = null
)

/**
 * Summary of the Trust-but-Verify validation cycle.
 */
data class ValidationSummary(
    /** `true` if the StructureMap compiled successfully within the allowed attempts. */
    val syntacticallyValid: Boolean,
    /** Ordered log of messages from each validation / reflexion attempt. */
    val messages: List<String>
)
