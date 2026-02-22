package com.example.fdd.service

import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.validation.FhirValidator
import ca.uhn.fhir.validation.ResultSeverityEnum
import com.example.fdd.api.dto.ProfileValidationIssue
import com.example.fdd.api.dto.ProfileValidationReport
import com.example.fdd.api.dto.ProfileValidationResult
import com.example.fdd.api.dto.ProfileValidationSummary
import com.example.fdd.util.FhirValidationUtils
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.io.Resource
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.stereotype.Service

// Service that validates FHIR resources on the classpath:
//   - Custom StructureDefinition profiles under custom-profiles/
//   - Standard FHIR StructureDefinition profiles under standard-profiles/
//   R5 profiles are validated using an R5 FhirContext + R5 validator;
//   all other profiles use the default R4 validator.
@Service
class ProfileValidationService(
    private val fhirJsonParser: IParser,
    private val fhirValidator: FhirValidator,
    @Qualifier("r5") private val fhirJsonParserR5: IParser,
    @Qualifier("r5") private val fhirValidatorR5: FhirValidator
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val resolver = PathMatchingResourcePatternResolver()

    // Validate every *.json file under classpath:custom-profiles/ (recursive)
    fun validateAllCustomProfiles(): ProfileValidationReport {
        log.info("Starting validation of all custom FHIR profiles")
        val pattern = "classpath*:custom-profiles/**/*.json"
        val profileResources = resolver.getResources(pattern)
        log.info("Found {} custom profile file(s) to validate", profileResources.size)

        val results = profileResources.map { res ->
            val source = deriveSource(res, "custom-profiles")
            val fileName = res.filename ?: "unknown"
            log.debug("Validating custom profile: {} (source: {})", fileName, source)
            try {
                val json = res.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val sd = fhirJsonParser.parseResource(StructureDefinition::class.java, json)
                validateStructureDefinition(sd, fileName, source)
            } catch (ex: Throwable) {
                log.error("Failed to parse or validate {}: {}", fileName, ex.message, ex)
                errorResult(fileName, source, ex)
            }
        }
        return buildReport(results, "custom profiles")
    }

    // Validate every *.json file under classpath:standard-profiles/ (recursive)
    // R5 profiles (source=r5) are validated with the R5 FhirContext + R5 validator
    fun validateAllStandardProfiles(): ProfileValidationReport {
        log.info("Starting validation of all standard FHIR profiles")
        val pattern = "classpath*:standard-profiles/**/*.json"
        val profileResources = resolver.getResources(pattern)
        log.info("Found {} standard profile file(s) to validate", profileResources.size)

        val results = profileResources.map { res ->
            val source = deriveSource(res, "standard-profiles")
            val fileName = res.filename ?: "unknown"
            val isR5 = source.equals("r5", ignoreCase = true)
            log.debug("Validating standard profile: {} (source: {}, r5={})", fileName, source, isR5)
            try {
                val json = res.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (isR5) {
                    // Parse and validate using R5 context
                    val sd = fhirJsonParserR5.parseResource(
                        org.hl7.fhir.r5.model.StructureDefinition::class.java, json
                    )
                    validateR5StructureDefinition(sd, fileName, source)
                } else {
                    val sd = fhirJsonParser.parseResource(StructureDefinition::class.java, json)
                    validateStructureDefinition(sd, fileName, source)
                }
            } catch (ex: Throwable) {
                log.error("Failed to parse or validate {}: {}", fileName, ex.message, ex)
                errorResult(fileName, source, ex)
            }
        }
        return buildReport(results, "standard profiles")
    }

    // Validate everything: custom profiles + standard profiles
    fun validateAll(): ProfileValidationReport {
        log.info("Starting validation of ALL FHIR profiles")
        val customReport = validateAllCustomProfiles()
        val standardReport = validateAllStandardProfiles()
        val allResults = (customReport.profiles + standardReport.profiles)
            .sortedWith(compareBy({ it.source }, { !it.valid }, { it.profileName }))

        val summary = ProfileValidationSummary(
            totalProfiles = allResults.size,
            validProfiles = allResults.count { it.valid },
            invalidProfiles = allResults.count { !it.valid },
            profilesWithWarnings = allResults.count { it.warningCount > 0 },
            totalErrors = allResults.sumOf { it.errorCount },
            totalWarnings = allResults.sumOf { it.warningCount }
        )

        log.info(
            "Full validation complete - {}/{} valid, {} errors, {} warnings",
            summary.validProfiles, summary.totalProfiles,
            summary.totalErrors, summary.totalWarnings
        )
        return ProfileValidationReport(profiles = allResults, summary = summary)
    }

    // -- helpers --

    private fun validateStructureDefinition(
        sd: StructureDefinition, identifier: String, source: String
    ): ProfileValidationResult {
        val result = fhirValidator.validateWithResult(sd)
        return toValidationResult(
            name = sd.name ?: identifier,
            url = sd.url ?: "unknown",
            type = sd.type ?: "unknown",
            pub = sd.publisher ?: "unknown",
            source = source,
            result = result,
            identifier = identifier
        )
    }

    private fun validateR5StructureDefinition(
        sd: org.hl7.fhir.r5.model.StructureDefinition, identifier: String, source: String
    ): ProfileValidationResult {
        val result = fhirValidatorR5.validateWithResult(sd)
        return toValidationResult(
            name = sd.name ?: identifier,
            url = sd.url ?: "unknown",
            type = sd.type ?: "unknown",
            pub = sd.publisher ?: "unknown",
            source = source,
            result = result,
            identifier = identifier
        )
    }

    // Patterns for HAPI-FHIR false-positive errors that are downgraded to warnings.
    // Single source of truth lives in FhirValidationUtils.
    private fun isDowngradableError(msg: String?) = FhirValidationUtils.isDowngradableError(msg)

    private fun toValidationResult(
        name: String, url: String, type: String, pub: String,
        source: String,
        result: ca.uhn.fhir.validation.ValidationResult,
        identifier: String
    ): ProfileValidationResult {
        // Reclassify known false-positive errors as warnings
        val errors = result.messages.filter {
            (it.severity == ResultSeverityEnum.ERROR || it.severity == ResultSeverityEnum.FATAL) &&
                    !isDowngradableError(it.message)
        }
        val warnings = result.messages.filter {
            it.severity == ResultSeverityEnum.WARNING || isDowngradableError(it.message)
        }

        val issues = result.messages
            .filter {
                it.severity == ResultSeverityEnum.ERROR ||
                        it.severity == ResultSeverityEnum.FATAL ||
                        it.severity == ResultSeverityEnum.WARNING
            }
            .map { msg ->
                val effectiveSeverity = if (isDowngradableError(msg.message)) "WARNING"
                else msg.severity.name
                ProfileValidationIssue(
                    severity = effectiveSeverity,
                    location = msg.locationString ?: identifier,
                    message = msg.message ?: "(no message)"
                )
            }

        val isValid = errors.isEmpty()

        if (!isValid) {
            log.warn("{} has {} error(s) - marked INVALID", identifier, errors.size)
        } else if (warnings.isNotEmpty()) {
            log.info("{} is valid with {} warning(s)", identifier, warnings.size)
        } else {
            log.info("{} passed HAPI-FHIR validation cleanly", identifier)
        }

        return ProfileValidationResult(
            profileName = name,
            canonicalUrl = url,
            resourceType = type,
            publisher = pub,
            source = source,
            valid = isValid,
            errorCount = errors.size,
            warningCount = warnings.size,
            issues = issues
        )
    }

    private fun errorResult(fileName: String, source: String, ex: Throwable) =
        ProfileValidationResult(
            profileName = fileName,
            canonicalUrl = "unknown",
            resourceType = "unknown",
            publisher = "unknown",
            source = source,
            valid = false,
            errorCount = 1,
            warningCount = 0,
            issues = listOf(
                ProfileValidationIssue(
                    severity = "ERROR",
                    location = fileName,
                    message = "Failed to load: ${ex.message}"
                )
            )
        )

    private fun buildReport(results: List<ProfileValidationResult>, label: String): ProfileValidationReport {
        val sorted = results.sortedWith(compareBy({ !it.valid }, { it.profileName }))
        val summary = ProfileValidationSummary(
            totalProfiles = sorted.size,
            validProfiles = sorted.count { it.valid },
            invalidProfiles = sorted.count { !it.valid },
            profilesWithWarnings = sorted.count { it.warningCount > 0 },
            totalErrors = sorted.sumOf { it.errorCount },
            totalWarnings = sorted.sumOf { it.warningCount }
        )
        log.info(
            "{} validation complete - {}/{} valid, {} errors, {} warnings",
            label, summary.validProfiles, summary.totalProfiles,
            summary.totalErrors, summary.totalWarnings
        )
        return ProfileValidationReport(profiles = sorted, summary = summary)
    }

    // Derive the source directory from the resource URL path
    private fun deriveSource(res: Resource, base: String): String {
        return try {
            val uri = res.uri.toString().replace('\\', '/')
            val idx = uri.indexOf(base)
            if (idx >= 0) {
                val afterBase = uri.substring(idx + base.length + 1)
                val slash = afterBase.indexOf('/')
                if (slash > 0) afterBase.substring(0, slash) else base
            } else base
        } catch (_: Exception) {
            base
        }
    }
}
