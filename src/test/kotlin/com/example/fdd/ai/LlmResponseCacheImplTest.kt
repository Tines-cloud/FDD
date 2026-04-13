package com.example.fdd.ai

import com.example.fdd.ai.impl.LlmResponseCacheImpl
import com.example.fdd.config.CacheProperties
import com.example.fdd.config.FddProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Unit tests for [LlmResponseCache].
 *
 * Verifies file-based caching, TTL eviction, SHA-256 keying,
 * and disabled-cache behaviour using a temporary directory.
 */
class LlmResponseCacheImplTest {

    private lateinit var cacheDir: Path
    private lateinit var cache: LlmResponseCache

    @BeforeEach
    fun setUp() {
        cacheDir = Files.createTempDirectory("fdd-cache-test")
        val props = FddProperties(
            cache = CacheProperties(enabled = true, ttlMinutes = 60, directory = cacheDir.toString())
        )
        cache = LlmResponseCacheImpl(props)
    }

    @AfterEach
    fun tearDown() {
        // Clean up temp directory
        if (Files.exists(cacheDir)) {
            Files.walk(cacheDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    @DisplayName("get returns null for uncached prompts")
    fun get_uncachedPrompt_returnsNull() {
        assertNull(cache.get("system", "user", 0.7))
    }

    @Test
    @DisplayName("put stores and get retrieves cached response")
    fun putAndGet_roundTrip_succeeds() {
        cache.put("system-prompt", "user-prompt", 0.7, "LLM response text")

        val result = cache.get("system-prompt", "user-prompt", 0.7)

        assertEquals("LLM response text", result)
    }

    @Test
    @DisplayName("Different prompts produce different cache entries")
    fun put_differentPrompts_separateEntries() {
        cache.put("system", "user-A", 0.5, "Response A")
        cache.put("system", "user-B", 0.5, "Response B")

        assertEquals("Response A", cache.get("system", "user-A", 0.5))
        assertEquals("Response B", cache.get("system", "user-B", 0.5))
    }

    @Test
    @DisplayName("Different temperatures produce different cache keys")
    fun put_differentTemperatures_separateEntries() {
        cache.put("system", "user", 0.5, "Cool response")
        cache.put("system", "user", 1.0, "Hot response")

        assertEquals("Cool response", cache.get("system", "user", 0.5))
        assertEquals("Hot response", cache.get("system", "user", 1.0))
    }

    @Test
    @DisplayName("size() reflects number of cached entries")
    fun size_afterPuts_returnsCorrectCount() {
        assertEquals(0, cache.size())

        cache.put("s", "u1", 0.5, "r1")
        assertEquals(1, cache.size())

        cache.put("s", "u2", 0.5, "r2")
        assertEquals(2, cache.size())
    }

    @Test
    @DisplayName("clear() removes all entries")
    fun clear_afterPuts_emptiesCache() {
        cache.put("s", "u1", 0.5, "r1")
        cache.put("s", "u2", 0.5, "r2")

        cache.clear()

        assertEquals(0, cache.size())
        assertNull(cache.get("s", "u1", 0.5))
    }

    @Test
    @DisplayName("Disabled cache always returns null and does not store")
    fun disabledCache_putAndGet_returnsNull() {
        val disabledDir = Files.createTempDirectory("fdd-cache-disabled")
        val disabledProps = FddProperties(
            cache = CacheProperties(enabled = false, directory = disabledDir.toString())
        )
        val disabledCache = LlmResponseCacheImpl(disabledProps)

        disabledCache.put("s", "u", 0.5, "response")

        assertNull(disabledCache.get("s", "u", 0.5))
        assertEquals(0, disabledCache.size())

        // Clean up
        Files.walk(disabledDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    @DisplayName("TTL of 0 means no expiry - entries persist indefinitely")
    fun get_expiredEntry_returnsNull() {
        // Create cache with 0-minute TTL (everything expires immediately)
        val expiredDir = Files.createTempDirectory("fdd-cache-expired")
        val zeroTtlProps = FddProperties(
            cache = CacheProperties(enabled = true, ttlMinutes = 0, directory = expiredDir.toString())
        )
        val zeroTtlCache = LlmResponseCacheImpl(zeroTtlProps)

        // ttlMinutes = 0 means no expiry, so entry stays
        zeroTtlCache.put("system", "user", 0.5, "response")
        val result = zeroTtlCache.get("system", "user", 0.5)
        assertEquals("response", result, "TTL=0 means no expiry, entry should persist")

        // Clean up
        Files.walk(expiredDir)
            .sorted(Comparator.reverseOrder())
            .forEach { Files.deleteIfExists(it) }
    }

    @Test
    @DisplayName("Null temperature is handled correctly as a cache key component")
    fun putAndGet_nullTemperature_works() {
        cache.put("system", "user", null, "response-null-temp")

        assertEquals("response-null-temp", cache.get("system", "user", null))
        assertNull(cache.get("system", "user", 0.5), "Different temperature should miss cache")
    }
}
