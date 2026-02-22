package com.example.fdd.api

import com.example.fdd.api.dto.ProfileValidationReport
import com.example.fdd.api.dto.ProfileValidationResult
import com.example.fdd.api.dto.ProfileValidationSummary
import com.example.fdd.api.impl.ProfileValidationController
import com.example.fdd.output.IOutputStore
import com.example.fdd.service.IProfileValidationService
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Unit tests for [com.example.fdd.api.impl.ProfileValidationController].
 *
 * Uses `@WebMvcTest` to test all three validation endpoints at the HTTP layer
 * without loading the full application context. The [ProfileValidationService]
 * is mocked to isolate controller behaviour from HAPI-FHIR classpath scanning.
 */
@WebMvcTest(ProfileValidationController::class)
class ProfileValidationControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var profileValidationService: IProfileValidationService

    @MockitoBean
    private lateinit var outputStore: IOutputStore

    // ---- Fixture helpers ----

    private fun validProfile(name: String, source: String) = ProfileValidationResult(
        profileName = name,
        canonicalUrl = "http://example.org/$name",
        resourceType = "Patient",
        publisher = "Test Org",
        source = source,
        valid = true,
        errorCount = 0,
        warningCount = 0,
        issues = emptyList()
    )

    private fun summary(total: Int, valid: Int) = ProfileValidationSummary(
        totalProfiles = total,
        validProfiles = valid,
        invalidProfiles = total - valid,
        profilesWithWarnings = 0,
        totalErrors = 0,
        totalWarnings = 0
    )

    // ---- GET /api/validate/profiles ----

    @Test
    @DisplayName("GET /api/validate/profiles returns 200 with custom profile report")
    fun validateAllProfiles_returns200WithReport() {
        val report = ProfileValidationReport(
            profiles = listOf(validProfile("tk-soft-patient", "tk-soft")),
            summary = summary(1, 1)
        )
        whenever(profileValidationService.validateAllCustomProfiles()).thenReturn(report)

        mockMvc.perform(get("/api/validate/profiles"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.totalProfiles").value(1))
            .andExpect(jsonPath("$.summary.validProfiles").value(1))
            .andExpect(jsonPath("$.summary.invalidProfiles").value(0))
            .andExpect(jsonPath("$.profiles[0].profileName").value("tk-soft-patient"))
            .andExpect(jsonPath("$.profiles[0].valid").value(true))
    }

    @Test
    @DisplayName("GET /api/validate/profiles returns correct structure when no profiles found")
    fun validateAllProfiles_emptyReport_returns200() {
        val report = ProfileValidationReport(profiles = emptyList(), summary = summary(0, 0))
        whenever(profileValidationService.validateAllCustomProfiles()).thenReturn(report)

        mockMvc.perform(get("/api/validate/profiles"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.totalProfiles").value(0))
            .andExpect(jsonPath("$.profiles").isArray)
    }

    // ---- GET /api/validate/standard ----

    @Test
    @DisplayName("GET /api/validate/standard returns 200 with standard profile report")
    fun validateAllStandardProfiles_returns200WithReport() {
        val report = ProfileValidationReport(
            profiles = listOf(
                validProfile("r4-patient", "r4"),
                validProfile("us-core-patient", "us-core")
            ),
            summary = summary(2, 2)
        )
        whenever(profileValidationService.validateAllStandardProfiles()).thenReturn(report)

        mockMvc.perform(get("/api/validate/standard"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.totalProfiles").value(2))
            .andExpect(jsonPath("$.profiles.length()").value(2))
    }

    // ---- GET /api/validate/all ----

    @Test
    @DisplayName("GET /api/validate/all returns combined report of custom and standard profiles")
    fun validateAll_returnsCombinedReport() {
        val report = ProfileValidationReport(
            profiles = listOf(
                validProfile("tk-soft-patient", "tk-soft"),
                validProfile("r4-patient", "r4")
            ),
            summary = summary(2, 2)
        )
        whenever(profileValidationService.validateAll()).thenReturn(report)

        mockMvc.perform(get("/api/validate/all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.totalProfiles").value(2))
            .andExpect(jsonPath("$.summary.totalErrors").value(0))
    }

    @Test
    @DisplayName("GET /api/validate/all includes invalid profiles in report")
    fun validateAll_withInvalidProfiles_includesErrors() {
        val invalidProfile = ProfileValidationResult(
            profileName = "broken-profile",
            canonicalUrl = "http://example.org/broken",
            resourceType = "Observation",
            publisher = "Test",
            source = "tk-soft",
            valid = false,
            errorCount = 2,
            warningCount = 1,
            issues = emptyList()
        )
        val report = ProfileValidationReport(
            profiles = listOf(invalidProfile),
            summary = summary(1, 0).copy(totalErrors = 2, profilesWithWarnings = 1)
        )
        whenever(profileValidationService.validateAll()).thenReturn(report)

        mockMvc.perform(get("/api/validate/all"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summary.invalidProfiles").value(1))
            .andExpect(jsonPath("$.summary.totalErrors").value(2))
            .andExpect(jsonPath("$.profiles[0].valid").value(false))
            .andExpect(jsonPath("$.profiles[0].errorCount").value(2))
    }
}
