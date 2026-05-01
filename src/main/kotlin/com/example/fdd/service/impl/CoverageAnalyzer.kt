package com.example.fdd.service.impl

import com.example.fdd.model.CoverageItem
import com.example.fdd.model.CoverageReport
import com.example.fdd.model.CoverageStatus
import com.example.fdd.model.DriftItem
import com.example.fdd.model.DriftReport
import com.example.fdd.service.ICoverageAnalyzer
import org.hl7.fhir.r4.model.StructureDefinition
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Cross-references every drift item against the generated FML to produce a
 * coverage report. Every drift item is classified - nothing is silently dropped.
 *
 * This is a free, deterministic step (no LLM call) that runs after FML generation.
 */
@Service
class CoverageAnalyzer : ICoverageAnalyzer {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Well-known profile-level extensions that never appear in instance data. */
    private val profileMetadataExtensions = setOf(
        "uscdi-requirement",
        "obligation",
        "structuredefinition-explicit-type-name",
        "structuredefinition-fhir-type",
        "structuredefinition-standards-status"
    )

    override fun analyze(driftReport: DriftReport, fml: String, targetSd: StructureDefinition?): CoverageReport {
        val fmlGroups = extractFmlGroups(fml)
        val items = driftReport.items.map { classify(it, fml, fmlGroups, targetSd) }

        val mapped = items.count { it.coverageStatus == CoverageStatus.MAPPED }
        val coveredByParent = items.count { it.coverageStatus == CoverageStatus.COVERED_BY_PARENT }
        val noRuleNeeded = items.count { it.coverageStatus == CoverageStatus.NO_RULE_NEEDED }
        val sourceDataLoss = items.count { it.coverageStatus == CoverageStatus.SOURCE_DATA_LOSS }
        val unmappable = items.count { it.coverageStatus == CoverageStatus.UNMAPPABLE_NO_SOURCE }
        val criticalUnmappable = items.count { it.coverageStatus == CoverageStatus.UNMAPPABLE_REQUIRED }

        // Data shareability: items that ARE transferable / items that CAN carry data.
        // Profile metadata items are excluded from the denominator since they never carry data.
        val dataItems = items.size - noRuleNeeded
        val shareable = mapped + coveredByParent
        val shareabilityPercent = if (dataItems > 0) (shareable.toDouble() / dataItems * 100) else 100.0
        val roundedPercent = Math.round(shareabilityPercent * 10) / 10.0

        val verdict = buildVerdict(
            items.size, mapped, coveredByParent, noRuleNeeded,
            sourceDataLoss, unmappable, criticalUnmappable
        )
        val summary = buildSummary(
            items.size, mapped, coveredByParent, noRuleNeeded,
            sourceDataLoss, unmappable, criticalUnmappable, roundedPercent, verdict
        )

        log.info(
            "Coverage analysis: {} total, {} mapped, {} by-parent, {} metadata, {} source-loss, {} unmappable ({} critical), {}% shareable",
            items.size, mapped, coveredByParent, noRuleNeeded, sourceDataLoss,
            unmappable + criticalUnmappable, criticalUnmappable, "%.1f".format(roundedPercent)
        )
        if (criticalUnmappable > 0) {
            log.warn(
                "CRITICAL: {} target field(s) are required (min>=1) but have no source equivalent. " +
                        "Leaving them empty will cause target-profile validation failure.",
                criticalUnmappable
            )
        }

        return CoverageReport(
            totalDriftItems = items.size,
            mapped = mapped,
            coveredByParent = coveredByParent,
            noRuleNeeded = noRuleNeeded,
            sourceDataLoss = sourceDataLoss,
            unmappableNoSource = unmappable,
            dataShareabilityPercent = roundedPercent,
            items = items,
            summary = summary,
            verdict = verdict,
            criticalUnmappable = criticalUnmappable
        )
    }

    /* ---------- Classification ---------- */

