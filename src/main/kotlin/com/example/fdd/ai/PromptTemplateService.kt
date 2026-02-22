package com.example.fdd.ai

import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Loads and caches prompt template files from `classpath:ai/`.
 *
 * Templates support simple Mustache-style variable substitution: any
 * occurrence of `{{variableName}}` is replaced with the corresponding
 * value from the supplied `variables` map.
 *
 * Template files are cached in memory after the first read to avoid
 * repeated I/O on the classpath.
 */
@Service
class PromptTemplateService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Load a template from `classpath:ai/[templateName]` and perform
     * variable substitution.
     *
     * @param templateName File name relative to the `ai/` resource directory
     *   (e.g. `drift-analysis-system.txt`).
     * @param variables    Key-value pairs for `{{key}}` replacement.
     * @return The fully resolved template string.
     */
    fun loadTemplate(
        templateName: String,
        variables: Map<String, String> = emptyMap()
    ): String {
        val raw = cache.computeIfAbsent(templateName) { name ->
            val resource = ClassPathResource("ai/$name")
            require(resource.exists()) {
                "Prompt template not found: classpath:ai/$name"
            }
            resource.inputStream.bufferedReader().readText().also {
                log.debug("Loaded prompt template: {} ({} chars)", name, it.length)
            }
        }

        var resolved = raw
        variables.forEach { (key, value) ->
            resolved = resolved.replace("{{$key}}", value)
        }
        return resolved
    }

    /**
     * Evict all cached templates (useful during hot-reload in development).
     */
    fun clearCache() {
        cache.clear()
        log.info("Prompt template cache cleared")
    }
}
