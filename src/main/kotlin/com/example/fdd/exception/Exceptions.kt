package com.example.fdd.exception

/** Base exception for all FDD errors. */
open class FddException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Profile not found by URL or classpath. */
class ProfileNotFoundException(message: String) : FddException(message)

/** Profile failed HAPI-FHIR structural validation. */
class ProfileValidationException(
    message: String,
    val validationMessages: List<String> = emptyList()
) : FddException(message)

/** LLM returned an empty or broken response. */
class LlmResponseException(message: String, cause: Throwable? = null) : FddException(message, cause)

/** Drift analysis produced invalid output. */
class DriftAnalysisException(message: String, cause: Throwable? = null) : FddException(message, cause)

/**
 * FML compilation failed after all repair attempts.
 * Contains per-cycle error details so you can see exactly what went wrong each time.
 */
class MapValidationException(
    message: String,
    val attemptErrors: List<String> = emptyList(),
    cause: Throwable? = null
) : FddException(message, cause)
