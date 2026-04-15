package com.example.fdd.ai.impl

import com.example.fdd.ai.LlmResponseCache
import com.example.fdd.config.FddProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant

/**
 * File-based LLM response cache for experiment reproducibility.
 *
 * Caches LLM responses using a SHA-256 hash of (systemPrompt + userPrompt + temperature)
 * as the key. When a match is found, the cached response is returned directly.
 *
 * Controlled by `fdd.cache.*` properties:
 * - `enabled`: toggle caching on/off (default: true)
 * - `ttl-minutes`: evict entries older than N minutes (0 = no expiry)
 * - `directory`: storage directory (default: `.fdd-cache`)
 *
 * The cache is cleared on each application startup.
 */
@Component
class LlmResponseCacheImpl(
    private val properties: FddProperties,
    private val objectMapper: ObjectMapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
) : LlmResponseCache {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheDir: Path get() = Path.of(properties.cache.directory)
    private val enabled: Boolean get() = properties.cache.enabled

    /**
     * Clear cache on application startup so every restart begins with a clean state.
     */
    init {
        clear()
        if (enabled) {
            log.info("LLM response cache enabled (dir={}, ttl={}min)", cacheDir, properties.cache.ttlMinutes)
        } else {
            log.info("LLM response cache is DISABLED")
        }
    }

    /**
     * Look up a cached response for the given prompt combination.
     * @return cached response text, or `null` if not cached or caching is disabled
     */
    override fun get(systemPrompt: String, userPrompt: String, temperature: Double?): String? {
        if (!enabled) return null

        val key = cacheKey(systemPrompt, userPrompt, temperature)
        val file = cacheDir.resolve("$key.json")
        if (!Files.exists(file)) return null

        return try {
            val entry = objectMapper.readValue(file.toFile(), CacheEntry::class.java)

            // Check TTL
            if (isExpired(entry)) {
                log.debug("Cache entry {} expired, evicting", key.take(12))
                Files.deleteIfExists(file)
                return null
            }

            log.debug("Cache HIT for key={}", key.take(12))
            entry.response
        } catch (e: Exception) {
            log.warn("Corrupt cache entry {}, ignoring: {}", key.take(12), e.message)
            null
        }
    }

    /**
     * Store a response in the cache. Not if caching is disabled.
     */
    override fun put(systemPrompt: String, userPrompt: String, temperature: Double?, response: String) {
        if (!enabled) return

        val key = cacheKey(systemPrompt, userPrompt, temperature)
        val file = cacheDir.resolve("$key.json")
        try {
            if (!Files.exists(cacheDir)) Files.createDirectories(cacheDir)
            val entry = CacheEntry(
                systemPromptHash = sha256(systemPrompt).take(16),
                userPromptHash = sha256(userPrompt).take(16),
                temperature = temperature,
                response = response,
                createdAt = Instant.now().toEpochMilli()
            )
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entry)
            log.debug("Cache STORE for key={}", key.take(12))
        } catch (e: Exception) {
            log.warn("Failed to write cache entry {}: {}", key.take(12), e.message)
        }
    }

    /**
     * Clear the entire cache directory.
     */
    override fun clear() {
        if (Files.exists(cacheDir)) {
            Files.walk(cacheDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
            log.info("LLM response cache cleared")
        }
        Files.createDirectories(cacheDir)
    }

    /**
     * Number of entries currently in the cache.
     */
    override fun size(): Int =
        if (Files.exists(cacheDir)) {
            Files.list(cacheDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.count().toInt()
            }
        } else 0

    /**
     * Scheduled task to evict expired cache entries.
     * Runs every 10 minutes. Only effective when `fdd.cache.ttl-minutes > 0`.
     */
    @Scheduled(fixedRateString = "\${fdd.cache.eviction-interval-ms:600000}")
    override fun evictExpiredEntries() {
        val ttl = properties.cache.ttlMinutes
        if (!enabled || ttl <= 0 || !Files.exists(cacheDir)) return

        var evicted = 0
        Files.list(cacheDir).use { stream ->
            stream.filter { it.toString().endsWith(".json") }.forEach { file ->
                try {
                    val entry = objectMapper.readValue(file.toFile(), CacheEntry::class.java)
                    if (isExpired(entry)) {
                        Files.deleteIfExists(file)
                        evicted++
                    }
                } catch (_: Exception) {
                    Files.deleteIfExists(file)
                    evicted++
                }
            }
        }
        if (evicted > 0) log.info("Evicted {} expired cache entries", evicted)
    }

    private fun isExpired(entry: CacheEntry): Boolean {
        val ttl = properties.cache.ttlMinutes
        if (ttl <= 0) return false
        val age = Instant.now().toEpochMilli() - entry.createdAt
        return age > ttl * 60_000
    }

    private fun cacheKey(systemPrompt: String, userPrompt: String, temperature: Double?): String {
        val composite = "$systemPrompt\n---\n$userPrompt\n---\n${temperature ?: "default"}"
        return sha256(composite)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    data class CacheEntry(
        val systemPromptHash: String,
        val userPromptHash: String,
        val temperature: Double?,
        val response: String,
        val createdAt: Long = 0
    )
}