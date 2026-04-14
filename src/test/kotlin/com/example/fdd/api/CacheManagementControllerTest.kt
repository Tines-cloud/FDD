package com.example.fdd.api

import com.example.fdd.ai.LlmResponseCache
import com.example.fdd.ai.PromptTemplateService
import com.example.fdd.api.impl.CacheManagementController
import com.example.fdd.output.IOutputStore
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * Unit tests for [CacheManagementController].
 *
 * Uses `@WebMvcTest` to test HTTP endpoints without loading the full context.
 */
@WebMvcTest(CacheManagementController::class)
class CacheManagementControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var llmResponseCache: LlmResponseCache

    @MockitoBean
    private lateinit var promptTemplateService: PromptTemplateService

    @MockitoBean
    private lateinit var outputStore: IOutputStore

    @Test
    @DisplayName("GET /api/cache/llm/size returns cache size")
    fun cacheSize_returnsSize() {
        whenever(llmResponseCache.size()).thenReturn(42)

        mockMvc.perform(get("/api/cache/llm/size"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.size").value(42))
    }

    @Test
    @DisplayName("DELETE /api/cache/llm clears the LLM cache")
    fun clearLlmCache_invokesAndReturnsOk() {
        mockMvc.perform(delete("/api/cache/llm"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("LLM response cache cleared"))

        verify(llmResponseCache).clear()
    }

    @Test
    @DisplayName("DELETE /api/cache/templates clears the template cache")
    fun clearTemplateCache_invokesAndReturnsOk() {
        mockMvc.perform(delete("/api/cache/templates"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("Prompt template cache cleared"))

        verify(promptTemplateService).clearCache()
    }

    @Test
    @DisplayName("DELETE /api/cache clears all caches")
    fun clearAllCaches_invokesBothAndReturnsOk() {
        mockMvc.perform(delete("/api/cache/allCaches"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("All caches cleared"))

        verify(llmResponseCache).clear()
        verify(promptTemplateService).clearCache()
    }
}
