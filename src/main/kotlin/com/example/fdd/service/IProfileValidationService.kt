package com.example.fdd.service

import com.example.fdd.api.dto.ProfileValidationReport

interface IProfileValidationService {
    // Validate every *.json file under classpath:custom-profiles/ (recursive)
    fun validateAllCustomProfiles(): ProfileValidationReport

    // Validate every *.json file under classpath:standard-profiles/ (recursive)
    // R5 profiles (source=r5) are validated with the R5 FhirContext + R5 validator
    fun validateAllStandardProfiles(): ProfileValidationReport

    // Validate everything: custom profiles + standard profiles
    fun validateAll(): ProfileValidationReport
}
