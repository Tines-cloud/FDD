package com.example.fdd.util

/**
 * Shared utility for working with FHIR Mapping Language (FML) text.
 *
 * Extracted to eliminate duplication between [com.example.fdd.service.impl.DefaultMapGenerator]
 * and [com.example.fdd.validation.impl.DefaultMapValidator].
 */
object FmlUtils {

    /**
     * Regex matching markdown code-fence blocks commonly returned by LLMs.
     * Handles language tags like `fml`, `map`, `fhir`, or no tag at all.
     */
    private val CODE_FENCE_PATTERN =
        Regex("^```(?:fml|map|fhir)?\\s*\\n?([\\s\\S]*?)\\n?```$")

    /**
     * Strip any markdown code-fence wrapping from raw LLM output and return
     * the clean FML text.
     *
     * Examples of handled formats:
     * - ` ```fml\n<code>\n``` `
     * - ` ```\n<code>\n``` `
     * - Plain text (returned unchanged)
     *
     * @param response Raw LLM response text.
     * @return Clean FML text with fences removed.
     */
    fun extractFml(response: String): String {
        var cleaned = response.trim()

        CODE_FENCE_PATTERN.find(cleaned)?.let {
            cleaned = it.groupValues[1].trim()
        }

        return cleaned
    }
}
