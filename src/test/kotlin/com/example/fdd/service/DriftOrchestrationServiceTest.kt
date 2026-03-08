package com.example.fdd.service

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.exception.FddException
import com.example.fdd.exception.MapValidationException
import com.example.fdd.exception.ProfileNotFoundException
import com.example.fdd.exception.ProfileValidationException
import com.example.fdd.fhir.ProfileLoader
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.model.DriftType
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.service.CoverageAnalyzer
import com.example.fdd.validation.DriftProfileValidator
import com.example.fdd.validation.MapValidator
import org.hl7.fhir.r4.model.StructureDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlin.test.Test

/**
 * Unit tests for [DefaultDriftOrchestrationService].
 *
 * Verifies correct wiring of the four-stage pipeline:
 *   1. Profile resolution (load & validate)
 *   2. Drift analysis
 *   3. StructureMap generation
 *   4. Trust-but-Verify validation loop
 */
class DriftOrchestrationServiceTest {

    private lateinit var profileLoader: ProfileLoader
    private lateinit var driftValidator: DriftProfileValidator
    private lateinit var driftAnalyzer: DriftAnalyzer
    private lateinit var mapGenerator: MapGenerator
    private lateinit var mapValidator: MapValidator
    private lateinit var coverageAnalyzer: CoverageAnalyzer
    private lateinit var orchestrator: DefaultDriftOrchestrationService

    private val sourceSD = StructureDefinition().apply {
        url = "http://example.org/fhir/StructureDefinition/Source"
        name = "SourceProfile"
    }
    private val targetSD = StructureDefinition().apply {
        url = "http://example.org/fhir/StructureDefinition/Target"
        name = "TargetProfile"
    }

    private val sampleDriftReport = DriftReport(
        sourceProfileCanonical = sourceSD.url,
        targetProfileCanonical = targetSD.url,
        items = listOf(
            DriftItem(
                id = "drift-001",
                type = DriftType.CARDINALITY,
                sourcePath = "Patient.identifier",
                targetPath = "Patient.identifier",
                severity = "MAJOR",
                description = "Identifier made mandatory"
            )
        )
    )

    private val validMapResult = MapGenerationResult(
        structureMapFml = "map \"http://example.org/maps/SourceToTarget\" = SourceToTarget\nuses ...",
        syntacticallyValid = true,
        validationMessages = listOf("Compiled successfully on attempt 1")
    )

    private val invalidMapResult = MapGenerationResult(
        structureMapFml = "map invalid ...",
        syntacticallyValid = false,
        validationMessages = listOf("Parse error on attempt 1", "Parse error on attempt 2", "Parse error on attempt 3")
    )

    @BeforeEach
    fun setUp() {
        profileLoader = mock()
        driftValidator = mock()
        driftAnalyzer = mock()
        mapGenerator = mock()
        mapValidator = mock()
        coverageAnalyzer = CoverageAnalyzer()
        orchestrator =
            DefaultDriftOrchestrationService(profileLoader, driftValidator, driftAnalyzer, mapGenerator, mapValidator, coverageAnalyzer)
    }

    /* ---------------- analyzeDrift() ---------------- */

