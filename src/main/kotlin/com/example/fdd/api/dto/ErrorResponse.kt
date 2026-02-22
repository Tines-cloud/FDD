package com.example.fdd.api.dto

/**
 * Uniform error response body returned by [com.example.fdd.api.impl.GlobalExceptionHandler].
 *
 * @property details Optional list of individual validation messages (e.g. from HAPI-FHIR).
 */
data class ErrorResponse(
    val code: String,
    val message: String,
    val details: List<String> = emptyList()
)
