package com.example.fdd.api

import com.example.fdd.api.dto.AnalyzeRequest
import com.example.fdd.api.dto.AnalyzeResponse
import com.example.fdd.api.dto.RepairRequest
import com.example.fdd.api.dto.RepairResponse
import com.example.fdd.api.dto.ValidationSummary
import com.example.fdd.output.OutputStore
import com.example.fdd.service.DriftOrchestrationService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * REST controller exposing the FHIR Drift Doctor endpoints.
 *
 * | Method | Path                  | Description                      |
 * |--------|-----------------------|----------------------------------|
 * | POST   | `/api/drift/analyze`  | Detect drift between two profiles |
 * | POST   | `/api/drift/repair`   | Detect drift + generate repair map |
 */
@RestController
@RequestMapping("/api/drift", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Drift Detection & Repair", description = "FHIR profile drift analysis and StructureMap generation")
class DriftController(
    private val orchestrationService: DriftOrchestrationService,
    private val outputStore: OutputStore
) {

    private val log = LoggerFactory.getLogger(javaClass)

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
        @RequestBody request: AnalyzeRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<AnalyzeResponse> {
        val outputContext = outputStore.createContext(
            requestType = "analyze",
            sourceLabel = request.source.label(),
            targetLabel = request.target.label(),
            requestPayload = request
        )
        outputStore.attachToRequest(httpRequest, outputContext)

        request.validate()
        log.info(
            "POST /api/drift/analyze - source: {}, target: {}",
            request.source.label(),
            request.target.label()
        )

        val driftReport = orchestrationService.analyzeDrift(
            source = request.source,
            target = request.target
        )

        val response = AnalyzeResponse(
            driftReport = driftReport,
            outputDirectory = outputContext?.directory?.toString()
        )
        outputStore.writeAnalyzeResult(outputContext, driftReport, response)

        return ResponseEntity.ok(response)
    }

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
        @RequestBody request: RepairRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<RepairResponse> {
        val outputContext = outputStore.createContext(
            requestType = "repair",
            sourceLabel = request.source.label(),
            targetLabel = request.target.label(),
            requestPayload = request
        )
        outputStore.attachToRequest(httpRequest, outputContext)

        request.validate()
        log.info(
            "POST /api/drift/repair - source: {}, target: {}",
            request.source.label(),
            request.target.label()
        )

        val (driftReport, mapResult, coverageReport) = orchestrationService.analyzeAndRepair(
            source = request.source,
            target = request.target
        )

        val response = RepairResponse(
            driftReport = driftReport,
            structureMap = mapResult.structureMapFml,
            validation = ValidationSummary(
                syntacticallyValid = mapResult.syntacticallyValid,
                messages = mapResult.validationMessages
            ),
            coverage = coverageReport,
            outputDirectory = outputContext?.directory?.toString()
        )

        outputStore.writeRepairResult(outputContext, response)

        return ResponseEntity.ok(response)
    }
}
