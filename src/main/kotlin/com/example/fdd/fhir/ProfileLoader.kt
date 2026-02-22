package com.example.fdd.fhir

import org.hl7.fhir.r4.model.StructureDefinition

/**
 * Loads FHIR [StructureDefinition] profiles from various sources.
 *
 * Supported input methods:
 * - Canonical URL - resolved from HAPI-FHIR's built-in R4/R5 definitions.
 * - Raw JSON - inline StructureDefinition JSON string.
 * - HTTP URL - fetch from any public URL (FHIR registries, GitHub raw links, simplifier.net).
 * - Classpath - load from bundled classpath resources.
 * - Local file - read from the server's file system (development use only).
 *
 * All loaded profiles are validated with HAPI-FHIR before being returned.
 * Invalid profiles throw [com.example.fdd.exception.ProfileValidationException].
 */
interface ProfileLoader {

    /**
     * Load a profile by its canonical URL from HAPI-FHIR's built-in definitions.
     *
     * @param canonical Canonical URL, e.g.
     *   `http://hl7.org/fhir/StructureDefinition/Patient`.
     * @throws com.example.fdd.exception.ProfileNotFoundException if the profile cannot be resolved.
     */
    fun loadProfileByCanonical(canonical: String): StructureDefinition

    /**
     * Parse a [StructureDefinition] from raw JSON content.
     *
     * @param json Valid FHIR JSON representation of a StructureDefinition.
     * @throws com.example.fdd.exception.ProfileValidationException if the profile fails validation.
     */
    fun loadProfileFromJson(json: String): StructureDefinition

    /**
     * Fetch a [StructureDefinition] from an HTTP(S) URL.
     *
     * This supports any publicly accessible URL that returns StructureDefinition JSON,
     * including FHIR registries (simplifier.net), GitHub raw links, and
     * build.fhir.org implementation guide URLs.
     *
     * @param url HTTP(S) URL pointing to a StructureDefinition JSON resource.
     * @throws com.example.fdd.exception.ProfileNotFoundException if the URL is unreachable or returns non-FHIR content.
     * @throws com.example.fdd.exception.ProfileValidationException if the fetched profile fails validation.
     */
    fun loadProfileFromUrl(url: String): StructureDefinition

    /**
     * Load a profile from an explicit classpath location.
     *
     * @param path Classpath-relative path, e.g.
     *   `fhir/profiles/us-core/StructureDefinition-us-core-patient.json`.
     */
    fun loadProfileFromClasspath(path: String): StructureDefinition

    /**
     * Load a profile from a local file-system path.
     *
     * Reads the file, parses the JSON as a [StructureDefinition], and validates it.
     * Use only in development or same-machine scenarios - REST APIs should generally
     * not read arbitrary server-side files in production.
     *
     * @param path Absolute or relative file-system path to a `.json` StructureDefinition file.
     * @throws com.example.fdd.exception.ProfileNotFoundException if the file does not exist.
     * @throws com.example.fdd.exception.ProfileValidationException if the parsed profile fails validation.
     */
    fun loadProfileFromFile(path: String): StructureDefinition
}
