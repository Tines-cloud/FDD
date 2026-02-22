package com.example.fdd.service

import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftType
import com.example.fdd.model.ProfileContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * Default rule-based drift detector implementing deterministic structural comparison.
 *
 * Covers **all five** [DriftType] categories:
 *
 * | # | Category       | Rules                                                       |
 * |---|----------------|-------------------------------------------------------------|
 * | 1 | CARDINALITY    | min/max changes                                             |
 * | 2 | TERMINOLOGY    | Binding strength, value-set URL, fixed/pattern value changes |
 * | 3 | STRUCTURAL     | Type code/profile/targetProfile changes, mustSupport,        |
 * |   |                | isModifier, isSummary, slicing, constraints,                 |
 * |   |                | contentReference, elements added/removed, slice changes      |
 * | 4 | EXTENSION      | Extensions and modifier-extensions added or removed          |
 * | 5 | VERSION        | FHIR version mismatch between profiles                      |
 *
 * This deterministic analysis runs in O(n) time (two indexed passes) and
 * provides consistent, reproducible results that complement the LLM's
 * semantic analysis.
 */
@Component
class DefaultRuleBasedDriftDetector : RuleBasedDriftDetector {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun detect(context: ProfileContext): List<DriftItem> {
        val items = mutableListOf<DriftItem>()
        var idCounter = 0

        fun nextId() = "rule-${++idCounter}"

        /**
         * Returns true when the target max is strictly tighter than the source max.
         * Examples: "*" -> "1" = tighter, "3" -> "1" = tighter, "1" -> "*" = looser.
         */
        fun isMaxTighter(srcMax: String, tgtMax: String): Boolean {
            if (srcMax == tgtMax) return false
            if (srcMax == "*") return tgtMax != "*"   // unbounded -> bounded = tighter
            if (tgtMax == "*") return false             // bounded -> unbounded = looser
            val srcInt = srcMax.toIntOrNull() ?: return false
            val tgtInt = tgtMax.toIntOrNull() ?: return false
            return tgtInt < srcInt
        }

        val sourceProfile = context.sourceProfile
        val targetProfile = context.targetProfile
        val sourceElements = sourceProfile.elements
        val targetElements = targetProfile.elements

