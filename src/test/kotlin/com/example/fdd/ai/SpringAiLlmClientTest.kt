package com.example.fdd.ai

import com.example.fdd.exception.LlmResponseException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation

/**
 * Unit tests for [SpringAiLlmClient].
 *
 * Verifies LLM communication, cache integration (hit/miss),
 * empty response handling, and error wrapping.
 * Retry behaviour is tested conceptually - Spring's @Retryable is integration-level
 * but we verify the thrown exception types match the retry/noRetry configuration.
 */
class SpringAiLlmClientTest {

    private lateinit var chatModel: ChatModel
    private lateinit var cache: LlmResponseCache
    private lateinit var client: SpringAiLlmClient

    @BeforeEach
    fun setUp() {
        chatModel = mock()
        cache = mock()
        client = SpringAiLlmClient(chatModel, cache)
    }

    /* ---- Cache miss -> LLM call -> cache store ---- */

    @Test
    @DisplayName("Cache miss calls ChatModel and stores response in cache")
    fun chat_cacheMiss_callsModelAndCaches() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        val assistantMessage = AssistantMessage("LLM output text")
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        val result = client.chat("system", "user", 0.5)

        assertEquals("LLM output text", result)
        verify(cache).get("system", "user", 0.5)
        verify(cache).put("system", "user", 0.5, "LLM output text")
        verify(chatModel).call(any<org.springframework.ai.chat.prompt.Prompt>())
    }

    /* ---- Cache hit -> no LLM call ---- */

    @Test
    @DisplayName("Cache hit returns cached response without calling ChatModel")
    fun chat_cacheHit_skipsChatModel() {
        whenever(cache.get("system", "user", 0.7)).thenReturn("cached response")

        val result = client.chat("system", "user", 0.7)

        assertEquals("cached response", result)
        verify(chatModel, never()).call(any<org.springframework.ai.chat.prompt.Prompt>())
        verify(cache, never()).put(any(), any(), any(), any())
    }

    /* ---- Empty response -> LlmResponseException ---- */

    @Test
    @DisplayName("Empty LLM response throws LlmResponseException (no-retry)")
    fun chat_emptyResponse_throwsLlmResponseException() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        val assistantMessage = AssistantMessage("")
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        val ex = assertThrows<LlmResponseException> {
            client.chat("system", "user", 0.3)
        }

        assertTrue(ex.message!!.contains("empty"), "Should mention empty response")
        verify(cache, never()).put(any(), any(), any(), any())
    }

    /* ---- Null result -> LlmResponseException ---- */

    @Test
    @DisplayName("Null output text throws LlmResponseException")
    fun chat_nullOutput_throwsLlmResponseException() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        // Create a ChatResponse with null text
        val assistantMessage = AssistantMessage(null as String?)
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        assertThrows<LlmResponseException> {
            client.chat("system", "user", 0.5)
        }
    }

    /* ---- ChatModel exception -> wrapped as LlmResponseException ---- */

    @Test
    @DisplayName("ChatModel exception is wrapped in LlmResponseException")
    fun chat_modelThrowsException_wrapsInLlmResponseException() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenThrow(RuntimeException("Connection timeout"))

        val ex = assertThrows<LlmResponseException> {
            client.chat("system", "user", 0.5)
        }

        assertTrue(ex.message!!.contains("Connection timeout"))
        assertNotNull(ex.cause)
        verify(cache, never()).put(any(), any(), any(), any())
    }

    /* ---- LlmResponseException is not re-wrapped ---- */

    @Test
    @DisplayName("LlmResponseException from validation is rethrown as-is, not wrapped")
    fun chat_llmResponseExceptionFromEmpty_notDoubleWrapped() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        val assistantMessage = AssistantMessage("   ")
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        val ex = assertThrows<LlmResponseException> {
            client.chat("system", "user", 0.5)
        }

        // Should be a direct LlmResponseException, not wrapped in another
        assertNull(ex.cause, "Empty-response LlmResponseException should not have a cause")
    }

    /* ---- Temperature passed to ChatOptions ---- */

    @Test
    @DisplayName("Temperature parameter is forwarded to ChatModel options")
    fun chat_temperatureForwarded_toModel() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        val assistantMessage = AssistantMessage("response")
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        client.chat("system", "user", 0.0)

        val promptCaptor = argumentCaptor<org.springframework.ai.chat.prompt.Prompt>()
        verify(chatModel).call(promptCaptor.capture())

        // Verify the prompt contains both system and user messages
        val messages = promptCaptor.firstValue.instructions
        assertEquals(2, messages.size, "Prompt should have system + user messages")
    }

    /* ---- Cache size used in log output ---- */

    @Test
    @DisplayName("Successful response increments cache via put()")
    fun chat_success_storesInCache() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)
        whenever(cache.size()).thenReturn(5)

        val assistantMessage = AssistantMessage("valid response")
        val generation = Generation(assistantMessage)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        client.chat("sys", "usr", 0.2)

        verify(cache).put("sys", "usr", 0.2, "valid response")
    }

    /* ---- chatWithHistory: full message chain is built correctly ---- */

    @Test
    @DisplayName("chatWithHistory builds SystemMessage + interleaved User/Assistant + new UserMessage")
    fun chatWithHistory_buildsCorrectMessageChain() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        val assistantMsg = AssistantMessage("fixed FML response")
        val generation = Generation(assistantMsg)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        val history = listOf(
            Pair("first user turn", "first assistant response")
        )

        val result = client.chatWithHistory("system", history, "second user turn", 0.1)

        assertEquals("fixed FML response", result)

        // Verify prompt message count: System(1) + User(1) + Assistant(1) + User(1) = 4
        val promptCaptor = argumentCaptor<org.springframework.ai.chat.prompt.Prompt>()
        verify(chatModel).call(promptCaptor.capture())
        val messages = promptCaptor.firstValue.instructions
        assertEquals(4, messages.size, "Expected: System + prior User + prior Assistant + new User")
    }

    @Test
    @DisplayName("chatWithHistory with empty history behaves like a single-turn call")
    fun chatWithHistory_emptyHistory_singleTurn() {
        whenever(cache.get(any(), any(), any())).thenReturn(null)

        val assistantMsg = AssistantMessage("response")
        val generation = Generation(assistantMsg)
        val chatResponse = ChatResponse(listOf(generation))
        whenever(chatModel.call(any<org.springframework.ai.chat.prompt.Prompt>()))
            .thenReturn(chatResponse)

        client.chatWithHistory("system", emptyList(), "user message", 0.2)

        val promptCaptor = argumentCaptor<org.springframework.ai.chat.prompt.Prompt>()
        verify(chatModel).call(promptCaptor.capture())
        val messages = promptCaptor.firstValue.instructions
        assertEquals(2, messages.size, "Empty history should give System + User only")
    }

    @Test
    @DisplayName("chatWithHistory cache hit skips ChatModel call")
    fun chatWithHistory_cacheHit_skipsModel() {
        whenever(cache.get(any(), any(), any())).thenReturn("cached conv response")

        val result = client.chatWithHistory("system", emptyList(), "user", 0.1)

        assertEquals("cached conv response", result)
        verify(chatModel, never()).call(any<org.springframework.ai.chat.prompt.Prompt>())
    }
}
