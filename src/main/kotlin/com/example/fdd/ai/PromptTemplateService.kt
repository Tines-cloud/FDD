package com.example.fdd.ai

interface PromptTemplateService {

    fun loadTemplate(templateName: String, variables: Map<String, String> = emptyMap()): String

    fun clearCache()
}