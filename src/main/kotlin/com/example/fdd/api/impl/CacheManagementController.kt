package com.example.fdd.api.impl

import com.example.fdd.ai.LlmResponseCache
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.api.ICacheManagementController
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Administrative endpoints for managing internal caches.
 *
 * Provides visibility into the LLM response cache and allows on-demand
 * eviction of both the LLM response cache and the prompt template cache.
 */
@RestController
@RequestMapping("/api/cache")
@Tag(name = "Cache Management", description = "View and clear internal caches")
class CacheManagementController(
    private val llmResponseCache: LlmResponseCache,
    private val promptTemplateService: PromptTemplateService
) : ICacheManagementController {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the current number of cached LLM responses.
     */
    @GetMapping("/llm/size")
    @Operation(summary = "Get LLM response cache size")
    override fun cacheSize(): ResponseEntity<Map<String, Int>> =
        ResponseEntity.ok(mapOf("size" to llmResponseCache.size()))

    /**
     * Clear the LLM response cache (file-based).
     */
    @DeleteMapping("/llm")
    @Operation(summary = "Clear the LLM response cache")
    override fun clearLlmCache(): ResponseEntity<Map<String, String>> {
        llmResponseCache.clear()
        log.info("LLM response cache cleared via API")
        return ResponseEntity.ok(mapOf("status" to "LLM response cache cleared"))
    }

    /**
     * Clear the in-memory prompt template cache, forcing templates to be
     * re-read from classpath on the next request.
     */
    @DeleteMapping("/templates")
    @Operation(summary = "Clear the prompt template cache")
    override fun clearTemplateCache(): ResponseEntity<Map<String, String>> {
        promptTemplateService.clearCache()
        log.info("Prompt template cache cleared via API")
        return ResponseEntity.ok(mapOf("status" to "Prompt template cache cleared"))
    }

    /**
     * Clear all caches (LLM responses + prompt templates).
     */
    @DeleteMapping("/allCaches")
    @Operation(summary = "Clear all caches")
    override fun clearAllCaches(): ResponseEntity<Map<String, String>> {
        llmResponseCache.clear()
        promptTemplateService.clearCache()
        log.info("All caches cleared via API")
        return ResponseEntity.ok(mapOf("status" to "All caches cleared"))
    }
}