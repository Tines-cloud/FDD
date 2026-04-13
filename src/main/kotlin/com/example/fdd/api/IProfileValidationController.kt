package com.example.fdd.api

import com.example.fdd.api.dto.ProfileValidationReport
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping

interface IProfileValidationController {
    /**
     * Validate all custom FHIR profiles found under `classpath:custom-profiles/`.
     */
    @GetMapping("/profiles")
    @Operation(
        summary = "Validate all custom FHIR profiles",
        description = "Scans all *.json files under classpath:custom-profiles/ recursively, parses each as a FHIR R4 StructureDefinition, and validates them using the HAPI-FHIR validator."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Validation completed"),
        ApiResponse(responseCode = "500", description = "Unexpected server error")
    )
    fun validateAllProfiles(): ResponseEntity<ProfileValidationReport>

    /**
     * Validate all standard FHIR profiles under `classpath:standard-profiles/`.
     */
    @GetMapping("/standard")
    @Operation(
        summary = "Validate all standard FHIR profiles",
        description = "Scans all *.json files under classpath:standard-profiles/ recursively, parses each as a FHIR R4 StructureDefinition, and validates them using the HAPI-FHIR validator."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Validation completed"),
        ApiResponse(responseCode = "500", description = "Unexpected server error")
    )
    fun validateAllStandardProfiles(): ResponseEntity<ProfileValidationReport>

    /**
     * Validate everything: custom profiles + standard profiles.
     */
    @GetMapping("/all")
    @Operation(
        summary = "Validate all FHIR profiles",
        description = "Validates all custom StructureDefinition profiles AND all standard FHIR profiles. Returns a combined report covering everything."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Validation completed"),
        ApiResponse(responseCode = "500", description = "Unexpected server error")
    )
    fun validateAll(): ResponseEntity<ProfileValidationReport>
}
