package com.example.fdd.config

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.parser.IParser
import ca.uhn.fhir.validation.FhirValidator
import org.hl7.fhir.common.hapi.validation.support.CommonCodeSystemsTerminologyService
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.common.hapi.validation.validator.FhirInstanceValidator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Spring configuration for HAPI-FHIR infrastructure beans.
 *
 * Provides both R4 and R5 [FhirContext] instances, JSON parsers,
 * validation support chains, and the [FhirValidator] used to
 * validate FHIR resource instances.
 *
 * R4 beans are `@Primary` so they are injected by default. R5 beans
 * are qualified as `"r5"` and used only for version-drift profile pairs.
 */
@Configuration
class FhirConfig {

    /* --------------------------- R4 (Primary) --------------------------- */

    /**
     * Singleton R4 FHIR context - expensive to create, so shared across the application.
     */
    @Bean
    @Primary
    fun fhirContext(): FhirContext = FhirContext.forR4()

    /**
     * Reusable JSON parser for FHIR resource (de)serialisation.
     * Configured for lenient parsing to tolerate minor issues in external profiles.
     */
    @Bean
    @Primary
    fun fhirJsonParser(fhirContext: FhirContext): IParser =
        fhirContext.newJsonParser().setPrettyPrint(false)

    /**
     * Validation support chain combining:
     * - Default profile validation (base R4 definitions)
     * - In-memory terminology service
     * - Common code-system service (UCUM, MIME-types, etc.)
     */
    @Bean
    @Primary
    fun validationSupportChain(fhirContext: FhirContext): ValidationSupportChain {
        return ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContext),
            InMemoryTerminologyServerValidationSupport(fhirContext),
            CommonCodeSystemsTerminologyService(fhirContext)
        )
    }

    /**
     * HAPI-FHIR instance validator, wired with the full validation support chain.
     */
    @Bean
    fun fhirValidator(
        fhirContext: FhirContext,
        validationSupportChain: ValidationSupportChain
    ): FhirValidator {
        val validator = fhirContext.newValidator()
        val instanceValidator = FhirInstanceValidator(validationSupportChain)
        validator.registerValidatorModule(instanceValidator)
        return validator
    }

    /* --------------------------- R5 (for version-drift) --------------------------- */

    /**
     * R5 FHIR context - used only when comparing R4-vs-R5 profile pairs (version drift).
     */
    @Bean
    @Qualifier("r5")
    fun fhirContextR5(): FhirContext = FhirContext.forR5()

    /**
     * R5 JSON parser for loading R5 StructureDefinitions from disk.
     */
    @Bean
    @Qualifier("r5")
    fun fhirJsonParserR5(@Qualifier("r5") fhirContextR5: FhirContext): IParser =
        fhirContextR5.newJsonParser().setPrettyPrint(false)

    /**
     * R5 validation support chain - mirrors the R4 chain but uses R5 base definitions.
     */
    @Bean
    @Qualifier("r5")
    fun validationSupportChainR5(@Qualifier("r5") fhirContextR5: FhirContext): ValidationSupportChain {
        return ValidationSupportChain(
            DefaultProfileValidationSupport(fhirContextR5),
            InMemoryTerminologyServerValidationSupport(fhirContextR5),
            CommonCodeSystemsTerminologyService(fhirContextR5)
        )
    }

    /**
     * R5 HAPI-FHIR instance validator, wired with the R5 validation support chain.
     * Used exclusively for validating R5 StructureDefinition profiles.
     */
    @Bean
    @Qualifier("r5")
    fun fhirValidatorR5(
        @Qualifier("r5") fhirContextR5: FhirContext,
        @Qualifier("r5") validationSupportChainR5: ValidationSupportChain
    ): FhirValidator {
        val validator = fhirContextR5.newValidator()
        val instanceValidator = FhirInstanceValidator(validationSupportChainR5)
        validator.registerValidatorModule(instanceValidator)
        return validator
    }
}
