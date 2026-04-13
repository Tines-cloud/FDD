package com.example.fdd.api.impl

import com.example.fdd.api.IProfileValidationController
import com.example.fdd.api.dto.ProfileValidationReport
import com.example.fdd.service.impl.ProfileValidationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller exposing HAPI-FHIR profile validation results.
 *
 * | Method | Path                             | Description                                      |
 * |--------|----------------------------------|--------------------------------------------------|
 * | GET    | `/api/validate/profiles`         | Validate all custom StructureDefinition profiles  |
 * | GET    | `/api/validate/standard`         | Validate all standard FHIR profiles               |
 * | GET    | `/api/validate/all`              | Validate everything (custom + standard profiles)  |
 */
@RestController
@RequestMapping("/api/validate", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Profile Validation", description = "HAPI-FHIR validation of custom and standard FHIR profiles")
class ProfileValidationController(
    private val profileValidationService: ProfileValidationService
) : IProfileValidationController {

    private val log = LoggerFactory.getLogger(javaClass)

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
    override fun validateAllProfiles(): ResponseEntity<ProfileValidationReport> {
        log.info("GET /api/validate/profiles - validating all custom profiles")
        val report = profileValidationService.validateAllCustomProfiles()
        return ResponseEntity.ok(report)
    }

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
    override fun validateAllStandardProfiles(): ResponseEntity<ProfileValidationReport> {
        log.info("GET /api/validate/standard - validating all standard profiles")
        val report = profileValidationService.validateAllStandardProfiles()
        return ResponseEntity.ok(report)
    }

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
    override fun validateAll(): ResponseEntity<ProfileValidationReport> {
        log.info("GET /api/validate/all - validating everything")
        val report = profileValidationService.validateAll()
        return ResponseEntity.ok(report)
    }
}