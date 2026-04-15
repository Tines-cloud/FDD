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
     */
    fun loadAll(): List<GoldStandardPair> {
        val resolver = PathMatchingResourcePatternResolver()
        return try {
            val resources = resolver.getResources("classpath:gold-standard/*.json")
            resources.mapNotNull { resource ->
                try {
                    resource.inputStream.use {
                        objectMapper.readValue(it, GoldStandardPair::class.java)
                    }
                } catch (ex: Exception) {
                    log.warn("Failed to load gold standard file: {}", resource.filename, ex)
                    null
                }
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
     */
    fun evaluate(predicted: DriftReport, gold: GoldStandardPair): EvaluationMetrics {
        val goldSet = gold.drifts.map { Triple(it.type, it.sourcePath, it.targetPath) }.toSet()
        val predSet = predicted.items.map { Triple(it.type, it.sourcePath, it.targetPath) }.toSet()

        val tp = predSet.intersect(goldSet).size
        val fp = predSet.size - tp
        val fn = goldSet.size - tp

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