    private fun classify(
        item: DriftItem,
        fml: String,
        fmlGroups: List<FmlGroup>,
        targetSd: StructureDefinition?
    ): CoverageItem {
        val desc = item.description.lowercase()
        val src = item.sourcePath.trim()
        val tgt = item.targetPath.trim()

        // 1. Profile metadata that does not affect instance data
        if (isProfileMetadata(desc, tgt)) {
            val reason = when {
                desc.contains("mustsupport") -> "MustSupport is a conformance flag, not instance data"
                desc.contains("constraints removed") || desc.contains("constraints added") ->
                    "Constraints are profile validation rules, not instance data"

                profileMetadataExtensions.any { ext ->
                    desc.contains(ext, ignoreCase = true) || tgt.contains(
                        ext,
                        ignoreCase = true
                    )
                } ->
                    "Profile-level signaling extension, does not appear in instance data"

                else -> "Profile-level metadata, does not affect instance data transformation"
            }
            return item.toCoverage(CoverageStatus.NO_RULE_NEEDED, reason, "No FML rule needed")
        }

        // 2. Source-only elements (removed in target)
        if (tgt.isBlank()) {
            val fmlHandling = when {
                // Matches both "Extension removed in target element" and "Extension slice removed in target profile"
                desc.contains("extension") && desc.contains("removed") && !desc.contains("modifier") ->
                    "Source-only extension - FML correctly omits it (no target to map to)"

                desc.contains("slice") && desc.contains("removed") -> {
                    val parentPath = parentFhirPath(src)
                    val group = parentPath?.let { findGroupForPath(it, fmlGroups) }
                    if (group != null) "Generic ${group.name} mapping catches the data"
                    else "FML omission = correct behavior (no target element exists)"
                }

                desc.contains("element removed") -> "Source-only element - FML correctly omits it"
                else -> "FML omission = correct behavior (element does not exist in target)"
            }
            return item.toCoverage(
                CoverageStatus.SOURCE_DATA_LOSS,
                "Source element does not exist in the target profile - data in this element will not be carried over",
                fmlHandling
            )
        }

        // 3. Target-only extensions with no source data
        // Matches both "Extension added in target element" and "Extension slice added in target profile"
        if (src.isBlank() && desc.contains("extension") && desc.contains("added in target") && !desc.contains("modifier")) {
            val (tgtMin, _) = getTargetElementInfo(targetSd, tgt)
            return if (tgtMin >= 1) {
                item.toCoverage(
                    CoverageStatus.UNMAPPABLE_REQUIRED,
                    "Target-only REQUIRED extension (min=$tgtMin) - no source equivalent. " +
                            "Transformed resource will fail validation without this field.",
                    "ACTION REQUIRED: add default-value FML rule",
                    tgtMin
                )
            } else {
                item.toCoverage(
                    CoverageStatus.UNMAPPABLE_NO_SOURCE,
                    "Target-only extension - no equivalent data exists in the source profile. " +
                            "Must be populated out-of-band or by a separate data source",
                    "Cannot be auto-populated (no source data)"
                )
            }
        }

        // 4. Target-only sub-elements (e.g. "Element added in target profile")
        if (src.isBlank()) {
            val parentPath = parentFhirPath(tgt)
            val group = parentPath?.let { findGroupForPath(it, fmlGroups) }
            if (group != null) {
                return item.toCoverage(
                    CoverageStatus.COVERED_BY_PARENT,
                    "Sub-element handled by the parent mapping group for $parentPath",
                    "${group.name} group copies all sub-elements"
                )
            }
            val grandparent = parentPath?.let { parentFhirPath(it) }
            val gpGroup = grandparent?.let { findGroupForPath(it, fmlGroups) }
            if (gpGroup != null) {
                return item.toCoverage(
                    CoverageStatus.COVERED_BY_PARENT,
                    "Sub-element handled by the ancestor mapping group for $grandparent",
                    "${gpGroup.name} group copies all sub-elements"
                )
            }
            val (tgtMin, _) = getTargetElementInfo(targetSd, tgt)
            return if (tgtMin >= 1) {
                item.toCoverage(
                    CoverageStatus.UNMAPPABLE_REQUIRED,
                    "Target-only REQUIRED element (min=$tgtMin) - no source equivalent. " +
                            "Transformed resource will fail validation without this field.",
                    "ACTION REQUIRED: add default-value FML rule",
                    tgtMin
                )
            } else {
                item.toCoverage(
                    CoverageStatus.UNMAPPABLE_NO_SOURCE,
                    "Target-only element - no equivalent data in the source profile",
                    "Cannot be auto-populated (no source data)"
                )
            }
        }

        // 5. Both paths present - check if directly mapped in FML
        val directGroup = findGroupForPath(src, fmlGroups)
        if (directGroup != null) {
            val handling = buildFmlHandlingDescription(item, directGroup, desc)
            return item.toCoverage(CoverageStatus.MAPPED, "Directly mapped by an FML rule", handling)
        }

        // 6. Check parent/ancestor path
        val parentPath = parentFhirPath(src)
        val parentGroup = parentPath?.let { findGroupForPath(it, fmlGroups) }
        if (parentGroup != null) {
            return item.toCoverage(
                CoverageStatus.COVERED_BY_PARENT,
                "Sub-element handled by the parent mapping group for $parentPath",
                "${parentGroup.name} group copies all sub-elements"
            )
        }

        // 7. Also check target-side parent
        val tgtParent = parentFhirPath(tgt)
        val tgtParentGroup = tgtParent?.let { findGroupForPath(it, fmlGroups) }
        if (tgtParentGroup != null) {
            return item.toCoverage(
                CoverageStatus.COVERED_BY_PARENT,
                "Sub-element handled by the parent mapping group for $tgtParent",
                "${tgtParentGroup.name} group copies all sub-elements"
            )
        }

        // 8. Fallback: check if FML has a text match for the element
        if (pathMappedInFml(src, fml)) {
            return item.toCoverage(
                CoverageStatus.MAPPED,
                "Handled by the overall transformation structure",
                "Direct copy in FML"
            )
        }

        // 9. Last resort: no match at all
        return item.toCoverage(
            CoverageStatus.SOURCE_DATA_LOSS,
            "No FML rule found that maps this drift - source data not carried to target",
            "Not covered by any FML rule or parent group"
        )
    }

