package com.example.fdd.fhir.impl

import ca.uhn.fhir.context.FhirContext
import ca.uhn.fhir.context.support.DefaultProfileValidationSupport
import ca.uhn.fhir.context.support.ValidationSupportContext
import com.example.fdd.fhir.ProfileContextBuilder
import com.example.fdd.model.BindingSummary
import com.example.fdd.model.ConstraintSummary
import com.example.fdd.model.DriftItem
import com.example.fdd.model.ElementSummary
import com.example.fdd.model.MappingSummary
import com.example.fdd.model.ProfileContext
import com.example.fdd.model.ProfileSummary
import com.example.fdd.model.SlicingSummary
import com.example.fdd.model.TypeSummary
import org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport
import org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport
import org.hl7.fhir.common.hapi.validation.support.ValidationSupportChain
import org.hl7.fhir.r4.model.ElementDefinition
import org.hl7.fhir.r4.model.StructureDefinition
import org.hl7.fhir.r4.model.Type
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

/**
 * Default implementation of [com.example.fdd.fhir.ProfileContextBuilder].
 *
 * Converts HAPI FHIR profile objects into plain data classes that are serialised
 * to JSON and sent to the LLM. All fields from
 * [org.hl7.fhir.r4.model.ElementDefinition] that carry semantic meaning are
 * included so the LLM can spot drift in any dimension.
 *
 * Two extraction modes:
 *
 * 1. **Full context** ([buildContext]) - extracts every element from the
 *    snapshot (or differential) of each profile. Used by the Drift Analyzer.
 *
 * 2. **Drift-focused context** ([buildDriftFocusedContext]) - extracts only
 *    the elements mentioned in the drift report. Used by the Map Generator
 *    so it does not receive the full profiles again.
 */
