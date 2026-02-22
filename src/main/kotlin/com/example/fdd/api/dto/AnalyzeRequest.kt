package com.example.fdd.api.dto

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Describes how a single FHIR profile is provided.
 *
 * Supply the profile using any one of these fields (checked in priority order):
 * 1. `json`      - Raw StructureDefinition JSON content.
 * 2. `url`       - HTTP(S) URL pointing to a StructureDefinition JSON file.
 * 3. `canonical` - Canonical URL resolved from HAPI-FHIR's built-in R4/R5 definitions.
 * 4. `classpath` - Classpath-relative path to a bundled StructureDefinition JSON file.
 * 5. `file`      - Local file-system path (reads from the server's disk; use in development only).
 *
 * At least one field must be provided.
 */
@Schema(description = "Identifies a FHIR StructureDefinition profile via inline JSON, HTTP URL, canonical URL, classpath resource, or local file path")
data class ProfileInput(
    @Schema(description = "Raw StructureDefinition JSON content")
    val json: String? = null,

    @Schema(
        description = "HTTP(S) URL to fetch the StructureDefinition JSON from (e.g. public FHIR registry, GitHub raw link, simplifier.net URL)",
        example = "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json"
    )
    val url: String? = null,

    @Schema(
        description = "Canonical URL of a base FHIR profile (resolved from HAPI-FHIR built-in definitions)",
        example = "http://hl7.org/fhir/StructureDefinition/Patient"
    )
    val canonical: String? = null,

    @Schema(
        description = "Classpath-relative path to a bundled StructureDefinition JSON file (e.g. under resources/fhir/profiles/)",
        example = "fhir/profiles/us-core/StructureDefinition-us-core-patient.json"
    )
    val classpath: String? = null,

    @Schema(
        description = "Absolute or relative file-system path to a local .json StructureDefinition file. Use only in development or same-machine scenarios.",
        example = "C:/profiles/StructureDefinition-us-core-patient.json"
    )
    val file: String? = null
) {
    /** Returns `true` if at least one identification method is provided. */
    fun isProvided(): Boolean =
        !json.isNullOrBlank() || !url.isNullOrBlank() || !canonical.isNullOrBlank() ||
                !classpath.isNullOrBlank() || !file.isNullOrBlank()

    /** Human-readable label for logging. */
    fun label(): String = when {
        !json.isNullOrBlank() -> "(inline JSON)"
        !url.isNullOrBlank() -> url!!
        !canonical.isNullOrBlank() -> canonical!!
        !classpath.isNullOrBlank() -> "(classpath: $classpath)"
        !file.isNullOrBlank() -> "(file: $file)"
        else -> "(empty)"
    }
}

/**
 * Request body for `POST /api/drift/analyze`.
 *
 * Both source and target profiles must be provided using any supported input method.
 */
@Schema(description = "Request to analyze semantic drift between two FHIR profiles")
data class AnalyzeRequest(
    @Schema(description = "Source FHIR profile", required = true)
    val source: ProfileInput = ProfileInput(),

    @Schema(description = "Target FHIR profile", required = true)
    val target: ProfileInput = ProfileInput()
) {
    fun validate() {
        require(source.isProvided()) {
            "Source profile must be provided via 'json', 'url', 'canonical', 'classpath', or 'file'"
        }
        require(target.isProvided()) {
            "Target profile must be provided via 'json', 'url', 'canonical', 'classpath', or 'file'"
        }
    }
}

/**
 * Request body for `POST /api/drift/repair`.
 *
 * Same as [AnalyzeRequest] but semantically distinct - this triggers
 * the full pipeline including StructureMap generation and validation.
 */
@Schema(description = "Request to analyze drift and generate a validated StructureMap repair")
data class RepairRequest(
    @Schema(description = "Source FHIR profile", required = true)
    val source: ProfileInput = ProfileInput(),

    @Schema(description = "Target FHIR profile", required = true)
    val target: ProfileInput = ProfileInput()
) {
    fun validate() {
        require(source.isProvided()) {
            "Source profile must be provided via 'json', 'url', 'canonical', 'classpath', or 'file'"
        }
        require(target.isProvided()) {
            "Target profile must be provided via 'json', 'url', 'canonical', 'classpath', or 'file'"
        }
    }
}
