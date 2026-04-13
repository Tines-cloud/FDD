package com.example.fdd.api

import io.swagger.v3.oas.annotations.Operation
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping

interface ICacheManagementController {
    /**
     * Returns the current number of cached LLM responses.
     */
    @GetMapping("/llm/size")
    @Operation(summary = "Get LLM response cache size")
    fun cacheSize(): ResponseEntity<Map<String, Int>>

    /**
     * Clear the LLM response cache (file-based).
     */
    @DeleteMapping("/llm")
    @Operation(summary = "Clear the LLM response cache")
    fun clearLlmCache(): ResponseEntity<Map<String, String>>

    /**
     * Clear the in-memory prompt template cache, forcing templates to be
     * re-read from classpath on the next request.
     */
    @DeleteMapping("/templates")
    @Operation(summary = "Clear the prompt template cache")
    fun clearTemplateCache(): ResponseEntity<Map<String, String>>

    /**
     * Clear all caches (LLM responses + prompt templates).
     */
    @DeleteMapping("/allCaches")
    @Operation(summary = "Clear all caches")
    fun clearAllCaches(): ResponseEntity<Map<String, String>>
}
