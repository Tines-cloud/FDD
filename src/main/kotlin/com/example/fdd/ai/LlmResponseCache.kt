package com.example.fdd.ai

interface LlmResponseCache {

    fun evictExpiredEntries()

    fun put(systemPrompt: String, userPrompt: String, temperature: Double?, response: String)

    fun get(systemPrompt: String, userPrompt: String, temperature: Double?): String?

    fun clear()

    fun size(): Int
}