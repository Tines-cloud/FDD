package com.example.fdd.api

import com.example.fdd.api.dto.ErrorResponse
import com.example.fdd.exception.DriftAnalysisException
import com.example.fdd.exception.FddException
import com.example.fdd.exception.LlmResponseException
import com.example.fdd.exception.MapValidationException
import com.example.fdd.exception.ProfileNotFoundException
import com.example.fdd.exception.ProfileValidationException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler

interface IGlobalExceptionHandler {
    @ExceptionHandler(ProfileNotFoundException::class)
    fun handleProfileNotFound(
        ex: ProfileNotFoundException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(ProfileValidationException::class)
    fun handleProfileValidation(
        ex: ProfileValidationException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(LlmResponseException::class)
    fun handleLlmError(
        ex: LlmResponseException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(DriftAnalysisException::class)
    fun handleDriftAnalysisError(
        ex: DriftAnalysisException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(MapValidationException::class)
    fun handleMapValidationError(
        ex: MapValidationException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(FddException::class)
    fun handleFddException(
        ex: FddException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(
        ex: IllegalArgumentException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>

    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse>
}
