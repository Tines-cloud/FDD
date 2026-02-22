package com.example.fdd.service

import com.example.fdd.model.DriftItem
import com.example.fdd.model.ProfileContext

/**
 * Interface for deterministic, rule-based drift detection.
 *
 * This runs before the LLM is called. Hard-coded structural rules
 * identify obvious drift items quickly, and those results are:
 *
 * 1. Passed to the LLM as seed items for better accuracy.
 * 2. Used as a baseline when comparing with LLM-detected drift.
 * 3. The fallback result if the LLM is unavailable.
 *
 * Both this detector and the LLM receive the same [ProfileContext] so
 * they compare identical data.
 */
interface RuleBasedDriftDetector {

    /**
     * Run deterministic structural comparison between two profiles.
     *
     * @param context Normalised profile context containing element summaries
     *                for both the source and target profiles.
     * @return A list of [DriftItem]s detected by rules alone.
     */
    fun detect(context: ProfileContext): List<DriftItem>
}
