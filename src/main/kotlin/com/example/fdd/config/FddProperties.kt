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
 * Set `provider` to `openai`, `anthropic`, `gemini`, or `mistral` and populate
 * the corresponding nested block with API key, model name, etc.
 */
data class AiProperties(
    /** Active provider identifier: openai | anthropic | gemini | mistral */
    val provider: String = "gemini",
    /** Default sampling temperature for all providers. */
    val temperature: Double = 0.2,
    val openai: OpenAiProperties = OpenAiProperties(),
    val anthropic: AnthropicProperties = AnthropicProperties(),
    val gemini: GeminiProperties = GeminiProperties(),
    val mistral: MistralProperties = MistralProperties()
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
    val model: String = "gemini-pro",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai"
)

data class MistralProperties(
    val apiKey: String = "",
    val model: String = "mistral-large-latest",
    val baseUrl: String = "https://api.mistral.ai/v1"
)

/**
 * Trust-but-Verify validation loop parameters.
 */
data class ValidationProperties(
    /** Maximum reflexion (self-correction) attempts before giving up. */
    val maxAttempts: Int = 3
)

/**
 * LLM response cache settings.
 */
data class CacheProperties(
    /** Whether LLM response caching is enabled. */
    val enabled: Boolean = true,
    /** Time-to-live in minutes; cached entries older than this are automatically evicted. 0 = no expiry. */
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
