package com.example.fdd.ai

/**
 * Interface for talking to an LLM provider.
 *
 * All drift-analysis and map-generation code use this interface,
 * so the rest of the app doesn't depend on any specific LLM SDK.
 */
interface LlmClient {

    /**
     * Send a single message to the LLM and get a response.
     *
     * @param systemPrompt Sets the LLM's role and output format.
     * @param userPrompt   The actual question or task.
     * @param temperature  Controls randomness (lower = more predictable, default 0.2).
     * @return The LLM's response text.
     */
    fun chat(systemPrompt: String, userPrompt: String, temperature: Double = 0.2): String

    /**
     * Send a message with full conversation history so the LLM can see its own
     * prior replies and avoid repeating the same mistakes.
     *
     * @param systemPrompt   Sets the LLM's role and output format.
     * @param history        Previous (userMessage, llmResponse) pairs, oldest first.
     * @param newUserMessage The new message to send.
     * @param temperature    Controls randomness (default 0.2).
     * @return The LLM's response text.
     */
    fun chatWithHistory(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        newUserMessage: String,
        temperature: Double = 0.2
    ): String
}
