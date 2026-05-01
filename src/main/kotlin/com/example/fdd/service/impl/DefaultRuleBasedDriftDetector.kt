package com.example.fdd.service.impl

import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftType
import com.example.fdd.model.ElementSummary
import com.example.fdd.model.ProfileContext
import com.example.fdd.service.RuleBasedDriftDetector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Default rule-based drift detector implementing deterministic structural comparison.
 *
 * **Role in the pipeline:** This detector produces SEED items only. It is NOT the
 * primary drift authority - the LLM in [DefaultDriftAnalyzer] is. The seeds
 * surfaced here are passed to the LLM as hints; the LLM may confirm, refine, or
 * reject them. Items that the LLM does not cover are added back as a safety net.
 *
 * Covers all five [com.example.fdd.model.DriftType] categories:
 *
 * | # | Category    | Rules                                                          |
 * |---|-------------|----------------------------------------------------------------|
 * | 1 | CARDINALITY | min/max changes for shared elements and slices                 |
 * | 2 | TERMINOLOGY | Binding strength / value-set / additional binding semantics;   |
 * |   |             | fixed/pattern/default value changes on coded elements only.    |
 * | 3 | STRUCTURAL  | Type code (incl. one-sided), type/target profiles, mustSupport,|
 * |   |             | isModifier, slicing, constraints, contentReference,            |
 * |   |             | added/removed elements & slices, fixed/pattern values on       |
 * |   |             | identifier/Reference/boolean/non-bound code/structural paths.  |
 * | 4 | EXTENSION   | Named extension and modifier-extension slice add/remove/change.|
 * | 5 | VERSION     | FHIR version mismatch between profiles.                        |
 *
 * Each emitted [DriftItem] has `source = "rule"`. The analyzer is responsible for
 * relabelling items as `"hybrid"` when the LLM agrees with a seed.
 *
 * Notes on complexity: the per-element comparison is O(n) using path-keyed maps.
 * The added/removed parent suppression is O(n^2) in the worst case.
 *
 * Notes on slice identity: [com.example.fdd.fhir.impl.DefaultProfileContextBuilder]
 * encodes slice identity directly into [ElementSummary.path] using the
 * `path:sliceName` notation, so map indexing by `path` already distinguishes slices.
 */
@Component
class DefaultRuleBasedDriftDetector : RuleBasedDriftDetector {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Extension URLs that are conformance-program metadata markers with zero impact on
     * data exchange, validation, or StructureMap generation.
     * Removing them from seeds eliminates the primary source of false-positive EXTENSION
     * drift items in all comparisons involving US Core profiles.
     */
    private val METADATA_EXTENSION_URLS = setOf(
        "http://hl7.org/fhir/us/core/StructureDefinition/uscdi-requirement",
        "http://hl7.org/fhir/us/core/StructureDefinition/uscdi-plus"
    )