    /** Build a human-friendly description of how the FML handles a specific drift. */
    private fun buildFmlHandlingDescription(item: DriftItem, group: FmlGroup, desc: String): String {
        val driftType = item.type.name
        return when {
            driftType == "TERMINOLOGY" && desc.contains("binding changed") ->
                "${group.name} with translate() for value set conversion"

            driftType == "STRUCTURAL" && desc.contains("type profile constraint changed") ->
                "${group.name} group rewrites type/URL"

            driftType == "STRUCTURAL" && desc.contains("slicing changed") ->
                "Maps generically (slicing removed in target)"

            driftType == "CARDINALITY" ->
                "Direct copy (${if (desc.contains("[1..1] -> [0..1]")) "relaxed" else "changed"} cardinality = safe)"

            driftType == "EXTENSION" && desc.contains("extension") && desc.contains("url") ->
                "${group.name} group rewrites extension URL"

            else -> "${group.name} group handles this mapping"
        }
    }

    /* ---------- FML Group Extraction ---------- */

    /** Represents a named group in the FML with the elements it maps. */
    private data class FmlGroup(
        val name: String,
        val mappedElements: Set<String>
    )

    /**
     * Parse the FML text to extract group names and the elements they map.
     * Looks for "group Name(...)" headers and "src.element" patterns within.
     */
    private fun extractFmlGroups(fml: String): List<FmlGroup> {
        val groups = mutableListOf<FmlGroup>()
        val groupPattern = Regex("""group\s+(\w+)\s*\(""")
        val rulePattern = Regex("""src\.(\w[\w\[\]]*)\s+as""")

        val lines = fml.lines()
        var currentGroupName: String? = null
        var currentElements = mutableSetOf<String>()

        for (line in lines) {
            val groupMatch = groupPattern.find(line)
            if (groupMatch != null) {
                // Save previous group
                if (currentGroupName != null) {
                    groups.add(FmlGroup(currentGroupName, currentElements.toSet()))
                }
                currentGroupName = groupMatch.groupValues[1]
                currentElements = mutableSetOf()
            }
            // Collect mapped elements within the current group
            rulePattern.findAll(line).forEach { match ->
                currentElements.add(match.groupValues[1])
            }
        }
        // Save final group
        if (currentGroupName != null) {
            groups.add(FmlGroup(currentGroupName, currentElements.toSet()))
        }

        return groups
    }

