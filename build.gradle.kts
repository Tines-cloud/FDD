plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

group = "com.example"
version = "0.0.1-SNAPSHOT"
description = "FHIR Drift Doctor - Semantic drift detection and repair for FHIR profiles"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val hapiVersion = "7.6.0"
val springAiVersion = "2.0.0-M4"

repositories {
    mavenCentral()
    maven("https://repo.spring.io/milestone")
    maven("https://repo.spring.io/snapshot")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.ai:spring-ai-bom:$springAiVersion")
    }
}

dependencies {
    // ---- Spring Boot ----
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.aspectj:aspectjweaver")
    implementation("org.springframework.retry:spring-retry:2.0.12")

    // ---- API Documentation ----
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.1")

    // ---- Kotlin ----
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // ---- HAPI FHIR ----
    implementation("ca.uhn.hapi.fhir:hapi-fhir-base:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r5:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r4:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-validation-resources-r5:$hapiVersion")
    implementation("ca.uhn.hapi.fhir:hapi-fhir-caching-caffeine:$hapiVersion")

    // ---- Spring AI (multi-provider support) ----
    implementation("org.springframework.ai:spring-ai-openai:$springAiVersion")
    implementation("org.springframework.ai:spring-ai-anthropic:$springAiVersion")

    // ---- Testing ----
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers:1.21.1")
    testImplementation("org.testcontainers:junit-jupiter:1.21.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // security overrides
    implementation("com.nimbusds:nimbus-jose-jwt:10.7")
    implementation("org.fhir:ucum:1.0.9")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform {
        excludeTags("integration")
    }
    jvmArgs("-Xmx2g", "-Xms512m")
}

// ---- bootRun: pass environment variables through to the application ----
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Forward all system environment variables
    environment(System.getenv())

    // Also load from .env file if present
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Integration / Experiment tasks
//
//   All experiments:   .\gradlew.bat integrationTest
//   Only Exp 1:        .\gradlew.bat experiment1
//   Only Exp 2:        .\gradlew.bat experiment2
//   Only Exp 3:        .\gradlew.bat experiment3
//
//   Selective pairs (PowerShell - set env var before the task):
//     $env:FDD_PAIRS="pair1,pair2"; .\gradlew.bat experiment2
// ─────────────────────────────────────────────────────────────────────────────

// Base setup shared by all integration tasks (no JUnit platform config here)
fun Test.configureIntegrationBase() {
    group = "verification"
    enabled = gradle.startParameter.taskNames.any { name ->
        name == this.name || name.contains("integrationTest") || name.contains("experiment")
    }
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("-Xmx2g", "-Xms512m")
    // Accept pairs from project property OR env var FDD_PAIRS (both work on PowerShell)
    val pairsValue = project.findProperty("fdd.pairs")?.toString()
        ?: System.getenv("FDD_PAIRS") ?: ""
    systemProperty("fdd.pairs", pairsValue)
    systemProperty("project.root", project.projectDir.absolutePath)
    val envFile = file(".env")
    if (envFile.exists()) {
        envFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") && it.contains("=") }
            .forEach { line ->
                val (key, value) = line.split("=", limit = 2)
                environment(key.trim(), value.trim())
            }
    }
    environment(System.getenv())
}

// All experiments
tasks.register<Test>("integrationTest") {
    description = "Runs ALL experiment/integration tests"
    useJUnitPlatform { includeTags("integration") }
    configureIntegrationBase()
}

// Experiment 1 only - Drift Detection Accuracy
tasks.register<Test>("experiment1") {
    description = "Experiment 1: Drift Detection Accuracy"
    useJUnitPlatform { includeTags("experiment1") }
    configureIntegrationBase()
}

// Experiment 2 only - Syntactic Validity Rate
tasks.register<Test>("experiment2") {
    description = "Experiment 2: Syntactic Validity Rate"
    useJUnitPlatform { includeTags("experiment2") }
    configureIntegrationBase()
}

// Experiment 3 only - Semantic Correctness (requires Docker)
tasks.register<Test>("experiment3") {
    description = "Experiment 3: Semantic Correctness (requires Docker)"
    useJUnitPlatform { includeTags("experiment3") }
    configureIntegrationBase()
}


// ---- Kover (code coverage) ----
kover {
    reports {
        filters {
            excludes {
                // Exclude generated Spring Boot application entry-point and config-only classes
                classes(
                    "*.FddApplicationKt",
                    "*.config.*",
                    "*.api.dto.*",
                    "*.model.*",
                    "*.exception.*"
                )
            }
        }
    }
}