        /* ================================================================
         * VERSION drift - profile-level FHIR version mismatch
         * ================================================================ */
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
                    severity = "ERROR"
                )
            )
        }

        /* ================================================================
         * Element-level rules - compare elements present in both profiles
         * ================================================================ */
        val sourceByPath = sourceElements.associateBy { it.path }
        val targetByPath = targetElements.associateBy { it.path }

        for ((path, src) in sourceByPath) {
            val tgt = targetByPath[path] ?: continue

            /* --- CARDINALITY --- */
            if (src.min != tgt.min || src.max != tgt.max) {
                val cardSeverity = when {
                    tgt.min > src.min -> "ERROR"                          // required field added or tightened
                    src.min > tgt.min -> "INFO"                           // requirement relaxed
                    isMaxTighter(src.max, tgt.max) -> "WARNING"           // max restricted (could truncate data)
                    else -> "INFO"                                         // max relaxed (no data loss)
                }
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.CARDINALITY,
                        sourcePath = path, targetPath = path,
                        description = "Cardinality changed: [${src.min}..${src.max}] -> [${tgt.min}..${tgt.max}]",
                        severity = cardSeverity
                    )
                )
            }

            /* --- TERMINOLOGY: binding strength --- */
            val srcBind = src.binding
            val tgtBind = tgt.binding
            if (srcBind?.strength != tgtBind?.strength || srcBind?.valueSet != tgtBind?.valueSet) {
                if (srcBind != null || tgtBind != null) {
                    items.add(
                        DriftItem(
                            id = nextId(),
                            type = DriftType.TERMINOLOGY,
                            sourcePath = path, targetPath = path,
                            description = "Binding changed: " +
                                    "${srcBind?.strength ?: "none"}(${srcBind?.valueSet ?: "none"}) -> " +
                                    "${tgtBind?.strength ?: "none"}(${tgtBind?.valueSet ?: "none"})",
                            severity = if (tgtBind?.strength == "required") "ERROR" else "WARNING"
                        )
                    )
                }
            }

            /* --- TERMINOLOGY: fixed value changes --- */
            if (src.fixedValue != tgt.fixedValue && (src.fixedValue != null || tgt.fixedValue != null)) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.TERMINOLOGY,
                        sourcePath = path, targetPath = path,
                        description = "Fixed value changed: '${src.fixedValue ?: "none"}' -> '${tgt.fixedValue ?: "none"}'",
                        severity = "ERROR"
                    )
                )
            }

            /* --- TERMINOLOGY: pattern value changes --- */
            if (src.patternValue != tgt.patternValue && (src.patternValue != null || tgt.patternValue != null)) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.TERMINOLOGY,
                        sourcePath = path, targetPath = path,
                        description = "Pattern value changed: '${src.patternValue ?: "none"}' -> '${tgt.patternValue ?: "none"}'",
                        severity = "WARNING"
                    )
                )
            }

            /* --- TERMINOLOGY: default value changes --- */
            if (src.defaultValue != tgt.defaultValue && (src.defaultValue != null || tgt.defaultValue != null)) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.TERMINOLOGY,
                        sourcePath = path, targetPath = path,
                        description = "Default value changed: '${src.defaultValue ?: "none"}' -> '${tgt.defaultValue ?: "none"}'",
                        severity = "WARNING"
                    )
                )
            }

            /* --- STRUCTURAL: type code changes --- */
            val srcTypeCodes = src.types.map { it.code }.sorted()
            val tgtTypeCodes = tgt.types.map { it.code }.sorted()
            if (srcTypeCodes != tgtTypeCodes && srcTypeCodes.isNotEmpty() && tgtTypeCodes.isNotEmpty()) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Type changed: $srcTypeCodes -> $tgtTypeCodes",
                        severity = "WARNING"
                    )
                )
            }

            /* --- STRUCTURAL: type profile constraints changed --- */
            val srcProfiles = src.types.flatMap { it.profiles }.sorted()
            val tgtProfiles = tgt.types.flatMap { it.profiles }.sorted()
            if (srcProfiles != tgtProfiles && (srcProfiles.isNotEmpty() || tgtProfiles.isNotEmpty())) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Type profile constraint changed: $srcProfiles -> $tgtProfiles",
                        severity = "WARNING"
                    )
                )
            }

            /* --- STRUCTURAL: type target-profile constraints changed --- */
            val srcTargetProfiles = src.types.flatMap { it.targetProfiles }.sorted()
            val tgtTargetProfiles = tgt.types.flatMap { it.targetProfiles }.sorted()
            if (srcTargetProfiles != tgtTargetProfiles &&
                (srcTargetProfiles.isNotEmpty() || tgtTargetProfiles.isNotEmpty())
            ) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Reference target profile changed: $srcTargetProfiles -> $tgtTargetProfiles",
                        severity = "WARNING"
                    )
                )
            }

            /* --- STRUCTURAL: mustSupport flag changed --- */
            if (src.mustSupport != tgt.mustSupport) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = if (tgt.mustSupport) "MustSupport added in target" else "MustSupport removed in target",
                        severity = "INFO"
                    )
                )
            }

            /* --- STRUCTURAL: isModifier flag changed --- */
            if (src.isModifier != tgt.isModifier) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "IsModifier changed: ${src.isModifier} -> ${tgt.isModifier}",
                        severity = "ERROR"
                    )
                )
            }

            /* --- STRUCTURAL: isSummary flag changed --- */
            if (src.isSummary != tgt.isSummary) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "IsSummary changed: ${src.isSummary} -> ${tgt.isSummary}",
                        severity = "INFO"
                    )
                )
            }

            /* --- STRUCTURAL: slicing rules changed --- */
            if (src.slicing != tgt.slicing && (src.slicing != null || tgt.slicing != null)) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Slicing changed: " +
                                "${src.slicing?.let { "rules=${it.rules}, discriminators=${it.discriminators}" } ?: "none"} -> " +
                                "${tgt.slicing?.let { "rules=${it.rules}, discriminators=${it.discriminators}" } ?: "none"}",
                        severity = "WARNING"
                    )
                )
            }

            /* --- STRUCTURAL: constraints (invariants) changed --- */
            val srcConstraintKeys = src.constraints.map { it.key }.toSet()
            val tgtConstraintKeys = tgt.constraints.map { it.key }.toSet()
            val addedConstraints = tgtConstraintKeys - srcConstraintKeys
            val removedConstraints = srcConstraintKeys - tgtConstraintKeys
            if (addedConstraints.isNotEmpty()) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Constraints added in target: $addedConstraints",
                        severity = "WARNING"
                    )
                )
            }
            if (removedConstraints.isNotEmpty()) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Constraints removed in target: $removedConstraints",
                        severity = "WARNING"
                    )
                )
            }

            /* --- STRUCTURAL: constraint expression / severity changed for shared keys --- */
            val sharedConstraintKeys = srcConstraintKeys.intersect(tgtConstraintKeys)
            for (key in sharedConstraintKeys) {
                val srcC = src.constraints.first { it.key == key }
                val tgtC = tgt.constraints.first { it.key == key }
                if (srcC.expression != tgtC.expression || srcC.severity != tgtC.severity) {
                    items.add(
                        DriftItem(
                            id = nextId(),
                            type = DriftType.STRUCTURAL,
                            sourcePath = path, targetPath = path,
                            description = "Constraint '$key' modified: " +
                                    "expression '${srcC.expression ?: "none"}' -> '${tgtC.expression ?: "none"}', " +
                                    "severity '${srcC.severity ?: "none"}' -> '${tgtC.severity ?: "none"}'",
                            severity = if (tgtC.severity == "error") "ERROR" else "WARNING"
                        )
                    )
                }
            }

            /* --- STRUCTURAL: contentReference changed --- */
            if (src.contentReference != tgt.contentReference &&
                (src.contentReference != null || tgt.contentReference != null)
            ) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = path,
                        description = "Content reference changed: '${src.contentReference ?: "none"}' -> '${tgt.contentReference ?: "none"}'",
                        severity = "WARNING"
                    )
                )
            }

            /* --- EXTENSION: element-level extensions --- */
            val srcExts = src.extensions.toSet()
            val tgtExts = tgt.extensions.toSet()
            for (ext in tgtExts - srcExts) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.EXTENSION,
                        sourcePath = path, targetPath = "$path.extension($ext)",
                        description = "Extension added in target element: $ext",
                        severity = "WARNING"
                    )
                )
            }
            for (ext in srcExts - tgtExts) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.EXTENSION,
                        sourcePath = "$path.extension($ext)", targetPath = path,
                        description = "Extension removed in target element: $ext",
                        severity = "WARNING"
                    )
                )
            }

            /* --- EXTENSION: modifier extensions --- */
            val srcModExts = src.modifierExtensions.toSet()
            val tgtModExts = tgt.modifierExtensions.toSet()
            for (ext in tgtModExts - srcModExts) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.EXTENSION,
                        sourcePath = path, targetPath = "$path.modifierExtension($ext)",
                        description = "Modifier extension added in target: $ext",
                        severity = "ERROR"
                    )
                )
            }
            for (ext in srcModExts - tgtModExts) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.EXTENSION,
                        sourcePath = "$path.modifierExtension($ext)", targetPath = path,
                        description = "Modifier extension removed in target: $ext",
                        severity = "ERROR"
                    )
                )
            }
        }

        /* ================================================================
         * STRUCTURAL / EXTENSION: elements present in only one profile
         * ================================================================ */

        /**
         * Returns true if [path] is a named extension or modifier-extension slice,
         * e.g. "Patient.extension:employeeId" or "Obs.modifierExtension:approved".
         */
        fun isExtensionSlicePath(path: String) =
            path.contains(".extension:") || path.contains(".modifierExtension:")

        /**
         * Suppress child paths when a parent path is already in the set.
         * This prevents emitting "Patient.identifier.id", "Patient.identifier.use", …
         * when "Patient.identifier" is already flagged as added/removed.
         * We still report children that have their own cardinality/terminology
         * drift (handled in the per-element loop above).
         */
        fun topLevelOnly(paths: Set<String>): Set<String> =
            paths.filterTo(mutableSetOf()) { path ->
                paths.none { parent -> parent != path && path.startsWith("$parent.") }
            }

        // Elements added in target
        val newPaths = targetByPath.keys - sourceByPath.keys
        for (path in topLevelOnly(newPaths)) {
            val tgt = targetByPath[path]!!
            if (isExtensionSlicePath(path)) {
                // Named extension added in target
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.EXTENSION,
                        sourcePath = "", targetPath = path,
                        description = "Extension added in target profile: $path" +
                                (if (tgt.min > 0) " (required)" else ""),
                        severity = if (tgt.min > 0) "ERROR" else "WARNING"
                    )
                )
            } else {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = "", targetPath = path,
                        description = if (tgt.sliceName != null)
                            "New slice '${tgt.sliceName}' added in target profile"
                        else
                            "Element added in target profile: $path",
                        severity = if (tgt.min > 0) "ERROR" else "INFO"
                    )
                )
            }
        }

        // Elements removed from source (present in source but absent in target)
        val removedPaths = sourceByPath.keys - targetByPath.keys
        for (path in topLevelOnly(removedPaths)) {
            val src = sourceByPath[path]!!
            // An element is noteworthy if it was required (min > 0) or flagged mustSupport
            val removedSeverity = if (src.min > 0 || src.mustSupport) "WARNING" else "INFO"
            if (isExtensionSlicePath(path)) {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.EXTENSION,
                        sourcePath = path, targetPath = "",
                        description = "Extension removed in target profile: $path" +
                                (if (src.min > 0) " (was required)" else if (src.mustSupport) " (was mustSupport)" else ""),
                        severity = removedSeverity
                    )
                )
            } else {
                items.add(
                    DriftItem(
                        id = nextId(),
                        type = DriftType.STRUCTURAL,
                        sourcePath = path, targetPath = "",
                        description = if (src.sliceName != null)
                            "Slice '${src.sliceName}' removed in target profile"
                        else
                            "Element removed in target profile: $path" +
                                (if (src.min > 0) " (was required)" else if (src.mustSupport) " (was mustSupport)" else ""),
                        severity = removedSeverity
                    )
                )
            }
        }

        log.info("Rule-based analysis detected {} drift items across all 5 categories", items.size)
        return items
    }
}
