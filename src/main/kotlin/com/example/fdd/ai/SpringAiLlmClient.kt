package com.example.fdd.ai

import com.example.fdd.exception.LlmResponseException
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service

/**
 * Talks to the LLM using Spring AI's ChatModel.
 *
 * The actual provider (OpenAI, Anthropic, Gemini, Mistral) is set in application.yaml
 * and wired by AiConfig.
 *
 * - Retries up to 3 times on transient failures (rate limits, timeouts).
 * - Caches responses so the same request doesn't hit the LLM twice.
 */
@Service
class SpringAiLlmClient(
    private val chatModel: ChatModel,
    private val cache: LlmResponseCache
) : LlmClient {

    private val log = LoggerFactory.getLogger(javaClass)

    @Retryable(
        retryFor = [RuntimeException::class],
        noRetryFor = [LlmResponseException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000)
    )
    override fun chat(
        systemPrompt: String,
        userPrompt: String,
        temperature: Double
    ): String {
        // Check cache first
        cache.get(systemPrompt, userPrompt, temperature)?.let { cached ->
            log.info("Returning cached LLM response (cache size={})", cache.size())
            return cached
        }

        log.debug(
            "LLM request - system prompt length: {}, user prompt length: {}, temperature: {}",
            systemPrompt.length, userPrompt.length, temperature
        )

        return try {
            val options = ChatOptions.builder()
                .temperature(temperature)
                .build()

            val prompt = Prompt(
                listOf(SystemMessage(systemPrompt), UserMessage(userPrompt)),
                options
            )

            val chatResponse = chatModel.call(prompt)
            val response = chatResponse.result?.output?.text

            if (response.isNullOrBlank()) {
                throw LlmResponseException("LLM returned an empty response")
            }

            log.debug("LLM response length: {}", response.length)

            // Store in cache
            cache.put(systemPrompt, userPrompt, temperature, response)

            response
        } catch (ex: LlmResponseException) {
            throw ex
        } catch (ex: Exception) {
            log.error("LLM communication failure", ex)
            throw LlmResponseException("Failed to obtain response from LLM: ${ex.message}", ex)
        }
    }

    @Retryable(
        retryFor = [RuntimeException::class],
        noRetryFor = [LlmResponseException::class],
        maxAttempts = 3,
        backoff = Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8000)
    )
    override fun chatWithHistory(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        newUserMessage: String,
        temperature: Double
    ): String {
        // Build a cache key from the full conversation so repeated identical calls are cached.
        val combinedUserKey = buildString {
            history.forEachIndexed { i, (userMsg, assistantResponse) ->
                append("[turn${i + 1}-user]$userMsg\n[turn${i + 1}-assistant]$assistantResponse\n")
            }
            append("[new-user]$newUserMessage")
        }

        cache.get(systemPrompt, combinedUserKey, temperature)?.let { cached ->
            log.info("Returning cached LLM response (conversation, cache size={})", cache.size())
            return cached
        }

        // Build the message chain: System -> [User, Assistant]* -> User
        val messageList = buildList {
            add(SystemMessage(systemPrompt))
            for ((userMsg, assistantResponse) in history) {
                add(UserMessage(userMsg))
                add(AssistantMessage(assistantResponse))
            }
            add(UserMessage(newUserMessage))
        }

        log.debug(
            "LLM conversation request - {} turn(s) in history, system length: {}, new user length: {}, temperature: {}",
            history.size, systemPrompt.length, newUserMessage.length, temperature
        )

        return try {
            val options = ChatOptions.builder()
                .temperature(temperature)
                .build()

            val prompt = Prompt(messageList, options)
            val chatResponse = chatModel.call(prompt)
            val response = chatResponse.result?.output?.text

            if (response.isNullOrBlank()) {
                throw LlmResponseException("LLM returned an empty response")
            }

            log.debug("LLM conversation response length: {}", response.length)
            cache.put(systemPrompt, combinedUserKey, temperature, response)
            response
        } catch (ex: LlmResponseException) {
            throw ex
        } catch (ex: Exception) {
            log.error("LLM communication failure", ex)
            throw LlmResponseException("Failed to obtain response from LLM: ${ex.message}", ex)
        }
    }
}
