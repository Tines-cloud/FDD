package com.example.fdd.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Contact
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.servers.Server
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * OpenAPI 3.0 documentation configuration for the FHIR Drift Doctor API.
 *
 * Swagger UI is accessible at `/swagger-ui.html`.
 * OpenAPI JSON spec is available at `/v3/api-docs`.
 */
@Configuration
class OpenApiConfig {

    @Bean
    fun openApi(): OpenAPI = OpenAPI()
        .info(
            Info()
                .title("FHIR Drift Doctor API")
                .description(
                    """
                    REST API for detecting semantic drift between FHIR profiles and 
                    generating FHIR StructureMap repair artefacts.
                    
                    The API supports two main workflows:
                    - **Drift Analysis**: Detect semantic differences between a source and target FHIR profile.
                    - **Drift Repair**: Detect drift and generate a validated FHIR StructureMap to resolve it.
                    
                    Profiles can be identified by canonical URL or by providing raw StructureDefinition JSON.
                    """.trimIndent()
                )
                .version("1.0.0")
                .contact(
                    Contact()
                        .name("FHIR Drift Doctor")
                )
                .license(
                    License()
                        .name("MIT License")
                        .url("https://opensource.org/licenses/MIT")
                )
        )
        .servers(
            listOf(
                Server().url("/").description("Current server (auto-detected)")
            )
        )
}