    override fun detect(context: ProfileContext): List<DriftItem> {
        val items = mutableListOf<DriftItem>()
        var idCounter = 0

        fun nextId() = "rule-${++idCounter}"

        val sourceProfile = context.sourceProfile
        val targetProfile = context.targetProfile
        val sourceElements = sourceProfile.elements
        val targetElements = targetProfile.elements

        /* -----------------------------------------------------------------
         * VERSION drift - profile-level FHIR version mismatch
         * ----------------------------------------------------------------- */
        if (sourceProfile.fhirVersion != null && targetProfile.fhirVersion != null &&
            sourceProfile.fhirVersion != targetProfile.fhirVersion
        ) {
            items.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.VERSION,
                    sourcePath = sourceProfile.canonical,
                    targetPath = targetProfile.canonical,
                    description = "FHIR version mismatch: ${sourceProfile.fhirVersion} -> ${targetProfile.fhirVersion}",
                    severity = "ERROR",
                    source = "rule"
                )
            )
        }

        /* -------------------------------------------------------------------
         * Element-level rules - compare elements present in both profiles.
         *
         * Indexing key: ElementSummary.path already encodes "path:sliceName"
         * for sliced elements, so this map distinguishes slices automatically.
         * If a profile contains duplicate path keys (rare, malformed), keep
         * the first occurrence to avoid silent loss.
         * ------------------------------------------------------------------- */
        val sourceByPath = sourceElements
            .groupBy { it.path }
            .mapValues { it.value.first() }
        val targetByPath = targetElements
            .groupBy { it.path }
            .mapValues { it.value.first() }

        // MustSupport: emit every element individually (no parent-cascade suppression).
        // The gold-standard ground truth lists each leaf separately because each element
        // that gains mustSupport is an independent implementer obligation.  Suppressing
        // children here would create false negatives that lower recall in evaluation.

        for ((path, src) in sourceByPath) {
            val tgt = targetByPath[path] ?: continue

            // 1. CARDINALITY drift
            detectCardinalityDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 2. BINDING drift (TERMINOLOGY)
            detectBindingDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 3. Fixed / pattern / default values - classified per element kind
            items.addAll(detectFixedValueDrifts(src, tgt, path, ::nextId))

            // 4. Type-code drift (STRUCTURAL) - includes one-sided changes
            detectTypeCodeDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 5. Type profile / target profile (STRUCTURAL)
            detectTypeProfileDrift(src, tgt, path, ::nextId)?.let { items.add(it) }
            detectTargetProfileDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 6. mustSupport (STRUCTURAL) - emit every changed element (leaf-level granularity)
            detectMustSupportDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 7. isModifier (STRUCTURAL) - always ERROR
            detectIsModifierDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // NOTE: isSummary is intentionally NOT compared - it is a search/summary
            // view flag and does not constitute profile-level semantic drift.

            // 8. Slicing rules (STRUCTURAL)
            detectSlicingDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 9. Constraints (STRUCTURAL) - added, removed, modified
            items.addAll(detectConstraintDrifts(src, tgt, path, ::nextId))

            // 10. contentReference (STRUCTURAL)
            detectContentReferenceDrift(src, tgt, path, ::nextId)?.let { items.add(it) }

            // 11. Element-level extensions / modifier extensions (EXTENSION)
            // These are URLs declared on the element itself, distinct from named
            // extension slices which are emitted via the path-add/remove pass below.
            items.addAll(detectElementExtensionDrifts(src, tgt, path, ::nextId))
        }

        /* -------------------------------------------------------------------
         * STRUCTURAL / EXTENSION: elements present in only one profile.
         * Slice paths are distinguished by the ":sliceName" suffix encoded in
         * ElementSummary.path.
         * ------------------------------------------------------------------- */

        // Elements / slices added in target
        val newPaths = targetByPath.keys - sourceByPath.keys
        for (path in suppressChildrenWhenParentInSet(newPaths)) {
            val tgt = targetByPath.getValue(path)
            items.add(buildAddedElementItem(path, tgt, nextId()))
        }

        // Elements / slices removed in target
        val removedPaths = sourceByPath.keys - targetByPath.keys
        for (path in suppressChildrenWhenParentInSet(removedPaths)) {
            val src = sourceByPath.getValue(path)
            items.add(buildRemovedElementItem(path, src, nextId()))
        }

        log.info(
            "Rule-based detector produced {} SEED items (these are HINTS for the LLM, not authoritative)",
            items.size
        )
        return items
    }

    /* =====================================================================
     * Per-element rule helpers
     * Each returns a single DriftItem or null when there is no drift.
     * ===================================================================== */

    private fun detectCardinalityDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        if (src.min == tgt.min && src.max == tgt.max) return null
        val severity = when {
            tgt.min > src.min -> "ERROR"                          // requirement tightened
            src.min > tgt.min -> "INFO"                           // requirement relaxed
            isMaxTighter(src.max, tgt.max) -> "WARNING"           // max restricted - possible truncation
            else -> "INFO"                                         // max relaxed - safe
        }
        return DriftItem(
            id = nextId(),
            type = DriftType.CARDINALITY,
            sourcePath = path, targetPath = path,
            description = "Cardinality changed: [${src.min}..${src.max}] -> [${tgt.min}..${tgt.max}]",
            severity = severity,
            source = "rule"
        )
    }

    private fun detectBindingDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        val srcBind = src.binding
        val tgtBind = tgt.binding
        if (srcBind == null && tgtBind == null) return null
        if (srcBind?.strength == tgtBind?.strength && srcBind?.valueSet == tgtBind?.valueSet) return null

        val srcVs = srcBind?.valueSet
        val tgtVs = tgtBind?.valueSet

        // Detect canonical |version pin difference (same value set, different version pin only).
        val sameVsModuloVersion = srcVs != null && tgtVs != null &&
                srcVs.substringBefore('|') == tgtVs.substringBefore('|') &&
                srcVs != tgtVs
        val strengthSame = srcBind?.strength == tgtBind?.strength

        // Severity:
        //   - strength tightened to "required" with a different value-set domain   -> ERROR
        //   - strength tightened to "required" with version-pin-only change         -> WARNING
        //   - any other strength or value-set change                                -> WARNING
        val severity = when {
            tgtBind?.strength == "required" && !sameVsModuloVersion && srcBind?.strength != "required" -> "ERROR"
            tgtBind?.strength == "required" && sameVsModuloVersion && strengthSame -> "WARNING"
            else -> "WARNING"
        }

        return DriftItem(
            id = nextId(),
            type = DriftType.TERMINOLOGY,
            sourcePath = path, targetPath = path,
            description = "Binding changed: " +
                    "${srcBind?.strength ?: "none"}(${srcVs ?: "none"}) -> " +
                    "${tgtBind?.strength ?: "none"}(${tgtVs ?: "none"})" +
                    (if (sameVsModuloVersion) " [value-set version pin only]" else ""),
            severity = severity,
            source = "rule"
        )
    }

    /**
     * Fixed / pattern / default value drift.
     *
     * Classification:
     * - On a coded element WITH a binding -> TERMINOLOGY (changes the allowed concept).
     * - On identifier system, Reference, boolean, or any non-bound element -> STRUCTURAL
     *   (changes data structure, not terminology semantics).
     */
    private fun detectFixedValueDrifts(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): List<DriftItem> {
        val out = mutableListOf<DriftItem>()
        val isCoded = src.binding != null || tgt.binding != null
        val isStructuralPath =
            path.endsWith(".system") || path.endsWith(".reference") ||
                    path.endsWith(".url") || path.endsWith(".value") ||
                    src.types.any { it.code in setOf("Reference", "boolean", "Identifier", "uri", "url", "string") }
        val classifyAs = if (isCoded && !isStructuralPath) DriftType.TERMINOLOGY else DriftType.STRUCTURAL

        if (src.fixedValue != tgt.fixedValue && (src.fixedValue != null || tgt.fixedValue != null)) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = classifyAs,
                    sourcePath = path, targetPath = path,
                    description = "Fixed value changed: '${src.fixedValue ?: "none"}' -> '${tgt.fixedValue ?: "none"}'",
                    severity = "ERROR",
                    source = "rule"
                )
            )
        }
        if (src.patternValue != tgt.patternValue && (src.patternValue != null || tgt.patternValue != null)) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = classifyAs,
                    sourcePath = path, targetPath = path,
                    description = "Pattern value changed: '${src.patternValue ?: "none"}' -> '${tgt.patternValue ?: "none"}'",
                    severity = "WARNING",
                    source = "rule"
                )
            )
        }
        if (src.defaultValue != tgt.defaultValue && (src.defaultValue != null || tgt.defaultValue != null)) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = classifyAs,
                    sourcePath = path, targetPath = path,
                    description = "Default value changed: '${src.defaultValue ?: "none"}' -> '${tgt.defaultValue ?: "none"}'",
                    severity = "INFO",
                    source = "rule"
                )
            )
        }
        return out
    }

    /**
     * Type-code drift. Catches one-sided changes too: when a profile adds or
     * removes a permissible type code on an element shared by both profiles.
     */
    private fun detectTypeCodeDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        val srcTypeCodes = src.types.map { it.code }.sorted()
        val tgtTypeCodes = tgt.types.map { it.code }.sorted()
        if (srcTypeCodes == tgtTypeCodes) return null
        // Both empty -> nothing to compare; either side declaring types is significant.
        if (srcTypeCodes.isEmpty() && tgtTypeCodes.isEmpty()) return null
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = "Type changed: $srcTypeCodes -> $tgtTypeCodes",
            severity = "WARNING",
            source = "rule"
        )
    }

    private fun detectTypeProfileDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        val srcProfiles = src.types.flatMap { it.profiles }.sorted()
        val tgtProfiles = tgt.types.flatMap { it.profiles }.sorted()
        if (srcProfiles == tgtProfiles) return null
        if (srcProfiles.isEmpty() && tgtProfiles.isEmpty()) return null
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = "Type profile constraint changed: $srcProfiles -> $tgtProfiles",
            severity = "WARNING",
            source = "rule"
        )
    }

    private fun detectTargetProfileDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        val srcTp = src.types.flatMap { it.targetProfiles }.sorted()
        val tgtTp = tgt.types.flatMap { it.targetProfiles }.sorted()
        if (srcTp == tgtTp) return null
        if (srcTp.isEmpty() && tgtTp.isEmpty()) return null
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = "Reference target profile changed: $srcTp -> $tgtTp",
            severity = "WARNING",
            source = "rule"
        )
    }

    private fun detectMustSupportDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        if (src.mustSupport == tgt.mustSupport) return null
        // Severity: WARNING when the element is required (mustSupport on required
        // changes implementer obligations); INFO otherwise.
        val severity = if (tgt.min > 0 || src.min > 0) "WARNING" else "INFO"
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = if (tgt.mustSupport) "MustSupport added in target" else "MustSupport removed in target",
            severity = severity,
            source = "rule"
        )
    }

    private fun detectIsModifierDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        if (src.isModifier == tgt.isModifier) return null
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = "IsModifier changed: ${src.isModifier} -> ${tgt.isModifier}",
            severity = "ERROR",
            source = "rule"
        )
    }

    private fun detectSlicingDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        if (src.slicing == tgt.slicing) return null
        if (src.slicing == null && tgt.slicing == null) return null
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = "Slicing changed: " +
                    "${src.slicing?.let { "rules=${it.rules}, discriminators=${it.discriminators}" } ?: "none"} -> " +
                    "${tgt.slicing?.let { "rules=${it.rules}, discriminators=${it.discriminators}" } ?: "none"}",
            severity = "WARNING",
            source = "rule"
        )
    }

    private fun detectConstraintDrifts(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): List<DriftItem> {
        val out = mutableListOf<DriftItem>()
        val srcKeys = src.constraints.map { it.key }.toSet()
        val tgtKeys = tgt.constraints.map { it.key }.toSet()

        val rawAdded = tgtKeys - srcKeys
        // Filter out pure USCDI conformance markers (uscdi-N) from the added set.
        // These are IG-level metadata markers (e.g. uscdi-1, uscdi-2) added en-masse
        // to US Core profiled elements. They do not represent semantic data-transformation
        // drift and would otherwise generate one noise item per profiled element.
        // Semantic constraints (us-core-N, ele-1, custom keys, etc.) are kept.
        val added = rawAdded.filterNot { key -> key.matches(Regex("""uscdi-\d+""", RegexOption.IGNORE_CASE)) }.toSet()
        if (added.isNotEmpty()) {
            // If any added constraint has FHIR severity=error, the DriftItem is ERROR;
            // otherwise WARNING. This ensures error-level constraints (e.g. us-core-1,
            // us-core-2, us-core-3) are not downgraded to WARNING in the seed.
            val hasErrorConstraint = tgt.constraints.any { it.key in added && it.severity == "error" }
            out.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.STRUCTURAL,
                    sourcePath = path, targetPath = path,
                    description = "Constraints added in target: $added",
                    severity = if (hasErrorConstraint) "ERROR" else "WARNING",
                    source = "rule"
                )
            )
        }
        val removed = srcKeys - tgtKeys
        if (removed.isNotEmpty()) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.STRUCTURAL,
                    sourcePath = path, targetPath = path,
                    description = "Constraints removed in target: $removed",
                    severity = "WARNING",
                    source = "rule"
                )
            )
        }
        for (key in srcKeys.intersect(tgtKeys)) {
            val sc = src.constraints.first { it.key == key }
            val tc = tgt.constraints.first { it.key == key }
            if (sc.expression != tc.expression || sc.severity != tc.severity) {
                out.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Constraint '$key' modified: " +
                                "expression '${sc.expression ?: "none"}' -> '${tc.expression ?: "none"}', " +
                                "severity '${sc.severity ?: "none"}' -> '${tc.severity ?: "none"}'",
                        severity = if (tc.severity == "error") "ERROR" else "WARNING",
                        source = "rule"
                    )
                )
            }
        }
        return out
    }

    private fun detectContentReferenceDrift(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): DriftItem? {
        if (src.contentReference == tgt.contentReference) return null
        if (src.contentReference == null && tgt.contentReference == null) return null
        return DriftItem(
            id = nextId(),
            type = DriftType.STRUCTURAL,
            sourcePath = path, targetPath = path,
            description = "Content reference changed: '${src.contentReference ?: "none"}' -> '${tgt.contentReference ?: "none"}'",
            severity = "WARNING",
            source = "rule"
        )
    }

    /**
     * Element-level extensions and modifier extensions declared via the
     * [ElementSummary.extensions] / [ElementSummary.modifierExtensions]
     * URL lists (distinct from full named extension slices).
     */
    private fun detectElementExtensionDrifts(
        src: ElementSummary, tgt: ElementSummary, path: String, nextId: () -> String
    ): List<DriftItem> {
        val out = mutableListOf<DriftItem>()

        val srcExts = src.extensions.toSet() - METADATA_EXTENSION_URLS
        val tgtExts = tgt.extensions.toSet() - METADATA_EXTENSION_URLS
        for (ext in tgtExts - srcExts) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.EXTENSION,
                    sourcePath = path, targetPath = "$path.extension($ext)",
                    description = "Extension added in target element: $ext",
                    severity = "WARNING",
                    source = "rule"
                )
            )
        }
        for (ext in srcExts - tgtExts) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.EXTENSION,
                    sourcePath = "$path.extension($ext)", targetPath = path,
                    description = "Extension removed in target element: $ext",
                    severity = "WARNING",
                    source = "rule"
                )
            )
        }

        val srcModExts = src.modifierExtensions.toSet()
        val tgtModExts = tgt.modifierExtensions.toSet()
        for (ext in tgtModExts - srcModExts) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.EXTENSION,
                    sourcePath = path, targetPath = "$path.modifierExtension($ext)",
                    description = "Modifier extension added in target: $ext",
                    severity = "ERROR",
                    source = "rule"
                )
            )
        }
        for (ext in srcModExts - tgtModExts) {
            out.add(
                DriftItem(
                    id = nextId(),
                    type = DriftType.EXTENSION,
                    sourcePath = "$path.modifierExtension($ext)", targetPath = path,
                    description = "Modifier extension removed in target: $ext",
                    severity = "ERROR",
                    source = "rule"
                )
            )
        }
        return out
    }

    /* =====================================================================
     * Added / removed element item builders
     * ===================================================================== */

    private fun buildAddedElementItem(path: String, tgt: ElementSummary, id: String): DriftItem {
        return if (isExtensionSlicePath(path)) {
            DriftItem(
                id = id,
                type = DriftType.EXTENSION,
                sourcePath = "", targetPath = path,
                description = "Extension slice added in target profile: $path" +
                        (if (tgt.min > 0) " (required)" else ""),
                severity = if (tgt.min > 0) "ERROR" else "WARNING",
                source = "rule"
            )
        } else {
            DriftItem(
                id = id,
                type = DriftType.STRUCTURAL,
                sourcePath = "", targetPath = path,
                description = if (tgt.sliceName != null)
                    "New slice '${tgt.sliceName}' added in target profile at $path"
                else
                    "Element added in target profile: $path",
                severity = if (tgt.min > 0) "ERROR" else "INFO",
                source = "rule"
            )
        }
    }

    private fun buildRemovedElementItem(path: String, src: ElementSummary, id: String): DriftItem {
        val severity = if (src.min > 0 || src.mustSupport) "WARNING" else "INFO"
        val noteSuffix =
            if (src.min > 0) " (was required)"
            else if (src.mustSupport) " (was mustSupport)"
            else ""
        return if (isExtensionSlicePath(path)) {
            DriftItem(
                id = id,
                type = DriftType.EXTENSION,
                sourcePath = path, targetPath = "",
                description = "Extension slice removed in target profile: $path$noteSuffix",
                severity = severity,
                source = "rule"
            )
        } else {
            DriftItem(
                id = id,
                type = DriftType.STRUCTURAL,
                sourcePath = path, targetPath = "",
                description = if (src.sliceName != null)
                    "Slice '${src.sliceName}' removed in target profile at $path"
                else
                    "Element removed in target profile: $path$noteSuffix",
                severity = severity,
                source = "rule"
            )
        }
    }

    /* =====================================================================
     * Path utilities
     * ===================================================================== */

    /**
     * Returns true when [path] designates a named extension or modifier-extension
     * slice (e.g. `Patient.extension:employeeId`, `Org.identifier:hpio.extension:NPI`,
     * `Obs.modifierExtension:approved`).
     */
    private fun isExtensionSlicePath(path: String): Boolean =
        path.contains(".extension:") || path.contains(".modifierExtension:")

    /**
     * From a set of paths, suppress those whose direct or transitive parent path
     * is also in the set. Prevents emitting one item per sub-element when an entire
     * subtree was added or removed - the topmost element captures the change.
     * Children with their OWN independent drift are emitted by the per-element pass.
     */
    private fun suppressChildrenWhenParentInSet(paths: Set<String>): Set<String> =
        paths.filterTo(mutableSetOf()) { path ->
            paths.none { other -> other != path && path.startsWith("$other.") }
        }

    /**
     * Returns true when target max is strictly tighter than source max.
     * Examples: "*" -> "1" tighter, "3" -> "1" tighter, "1" -> "*" looser.
     */
    private fun isMaxTighter(srcMax: String, tgtMax: String): Boolean {
        if (srcMax == tgtMax) return false
        if (srcMax == "*") return tgtMax != "*"
        if (tgtMax == "*") return false
        val s = srcMax.toIntOrNull() ?: return false
        val t = tgtMax.toIntOrNull() ?: return false
        return t < s
    }
}