@Component
class DefaultProfileContextBuilder(
    private val fhirContext: FhirContext,
    @Qualifier("r5") private val fhirContextR5: FhirContext
) : ProfileContextBuilder {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Dedicated R4 validation chain for snapshot generation.
     * Separate from the main validation chain to avoid side-effects.
     * Includes:
     * - [org.hl7.fhir.common.hapi.validation.support.SnapshotGeneratingValidationSupport] - drives snapshot generation
     * - [ca.uhn.fhir.context.support.DefaultProfileValidationSupport]     - resolves base R4 StructureDefinitions
     * - [org.hl7.fhir.common.hapi.validation.support.InMemoryTerminologyServerValidationSupport] - resolves value-set bindings
     */
    private val snapshotChainR4: ValidationSupportChain by lazy {
        ValidationSupportChain(
            SnapshotGeneratingValidationSupport(fhirContext),
            DefaultProfileValidationSupport(fhirContext),
            InMemoryTerminologyServerValidationSupport(fhirContext)
        )
    }

    /** Equivalent snapshot chain for R5 profiles. */
    private val snapshotChainR5: ValidationSupportChain by lazy {
        ValidationSupportChain(
            SnapshotGeneratingValidationSupport(fhirContextR5),
            DefaultProfileValidationSupport(fhirContextR5),
            InMemoryTerminologyServerValidationSupport(fhirContextR5)
        )
    }

    /**
     * Ensures [sd] has a snapshot, generating one from the differential if needed.
     *
     * Custom profiles are often differential-only (no snapshot). Without a snapshot
     * the rule-based detector can only compare elements explicitly listed in the
     * differential, missing all inherited base elements.
     *
     * Uses HAPI FHIR's [SnapshotGeneratingValidationSupport] to expand the differential
     * against the base StructureDefinition (e.g. R4 Patient) and produce a full
     * element list in [org.hl7.fhir.r4.model.StructureDefinition.snapshot].
     *
     * If generation fails, the method falls back to the differential so the
     * application keeps working rather than throwing an error.
     */
    private fun ensureSnapshot(sd: StructureDefinition): StructureDefinition {
        if (sd.hasSnapshot()) return sd

        val baseUrl = sd.baseDefinition
        if (baseUrl.isNullOrBlank()) {
            log.warn("Profile {} has no baseDefinition - using differential only", sd.url)
            return sd
        }

        // Pick the correct chain based on the profile's declared FHIR version
        val chain = if (sd.fhirVersion?.display?.startsWith("5") == true) snapshotChainR5 else snapshotChainR4

        return try {
            val ctx = ValidationSupportContext(chain)
            chain.generateSnapshot(ctx, sd, sd.url ?: "", null, sd.name ?: "")
            val snapSize = sd.snapshot?.element?.size ?: 0
            if (snapSize > 0) {
                log.debug("Snapshot generated for {} - {} elements (base: {})", sd.url, snapSize, baseUrl)
            } else {
                log.warn("Snapshot generation produced 0 elements for {} - using differential", sd.url)
            }
            sd
        } catch (e: Exception) {
            log.warn(
                "Snapshot generation failed for {} (base: {}) - using differential only. Reason: {}",
                sd.url, baseUrl, e.message
            )
            sd
        }
    }

    /* ---- Stage 1: Full context for Drift Analysis ---- */

    override fun buildContext(
        source: StructureDefinition,
        target: StructureDefinition
    ): ProfileContext {
        log.debug(
            "Building full profile context: {} -> {}",
            source.url ?: "unknown",
            target.url ?: "unknown"
        )
        return ProfileContext(
            sourceProfile = summarise(source),
            targetProfile = summarise(target)
        )
    }

    /* ---- Stage 2: Abbreviated context for Map Generation ---- */

    override fun buildDriftFocusedContext(
        source: StructureDefinition,
        target: StructureDefinition,
        driftItems: List<DriftItem>
    ): ProfileContext {
        // Collect every FHIR path referenced in the drift report
        val driftPaths = driftItems
            .flatMap { listOfNotNull(it.sourcePath, it.targetPath) }
            .toSet()

        log.debug(
            "Building drift-focused context ({} paths) for: {} -> {}",
            driftPaths.size,
            source.url ?: "unknown",
            target.url ?: "unknown"
        )

        return ProfileContext(
            sourceProfile = summariseFiltered(source, driftPaths),
            targetProfile = summariseFiltered(target, driftPaths)
        )
    }

    private fun summarise(sd: StructureDefinition): ProfileSummary {
        val elements = resolveElements(sd).map { it.toSummary() }
        log.debug("Profile {} has {} elements", sd.url, elements.size)

        return ProfileSummary(
            canonical = sd.url ?: "unknown",
            type = sd.type ?: "unknown",
            version = sd.version,
            fhirVersion = sd.fhirVersion?.display,
            elements = elements
        )
    }

    /**
     * Build a [ProfileSummary] containing only elements whose path matches
     * one of the provided [driftPaths], plus any parent paths needed for
     * context (e.g. if "Patient.identifier" is in drift, include "Patient"
     * so the LLM understands the hierarchy).
     */
    private fun summariseFiltered(
        sd: StructureDefinition,
        driftPaths: Set<String>
    ): ProfileSummary {
        val allElements = resolveElements(sd)

        // Build the set of paths to include: the drift paths themselves
        // plus every ancestor (e.g. "Patient.name.given" -> also "Patient.name", "Patient")
        val includePaths = buildSet {
            for (path in driftPaths) {
                add(path)
                val parts = path.split(".")
                for (i in 1 until parts.size) {
                    add(parts.subList(0, i).joinToString("."))
                }
            }
        }

        val filtered = allElements
            .filter { it.path in includePaths }
            .map { it.toSummary() }

        return ProfileSummary(
            canonical = sd.url ?: "unknown",
            type = sd.type ?: "unknown",
            version = sd.version,
            fhirVersion = sd.fhirVersion?.display,
            elements = filtered
        )
    }

    /**
     * Prefer snapshot elements fall back to differential if the snapshot is absent.
     *
     * Before resolving, calls [ensureSnapshot] so that differential-only profiles
     * (all custom profiles) get their snapshot populated from the R4/R5 base
     * StructureDefinition. This ensures the drift detector compares ALL
     * inherited elements.
     *
     * **Important:** We use [StructureDefinition.hasSnapshot] / [StructureDefinition.hasDifferential]
     * instead of null-safe access because HAPI FHIR auto-creates an empty component object
     * when the getter is called (via `Configuration.doAutoCreate()`), so `sd.snapshot?.element`
     * would return an empty list instead of null for differential-only profiles.
     */
    private fun resolveElements(sd: StructureDefinition): List<ElementDefinition> {
        val resolved = ensureSnapshot(sd)
        return if (resolved.hasSnapshot() && resolved.snapshot.element.isNotEmpty())
            resolved.snapshot.element
        else if (resolved.hasDifferential()) resolved.differential.element
        else emptyList<ElementDefinition>().also {
            log.warn("StructureDefinition {} has neither snapshot nor differential", resolved.url)
        }
    }

    /**
     * Convert a HAPI [ElementDefinition] into a normalised [com.example.fdd.model.ElementSummary].
     *
     * Captures **all** semantically meaningful fields so the LLM can detect
     * drift anywhere - we do not pre-decide which fields are relevant.
     */
    private fun ElementDefinition.toSummary(): ElementSummary = ElementSummary(
        // Include sliceName in path using standard FHIR notation (e.g. Patient.extension:employeeId).
        // This ensures extension slices are treated as distinct elements during comparison
        // rather than all collapsing to the same "Patient.extension" key.
        path = if (this.sliceName != null) "${this.path ?: ""}:${this.sliceName}" else (this.path ?: ""),
        sliceName = this.sliceName,
        slicing = this.slicing?.takeIf { it.hasDiscriminator() || it.hasRules() }?.let { s ->
            SlicingSummary(
                discriminators = s.discriminator?.map { d -> "${d.type?.toCode()}:${d.path}" } ?: emptyList(),
                ordered = s.ordered,
                rules = s.rules?.toCode()
            )
        },
        types = this.type?.map { t ->
            TypeSummary(
                code = t.code ?: "",
                profiles = t.profile?.mapNotNull { it.value } ?: emptyList(),
                targetProfiles = t.targetProfile?.mapNotNull { it.value } ?: emptyList()
            )
        } ?: emptyList(),
        min = this.min,
        max = this.max ?: "*",
        mustSupport = this.mustSupport,
        isModifier = this.isModifier,
        isSummary = this.isSummary,
        binding = this.binding?.takeIf { it.strength != null }?.let { b ->
            BindingSummary(
                strength = b.strength.toCode(),
                valueSet = b.valueSet
            )
        },
        constraints = this.constraint?.map { c ->
            ConstraintSummary(
                key = c.key ?: "",
                severity = c.severity?.toCode(),
                human = c.human,
                expression = c.expression
            )
        } ?: emptyList(),
        fixedValue = this.fixed?.toShortString(),
        patternValue = this.pattern?.toShortString(),
        defaultValue = this.defaultValue?.toShortString(),
        meaningWhenMissing = this.meaningWhenMissing,
        contentReference = this.contentReference,
        extensions = this.extension?.mapNotNull { it.url } ?: emptyList(),
        modifierExtensions = this.modifierExtension?.mapNotNull { it.url } ?: emptyList(),
        mappings = this.mapping?.map { m ->
            MappingSummary(
                identity = m.identity ?: "",
                map = m.map
            )
        } ?: emptyList(),
        short = this.short,
        definition = this.definition
    )

    /**
     * Render a HAPI [org.hl7.fhir.r4.model.Type] value to a concise string for LLM consumption.
     * Uses HAPI's built-in primitiveValue where available, otherwise toString().
     */
    private fun Type.toShortString(): String? =
        this.primitiveValue() ?: this.toString().takeIf { it.isNotBlank() }
}