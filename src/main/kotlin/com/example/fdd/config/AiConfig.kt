package com.example.fdd.config

import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.api.OpenAiApi
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Conditional AI / LLM provider configuration.
 *
 * One [ChatModel] bean is activated based on the `fdd.ai.provider` property.
 *
 * Supported providers:
 * - `openrouter` - OpenRouter gateway (default, any model via OpenAI-compatible endpoint)
 * - `gemini`     - Google Gemini (via OpenAI-compatible endpoint)
 * - `openai`     - GPT-4o
 * - `anthropic`  - Claude Sonnet
 * - `mistral`    - Mistral Large (via OpenAI-compatible endpoint)
 * - `groq`       - Groq (Llama 3.3, via OpenAI-compatible endpoint)
 *
 * OpenRouter, Gemini, Mistral, and Groq use OpenAI-compatible REST APIs,
 * so [OpenAiChatModel] is reused with a custom `baseUrl` for those providers.
 */
@Configuration
@EnableScheduling
class AiConfig {

    /* ---------------- OpenAI (GPT-4o) ---------------- */

    @Bean
    @ConditionalOnProperty("fdd.ai.provider", havingValue = "openai")
    fun openAiChatModel(props: FddProperties): ChatModel {
        val api = OpenAiApi.builder()
            .apiKey(requireApiKey("openai", props.ai.openai.apiKey))
            .baseUrl(props.ai.openai.baseUrl)
            .build()
        val options = OpenAiChatOptions.builder()
            .model(props.ai.openai.model)
            .temperature(props.ai.temperature)
            .build()
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build()
    }

    /* ---------------- Anthropic (Claude) ---------------- */

    @Bean
    @ConditionalOnProperty("fdd.ai.provider", havingValue = "anthropic")
    fun anthropicChatModel(props: FddProperties): ChatModel {
        val options = AnthropicChatOptions.builder()
            .apiKey(requireApiKey("anthropic", props.ai.anthropic.apiKey))
            .model(props.ai.anthropic.model)
            .temperature(props.ai.temperature)
            .build()

        return AnthropicChatModel.builder()
            .options(options)
            .build()
    }

    /* ---------------- Google Gemini (OpenAI-compatible API) ---------------- */

    @Bean
    @ConditionalOnProperty("fdd.ai.provider", havingValue = "gemini")
    fun geminiChatModel(props: FddProperties): ChatModel {
        val api = OpenAiApi.builder()
            .apiKey(requireApiKey("gemini", props.ai.gemini.apiKey))
            .baseUrl(props.ai.gemini.baseUrl)
            .build()
        val options = OpenAiChatOptions.builder()
            .model(props.ai.gemini.model)
            .temperature(props.ai.temperature)
            .build()
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build()
    }

    /* ---------------- Mistral (OpenAI-compatible API) ---------------- */

    @Bean
    @ConditionalOnProperty("fdd.ai.provider", havingValue = "mistral")
    fun mistralChatModel(props: FddProperties): ChatModel {
        val api = OpenAiApi.builder()
            .apiKey(requireApiKey("mistral", props.ai.mistral.apiKey))
            .baseUrl(props.ai.mistral.baseUrl)
            .build()
        val options = OpenAiChatOptions.builder()
            .model(props.ai.mistral.model)
            .temperature(props.ai.temperature)
            .build()
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build()
    }

    /* ---------------- Groq (OpenAI-compatible API) ---------------- */

    @Bean
    @ConditionalOnProperty("fdd.ai.provider", havingValue = "groq")
    fun groqChatModel(props: FddProperties): ChatModel {
        val api = OpenAiApi.builder()
            .apiKey(requireApiKey("groq", props.ai.groq.apiKey))
            .baseUrl(props.ai.groq.baseUrl)
            .build()
        val options = OpenAiChatOptions.builder()
            .model(props.ai.groq.model)
            .temperature(props.ai.temperature)
            .build()
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build()
    }

    /* ---------------- OpenRouter (OpenAI-compatible API) ---------------- */

    @Bean
    @ConditionalOnProperty("fdd.ai.provider", havingValue = "openrouter", matchIfMissing = true)
    fun openRouterChatModel(props: FddProperties): ChatModel {
        val api = OpenAiApi.builder()
            .apiKey(requireApiKey("openrouter", props.ai.openrouter.apiKey))
            .baseUrl(props.ai.openrouter.baseUrl)
            .build()
        val options = OpenAiChatOptions.builder()
            .model(props.ai.openrouter.model)
            .temperature(props.ai.temperature)
            .build()
        return OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(options)
            .build()
    }
}


private fun requireApiKey(provider: String, key: String): String {
    require(key.isNotBlank()) {
        """
        |
        |------------------------------------------------------
        |  MISSING API KEY for provider '$provider'
        |------------------------------------------------------
        |  Set the environment variable before starting:
        |
        |    Windows:    ${'$'}env:${provider.uppercase()}_API_KEY = "API-key-here"
        |    Linux/Mac:  export ${provider.uppercase()}_API_KEY="API-key-here"
        |
        |  Or create a .env file (see .env.example)
        |--------------------------------------------------------
        """.trimMargin()
    }
    return key
}