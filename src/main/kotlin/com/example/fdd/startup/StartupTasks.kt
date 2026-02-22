package com.example.fdd.startup

import com.example.fdd.config.FddProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Startup tasks: cleanup output folder, initialize caches, etc.
 */
@Component
class StartupTasks(
    private val props: FddProperties
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        ensureOutputFolder()
        log.info("--- FHIR Drift Doctor started successfully ---")
    }

    /**
     * Ensures the output directory exists without deleting previous results.
     * Previous drift reports are preserved across restarts.
     */
    private fun ensureOutputFolder() {
        try {
            val outputDir = Paths.get(props.output.directory)
            if (Files.exists(outputDir)) {
                val count = Files.list(outputDir).use { it.count() }
                log.info("Output folder exists with {} entries: {}", count, outputDir)
            } else {
                Files.createDirectories(outputDir)
                log.info("Output folder created: {}", outputDir)
            }
        } catch (ex: Exception) {
            log.error("Failed to initialize output folder", ex)
        }
    }
}
