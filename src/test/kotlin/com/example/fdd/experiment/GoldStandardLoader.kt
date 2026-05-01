package com.example.fdd.experiment

import com.example.fdd.model.DriftReport
import com.example.fdd.model.GoldStandardPair
import org.slf4j.LoggerFactory
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule

/**
 * Utility for loading gold-standard drift annotations from
 * classpath JSON files and computing evaluation metrics.
 */
object GoldStandardLoader {

    private val log = LoggerFactory.getLogger(javaClass)

    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()

    /**
     * Load all gold-standard pairs from the test classpath.
     *
     * Loads JSON files from `gold-standard/`
     */
    fun loadAll(): List<GoldStandardPair> {
        val resolver = PathMatchingResourcePatternResolver()
        return try {
            val resources = resolver.getResources("classpath*:gold-standard*/*.json")
            val byPair = linkedMapOf<String, GoldStandardPair>()
            for (resource in resources) {
                try {
                    val pair = resource.inputStream.use {
                        objectMapper.readValue(it, GoldStandardPair::class.java)
                    }
                    val existing = byPair[pair.pairId]
                    val isManual = resource.url?.toString()?.contains("gold-standard") == true
                    if (existing == null || isManual) {
                        byPair[pair.pairId] = pair
                    }
                } catch (ex: Exception) {
                    log.warn("Failed to load gold standard file: {}", resource.filename, ex)
                }
            }
            byPair.values.toList().also {
                log.info("Loaded {} gold-standard pair(s) from classpath", it.size)
            }
        } catch (ex: Exception) {
            log.warn("No gold-standard resources found", ex)
            emptyList()
        }
    }

    /**
     * Compute precision, recall, and F1 between a predicted [DriftReport]
     * and a gold-standard [GoldStandardPair].
     *
     * Match criteria: same [DriftType], sourcePath, and targetPath.
     * Null and empty-string paths are treated as equivalent, since gold files
     * may serialise "no path" as `null` while the rule-based detector emits "".
     *
     * **Multiset matching**: a gold standard file may legally have two items with
     * the same (type, sourcePath, targetPath) triple but different semantic reasons
     * (e.g. mustSupport change AND a new constraint on the same element path).
     * Converting to a Set would collapse those, under-counting the gold denominator
     * and inflating recall.  We therefore count occurrences and take the per-triple
     * minimum of gold vs predicted counts to determine true positives.
     */
    fun evaluate(predicted: DriftReport, gold: GoldStandardPair): EvaluationMetrics {
        fun norm(p: String?): String = p?.trim().orEmpty()

        // Multiset: count how many times each triple appears
        val goldCounts = gold.drifts
            .map { Triple(it.type, norm(it.sourcePath), norm(it.targetPath)) }
            .groupingBy { it }.eachCount()
        val predCounts = predicted.items
            .map { Triple(it.type, norm(it.sourcePath), norm(it.targetPath)) }
            .groupingBy { it }.eachCount()

        // TP = sum over all distinct triples of min(goldCount, predCount)
        val tp = goldCounts.entries.sumOf { (triple, goldCount) ->
            minOf(goldCount, predCounts.getOrDefault(triple, 0))
        }
        val fp = predicted.items.size - tp
        val fn = gold.drifts.size - tp

        val precision = if (tp + fp > 0) tp.toDouble() / (tp + fp) else 0.0
        val recall = if (tp + fn > 0) tp.toDouble() / (tp + fn) else 0.0
        val f1 = if (precision + recall > 0) 2 * precision * recall / (precision + recall) else 0.0

        return EvaluationMetrics(
            pairId = gold.pairId,
            sourceClasspath = gold.sourceClasspath,
            targetClasspath = gold.targetClasspath,
            truePositives = tp,
            falsePositives = fp,
            falseNegatives = fn,
            precision = precision,
            recall = recall,
            f1 = f1
        )
    }
}

/**
 * Drift detection evaluation metrics for a single profile pair.
 */
data class EvaluationMetrics(
    val pairId: String,
    val sourceClasspath: String,
    val targetClasspath: String,
    val truePositives: Int,
    val falsePositives: Int,
    val falseNegatives: Int,
    val precision: Double,
    val recall: Double,
    val f1: Double
)
