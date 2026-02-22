package com.example.fdd

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.retry.annotation.EnableRetry

/**
 * FHIR Drift Doctor - A Spring Boot service for detecting semantic drift
 * between FHIR profiles and generating StructureMap repair artefacts.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.example.fdd.config")
@EnableRetry
class FddApplication

fun main(args: Array<String>) {
    runApplication<FddApplication>(*args)
}
