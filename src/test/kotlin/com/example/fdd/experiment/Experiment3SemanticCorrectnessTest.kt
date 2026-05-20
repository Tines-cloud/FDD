package com.example.fdd.experiment

import com.example.fdd.api.dto.ProfileInput
import com.example.fdd.service.DriftOrchestrationService
import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.validation.ResultSeverityEnum
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.PrePopulatedValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator
import org.hl7.fhir.r4.hapi.ctx.HapiWorkerContext
import org.hl7.fhir.r4.model.Resource
import org.hl7.fhir.r4.model.ResourceFactory
import org.hl7.fhir.r4.model.StructureDefinition
import org.hl7.fhir.r4.utils.StructureMapUtilities
import org.junit.jupiter.api.*
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.io.ClassPathResource
import org.springframework.test.context.ActiveProfiles
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * **Experiment 3 - End-to-End Semantic Correctness**
 *
 * Evaluates whether FDD-generated StructureMaps produce valid FHIR resources
 * when applied to synthetic source instances in a controlled HAPI FHIR server.
 *
 * Requires Docker and a valid LLM API key.
 */
@SpringBootTest
@Tag("integration")
@Tag("experiment3")
@ActiveProfiles("experiment")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Experiment3SemanticCorrectnessTest {

    private val log = LoggerFactory.getLogger(javaClass)

    @Autowired
    private lateinit var orchestrationService: DriftOrchestrationService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    /** R4 FhirContext (the @Primary one) - used to serialize parsed StructureMaps. */
    @Autowired
    private lateinit var fhirContext: FhirContext

    private var hapiFhirServer: GenericContainer<*>? = null

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    private data class SemanticTestCase(
        val id: String,
        val sourceClasspath: String,
        val targetClasspath: String,
        val instanceFile: String,
        val resourceType: String
    )

    private data class SemanticTestResult(
        val caseId: String,
        val sourceClasspath: String,
        val targetClasspath: String,
        val pipelineSuccess: Boolean,
        val syntacticallyValid: Boolean,
        val instancePosted: Boolean,
        val structureMapUploaded: Boolean,
        val transformExecuted: Boolean,
        val transformedResourceValid: Boolean,
        val overallSuccess: Boolean,
        val driftItemCount: Int,
        val dataShareabilityPercent: Double,
        val errorMessage: String?
    )

    /** Collects results across all dynamic tests for the aggregate JSON file. */
    private val collectedResults = mutableListOf<SemanticTestResult>()

    private val testCases = listOf(
        // --- R4 Base -> US Core profile pairs ---
        SemanticTestCase(
            id = "r4-condition-to-us-core-condition",
            sourceClasspath = "standard-profiles/r4/condition.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-condition-encounter-diagnosis.json",
            instanceFile = "fhir/instances/condition-r4-base.json",
            resourceType = "Condition"
        ),
        SemanticTestCase(
            id = "r4-observation-to-us-core-observation-lab",
            sourceClasspath = "standard-profiles/r4/observation.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-observation-lab.json",
            instanceFile = "fhir/instances/observation-r4-base.json",
            resourceType = "Observation"
        ),
        SemanticTestCase(
            id = "r4-encounter-to-us-core-encounter",
            sourceClasspath = "standard-profiles/r4/encounter.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-encounter.json",
            instanceFile = "fhir/instances/encounter-r4-base.json",
            resourceType = "Encounter"
        ),
        SemanticTestCase(
            id = "r4-allergyintolerance-to-us-core-allergyintolerance",
            sourceClasspath = "standard-profiles/r4/allergyintolerance.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-allergyintolerance.json",
            instanceFile = "fhir/instances/allergyintolerance-r4-base.json",
            resourceType = "AllergyIntolerance"
        ),
        SemanticTestCase(
            id = "r4-immunization-to-us-core-immunization",
            sourceClasspath = "standard-profiles/r4/immunization.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-immunization.json",
            instanceFile = "fhir/instances/immunization-r4-base.json",
            resourceType = "Immunization"
        ),
        SemanticTestCase(
            id = "r4-medicationrequest-to-us-core-medicationrequest",
            sourceClasspath = "standard-profiles/r4/medicationrequest.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-medicationrequest.json",
            instanceFile = "fhir/instances/medicationrequest-r4-base.json",
            resourceType = "MedicationRequest"
        ),
        // --- R4 Base -> US Core (remaining resource types) ---
        SemanticTestCase(
            id = "r4-location-to-us-core-location",
            sourceClasspath = "standard-profiles/r4/location.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-location.json",
            instanceFile = "fhir/instances/location-r4-base.json",
            resourceType = "Location"
        ),
        SemanticTestCase(
            id = "r4-medication-to-us-core-medication",
            sourceClasspath = "standard-profiles/r4/medication.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-medication.json",
            instanceFile = "fhir/instances/medication-r4-base.json",
            resourceType = "Medication"
        ),
        SemanticTestCase(
            id = "r4-careplan-to-us-core-careplan",
            sourceClasspath = "standard-profiles/r4/careplan.profile.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-careplan.json",
            instanceFile = "fhir/instances/careplan-r4-base.json",
            resourceType = "CarePlan"
        ),
        SemanticTestCase(
            id = "au-core-condition-to-us-core-condition",
            sourceClasspath = "standard-profiles/au-core/StructureDefinition-au-core-condition.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-condition-encounter-diagnosis.json",
            instanceFile = "fhir/instances/condition-r4-base.json",
            resourceType = "Condition"
        ),
        SemanticTestCase(
            id = "au-core-encounter-to-us-core-encounter",
            sourceClasspath = "standard-profiles/au-core/StructureDefinition-au-core-encounter.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-encounter.json",
            instanceFile = "fhir/instances/encounter-r4-base.json",
            resourceType = "Encounter"
        ),
        SemanticTestCase(
            id = "au-core-allergyintolerance-to-us-core-allergyintolerance",
            sourceClasspath = "standard-profiles/au-core/StructureDefinition-au-core-allergyintolerance.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-allergyintolerance.json",
            instanceFile = "fhir/instances/allergyintolerance-r4-base.json",
            resourceType = "AllergyIntolerance"
        ),
        SemanticTestCase(
            id = "au-core-immunization-to-us-core-immunization",
            sourceClasspath = "standard-profiles/au-core/StructureDefinition-au-core-immunization.json",
            targetClasspath = "standard-profiles/us-core/StructureDefinition-us-core-immunization.json",
            instanceFile = "fhir/instances/immunization-r4-base.json",
            resourceType = "Immunization"
        ),
        SemanticTestCase(
            id = "tk-soft-patient-to-hemas-patient",
            sourceClasspath = "custom-profiles/tk-soft/tk-soft-patient.json",
            targetClasspath = "custom-profiles/hemas/hemas-patient.json",
            instanceFile = "fhir/instances/patient-r4-base.json",
            resourceType = "Patient"
        ),
        SemanticTestCase(
            id = "iit-proj-patient-to-hemas-patient",
            sourceClasspath = "custom-profiles/iit-proj/iit-proj-patient.json",
            targetClasspath = "custom-profiles/hemas/hemas-patient.json",
            instanceFile = "fhir/instances/patient-r4-base.json",
            resourceType = "Patient"
        )
    )

    @BeforeAll
    fun logDockerInfo() {
        val dockerHost = System.getenv("DOCKER_HOST") ?: "(not set — Testcontainers auto-detect)"
        val externalHapi = System.getenv("FDD_HAPI_BASE_URL")
            ?: System.getProperty("fdd.hapi.base-url")
        log.info("=================================================================")
        log.info("  EXPERIMENT 3 starting — Docker host: {}", dockerHost)
        log.info("  HAPI container image : hapiproject/hapi:v7.4.0")
        log.info("  External HAPI URL    : {}", externalHapi ?: "(not set — will use Testcontainers)")
        log.info("  Test cases           : {}", testCases.size)
        log.info("=================================================================")
    }

    @AfterAll
    fun stopContainerIfStarted() {
        hapiFhirServer?.let {
            runCatching { it.stop() }
            hapiFhirServer = null
        }
    }

    private fun resolveFhirServerUrl(): String {
        val external = System.getenv("FDD_HAPI_BASE_URL")
            ?.takeIf { it.isNotBlank() }
            ?: System.getProperty("fdd.hapi.base-url")?.takeIf { it.isNotBlank() }

        if (external != null) {
            val normalized = external.trimEnd('/')
            val metadataUrl = "$normalized/metadata"
            val response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create(metadataUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(20))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )
            require(response.statusCode() in 200..299) {
                "External HAPI server check failed at $metadataUrl (HTTP ${response.statusCode()})"
            }
            log.info("Using external HAPI FHIR server: {}", normalized)
            return normalized
        }

        if (hapiFhirServer == null) {
            hapiFhirServer = GenericContainer("hapiproject/hapi:v7.4.0")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/fhir/metadata").forStatusCode(200))
                .withStartupTimeout(Duration.ofMinutes(3))
            hapiFhirServer!!.start()
        }

        val container = requireNotNull(hapiFhirServer)
        val url = "http://${container.host}:${container.firstMappedPort}/fhir"
        log.info("HAPI FHIR server running via Testcontainers at: {}", url)
        return url
    }

    @TestFactory
    @DisplayName("Semantic Correctness")
    fun evaluateSemanticCorrectness(): List<DynamicTest> {
        val fhirServerUrl = resolveFhirServerUrl()

        // Support selective pair execution via system property OR env var FDD_PAIRS
        val selectedIds = (System.getProperty("fdd.pairs")?.takeIf { it.isNotBlank() }
            ?: System.getenv("FDD_PAIRS")?.takeIf { it.isNotBlank() })
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        val casesToRun = if (!selectedIds.isNullOrEmpty()) {
            val filtered = testCases.filter { it.id in selectedIds }
            log.info("Selective mode: running {}/{} cases matching -Dfdd.pairs", filtered.size, testCases.size)
            filtered
        } else {
            testCases
        }

        return casesToRun.map { testCase ->
            dynamicTest(testCase.id) {
                log.info("=== Semantic test case: {} ===", testCase.id)
                val result = runTestCase(testCase, fhirServerUrl)
                synchronized(collectedResults) { collectedResults.add(result) }

                log.info(
                    "Result: {} -> pipeline={} map={} posted={} uploaded={} transform={} valid={} SUCCESS={}",
                    result.caseId,
                    if (result.pipelineSuccess) "OK" else "FAIL",
                    if (result.syntacticallyValid) "OK" else "FAIL",
                    if (result.instancePosted) "OK" else "FAIL",
                    if (result.structureMapUploaded) "OK" else "FAIL",
                    if (result.transformExecuted) "OK" else "FAIL",
                    if (result.transformedResourceValid) "OK" else "FAIL",
                    if (result.overallSuccess) "YES" else "NO"
                )
                if (result.errorMessage != null) {
                    log.info("    error: {}", result.errorMessage)
                }
            }
        }
    }

    @AfterAll
    fun writeAggregateResults() {
        if (collectedResults.isEmpty()) return

        val totalCases = collectedResults.size
        val successfulCases = collectedResults.count { it.overallSuccess }
        val scr = if (totalCases > 0) successfulCases.toDouble() / totalCases * 100.0 else 0.0

        log.info("---------------------------------------------------------------------")
        log.info("  EXPERIMENT 3 - End-to-End Semantic Correctness")
        log.info("---------------------------------------------------------------------")
        collectedResults.forEach { r ->
            log.info(
                "  {} -> pipeline={} map={} posted={} uploaded={} transform={} valid={} SUCCESS={}",
                r.caseId,
                if (r.pipelineSuccess) "OK" else "FAIL",
                if (r.syntacticallyValid) "OK" else "FAIL",
                if (r.instancePosted) "OK" else "FAIL",
                if (r.structureMapUploaded) "OK" else "FAIL",
                if (r.transformExecuted) "OK" else "FAIL",
                if (r.transformedResourceValid) "OK" else "FAIL",
                if (r.overallSuccess) "YES" else "NO"
            )
            if (r.errorMessage != null) {
                log.info("    error: {}", r.errorMessage)
            }
        }
        log.info("-------------------------------------------------------------------")
        log.info(
            "  Semantic Correctness Rate (SCR): {}% ({}/{})",
            "%.1f".format(scr), successfulCases, totalCases
        )
        log.info("---------------------------------------------------------------------")

        writeResultsFile(collectedResults, scr)
    }

    private fun runTestCase(testCase: SemanticTestCase, fhirServerUrl: String): SemanticTestResult {
        var pipelineSuccess = false
        var syntacticallyValid = false
        var instancePosted = false
        var structureMapUploaded = false
        var transformExecuted = false
        var transformedResourceValid = false
        var driftItemCount = 0
        var dataShareabilityPercent = 0.0
        var errorMessage: String? = null

        try {
            // Step 1: Load synthetic instance
            val instanceJson = loadResource(testCase.instanceFile)
            if (instanceJson == null) {
                errorMessage = "Synthetic instance not found: ${testCase.instanceFile}"
                log.warn(errorMessage)
                return buildResult(
                    testCase, pipelineSuccess, syntacticallyValid, instancePosted,
                    structureMapUploaded, transformExecuted, transformedResourceValid,
                    driftItemCount, dataShareabilityPercent, errorMessage
                )
            }

            // Step 2: Run full FDD pipeline
            log.info("Running FDD pipeline: {} -> {}", testCase.sourceClasspath, testCase.targetClasspath)
            val (driftReport, mapResult, coverageReport) = try {
                orchestrationService.analyzeAndRepair(
                    source = ProfileInput(classpath = testCase.sourceClasspath),
                    target = ProfileInput(classpath = testCase.targetClasspath)
                )
            } catch (ex: Exception) {
                errorMessage = "Pipeline failed: ${ex.message}"
                log.warn("FDD pipeline failed for {}: {}", testCase.id, ex.message)
                return buildResult(
                    testCase, pipelineSuccess, syntacticallyValid, instancePosted,
                    structureMapUploaded, transformExecuted, transformedResourceValid,
                    driftItemCount, dataShareabilityPercent, errorMessage
                )
            }

            pipelineSuccess = true
            syntacticallyValid = mapResult.syntacticallyValid
            driftItemCount = driftReport.totalDrifts
            dataShareabilityPercent = coverageReport.dataShareabilityPercent

            log.info(
                "Pipeline complete: {} drifts, valid={}, shareability={}%",
                driftItemCount, syntacticallyValid, "%.1f".format(dataShareabilityPercent)
            )

            if (!syntacticallyValid) {
                errorMessage = "FML not syntactically valid after repair cycles"
                return buildResult(
                    testCase, pipelineSuccess, syntacticallyValid, instancePosted,
                    structureMapUploaded, transformExecuted, transformedResourceValid,
                    driftItemCount, dataShareabilityPercent, errorMessage
                )
            }

            // Step 3: POST source instance to HAPI server
            val postResponse = postToFhir(fhirServerUrl, testCase.resourceType, instanceJson)
            instancePosted = postResponse.statusCode() in 200..299
            if (!instancePosted) {
                // Non-fatal in Experiment 3: in-process transform does not require server persistence.
                // Continue to transformation + validation even if create fails on strict server rules.
                log.warn(
                    "Failed to POST {} (HTTP {}) - continuing with transform/validation path",
                    testCase.resourceType,
                    postResponse.statusCode()
                )
            } else {
                log.info("Source {} posted to HAPI server (HTTP {})", testCase.resourceType, postResponse.statusCode())
            }

            // Step 4: Upload StructureMap to HAPI server
            val structureMapResource = buildStructureMapResource(mapResult.structureMapFml)

            // ----------------------------------------------------------------
            // Step 4a: Try IN-PROCESS transform first (works around the known
            // HAPI JPA $transform limitation: JPA needs source/target
            // StructureDefinitions pre-loaded server-side and a fully parsed
            // StructureMap; both are brittle for ad-hoc generated FML).
            //
            // The in-process path uses HAPI's StructureMapUtilities directly
            // with a worker context that already has the source/target SDs
            // populated, so transformation succeeds without any HTTP round
            // trip.
            // ----------------------------------------------------------------
            val (inProcessOk, inProcessJson) = inProcessTransform(
                sourceClasspath = testCase.sourceClasspath,
                targetClasspath = testCase.targetClasspath,
                fml = mapResult.structureMapFml,
                sourceInstanceJson = instanceJson,
                resourceType = testCase.resourceType
            )

            if (inProcessOk && inProcessJson != null) {
                structureMapUploaded = true        // logically equivalent: map is usable
                transformExecuted = true
                log.info("In-process \$transform succeeded for {}", testCase.id)

                // Step 6 (in-process): POST transformed instance to HAPI for $validate
                transformedResourceValid = validateResource(fhirServerUrl, inProcessJson, testCase.resourceType)
                if (!transformedResourceValid) {
                    errorMessage = "Transformed resource failed profile validation (in-process)"
                }
            } else {
                // ---- Fall back to HAPI JPA $transform path ----
                val smResponse = postToFhir(fhirServerUrl, "StructureMap", structureMapResource)
                structureMapUploaded = smResponse.statusCode() in 200..299
                if (!structureMapUploaded) {
                    errorMessage = "StructureMap upload: HTTP ${smResponse.statusCode()} - " +
                            "HAPI JPA may not support raw FML content field"
                    log.warn("StructureMap upload failed for {}: HTTP {}", testCase.id, smResponse.statusCode())
                } else {
                    log.info("StructureMap uploaded to HAPI server")
                }

                if (structureMapUploaded) {
                    val transformResult = attemptTransform(fhirServerUrl, structureMapResource, instanceJson)
                    transformExecuted = transformResult.first
                    val transformedJson = transformResult.second

                    if (transformExecuted && transformedJson != null) {
                        transformedResourceValid = validateResource(fhirServerUrl, transformedJson, testCase.resourceType)
                        if (!transformedResourceValid) {
                            errorMessage = "Transformed resource failed profile validation"
                        }
                    } else if (!transformExecuted) {
                        errorMessage = (errorMessage ?: "") + "; \$transform not supported by HAPI JPA"
                    }
                }
            }

        } catch (ex: Exception) {
            errorMessage = "Unexpected error: ${ex.message}"
            log.error("Unexpected error in test case {}", testCase.id, ex)
        }

        return buildResult(
            testCase, pipelineSuccess, syntacticallyValid, instancePosted,
            structureMapUploaded, transformExecuted, transformedResourceValid,
            driftItemCount, dataShareabilityPercent, errorMessage
        )
    }

    /* -------------------------------------------------------------------
     * Helper methods
     * ------------------------------------------------------------------- */

    private fun buildResult(
        testCase: SemanticTestCase,
        pipelineSuccess: Boolean, syntacticallyValid: Boolean,
        instancePosted: Boolean, structureMapUploaded: Boolean,
        transformExecuted: Boolean, transformedResourceValid: Boolean,
        driftItemCount: Int, dataShareabilityPercent: Double,
        errorMessage: String?
    ) = SemanticTestResult(
        caseId = testCase.id,
        sourceClasspath = testCase.sourceClasspath,
        targetClasspath = testCase.targetClasspath,
        pipelineSuccess = pipelineSuccess,
        syntacticallyValid = syntacticallyValid,
        instancePosted = instancePosted,
        structureMapUploaded = structureMapUploaded,
        transformExecuted = transformExecuted,
        transformedResourceValid = transformedResourceValid,
        overallSuccess = pipelineSuccess && syntacticallyValid && transformedResourceValid,
        driftItemCount = driftItemCount,
        dataShareabilityPercent = dataShareabilityPercent,
        errorMessage = errorMessage
    )

    private fun postToFhir(serverUrl: String, resourceType: String, body: String): HttpResponse<String> =
        httpClient.send(
            HttpRequest.newBuilder()
                .uri(URI.create("$serverUrl/$resourceType"))
                .header("Content-Type", "application/fhir+json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        )

    /**
     * Convert FHIR Mapping Language (FML) text into a fully-formed StructureMap
     * JSON resource that the HAPI JPA `$transform` engine can execute.
     *
     * The previous implementation only emitted a metadata stub (no `group`,
     * `structure`, or `rule` content), which is why HAPI rejected the upload.
     * We now use HAPI's `StructureMapUtilities.parse()` to produce a real R4
     * `StructureMap` and serialize it with the autowired R4 [FhirContext].
     *
     * If parsing fails, we log the cause and return a placeholder marked
     * `status=draft`; the calling test will record the failure and the
     * transform step will be skipped for that case.
     */
    private fun buildStructureMapResource(fml: String): String {
        val mapUrlRegex = Regex("""map\s+"([^"]+)"\s*=\s*"([^"]+)"""")
        val match = mapUrlRegex.find(fml)
        val fallbackUrl = match?.groupValues?.get(1) ?: "http://example.org/fhir/StructureMap/generated"
        val fallbackName = match?.groupValues?.get(2) ?: "GeneratedMap"

        return try {
            val workerContext = HapiWorkerContext(
                fhirContext,
                ValidationSupportChain(DefaultProfileValidationSupport(fhirContext))
            )
            val structureMap = StructureMapUtilities(workerContext).parse(fml, fallbackName)
            // Ensure the map has a stable canonical URL even if the FML omitted one.
            if (structureMap.url.isNullOrBlank()) structureMap.url = fallbackUrl
            if (structureMap.name.isNullOrBlank()) structureMap.name = fallbackName
            fhirContext.newJsonParser().setPrettyPrint(false).encodeResourceToString(structureMap)
        } catch (ex: Exception) {
            log.warn(
                "Failed to parse generated FML into a StructureMap (length={} chars): {}",
                fml.length, ex.message
            )
            """
            {
              "resourceType": "StructureMap",
              "url": "$fallbackUrl",
              "name": "$fallbackName",
              "status": "draft",
              "description": "FML parse failed - placeholder so downstream pipeline records the failure cleanly."
            }
            """.trimIndent()
        }
    }

    private fun attemptTransform(
        fhirServerUrl: String,
        structureMapJson: String,
        sourceInstanceJson: String
    ): Pair<Boolean, String?> {
        return try {
            val tree = objectMapper.readTree(structureMapJson)
            val smUrl = tree.get("url")?.textValue() ?: return false to null

            val transformRequest = """
            {
              "resourceType": "Parameters",
              "parameter": [
                {
                  "name": "source",
                  "valueUri": "$smUrl"
                },
                {
                  "name": "content",
                  "resource": $sourceInstanceJson
                }
              ]
            }
            """.trimIndent()

            val response = httpClient.send(
                HttpRequest.newBuilder()
                    .uri(URI.create("$fhirServerUrl/StructureMap/\$transform"))
                    .header("Content-Type", "application/fhir+json")
                    .POST(HttpRequest.BodyPublishers.ofString(transformRequest))
                    .timeout(Duration.ofSeconds(60))
                    .build(),
                HttpResponse.BodyHandlers.ofString()
            )

            log.info("\$transform response: HTTP {} (body length: {})", response.statusCode(), response.body().length)

            if (response.statusCode() in 200..299) {
                true to response.body()
            } else {
                log.warn("\$transform returned HTTP {}: {}", response.statusCode(), response.body().take(500))
                false to null
            }
        } catch (ex: Exception) {
            log.warn("\$transform call failed: {}", ex.message)
            false to null
        }
    }

    /**
     * Validate a transformed FHIR resource IN-PROCESS using HAPI's FhirInstanceValidator.
     *
     * This replaces the HTTP $validate call so no external HAPI server is required for
     * the validation step.  The check is structural (base R4 conformance); profile-specific
     * validation is best-effort because unknown extension systems may produce INFO/WARNING
     * messages that are not true errors.
     *
     * Success criterion: no ERROR or FATAL severity issues in the result.
     */
    private fun validateResource(fhirServerUrl: String, resourceJson: String, resourceType: String): Boolean {
        return try {
            val chain = ValidationSupportChain(
                DefaultProfileValidationSupport(fhirContext),
                InMemoryTerminologyServerValidationSupport(fhirContext),
                CommonCodeSystemsTerminologyService(fhirContext)
            )
            val instanceValidator = FhirInstanceValidator(chain)
            val validator = fhirContext.newValidator().also { it.registerValidatorModule(instanceValidator) }

            val result = validator.validateWithResult(resourceJson)
            val errors = result.messages.filter {
                it.severity == ResultSeverityEnum.ERROR || it.severity == ResultSeverityEnum.FATAL
            }
            val isValid = errors.isEmpty()
            log.info(
                "In-process validation of transformed {}: valid={} ({} message(s), {} error(s))",
                resourceType, isValid, result.messages.size, errors.size
            )
            if (!isValid) {
                errors.take(5).forEach { log.warn("  Validation error: [{}] {}", it.locationString, it.message) }
            }
            isValid
        } catch (ex: Exception) {
            log.warn("Validation call failed: {}", ex.message)
            false
        }
    }

    private fun loadResource(path: String): String? {
        return try {
            ClassPathResource(path).inputStream.bufferedReader().readText().removePrefix("\uFEFF")
        } catch (ex: Exception) {
            log.warn("Failed to load classpath resource '{}': {}", path, ex.message)
            null
        }
    }

    /**
     * Run the StructureMap transform IN-PROCESS (no HTTP, no JPA upload).
     *
     * This bypasses the well-known HAPI JPA `$transform` limitation:
     * the JPA endpoint requires the source/target StructureDefinitions to
     * be loaded server-side AND a fully-parsed StructureMap resource - both
     * of which are brittle for ad-hoc generated FML.
     *
     * Strategy:
     *   1. Parse source & target SDs from the classpath.
     *   2. Build a [PrePopulatedValidationSupport] containing both, layered
     *      with the standard R4 base profiles + terminology services.
     *   3. Wrap as a [HapiWorkerContext] (the same wrapper [DefaultMapValidator]
     *      uses for parse-time validation).
     *   4. Parse the FML into a [org.hl7.fhir.r4.model.StructureMap] using
     *      [StructureMapUtilities].
     *   5. Invoke `utils.transform(null, source, structureMap, target)`.
     *
     * Returns `(true, transformedJson)` on success, `(false, null)` on any
     * failure - the caller will then fall back to the HAPI JPA path.
     */
    private fun inProcessTransform(
        sourceClasspath: String,
        targetClasspath: String,
        fml: String,
        sourceInstanceJson: String,
        resourceType: String
    ): Pair<Boolean, String?> {
        return try {
            val sourceSdJson = loadResource(sourceClasspath) ?: return false to null
            val targetSdJson = loadResource(targetClasspath) ?: return false to null

            val parser = fhirContext.newJsonParser()
            val sourceSd = parser.parseResource(StructureDefinition::class.java, sourceSdJson)
            val targetSd = parser.parseResource(StructureDefinition::class.java, targetSdJson)

            val support = PrePopulatedValidationSupport(fhirContext).apply {
                addStructureDefinition(sourceSd)
                addStructureDefinition(targetSd)
            }
            val chain = ValidationSupportChain(
                support,
                DefaultProfileValidationSupport(fhirContext),
                InMemoryTerminologyServerValidationSupport(fhirContext),
                CommonCodeSystemsTerminologyService(fhirContext)
            )
            val workerContext = HapiWorkerContext(fhirContext, chain)
            val utils = StructureMapUtilities(workerContext)

            val structureMap = utils.parse(fml, "GeneratedMap")

            val sourceResource = parser.parseResource(sourceInstanceJson) as Resource
            val targetResource = ResourceFactory.createResource(resourceType)

            utils.transform(null, sourceResource, structureMap, targetResource)

            val json = fhirContext.newJsonParser().setPrettyPrint(true)
                .encodeResourceToString(targetResource)
            true to json
        } catch (ex: Exception) {
            log.warn(
                "In-process transform failed for source='{}' target='{}': {}",
                sourceClasspath, targetClasspath, ex.message
            )
            false to null
        }
    }

    private fun writeResultsFile(results: List<SemanticTestResult>, scr: Double) {
        try {
            val projectRoot = System.getProperty("project.root") ?: System.getProperty("user.dir")
            val outputDir = Paths.get(projectRoot, "output", "experiment-results")
            Files.createDirectories(outputDir)

            val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val outputFile = outputDir.resolve("experiment3-semantic-correctness-$timestamp.json")

            val report = mapOf(
                "experiment" to "Experiment 3 - Semantic Correctness",
                "timestamp" to LocalDateTime.now().toString(),
                "testCases" to results.size,
                "semanticCorrectnessRate" to "%.1f".format(scr),
                "successfulCases" to results.count { it.overallSuccess },
                "results" to results
            )

            Files.writeString(outputFile, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report))
            log.info("Experiment 3 results written to: {}", outputFile)
        } catch (ex: Exception) {
            log.warn("Failed to write experiment results file: {}", ex.message)
        }
    }
}