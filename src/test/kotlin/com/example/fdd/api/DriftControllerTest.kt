package com.example.fdd.api

import com.example.fdd.api.impl.DriftController
import com.example.fdd.model.CoverageReport
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.model.DriftType
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.output.IOutputStore
import com.example.fdd.service.DriftOrchestrationService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

/**
 * Unit tests for [com.example.fdd.api.impl.DriftController].
 *
 * Uses `@WebMvcTest` to load only the web layer with a mocked orchestration service.
 */
@WebMvcTest(DriftController::class)
class DriftControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var orchestrationService: DriftOrchestrationService

    @MockitoBean
    private lateinit var outputStore: IOutputStore

    private val sampleDriftReport = DriftReport(
        sourceProfileCanonical = "http://hl7.org/fhir/StructureDefinition/Patient",
        targetProfileCanonical = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient",
        items = listOf(
            DriftItem(
                id = "identifier-cardinality",
                type = DriftType.CARDINALITY,
                sourcePath = "Patient.identifier",
                targetPath = "Patient.identifier",
                description = "US Core requires at least one identifier",
                severity = "ERROR"
            )
        )
    )

    @Test
    @DisplayName("POST /api/drift/analyze returns drift report for canonical URLs")
    fun analyzeDrift_withCanonicals_returnsDriftReport() {
        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenReturn(sampleDriftReport)

        mockMvc.post("/api/drift/analyze") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "source": {
                    "canonical": "http://hl7.org/fhir/StructureDefinition/Patient"
                  },
                  "target": {
                    "canonical": "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.driftReport.sourceProfileCanonical") {
                value("http://hl7.org/fhir/StructureDefinition/Patient")
            }
            jsonPath("$.driftReport.items.length()") { value(1) }
            jsonPath("$.driftReport.items[0].type") { value("CARDINALITY") }
        }
    }

    @Test
    @DisplayName("POST /api/drift/analyze rejects request missing both source and target")
    fun analyzeDrift_missingInput_returnsBadRequest() {
        mockMvc.post("/api/drift/analyze") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "source": {
                    "canonical": "http://hl7.org/fhir/StructureDefinition/Patient"
                  },
                  "target": {}
                }
            """.trimIndent()
        }.andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    @DisplayName("POST /api/drift/repair returns drift report and structure map")
    fun analyzeAndRepair_returnsRepairResponse() {
        val mapResult = MapGenerationResult(
            structureMapFml = "map \"test\" = \"test\" { }",
            syntacticallyValid = true,
            validationMessages = listOf("Attempt 1: Compilation successful")
        )

        val sampleCoverage = CoverageReport(
            totalDriftItems = 1, mapped = 1, coveredByParent = 0,
            noRuleNeeded = 0, sourceDataLoss = 0, unmappableNoSource = 0,
            dataShareabilityPercent = 100.0, items = emptyList(),
            summary = "All covered", verdict = "1/1 drifts handled"
        )

        whenever(orchestrationService.analyzeAndRepair(source = any(), target = any()))
            .thenReturn(Triple(sampleDriftReport, mapResult, sampleCoverage))

        mockMvc.post("/api/drift/repair") {
            contentType = MediaType.APPLICATION_JSON
            content = """
                {
                  "source": {
                    "canonical": "http://hl7.org/fhir/StructureDefinition/Patient"
                  },
                  "target": {
                    "canonical": "http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient"
                  }
                }
            """.trimIndent()
        }.andExpect {
            status { isOk() }
            jsonPath("$.driftReport.totalDrifts") { value(1) }
            jsonPath("$.structureMap") { isNotEmpty() }
            jsonPath("$.validation.syntacticallyValid") { value(true) }
        }
    }
}
