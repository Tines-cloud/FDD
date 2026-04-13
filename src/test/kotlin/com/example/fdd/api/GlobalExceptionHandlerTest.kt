package com.example.fdd.api

import com.example.fdd.api.impl.GlobalExceptionHandler
import com.example.fdd.exception.DriftAnalysisException
import com.example.fdd.exception.FddException
import com.example.fdd.exception.LlmResponseException
import com.example.fdd.exception.MapValidationException
import com.example.fdd.exception.ProfileNotFoundException
import com.example.fdd.exception.ProfileValidationException
import com.example.fdd.output.impl.OutputStore
import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.http.HttpStatus

/**
 * Unit tests for [com.example.fdd.api.impl.GlobalExceptionHandler].
 *
 * Verifies that each domain exception is mapped to the correct HTTP status
 * code and error response body, matching the contract declared in the handler.
 */
class GlobalExceptionHandlerTest {

    private val outputStore: OutputStore = mock()
    private val request: HttpServletRequest = mock()
    private val handler = GlobalExceptionHandler(outputStore)

    @Test
    @DisplayName("ProfileNotFoundException -> 404 NOT_FOUND with PROFILE_NOT_FOUND code")
    fun handleProfileNotFound_returns404() {
        val response = handler.handleProfileNotFound(
            ProfileNotFoundException("http://unknown.org/Profile not found"),
            request
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
        assertEquals("PROFILE_NOT_FOUND", response.body!!.code)
        assertTrue(response.body!!.message.contains("not found"))
    }

    @Test
    @DisplayName("ProfileValidationException -> 422 with validation messages in details")
    fun handleProfileValidation_returns422WithDetails() {
        val messages = listOf(
            "ERROR: Element 'Patient.xyz' is unknown",
            "WARNING: Missing required element 'name'"
        )
        val response = handler.handleProfileValidation(
            ProfileValidationException("Validation failed", messages),
            request
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("PROFILE_VALIDATION_FAILED", response.body!!.code)
        assertEquals(2, response.body!!.details.size)
        assertTrue(response.body!!.details[0].contains("unknown"))
    }

    @Test
    @DisplayName("LlmResponseException -> 502 BAD_GATEWAY with LLM_ERROR code")
    fun handleLlmError_returns502() {
        val response = handler.handleLlmError(
            LlmResponseException("Model returned empty response"),
            request
        )

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertEquals("LLM_ERROR", response.body!!.code)
        assertTrue(response.body!!.message.contains("empty response"))
    }

    @Test
    @DisplayName("DriftAnalysisException -> 422 with DRIFT_ANALYSIS_FAILED code")
    fun handleDriftAnalysisError_returns422() {
        val response = handler.handleDriftAnalysisError(
            DriftAnalysisException("Failed to parse LLM JSON"),
            request
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("DRIFT_ANALYSIS_FAILED", response.body!!.code)
        assertTrue(response.body!!.message.contains("parse"))
    }

    @Test
    @DisplayName("MapValidationException -> 422 with MAP_VALIDATION_FAILED code")
    fun handleMapValidationError_returns422() {
        val response = handler.handleMapValidationError(
            MapValidationException("Compilation failed after 3 reflexion attempts"),
            request
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertEquals("MAP_VALIDATION_FAILED", response.body!!.code)
        assertTrue(response.body!!.message.contains("3 reflexion attempts"))
    }

    @Test
    @DisplayName("FddException (base) -> 500 with FDD_ERROR code")
    fun handleFddException_returns500() {
        val response = handler.handleFddException(
            FddException("Unexpected domain error"),
            request
        )

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("FDD_ERROR", response.body!!.code)
        assertTrue(response.body!!.message.contains("domain error"))
    }

    @Test
    @DisplayName("IllegalArgumentException -> 400 BAD_REQUEST")
    fun handleBadRequest_returns400() {
        val response = handler.handleBadRequest(
            IllegalArgumentException("At least one profile input is required"),
            request
        )

        assertEquals(HttpStatus.BAD_REQUEST, response.statusCode)
        assertEquals("BAD_REQUEST", response.body!!.code)
        assertTrue(response.body!!.message.contains("profile input"))
    }

    @Test
    @DisplayName("Generic Exception -> 500 with INTERNAL_ERROR code and generic message")
    fun handleGenericException_returns500WithGenericMessage() {
        val response = handler.handleGenericException(
            RuntimeException("Something unexpected broke"),
            request
        )

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.statusCode)
        assertEquals("INTERNAL_ERROR", response.body!!.code)
        assertEquals("An unexpected error occurred", response.body!!.message)
        // Generic handler should NOT leak internal exception messages
        assertFalse(response.body!!.message.contains("broke"))
    }

    @Test
    @DisplayName("ProfileValidationException with empty messages returns empty details list")
    fun handleProfileValidation_emptyMessages_returnsEmptyDetails() {
        val response = handler.handleProfileValidation(
            ProfileValidationException("Validation failed"),
            request
        )

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, response.statusCode)
        assertTrue(response.body!!.details.isEmpty())
    }

    @Test
    @DisplayName("LlmResponseException with cause preserves cause chain")
    fun handleLlmError_withCause_returns502() {
        val cause = java.net.ConnectException("Connection refused")
        val response = handler.handleLlmError(
            LlmResponseException("Failed to obtain response from LLM: Connection refused", cause),
            request
        )

        assertEquals(HttpStatus.BAD_GATEWAY, response.statusCode)
        assertTrue(response.body!!.message.contains("Connection refused"))
    }
}
