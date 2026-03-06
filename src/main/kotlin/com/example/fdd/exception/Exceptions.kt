package com.example.fdd.exception

/**
 * Base exception for all FDD domain errors.
 */
open class FddException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Thrown when a FHIR StructureDefinition cannot be located by canonical URL or classpath path.
 */
class ProfileNotFoundException(message: String) : FddException(message)

/**
 * Thrown when a FHIR profile fails structural validation via HAPI-FHIR.
 *
 * Contains the detailed validation messages so the caller can inspect
 * exactly what is wrong with the profile.
 */
class ProfileValidationException(
    message: String,
    /** Individual HAPI-FHIR validation messages (severity + location + text). */
    val validationMessages: List<String> = emptyList()
) : FddException(message)

/**
 * Thrown when the LLM returns an unusable or unparseable response.
 */
class LlmResponseException(message: String, cause: Throwable? = null) : FddException(message, cause)

/**
 * Thrown when the drift analysis produces invalid or unparseable output.
 */
class DriftAnalysisException(message: String, cause: Throwable? = null) : FddException(message, cause)

/**
 * Thrown when StructureMap compilation fails after exhausting all reflexion attempts.
 *
 * Contains per-attempt failure reasons so callers can inspect exactly what went wrong
 * at each stage of the Trust-but-Verify / Reflexion loop.
 */
class MapValidationException(
    message: String,
    /** Per-attempt error messages, e.g. "Attempt 1: <parse error>", "Attempt 2: <reflexion error>" */
    val attemptErrors: List<String> = emptyList(),
    cause: Throwable? = null
) : FddException(message, cause)