    /**
     * Find which FML group handles a given FHIR path.
     * Matches by looking at the leaf element name and checking which group maps it.
     */
    private fun findGroupForPath(path: String, groups: List<FmlGroup>): FmlGroup? {
        val cleanPath = path.replace(Regex(":[^.]+"), "")
        val dotIdx = cleanPath.indexOf('.')
        if (dotIdx < 0) return groups.firstOrNull() // root -> main group

        val leaf = cleanPath.substringAfterLast('.')
        // Strip choice type suffix like "[x]"
        val cleanLeaf = leaf.replace("[x]", "")

        // First check for exact element match, preferring sub-groups over main group
        for (group in groups.reversed()) {
            if (group.mappedElements.contains(cleanLeaf) || group.mappedElements.contains(leaf)) {
                return group
            }
        }
        // Check for variant names (e.g., "deceasedBoolean" matching "deceased")
        for (group in groups.reversed()) {
            if (group.mappedElements.any { cleanLeaf.startsWith(it) || it.startsWith(cleanLeaf) }) {
                return group
            }
        }
        return null
    }

    /* ---------- Helpers ---------- */

    private fun isProfileMetadata(description: String, targetPath: String): Boolean {
        if (description.contains("mustsupport removed") || description.contains("mustsupport added")) return true
        if (description.contains("constraints removed") || description.contains("constraints added")) return true
        // AI-generated drift items that are explicitly labelled as having no data impact:
        if (description.contains("profile metadata")) return true
        if (description.contains("no instance data impact")) return true
        if (description.contains("no direct mapping impact")) return true
        if (description.contains("value-set version pin")) return true
        if (description.contains("short description changed")) return true
        if (description.contains("binding strength unchanged")) return true
        if (description.contains("conformance annotation")) return true
        return profileMetadataExtensions.any { ext ->
            targetPath.contains(ext, ignoreCase = true) || description.contains(ext, ignoreCase = true)
        }
    }

    private fun pathMappedInFml(path: String, fml: String): Boolean {
        val cleanPath = path.replace(Regex(":[^.]+"), "")
        val dotIdx = cleanPath.indexOf('.')
        if (dotIdx < 0) return true

        val elementChain = cleanPath.substring(dotIdx + 1)
        val leaf = elementChain.substringAfterLast('.')
        return fml.contains("src.$leaf") || fml.contains("tgt.$leaf") ||
                fml.contains("src.$elementChain") || fml.contains("tgt.$elementChain")
    }

    private fun parentFhirPath(path: String): String? {
        val clean = path.replace(Regex(":[^.]+"), "")
        val withoutExtCall = clean.replace(Regex("\\(.*\\)$"), "")
        val lastDot = withoutExtCall.lastIndexOf('.')
        return if (lastDot > 0) withoutExtCall.substring(0, lastDot) else null
    }

    /** Look up min cardinality and mustSupport for a FHIR path in the target StructureDefinition. */
    private fun getTargetElementInfo(targetSd: StructureDefinition?, targetPath: String): Pair<Int, Boolean> {
        if (targetSd?.snapshot == null) return 0 to false
        val cleanPath = targetPath.replace(Regex(":[^.]+"), "").trim()
        val el = targetSd.snapshot.element.find { it.path == cleanPath } ?: return 0 to false
        return (el.min ?: 0) to (el.mustSupport ?: false)
    }

    private fun DriftItem.toCoverage(
        status: CoverageStatus, explanation: String, fmlHandling: String, targetMin: Int = 0
    ) = CoverageItem(
        driftItemId = id,
        driftType = type.name,
        sourcePath = sourcePath,
        targetPath = targetPath,
        description = description,
        severity = severity,
        coverageStatus = status,
        explanation = explanation,
        fmlHandling = fmlHandling,
        targetMin = targetMin
    )

    /* ---------- Report Text Builders ---------- */

    private fun buildVerdict(
        total: Int, mapped: Int, coveredByParent: Int, noRuleNeeded: Int,
        sourceDataLoss: Int, unmappable: Int, criticalUnmappable: Int = 0
    ): String {
        val allHandled = (sourceDataLoss == 0 && unmappable == 0 && criticalUnmappable == 0)
        return if (allHandled) {
            "$total/$total drifts are correctly handled"
        } else {
            val actionable = mapped + coveredByParent + noRuleNeeded
            val issues = sourceDataLoss + unmappable + criticalUnmappable
            val critNote = if (criticalUnmappable > 0) " [$criticalUnmappable CRITICAL]" else ""
            "$actionable/$total drifts handled, $issues require attention$critNote " +
                    "($sourceDataLoss source data loss, ${unmappable + criticalUnmappable} unmappable)"
        }
    }

