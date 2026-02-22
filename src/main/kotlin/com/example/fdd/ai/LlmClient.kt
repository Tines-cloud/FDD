package com.example.fdd.ai

/**
 * Abstraction over the underlying LLM provider.
 *
 * All drift-analysis and map-generation logic communicates with the LLM
 * exclusively through this interface, keeping the service layer decoupled
 * from any specific SDK (Spring AI, LangChain4j, or direct REST).
 */
interface LlmClient {

    /**
     * Send a chat-completion request with separate system and user prompts.
     *
     * @param systemPrompt Instructions that define the LLM's persona and output format.
     * @param userPrompt   The user-turn content (profile context, drift report, etc.).
     * @param temperature  Sampling temperature controlling response randomness
     *                     lower values produce more deterministic output (default `0.2`).
     * @return The raw text content of the LLM's response.
     * @throws com.example.fdd.exception.LlmResponseException on communication or parsing failure.
     */
    fun chat(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): String
}
