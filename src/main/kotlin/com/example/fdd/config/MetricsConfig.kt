package com.example.fdd.config

import io.micrometer.core.aop.TimedAspect
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Micrometer metrics configuration for observability.
 *
 * Enables `@Timed` annotation support for method-level timing metrics.
 * Key metrics exposed:
 * - `fdd.drift.analysis.duration` - Time taken for drift analysis
 * - `fdd.map.generation.duration` - Time taken for StructureMap generation
 * - `fdd.map.validation.duration` - Time taken for Trust-but-Verify loop
 * - `fdd.llm.call.duration` - Time taken for individual LLM calls
 *
 * All metrics are available via the `/actuator/metrics` endpoint.
 */
@Configuration
class MetricsConfig {

    @Bean
    fun timedAspect(registry: MeterRegistry): TimedAspect = TimedAspect(registry)
}
