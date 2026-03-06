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
 * [LlmClient] implementation backed by Spring AI's [ChatModel].
 *
 * The concrete [ChatModel] (OpenAI, Anthropic, Gemini, Mistral) is chosen at startup
 * via the `fdd.ai.provider` property and wired in by [com.example.fdd.config.AiConfig].
 *
 * Retries: Each call is retried up to 3 times on transient failures (HTTP 429,
 * 503, network timeouts) using exponential backoff starting at 1 second.
 *
 * Caching: Responses are stored in [LlmResponseCache] so experiments can be
 * replayed without hitting the LLM API again (toggle via `fdd.cache.enabled`).
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
        // -- Check cache first (experiment reproducibility) --
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

            // -- Store in cache for subsequent runs --
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
        // Build a single cache-key string from the full conversation so past turns
        // are part of the lookup - identical follow-ups within the same conversation
        // can still be served from cache during experiment replay.
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

        // Build the full message chain: System -> [User, Assistant]* -> User
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