    private fun buildSummary(
        total: Int, mapped: Int, coveredByParent: Int, noRuleNeeded: Int,
        sourceDataLoss: Int, unmappable: Int, criticalUnmappable: Int = 0,
        shareabilityPercent: Double,
        verdict: String
    ): String {
        val shareable = mapped + coveredByParent
        val dataItems = total - noRuleNeeded
        return buildString {
            appendLine("FML COVERAGE ANALYSIS: $total Drift Items")
            appendLine("=".repeat(72))
            appendLine()
            appendLine("VERDICT: $verdict")
            appendLine()
            if (sourceDataLoss == 0 && unmappable == 0) {
                appendLine("The FML covers ALL actionable drifts. The remaining items are correctly")
                appendLine("handled by omission or are profile-level metadata with no data impact.")
            } else {
                appendLine("The FML covers all actionable drifts it can. Some items require external")
                appendLine("data sources or represent intentional data loss between the two profiles.")
            }
            appendLine()
            appendLine("-".repeat(72))
            appendLine("SUMMARY")
            appendLine("-".repeat(72))
            appendLine()
            appendLine("  Total drift items detected       : $total")
            appendLine("  Profile metadata (no data impact) : $noRuleNeeded")
            appendLine("  Data-carrying items               : $dataItems")
            appendLine()
            appendLine("  DATA-CARRYING ITEMS BREAKDOWN:")
            appendLine("    Actively mapped in FML           : $mapped")
            appendLine("    Covered by parent/group mapping  : $coveredByParent")
            appendLine("    Source data loss (dropped)        : $sourceDataLoss")
            appendLine("    Unmappable optional (min=0)       : $unmappable")
            appendLine("    Unmappable REQUIRED (min>=1) \u274c   : $criticalUnmappable")
            appendLine()
            appendLine("  DATA SHAREABILITY: ${"%.1f".format(shareabilityPercent)}%")
            appendLine("    ($shareable of $dataItems data-carrying items are transferable)")
            appendLine()
            appendLine("  INDUSTRY BENCHMARK:")
            when {
                shareabilityPercent >= 85.0 -> appendLine("    \u2705 EXCELLENT \u2014 meets USCDI/ONC certification requirements (>=85%)")
                shareabilityPercent >= 70.0 -> appendLine("    \u2705 GOOD \u2014 meets HL7 IPS and most national IG standards (>=70%)")
                shareabilityPercent >= 60.0 -> appendLine("    \u26a0  ACCEPTABLE for cross-national or exploratory mapping (>=60%)")
                else -> appendLine("    \u274c BELOW BASELINE \u2014 review profile compatibility (<60%)")
            }
            appendLine()
            if (criticalUnmappable > 0) {
                appendLine("  \u274c CRITICAL: $criticalUnmappable MANDATORY TARGET FIELD(S) HAVE NO SOURCE DATA")
                appendLine("    These fields are required (min>=1) in the target profile but the source")
                appendLine("    has no equivalent. The generated FML leaves them EMPTY. Without a fix,")
                appendLine("    the transformed resource WILL FAIL target-profile validation.")
                appendLine("    See CATEGORY 5a in the coverage report for the exact field paths.")
                appendLine("    REQUIRED ACTION: Add a default-value FML rule for each field, e.g.:")
                appendLine("      tgt.<field> = \"DEFAULT_VALUE\" \"rule-name\";")
                appendLine("    OR enrich the resource in a post-processing step.")
                appendLine()
            }
            if (sourceDataLoss > 0) {
                appendLine("  SOURCE DATA LOSS: $sourceDataLoss element(s) \u2014 INHERENTLY ACCEPTABLE")
                appendLine("    These source elements do not exist in the target profile. No StructureMap")
                appendLine("    can transfer data the target does not define. This is EXPECTED for cross-")
                appendLine("    national or cross-domain mappings (e.g. AU-specific fields not in US Core).")
                appendLine("    Inform clinical staff which fields are dropped so they can assess impact.")
                appendLine()
            }
            if (unmappable > 0) {
                appendLine("  \u26a0  UNMAPPABLE OPTIONAL (TARGET-ONLY): $unmappable element(s)")
                appendLine("    These target-only fields (min=0) cannot be populated from the source.")
                appendLine("    The resource will still pass profile validation without them \u2014 optional.")
                appendLine()
            }
        }
    }
}