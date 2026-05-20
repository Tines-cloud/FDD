package com.example.fdd.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Externalised configuration properties for the FHIR Drift Doctor application.
 *
 * All properties live under the `fdd.*` prefix in `application.yaml`.
 */
@ConfigurationProperties(prefix = "fdd")
data class FddProperties(
    val ai: AiProperties = AiProperties(),
    val validation: ValidationProperties = ValidationProperties(),
    val cache: CacheProperties = CacheProperties(),
    val output: OutputProperties = OutputProperties()
)

/**
 * AI / LLM provider configuration.
 *
 * Set `provider` to `openai`, `anthropic`, `gemini`, `mistral`, `groq`, or `openrouter`
 * and populate the corresponding nested block with API key, model name, etc.
 */
data class AiProperties(
    /** Active provider identifier: openai | anthropic | gemini | mistral | groq | openrouter */
    val provider: String = "openrouter",
    /** Default sampling temperature for all providers. */
    val temperature: Double = 0.2,
    val openai: OpenAiProperties = OpenAiProperties(),
    val anthropic: AnthropicProperties = AnthropicProperties(),
    val gemini: GeminiProperties = GeminiProperties(),
    val mistral: MistralProperties = MistralProperties(),
    val groq: GroqProperties = GroqProperties(),
    val openrouter: OpenRouterProperties = OpenRouterProperties()
)

data class OpenAiProperties(
    val apiKey: String = "",
    val model: String = "gpt-4o",
    val baseUrl: String = "https://api.openai.com"
)

data class AnthropicProperties(
    val apiKey: String = "",
    val model: String = "claude-sonnet-4-20250514"
)

data class GeminiProperties(
    val apiKey: String = "",
    val model: String = "gemini-2.5-flash",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai"
)

data class MistralProperties(
    val apiKey: String = "",
    val model: String = "mistral-large-latest",
    val baseUrl: String = "https://api.mistral.ai"
)

data class GroqProperties(
    val apiKey: String = "",
    val model: String = "llama-3.3-70b-versatile",
    val baseUrl: String = "https://api.groq.com/openai"
)

data class OpenRouterProperties(
    val apiKey: String = "",
    val model: String = "openai/gpt-5.4",
    val baseUrl: String = "https://openrouter.ai/api"
)

/**
 * Trust-but-Verify validation loop parameters.
 */
data class ValidationProperties(
    /** Maximum reflexion (self-correction) attempts before giving up. */
    val maxAttempts: Int = 5
)

/**
 * LLM response cache settings.
 */
data class CacheProperties(
    /** Whether LLM response caching is enabled. */
    val enabled: Boolean = false,
    /** Time-to-live in minutes cached entries older than this are automatically evicted. 0 = no expiry. */
    val ttlMinutes: Long = 0,
    /** Directory for file-based cache storage. */
    val directory: String = ".fdd-cache"
)

/**
 * Output persistence settings for drift reports, errors, and StructureMaps.
 */
data class OutputProperties(
    /** Whether output persistence is enabled. */
    val enabled: Boolean = true,
    /** Root directory for output folders. */
    val directory: String = "output"
)
