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

        // 1. Code-fence wins if present
        CODE_FENCE_PATTERN.find(cleaned)?.let {
            return it.groupValues[1].trim()
        }

        // 2. No code fence - the LLM may have prefixed the FML with explanatory text
        //    (e.g. "Here is the fixed FML:\n\nmap ...").  Find the first line that starts
        //    the FML (the map declaration) and discard everything before it, BUT only when
        //    the prefix is plain prose (not a partial/malformed code fence).
        val lines = cleaned.lines()
        val mapLineIdx = lines.indexOfFirst { it.trimStart().startsWith("map ") }
        if (mapLineIdx > 0) {
            val prefix = lines.take(mapLineIdx).joinToString("\n").trim()
            if (!prefix.startsWith("```")) {
                cleaned = lines.drop(mapLineIdx).joinToString("\n")
            }
        }

        return cleaned
    }
}
