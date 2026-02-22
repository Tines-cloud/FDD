package com.example.fdd

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Smoke test verifying the Spring application context loads successfully.
 *
 * Uses the 'test' profile so that the LLM provider is not required
 * (mocked via test configuration or skipped gracefully).
 */
@SpringBootTest
@ActiveProfiles("test")
class FddApplicationTests {

    @Test
    fun contextLoads() {
        // Context loading validates all bean wiring, config properties, etc.
    }
}