    @Nested
    @DisplayName("analyzeDrift()")
    inner class AnalyzeDriftTests {

        @Test
        @DisplayName("resolves profiles from JSON and returns drift report")
        fun resolveFromJsonAndAnalyze() {
            val sourceJson = """{"resourceType":"StructureDefinition","id":"source"}"""
            val targetJson = """{"resourceType":"StructureDefinition","id":"target"}"""

            whenever(profileLoader.loadProfileFromJson(sourceJson)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileFromJson(targetJson)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            val result = orchestrator.analyzeDrift(
                source = ProfileInput(json = sourceJson),
                target = ProfileInput(json = targetJson)
            )

            assertEquals(sampleDriftReport, result)
            assertEquals(1, result.totalDrifts)
            verify(profileLoader).loadProfileFromJson(sourceJson)
            verify(profileLoader).loadProfileFromJson(targetJson)
            verify(driftAnalyzer).analyzeDrift(sourceSD, targetSD)
        }

        @Test
        @DisplayName("resolves profiles from canonical URLs")
        fun resolveFromCanonical() {
            whenever(profileLoader.loadProfileByCanonical(sourceSD.url)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileByCanonical(targetSD.url)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            val result = orchestrator.analyzeDrift(
                source = ProfileInput(canonical = sourceSD.url),
                target = ProfileInput(canonical = targetSD.url)
            )

            assertEquals(sampleDriftReport, result)
            verify(profileLoader).loadProfileByCanonical(sourceSD.url)
            verify(profileLoader).loadProfileByCanonical(targetSD.url)
        }

        @Test
        @DisplayName("resolves profiles from HTTP URLs")
        fun resolveFromUrl() {
            val sourceUrl = "https://build.fhir.org/ig/source.json"
            val targetUrl = "https://build.fhir.org/ig/target.json"

            whenever(profileLoader.loadProfileFromUrl(sourceUrl)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileFromUrl(targetUrl)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            val result = orchestrator.analyzeDrift(
                source = ProfileInput(url = sourceUrl),
                target = ProfileInput(url = targetUrl)
            )

            assertEquals(sampleDriftReport, result)
            verify(profileLoader).loadProfileFromUrl(sourceUrl)
            verify(profileLoader).loadProfileFromUrl(targetUrl)
        }

        @Test
        @DisplayName("prefers JSON over URL and canonical when all are provided")
        fun jsonTakesPrecedence() {
            val sourceJson = """{"resourceType":"StructureDefinition"}"""

            whenever(profileLoader.loadProfileFromJson(sourceJson)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileByCanonical(targetSD.url)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            val result = orchestrator.analyzeDrift(
                source = ProfileInput(
                    json = sourceJson,
                    url = "https://ignored.org/should-not-be-used",
                    canonical = "http://ignored.org/should-not-be-used"
                ),
                target = ProfileInput(canonical = targetSD.url)
            )

            assertEquals(sampleDriftReport, result)
            verify(profileLoader).loadProfileFromJson(sourceJson)
            verify(profileLoader, never()).loadProfileFromUrl(any())
            verify(profileLoader, never()).loadProfileByCanonical("http://ignored.org/should-not-be-used")
        }

        @Test
        @DisplayName("resolves profiles from classpath resources")
        fun resolveFromClasspath() {
            val sourcePath = "fhir/profiles/us-core/StructureDefinition-us-core-patient.json"
            val targetPath = "fhir/profiles/ips/StructureDefinition-ips-patient.json"

            whenever(profileLoader.loadProfileFromClasspath(sourcePath)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileFromClasspath(targetPath)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            val result = orchestrator.analyzeDrift(
                source = ProfileInput(classpath = sourcePath),
                target = ProfileInput(classpath = targetPath)
            )

            assertEquals(sampleDriftReport, result)
            verify(profileLoader).loadProfileFromClasspath(sourcePath)
            verify(profileLoader).loadProfileFromClasspath(targetPath)
        }

        @Test
        @DisplayName("resolves profiles from local file paths")
        fun resolveFromFile() {
            val sourceFile = "C:/profiles/source-patient.json"
            val targetFile = "C:/profiles/target-patient.json"

            whenever(profileLoader.loadProfileFromFile(sourceFile)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileFromFile(targetFile)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            val result = orchestrator.analyzeDrift(
                source = ProfileInput(file = sourceFile),
                target = ProfileInput(file = targetFile)
            )

            assertEquals(sampleDriftReport, result)
            verify(profileLoader).loadProfileFromFile(sourceFile)
            verify(profileLoader).loadProfileFromFile(targetFile)
        }

        @Test
        @DisplayName("throws FddException when no input is provided for a profile")
        fun throwsOnMissingInput() {
            assertThrows<FddException> {
                orchestrator.analyzeDrift(
                    source = ProfileInput(),
                    target = ProfileInput(canonical = targetSD.url)
                )
            }
        }

        @Test
        @DisplayName("propagates ProfileNotFoundException from loader")
        fun propagatesProfileNotFound() {
            whenever(profileLoader.loadProfileByCanonical(any()))
                .thenThrow(ProfileNotFoundException("http://missing.org/Profile"))

            assertThrows<ProfileNotFoundException> {
                orchestrator.analyzeDrift(
                    source = ProfileInput(canonical = "http://missing.org/Profile"),
                    target = ProfileInput(canonical = targetSD.url)
                )
            }
        }

        @Test
        @DisplayName("propagates ProfileValidationException from loader")
        fun propagatesValidationError() {
            val json = """{"resourceType":"StructureDefinition","url":"bad"}"""
            whenever(profileLoader.loadProfileFromJson(json))
                .thenThrow(ProfileValidationException("bad", listOf("Element is unknown")))

            assertThrows<ProfileValidationException> {
                orchestrator.analyzeDrift(
                    source = ProfileInput(json = json),
                    target = ProfileInput(json = json)
                )
            }
        }
    }

    /* ---------------- analyzeAndRepair() ---------------- */

    @Nested
    @DisplayName("analyzeAndRepair()")
    inner class AnalyzeAndRepairTests {

        @Test
        @DisplayName("full pipeline: load -> analyze -> generate -> validate -> result")
        fun fullPipelineSuccess() {
            val sourceJson = """{"resourceType":"StructureDefinition","id":"source"}"""
            val targetJson = """{"resourceType":"StructureDefinition","id":"target"}"""

            whenever(profileLoader.loadProfileFromJson(sourceJson)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileFromJson(targetJson)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)
            whenever(mapGenerator.generateMap(sourceSD, targetSD, sampleDriftReport)).thenReturn(validMapResult)
            whenever(
                mapValidator.validateAndRepair(
                    fmlCode = validMapResult.structureMapFml,
                    source = sourceSD,
                    target = targetSD,
                    driftReport = sampleDriftReport
                )
            ).thenReturn(validMapResult)

            val (report, mapResult, coverageReport) = orchestrator.analyzeAndRepair(
                source = ProfileInput(json = sourceJson),
                target = ProfileInput(json = targetJson)
            )

            // Verify the drift report
            assertEquals(sampleDriftReport, report)
            assertEquals(1, report.totalDrifts)
            assertEquals("Patient.identifier", report.items[0].sourcePath)

            // Verify the map result
            assertTrue(mapResult.syntacticallyValid)
            assertEquals(validMapResult.structureMapFml, mapResult.structureMapFml)

            // Verify each pipeline stage was called in order
            verify(profileLoader).loadProfileFromJson(sourceJson)
            verify(profileLoader).loadProfileFromJson(targetJson)
            verify(driftAnalyzer).analyzeDrift(sourceSD, targetSD)
            verify(mapGenerator).generateMap(sourceSD, targetSD, sampleDriftReport)
            verify(mapValidator).validateAndRepair(
                fmlCode = validMapResult.structureMapFml,
                source = sourceSD,
                target = targetSD,
                driftReport = sampleDriftReport
            )
        }

        @Test
        @DisplayName("throws MapValidationException when validation loop exhausts all attempts")
        fun validationLoopExhausted() {
            whenever(profileLoader.loadProfileByCanonical(sourceSD.url)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileByCanonical(targetSD.url)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)
            whenever(mapGenerator.generateMap(sourceSD, targetSD, sampleDriftReport)).thenReturn(invalidMapResult)
            whenever(
                mapValidator.validateAndRepair(
                    fmlCode = invalidMapResult.structureMapFml,
                    source = sourceSD,
                    target = targetSD,
                    driftReport = sampleDriftReport
                )
            ).thenThrow(MapValidationException("Compilation failed after 3 reflexion attempts"))

            assertThrows<MapValidationException> {
                orchestrator.analyzeAndRepair(
                    source = ProfileInput(canonical = sourceSD.url),
                    target = ProfileInput(canonical = targetSD.url)
                )
            }
        }

        @Test
        @DisplayName("does not call map generator or validator for drift-only analysis")
        fun driftOnlyDoesNotCallDownstream() {
            whenever(profileLoader.loadProfileByCanonical(sourceSD.url)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileByCanonical(targetSD.url)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)

            orchestrator.analyzeDrift(
                source = ProfileInput(canonical = sourceSD.url),
                target = ProfileInput(canonical = targetSD.url)
            )

            verify(mapGenerator, never()).generateMap(any(), any(), any())
            verify(mapValidator, never()).validateAndRepair(any(), any(), any(), any())
        }

        @Test
        @DisplayName("propagates exception when map generator fails")
        fun propagatesGeneratorError() {
            val sourceJson = """{"resourceType":"StructureDefinition","id":"source"}"""
            val targetJson = """{"resourceType":"StructureDefinition","id":"target"}"""

            whenever(profileLoader.loadProfileFromJson(sourceJson)).thenReturn(sourceSD)
            whenever(profileLoader.loadProfileFromJson(targetJson)).thenReturn(targetSD)
            whenever(driftAnalyzer.analyzeDrift(sourceSD, targetSD)).thenReturn(sampleDriftReport)
            whenever(mapGenerator.generateMap(any(), any(), any()))
                .thenThrow(RuntimeException("LLM unavailable"))

            assertThrows<RuntimeException> {
                orchestrator.analyzeAndRepair(
                    source = ProfileInput(json = sourceJson),
                    target = ProfileInput(json = targetJson)
                )
            }

            verify(mapValidator, never()).validateAndRepair(any(), any(), any(), any())
        }
    }
}
