package com.example.fdd.cli

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.model.DriftType
import com.example.fdd.model.MapGenerationResult
import com.example.fdd.output.OutputStore
import com.example.fdd.service.DriftOrchestrationService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.boot.DefaultApplicationArguments
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

/**
 * Unit tests for [CliRunner].
 *
 * Verifies CLI argument parsing, mode selection (analyze/repair),
 * error handling, output file writing, and usage printing.
 */
class CliRunnerTest {

    private lateinit var orchestrationService: DriftOrchestrationService
    private lateinit var objectMapper: ObjectMapper
    private lateinit var outputStore: OutputStore
    private lateinit var runner: CliRunner

    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var capturedOut: ByteArrayOutputStream
    private lateinit var capturedErr: ByteArrayOutputStream

    @TempDir
    lateinit var tempDir: Path

    private val sampleDriftReport = DriftReport(
        sourceProfileCanonical = "http://source",
        targetProfileCanonical = "http://target",
        items = listOf(
            DriftItem(
                id = "drift-1",
                type = DriftType.CARDINALITY,
                sourcePath = "Patient.identifier",
                targetPath = "Patient.identifier",
                description = "Cardinality changed",
                severity = "ERROR"
            )
        )
    )

    private val sampleMapResult = MapGenerationResult(
        structureMapFml = "map \"test\" = \"test\" { }",
        syntacticallyValid = true,
        validationMessages = listOf("Attempt 1: Compilation successful")
    )

    @BeforeEach
    fun setUp() {
        orchestrationService = mock()
        objectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        outputStore = mock()
        runner = CliRunner(orchestrationService, objectMapper, outputStore)

        capturedOut = ByteArrayOutputStream()
        capturedErr = ByteArrayOutputStream()
        System.setOut(PrintStream(capturedOut))
        System.setErr(PrintStream(capturedErr))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    /* ---- Analyze mode ---- */

    @Test
    @DisplayName("Analyze mode with canonical URLs calls analyzeDrift and outputs JSON")
    fun run_analyzeMode_outputsJson() {
        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenReturn(sampleDriftReport)

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-canonical=http://source",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val output = capturedOut.toString()
        assertTrue(output.contains("driftReport"), "Output should contain driftReport JSON")
        assertTrue(output.contains("CARDINALITY"), "Output should contain drift type")
        verify(orchestrationService).analyzeDrift(
            source = eq(ProfileInput(canonical = "http://source")),
            target = eq(ProfileInput(canonical = "http://target"))
        )
    }

    /* ---- Repair mode ---- */

    @Test
    @DisplayName("Repair mode calls analyzeAndRepair and outputs full response JSON")
    fun run_repairMode_outputsRepairResponse() {
        whenever(orchestrationService.analyzeAndRepair(source = any(), target = any()))
            .thenReturn(sampleDriftReport to sampleMapResult)

        val args = DefaultApplicationArguments(
            "--mode=repair",
            "--source-canonical=http://source",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val output = capturedOut.toString()
        assertTrue(output.contains("driftReport"), "Output should contain driftReport")
        assertTrue(output.contains("structureMap"), "Output should contain structureMap")
        assertTrue(output.contains("syntacticallyValid"), "Output should contain validation summary")
    }

    /* ---- Output file ---- */

    @Test
    @DisplayName("--output flag writes JSON to file instead of stdout")
    fun run_withOutputFile_writesToFile() {
        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenReturn(sampleDriftReport)

        val outputFile = tempDir.resolve("result.json").toFile()

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-canonical=http://source",
            "--target-canonical=http://target",
            "--output=${outputFile.absolutePath}"
        )

        runner.run(args)

        assertTrue(outputFile.exists(), "Output file should exist")
        val fileContent = outputFile.readText()
        assertTrue(fileContent.contains("driftReport"), "File should contain driftReport JSON")
        assertTrue(capturedOut.toString().contains("Output written to"), "Stdout should confirm file write")
    }

    /* ---- Input methods ---- */

    @Test
    @DisplayName("--source-url and --target-url resolve via URL input")
    fun run_withUrls_passesUrlInput() {
        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenReturn(sampleDriftReport)

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-url=https://example.org/source.json",
            "--target-url=https://example.org/target.json"
        )

        runner.run(args)

        verify(orchestrationService).analyzeDrift(
            source = eq(ProfileInput(url = "https://example.org/source.json")),
            target = eq(ProfileInput(url = "https://example.org/target.json"))
        )
    }

