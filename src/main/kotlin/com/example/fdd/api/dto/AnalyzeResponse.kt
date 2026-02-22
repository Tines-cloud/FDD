package com.example.fdd.api.dto

import com.example.fdd.model.DriftReport

/**
 * Response body for `POST /api/drift/analyze`.
 */
data class AnalyzeResponse(
    val driftReport: DriftReport,
    val outputDirectory: String? = null
)
