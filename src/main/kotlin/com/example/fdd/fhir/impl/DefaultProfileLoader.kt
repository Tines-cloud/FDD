package com.example.fdd.fhir.impl

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.validation.FhirValidator
import ca.uhn.fhir.validation.ResultSeverityEnum
import com.example.fdd.exception.ProfileNotFoundException
import com.example.fdd.exception.ProfileValidationException
import com.example.fdd.fhir.ProfileLoader
import com.example.fdd.util.FhirValidationUtils
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Default [com.example.fdd.fhir.ProfileLoader] implementation.
 *
 * Resolves FHIR [org.hl7.fhir.r4.model.StructureDefinition] profiles from multiple sources:
 * 1. **Inline JSON** - raw StructureDefinition JSON string.
 * 2. **HTTP(S) URL** - fetches JSON from any public URL (FHIR registries,
 *    GitHub raw links, simplifier.net, build.fhir.org, etc.).
 * 3. **Canonical URL** - resolved from HAPI-FHIR's built-in base R4/R5 definitions.
 * 4. **Classpath** - loaded from bundled classpath resources.
 * 5. **Local file** - read from the server's file system.
 *
 * All profiles are validated using HAPI-FHIR's [ca.uhn.fhir.validation.FhirValidator] after loading.
 * Profiles with ERROR-level issues are rejected WARNING-level issues are logged.
 */
@Component
class DefaultProfileLoader(
    private val fhirContext: FhirContext,
    private val fhirJsonParser: IParser,
    private val fhirValidator: FhirValidator
) : ProfileLoader {

    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /* ---------------- Public API ---------------- */

    override fun loadProfileByCanonical(canonical: String): StructureDefinition {
        // Try HAPI built-in definitions (base R4 resources ship inside the JAR)
        val builtIn = fhirContext.validationSupport
            .fetchStructureDefinition(canonical) as? StructureDefinition
        if (builtIn != null) {
            log.debug("Resolved {} from HAPI built-in definitions", canonical)
            return builtIn
        }

        throw ProfileNotFoundException(
            "StructureDefinition not found for canonical URL: $canonical"
        )
    }

    override fun loadProfileFromJson(json: String): StructureDefinition {
        val sd = fhirJsonParser.parseResource(StructureDefinition::class.java, json)
        validateProfile(sd, sd.url ?: "inline-json")
        return sd
    }

    override fun loadProfileFromUrl(url: String): StructureDefinition {
        log.info("Fetching profile from URL: {}", url)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/fhir+json, application/json")
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()

        val response: HttpResponse<String> = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (ex: Exception) {
            throw ProfileNotFoundException(
                "Failed to fetch profile from URL '$url': ${ex.message}"
            )
        }

        if (response.statusCode() !in 200..299) {
            throw ProfileNotFoundException(
                "URL '$url' returned HTTP ${response.statusCode()}"
            )
        }

        val json = response.body()
        if (json.isNullOrBlank()) {
            throw ProfileNotFoundException("URL '$url' returned an empty response")
        }

        return try {
            loadProfileFromJson(json)
        } catch (ex: ProfileValidationException) {
            throw ex
        } catch (ex: Exception) {
            throw ProfileNotFoundException(
                "URL '$url' did not return a valid FHIR StructureDefinition: ${ex.message}"
            )
        }
    }

    override fun loadProfileFromClasspath(path: String): StructureDefinition {
        val resource = ClassPathResource(path)
        if (!resource.exists()) {
            throw ProfileNotFoundException("Classpath resource not found: $path")
        }
        val sd = resource.inputStream.use { stream ->
            fhirJsonParser.parseResource(StructureDefinition::class.java, stream)
        }
        validateProfile(sd, sd.url ?: path)
        return sd
    }

    override fun loadProfileFromFile(path: String): StructureDefinition {
        val file = File(path)
        if (!file.exists()) {
            throw ProfileNotFoundException("File not found: $path")
        }
        if (!file.extension.equals("json", ignoreCase = true)) {
            throw ProfileNotFoundException("Only .json files are supported (got: ${file.name})")
        }
        log.info("Loading profile from local file: {}", file.absolutePath)
        val json = file.readText()
        return loadProfileFromJson(json)
    }

    /* ---------------- FHIR Profile Validation ---------------- */

    companion object {
        /**
         * Patterns for HAPI-FHIR validation errors that are known false-positives
         * and should be downgraded to warnings rather than blocking the pipeline.
         *
         * - **HL7 owning committee**: US-Core / AU-Core profiles trigger this because
         *   they don't bundle the `http://hl7.org/fhir/StructureDefinition/structuredefinition-wg`
         *   extension in every profile. The profiles are structurally valid.
         * - **Profile not found**: Parent profiles (e.g., AU Base) may not be on the
         *   classpath. The profile itself is still parseable and usable for drift analysis.
         * - **Type not legal (R5)**: HAPI R5 validator sometimes flags valid R5 types
         *   as not being in the specification.
         */
        private fun isDowngradableError(msg: String?): Boolean = FhirValidationUtils.isDowngradableError(msg)
    }

    /**
     * Validate a loaded [StructureDefinition] using HAPI-FHIR's instance validator.
     *
     * Profiles that fail validation with ERROR-level issues are rejected outright
     * WARNING-level issues are logged but do not block the pipeline.
     * Known false-positive errors (e.g., HL7 wg extension, missing parent profiles)
     * are downgraded to warnings.
     *
     * @throws ProfileValidationException if any genuine ERROR-level validation issue is found.
     */
    private fun validateProfile(sd: StructureDefinition, identifier: String) {
        log.debug("Validating profile: {}", identifier)
        val result = fhirValidator.validateWithResult(sd)

        // Separate genuine errors from downgradable false-positives
        val allErrors = result.messages.filter {
            it.severity == ResultSeverityEnum.ERROR || it.severity == ResultSeverityEnum.FATAL
        }
        val genuineErrors = allErrors.filter { !isDowngradableError(it.message) }
        val downgradedErrors = allErrors.filter { isDowngradableError(it.message) }
        val warnings = result.messages.filter { it.severity == ResultSeverityEnum.WARNING }

        // Log downgraded errors as warnings
        if (downgradedErrors.isNotEmpty()) {
            log.warn("Profile {} has {} downgraded error(s) (treated as warnings)", identifier, downgradedErrors.size)
            downgradedErrors.forEach { log.warn("  [DOWNGRADED] {} - {}", it.locationString, it.message) }
        }

        if (warnings.isNotEmpty()) {
            log.warn("Profile {} has {} validation warning(s)", identifier, warnings.size)
            warnings.forEach { log.warn("  [{}] {} - {}", it.severity, it.locationString, it.message) }
        }

        if (genuineErrors.isNotEmpty()) {
            val messages = genuineErrors.map { "[${it.severity}] ${it.locationString}: ${it.message}" }
            log.error("Profile {} failed validation with {} genuine error(s)", identifier, genuineErrors.size)
            messages.forEach { log.error("  {}", it) }
            throw ProfileValidationException(
                "FHIR profile validation failed for '$identifier': ${genuineErrors.size} error(s)",
                validationMessages = messages
            )
        }

        log.info("Profile {} passed HAPI-FHIR validation", identifier)
    }
}