package com.example.fdd.api.dto

/**
 * Response payload for the profile validation endpoint.
 *
 * Contains per-profile validation results and a summary.
 */
data class ProfileValidationReport(
    val profiles: List<ProfileValidationResult>,
    val summary: ProfileValidationSummary
)

data class ProfileValidationResult(
    val profileName: String,
    val canonicalUrl: String,
    val resourceType: String,
    val publisher: String,
    val source: String = "",
    val valid: Boolean,
    val errorCount: Int,
    val warningCount: Int,
    val issues: List<ProfileValidationIssue>
)

data class ProfileValidationIssue(
    val severity: String,
    val location: String,
    val message: String
)

data class ProfileValidationSummary(
    val totalProfiles: Int,
    val validProfiles: Int,
    val invalidProfiles: Int,
    val profilesWithWarnings: Int,
    val totalErrors: Int,
    val totalWarnings: Int
)
