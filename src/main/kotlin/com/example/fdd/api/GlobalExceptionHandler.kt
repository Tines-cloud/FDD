package com.example.fdd.api

import com.example.fdd.api.dto.ErrorResponse
import com.example.fdd.exception.DriftAnalysisException
import com.example.fdd.exception.FddException
import com.example.fdd.exception.LlmResponseException
import com.example.fdd.exception.MapValidationException
import com.example.fdd.exception.ProfileNotFoundException
import com.example.fdd.exception.ProfileValidationException
import com.example.fdd.output.OutputStore
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Centralised exception handling for all REST endpoints.
 *
 * Maps domain-specific exceptions to appropriate HTTP status codes and
 * returns a consistent [ErrorResponse] body.
 */
@RestControllerAdvice
class GlobalExceptionHandler(
    private val outputStore: OutputStore
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ProfileNotFoundException::class)
    fun handleProfileNotFound(
        ex: ProfileNotFoundException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Profile not found: {}", ex.message)
        val response = ErrorResponse("PROFILE_NOT_FOUND", ex.message ?: "Profile not found")
        outputStore.writeError(request, response, ex)
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(response)
    }

    @ExceptionHandler(ProfileValidationException::class)
    fun handleProfileValidation(
        ex: ProfileValidationException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Profile validation failed: {}", ex.message)
        val response = ErrorResponse(
            code = "PROFILE_VALIDATION_FAILED",
            message = ex.message ?: "Profile validation failed",
            details = ex.validationMessages
        )
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response)
    }

    @ExceptionHandler(LlmResponseException::class)
    fun handleLlmError(
        ex: LlmResponseException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.error("LLM communication failure: {}", ex.message, ex)
        val response = ErrorResponse("LLM_ERROR", ex.message ?: "LLM communication failed")
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(response)
    }

    @ExceptionHandler(DriftAnalysisException::class)
    fun handleDriftAnalysisError(
        ex: DriftAnalysisException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.error("Drift analysis failed: {}", ex.message, ex)
        val response = ErrorResponse("DRIFT_ANALYSIS_FAILED", ex.message ?: "Drift analysis failed")
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response)
    }

    @ExceptionHandler(MapValidationException::class)
    fun handleMapValidationError(
        ex: MapValidationException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.error("Map validation failed after {} attempt(s): {}", ex.attemptErrors.size, ex.message)
        ex.attemptErrors.forEachIndexed { idx, msg -> log.error("  [Attempt {}] {}", idx + 1, msg) }
        val response = ErrorResponse(
            code = "MAP_VALIDATION_FAILED",
            message = ex.message ?: "Map validation failed",
            details = ex.attemptErrors
        )
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(response)
    }

    @ExceptionHandler(FddException::class)
    fun handleFddException(
        ex: FddException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.error("FDD domain error: {}", ex.message, ex)
        val response = ErrorResponse("FDD_ERROR", ex.message ?: "Internal error")
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(
        ex: IllegalArgumentException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.warn("Bad request: {}", ex.message)
        val response = ErrorResponse("BAD_REQUEST", ex.message ?: "Invalid request")
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response)
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error", ex)
        val response = ErrorResponse("INTERNAL_ERROR", "An unexpected error occurred")
        outputStore.writeError(request, response, ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response)
    }
}