    @Test
    @DisplayName("--source-classpath and --target-classpath resolve via classpath input")
    fun run_withClasspath_passesClasspathInput() {
        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenReturn(sampleDriftReport)

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-classpath=fhir/profiles/source.json",
            "--target-classpath=fhir/profiles/target.json"
        )

        runner.run(args)

        verify(orchestrationService).analyzeDrift(
            source = eq(ProfileInput(classpath = "fhir/profiles/source.json")),
            target = eq(ProfileInput(classpath = "fhir/profiles/target.json"))
        )
    }

    @Test
    @DisplayName("--source-file reads file content and passes as JSON input")
    fun run_withFile_readsFileAndPassesAsJson() {
        val sourceFile = tempDir.resolve("source.json").toFile()
        sourceFile.writeText("""{"resourceType":"StructureDefinition","url":"http://test"}""")

        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenReturn(sampleDriftReport)

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-file=${sourceFile.absolutePath}",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val captor = argumentCaptor<ProfileInput>()
        verify(orchestrationService).analyzeDrift(source = captor.capture(), target = any())
        assertNotNull(captor.firstValue.json, "File content should be passed as JSON field")
        assertTrue(captor.firstValue.json!!.contains("StructureDefinition"))
    }

    /* ---- Error handling ---- */

    @Test
    @DisplayName("Missing --mode prints error and usage")
    fun run_missingMode_printsError() {
        val args = DefaultApplicationArguments(
            "--source-canonical=http://source",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val errOutput = capturedErr.toString()
        assertTrue(errOutput.contains("--mode"), "Error should mention missing --mode")
        assertTrue(errOutput.contains("Usage"), "Should print usage")
    }

    @Test
    @DisplayName("Invalid --mode value prints error and usage")
    fun run_invalidMode_printsError() {
        val args = DefaultApplicationArguments(
            "--mode=invalid",
            "--source-canonical=http://source",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val errOutput = capturedErr.toString()
        assertTrue(
            errOutput.contains("analyze") || errOutput.contains("repair"),
            "Error should mention valid modes"
        )
    }

    @Test
    @DisplayName("Missing source profile input prints error")
    fun run_missingSource_printsError() {
        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val errOutput = capturedErr.toString()
        assertTrue(errOutput.contains("source"), "Error should mention missing source input")
    }

    @Test
    @DisplayName("Non-existent file path prints error")
    fun run_nonExistentFile_printsError() {
        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-file=/nonexistent/path/profile.json",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val errOutput = capturedErr.toString()
        assertTrue(errOutput.contains("ERROR"), "Should print error for missing file")
    }

    @Test
    @DisplayName("Non-JSON file extension prints error")
    fun run_nonJsonFile_printsError() {
        val xmlFile = tempDir.resolve("profile.xml").toFile()
        xmlFile.writeText("<xml/>")

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-file=${xmlFile.absolutePath}",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val errOutput = capturedErr.toString()
        assertTrue(
            errOutput.contains("json") || errOutput.contains("JSON"),
            "Error should mention JSON file requirement"
        )
    }

    @Test
    @DisplayName("Service exception is caught and printed to stderr")
    fun run_serviceException_printsError() {
        whenever(orchestrationService.analyzeDrift(source = any(), target = any()))
            .thenThrow(RuntimeException("LLM unavailable"))

        val args = DefaultApplicationArguments(
            "--mode=analyze",
            "--source-canonical=http://source",
            "--target-canonical=http://target"
        )

        runner.run(args)

        val errOutput = capturedErr.toString()
        assertTrue(errOutput.contains("LLM unavailable"), "Should print exception message")
    }
}
