package com.example.fdd.api

import com.example.fdd.api.dto.AnalyzeResponse
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.api.dto.Request
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody

interface IDriftController {
    /**
     * Analyse semantic drift between two FHIR profiles.
     *
     * Accepts profile identification by inline JSON, HTTP URL, canonical URL,
     * classpath resource, or local file path.
     */
    @PostMapping("/analyze")
    @Operation(
        summary = "Analyze drift between two FHIR profiles",
        description = "Detects semantic drift across five categories: terminology, extension, structural, cardinality, and version drift."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Drift report generated successfully"),
        ApiResponse(responseCode = "400", description = "Missing or invalid request parameters"),
        ApiResponse(responseCode = "404", description = "Profile not found by canonical URL"),
        ApiResponse(responseCode = "502", description = "LLM communication failure")
    )
    fun analyzeDrift(
        @RequestBody request: Request,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AnalyzeResponse>

    /**
     * Analyse drift AND generate a validated FHIR StructureMap to repair the drift.
     */
    @PostMapping("/repair")
    @Operation(
        summary = "Analyze drift and generate a StructureMap repair",
        description = "Detects drift, generates FHIR Mapping Language (FML) code, and validates it using the Trust-but-Verify reflexion loop."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Drift report and StructureMap generated"),
        ApiResponse(responseCode = "400", description = "Missing or invalid request parameters"),
        ApiResponse(responseCode = "404", description = "Profile not found by canonical URL"),
        ApiResponse(responseCode = "422", description = "Drift analysis or map generation failed"),
        ApiResponse(responseCode = "502", description = "LLM communication failure")
    )
    fun analyzeAndRepair(
        @RequestBody request: Request,
        httpRequest: HttpServletRequest
    ): ResponseEntity<RepairResponse>
}
