package com.example.fdd.util

import com.example.fdd.util.FhirValidationUtils.downgradablePatterns
import com.example.fdd.util.FhirValidationUtils.isDowngradableError

/**
 * Shared FHIR validation utilities used across [DefaultProfileLoader] and [ProfileValidationService].
 *
 * The [downgradablePatterns] list and [isDowngradableError] function were previously duplicated
 * in both classes; keeping them here ensures both classes always apply the same set of rules.
 */
object FhirValidationUtils {

    /**
     * Patterns for HAPI-FHIR validation errors that are known false-positives
     * and should be downgraded to warnings rather than blocking the pipeline.
     *
     * - HL7 owning committee: US-Core/AU-Core profiles trigger this because
     *   they don't bundle the structuredefinition-wg extension. Profiles are still valid.
     * - Profile not found: Parent profiles (e.g. AU Base) may not be on the classpath.
     *   The profile is still usable for drift analysis.
     * - Type not legal (R5): HAPI R5 validator sometimes flags valid R5 types.
     * - Snapshot generation: Fails when an intermediate parent is not on the classpath.
     *   The differential is still usable.
     */
    val downgradablePatterns = listOf(
        Regex("owning committee must be stated", RegexOption.IGNORE_CASE),
        Regex("Profile reference .+ has not been checked because it could not be found", RegexOption.IGNORE_CASE),
        Regex("is not legal because it is not defined in the FHIR specification", RegexOption.IGNORE_CASE),
        Regex("Error generating Snapshot.*\\bbase\\b.*\\bnull\\b", RegexOption.IGNORE_CASE)
    )

    fun isDowngradableError(msg: String?): Boolean =
        msg != null && downgradablePatterns.any { it.containsMatchIn(msg) }
}
