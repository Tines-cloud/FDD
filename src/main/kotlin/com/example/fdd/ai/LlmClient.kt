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
     * Send a single-turn chat-completion request with separate system and user prompts.
     *
     * @param systemPrompt Instructions that define the LLM's persona and output format.
     * @param userPrompt   The user-turn content (profile context, drift report, etc.).
     * @param temperature  Sampling temperature controlling response randomness;
     *                     lower values produce more deterministic output (default `0.2`).
     * @return The raw text content of the LLM's response.
     * @throws com.example.fdd.exception.LlmResponseException on communication or parsing failure.
     */
    fun chat(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): String

    /**
     * Send a **multi-turn** chat-completion request, preserving the full conversation history.
     *
     * The [history] list contains interleaved (userMessage, assistantResponse) pairs from all
     * previous turns. The LLM receives the complete message chain:
     *   SystemMessage → UserMessage → AssistantMessage → UserMessage → AssistantMessage → ...
     *   → [newUserMessage]
     *
     * This enables the LLM to see its own prior outputs and the errors they produced, so it
     * can avoid repeating the same mistake across repair attempts.
     *
     * @param systemPrompt   Instructions that define the LLM's persona and output format.
     * @param history        Previous turns as (userMessage, assistantResponse) pairs - oldest first.
     * @param newUserMessage The next user turn to append to the conversation.
     * @param temperature    Sampling temperature (default `0.2`).
     * @return The raw text of the LLM's next response.
     * @throws com.example.fdd.exception.LlmResponseException on communication or parsing failure.
     */
    fun chatWithHistory(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        newUserMessage: String,
        temperature: Double = 0.2
    ): String
}
