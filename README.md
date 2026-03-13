# FHIR Drift Doctor (FDD)

A Spring Boot service that detects **semantic drift** between two FHIR profiles,
generates a **StructureMap** (FML) to repair the drift, and validates it using
HAPI-FHIR's compiler in a **Trust-but-Verify** reflexion loop.

---

## Architecture

```
┌────────────────┐     ┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
│  REST API       │────>│  Stage 1     │────>│  Stage 2         │────>│  Stage 3     │
│  /api/drift/    │     │  Drift       │     │  StructureMap    │     │  Coverage    │
│  analyze|repair │     │  Analysis    │     │  Generation +    │     │  Analysis    │
└────────────────┘     │  (hybrid)    │     │  Trust-but-Verify│     │ (deterministic)│
                       └──────────────┘     └──────────────────┘     └──────────────┘
                              │                    │                        │
                     ┌────────┴────────┐    ┌──────┴──────────┐    ┌───────┴────────┐
                     │ Rule-based      │    │ autoFixRuleNames │    │ Cross-reference │
                     │ detector (20+)  │    │ (zero LLM cost)  │    │ drift vs FML   │
                     └─────────────────┘    ├──────────────────┤    │ 5 categories   │
                     │ LLM analysis   │    │ collectAllErrors │    │ + shareability │
                     │ + merge        │    │ (batch scanning)  │    └────────────────┘
                     └─────────────────┘    ├──────────────────┤
                                            │ Anti-oscillation │
                                            │ + regression det │
                                            │ + temp boost     │
                                            └──────────────────┘
```

**Four-stage pipeline:**

1. **Profile Resolution** - Load `StructureDefinition` JSON from inline content,
   HTTP URL, canonical URL, classpath resource, or local file path.
2. **Drift Analysis** - Hybrid: deterministic rule-based detector (all 5 drift types)
   seeds the LLM prompt, results are merged and de-duplicated.
3. **Repair Generation + Trust-but-Verify** - LLM generates FML code from the `DriftReport`.
   HAPI-FHIR compiles it; failures trigger multi-turn reflexion with conversation history.
   Includes `autoFixRuleNames()` (deterministic, zero LLM cost), `collectAllErrors()`
   (batch scanning), anti-oscillation detection, and line-level regression detection
   with temperature boost.
4. **Coverage Analysis** - Cross-references every drift item against the generated FML.
   Classifies into 5 categories (MAPPED, COVERED_BY_PARENT, NO_RULE_NEEDED,
   SOURCE_DATA_LOSS, UNMAPPABLE_NO_SOURCE). Computes data shareability percentage.
   Fully deterministic - zero LLM cost.

**Five drift categories:** `TERMINOLOGY` · `EXTENSION` · `STRUCTURAL` · `CARDINALITY` · `VERSION`

**Dual FHIR Context:** R4 (`@Primary`) and R5 (`@Qualifier("r5")`) FhirContext beans with
separate ValidationSupportChain, parser, and validator instances. R5 profiles are
automatically detected and validated using the R5 pipeline.

**AI cost per repair request:** Drift analysis (1 LLM call) + Map generation (1 LLM call)
+ Reflexion (0-2 LLM calls, only if compilation fails) + Coverage analysis (0 calls)
+ autoFixRuleNames (0 calls) = **2-4 LLM calls total** (typically 2 if generation succeeds
on first compile).

---

## Tech Stack

| Layer         | Technology                                                    |
|---------------|---------------------------------------------------------------|
| Runtime       | Java 21, Kotlin 2.x                                           |
| Framework     | Spring Boot 4.x, Spring Framework 7.x                         |
| AI            | Spring AI 2.0 (OpenAI, Anthropic, Gemini, Mistral)            |
| FHIR          | HAPI FHIR 7.6.0 (R4 + R5 dual context, StructureMapUtilities) |
| Serialisation | Jackson 3.x (tools.jackson namespace)                         |
| Metrics       | Micrometer + Prometheus endpoint                              |
| API Docs      | SpringDoc OpenAPI (Swagger UI)                                |
| Build         | Gradle 9.x (Kotlin DSL)                                       |
| Testing       | JUnit 5, Mockito-Kotlin, Testcontainers                       |

---

## Prerequisites

- **JDK 21+** - required by Spring Boot 4.
- **Gradle 9.x** - the included `gradlew` wrapper handles this automatically.
- **LLM API key** - at least one of: OpenAI, Gemini, Anthropic, or Mistral.

---

## Quick Start

### 1. Clone and build

```bash
git clone <repo-url> && cd fdd
./gradlew build        # Linux/macOS
gradlew.bat build      # Windows
```

### 2. Set an API key

Export one of these environment variables (or edit `application.yaml`):

```bash
# Option A - Google Gemini (default provider, generous free tier)
export GEMINI_API_KEY=AIza...

# Option B - OpenAI
export OPENAI_API_KEY=sk-...

# Option C - Anthropic Claude
export ANTHROPIC_API_KEY=sk-ant-...

# Option D - Mistral
export MISTRAL_API_KEY=...
```

Then set the active provider in `application.yaml`:

```yaml
fdd:
  ai:
    provider: gemini   # openai | anthropic | gemini | mistral
```

### 3. Run the application

```bash
./gradlew bootRun
```

The server starts on **http://localhost:8080**.

---

## Usage Modes

FDD can be used in **6 different ways** - pick whatever suits your workflow:

| # | Mode                                 | Server Required? | Best For                              |
|---|--------------------------------------|------------------|---------------------------------------|
| 1 | **Swagger UI** (browser)             | Yes              | Exploring the API interactively       |
| 2 | **Postman**                          | Yes              | Team collaboration, saved collections |
| 3 | **cURL** (command line)              | Yes              | Shell scripts, Linux/macOS            |
| 4 | **PowerShell** (`Invoke-RestMethod`) | Yes              | Windows automation                    |
| 5 | **CLI mode** (standalone)            | **No**           | CI/CD pipelines, local batch analysis |
| 6 | **OpenAPI code generation**          | Yes (once)       | Auto-generate clients in any language |

---

## Profile Input Methods

Each source/target profile can be provided using **any one** of these methods.
You can **mix and match** - e.g. source from a local file, target from a URL.

| # | Method                 | REST API field | CLI argument                                | Description                                                                                                                                    |
|---|------------------------|----------------|---------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------|
| 1 | **Canonical URL**      | `"canonical"`  | `--source-canonical` / `--target-canonical` | Resolve from HAPI-FHIR's built-in base R4 definitions (Patient, Observation, etc.)                                                             |
| 2 | **HTTP(S) URL**        | `"url"`        | `--source-url` / `--target-url`             | Fetch JSON from any public URL: build.fhir.org, simplifier.net, GitHub raw links, FHIR package registries                                      |
| 3 | **Inline JSON**        | `"json"`       | _(use `--source-file`)_                     | Paste the raw StructureDefinition JSON directly in the request body                                                                            |
| 4 | **Classpath resource** | `"classpath"`  | `--source-classpath` / `--target-classpath` | Load from bundled resources inside the JAR (`src/main/resources/`)                                                                             |
| 5 | **Local file**         | `"file"`       | `--source-file` / `--target-file`           | Read a `.json` file from a local path (D:\, E:\, network drives, etc.). REST reads from the **server's** filesystem - use in development only. |

**Priority order** (when multiple fields are set): `json` -> `url` -> `canonical` -> `classpath` -> `file`

---

## 1. Swagger UI (Browser)
~~~~
1. Start the server:
   ```powershell
   .\gradlew.bat bootRun
   ```
2. Open **http://localhost:8080/swagger-ui.html**
3. Expand **Drift Detection & Repair** -> click an endpoint -> **Try it out**
4. Paste any of the JSON request bodies shown below -> **Execute**

---

## 2. Postman

1. Start the server: `.\gradlew.bat bootRun`
2. Create a new **POST** request in Postman
3. Set header: `Content-Type: application/json`

**Quick import:** File -> Import -> Link -> `http://localhost:8080/v3/api-docs`
Postman will generate a complete collection with all endpoints pre-configured.

---

## 3. cURL

Start the server first: `.\gradlew.bat bootRun` (or `./gradlew bootRun` on Linux/macOS)

---

## 4. PowerShell

Start the server first: `.\gradlew.bat bootRun`

---

## 5. CLI Mode (No Server)

Build the JAR first: `.\gradlew.bat bootJar`

The CLI supports all profile input methods directly as command-line arguments:

| Argument                  | Description                                                           |
|---------------------------|-----------------------------------------------------------------------|
| `--mode=analyze\|repair`  | **Required.** Analysis mode                                           |
| `--source-canonical=URL`  | Source profile - canonical URL                                        |
| `--source-url=URL`        | Source profile - HTTP(S) URL                                          |
| `--source-file=PATH`      | Source profile - local .json file (any drive: C:\, D:\, E:\, network) |
| `--source-classpath=PATH` | Source profile - classpath resource                                   |
| `--target-canonical=URL`  | Target profile - canonical URL                                        |
| `--target-url=URL`        | Target profile - HTTP(S) URL                                          |
| `--target-file=PATH`      | Target profile - local .json file (any drive)                         |
| `--target-classpath=PATH` | Target profile - classpath resource                                   |
| `--output=FILE`           | Optional - write JSON output to file (default: stdout)                |

---

## 6. OpenAPI Code Generation

Generate API clients in **any language** from the OpenAPI spec:

1. Start the server: `.\gradlew.bat bootRun`
2. Spec available at: **http://localhost:8080/v3/api-docs**
3. Generate:
   ```bash
   # Python client
   npx @openapitools/openapi-generator-cli generate \
     -i http://localhost:8080/v3/api-docs -g python -o ./fdd-python-client

   # TypeScript client
   npx @openapitools/openapi-generator-cli generate \
     -i http://localhost:8080/v3/api-docs -g typescript-fetch -o ./fdd-ts-client
   ```

---

## Complete Examples - Every Profile Input Type

### A. Canonical URL (built-in FHIR R4 base resources)

Resolves from HAPI-FHIR's bundled definitions. Works for **all base R4 resource types**:
Patient, Observation, Condition, MedicationRequest, Encounter, Practitioner, etc.

**REST (cURL):**

```bash
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "source": { "canonical": "http://hl7.org/fhir/StructureDefinition/Patient" },
    "target": { "canonical": "http://hl7.org/fhir/StructureDefinition/Observation" }
  }'
```

**REST (PowerShell):**

```powershell
$body = @{
  source = @{ canonical = "http://hl7.org/fhir/StructureDefinition/Patient" }
  target = @{ canonical = "http://hl7.org/fhir/StructureDefinition/Observation" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/analyze" `
  -ContentType "application/json" -Body $body
```

**CLI:**

```powershell
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=analyze `
  --source-canonical=http://hl7.org/fhir/StructureDefinition/Patient `
  --target-canonical=http://hl7.org/fhir/StructureDefinition/Observation
```

---

### B. HTTP(S) URL (any public link returning StructureDefinition JSON)

Works with: **build.fhir.org** · **simplifier.net** · **GitHub raw links** · **FHIR package registries** · **any server
returning valid JSON**

**REST (cURL):**

```bash
# build.fhir.org (HL7 Implementation Guides)
curl -X POST http://localhost:8080/api/drift/repair \
  -H "Content-Type: application/json" \
  -d '{
    "source": {
      "url": "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json"
    },
    "target": {
      "url": "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-condition-encounter-diagnosis.json"
    }
  }'
```

```bash
# GitHub raw links
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "source": {
      "url": "https://raw.githubusercontent.com/AuDigitalHealth/ci-fhir-r4/master/resources/au-patient.json"
    },
    "target": { "canonical": "http://hl7.org/fhir/StructureDefinition/Patient" }
  }'
```

```bash
# simplifier.net
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "source": {
      "url": "https://simplifier.net/resolve?scope=hl7.fhir.us.core@7.0.0&canonical=http://hl7.org/fhir/us/core/StructureDefinition/us-core-patient&resourceType=StructureDefinition"
    },
    "target": { "canonical": "http://hl7.org/fhir/StructureDefinition/Patient" }
  }'
```

**REST (PowerShell):**

```powershell
$body = @{
  source = @{ url = "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json" }
  target = @{ url = "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-condition-encounter-diagnosis.json" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/repair" `
  -ContentType "application/json" -Body $body | ConvertTo-Json -Depth 10
```

**CLI:**

```powershell
# build.fhir.org URLs
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=repair `
  --source-url=https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json `
  --target-url=https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-condition-encounter-diagnosis.json `
  --output=result.json
```

```powershell
# GitHub raw link
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=analyze `
  --source-url=https://raw.githubusercontent.com/AuDigitalHealth/ci-fhir-r4/master/resources/au-patient.json `
  --target-canonical=http://hl7.org/fhir/StructureDefinition/Patient
```

---

### C. Local File Path (CLI + REST API - any drive: C:\, D:\, E:\, network shares)

Read a StructureDefinition `.json` file directly from the local filesystem.
Supports **absolute paths**, **relative paths**, and **any drive letter**.

> **Security note:** The REST API `"file"` field reads from the **server's** filesystem.
> Use this only in development or same-machine scenarios.

**CLI:**

```powershell
# Absolute paths on any drive
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=analyze `
  --source-file=D:\fhir-profiles\us-core-patient.json `
  --target-file=E:\organization\custom-patient.json

# Relative paths (from current working directory)
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=repair `
  --source-file=.\profiles\source-patient.json `
  --target-file=.\profiles\target-patient.json `
  --output=drift-report.json

# Network share / UNC path
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=analyze `
  --source-file=\\server\share\profiles\patient-v1.json `
  --target-file=\\server\share\profiles\patient-v2.json
```

**Linux/macOS:**

```bash
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=cli --mode=repair \
  --source-file=/home/user/profiles/patient-au.json \
  --target-file=/opt/fhir/profiles/patient-us.json \
  --output=result.json
```

**REST (direct `file` field - reads from server filesystem):**

```bash
# cURL - use the file field directly
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "source": { "file": "D:/profiles/source-patient.json" },
    "target": { "canonical": "http://hl7.org/fhir/StructureDefinition/Patient" }
  }'
```

```powershell
# PowerShell - use the file field directly
$body = @{
  source = @{ file = "D:\profiles\source-patient.json" }
  target = @{ canonical = "http://hl7.org/fhir/StructureDefinition/Patient" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/analyze" `
  -ContentType "application/json" -Body $body
```

**Alternative - pass file contents via `json` field (works with any REST client):**

```powershell
# PowerShell - read file into the json field
$sourceJson = Get-Content -Raw D:\profiles\source-patient.json
$body = @{
  source = @{ json = $sourceJson }
  target = @{ canonical = "http://hl7.org/fhir/StructureDefinition/Patient" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/analyze" `
  -ContentType "application/json" -Body $body
```

```bash
# cURL - read file into the json field
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d "{
    \"source\": { \"json\": $(cat /path/to/source-patient.json | jq -Rs .) },
    \"target\": { \"canonical\": \"http://hl7.org/fhir/StructureDefinition/Patient\" }
  }"
```

---

### D. Inline JSON (raw StructureDefinition pasted directly)

Paste the entire StructureDefinition JSON inline in the request body.
Useful for programmatic use or when you have the JSON in a variable.

**REST (cURL):**

```bash
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "source": {
      "json": "{\"resourceType\":\"StructureDefinition\",\"url\":\"http://example.org/SD/MyPatient\",\"name\":\"MyPatient\",\"status\":\"active\",\"kind\":\"resource\",\"abstract\":false,\"type\":\"Patient\",\"baseDefinition\":\"http://hl7.org/fhir/StructureDefinition/Patient\",\"derivation\":\"constraint\",\"differential\":{\"element\":[{\"id\":\"Patient\",\"path\":\"Patient\"},{\"id\":\"Patient.identifier\",\"path\":\"Patient.identifier\",\"min\":1}]}}"
    },
    "target": { "canonical": "http://hl7.org/fhir/StructureDefinition/Patient" }
  }'
```

**REST (PowerShell):**

```powershell
$inlineJson = @'
{"resourceType":"StructureDefinition","url":"http://example.org/SD/MyPatient","name":"MyPatient","status":"active","kind":"resource","abstract":false,"type":"Patient","baseDefinition":"http://hl7.org/fhir/StructureDefinition/Patient","derivation":"constraint","differential":{"element":[{"id":"Patient","path":"Patient"},{"id":"Patient.identifier","path":"Patient.identifier","min":1}]}}
'@

$body = @{
  source = @{ json = $inlineJson }
  target = @{ canonical = "http://hl7.org/fhir/StructureDefinition/Patient" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/analyze" `
  -ContentType "application/json" -Body $body
```

---

### E. Classpath Resource (bundled inside the JAR)

For profiles you want to ship with the application. Place `.json` files under
`src/main/resources/fhir/profiles/` and reference them by relative path.

**Setup:**

```
src/main/resources/
└── fhir/
    └── profiles/
        ├── au-patient.json
        └── us-core-patient.json
```

**REST (cURL):**

```bash
curl -X POST http://localhost:8080/api/drift/analyze \
  -H "Content-Type: application/json" \
  -d '{
    "source": { "classpath": "fhir/profiles/au-patient.json" },
    "target": { "classpath": "fhir/profiles/us-core-patient.json" }
  }'
```

**REST (PowerShell):**

```powershell
$body = @{
  source = @{ classpath = "fhir/profiles/au-patient.json" }
  target = @{ classpath = "fhir/profiles/us-core-patient.json" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/analyze" `
  -ContentType "application/json" -Body $body
```

**CLI:**

```powershell
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=analyze `
  --source-classpath=fhir/profiles/au-patient.json `
  --target-classpath=fhir/profiles/us-core-patient.json
```

---

### F. Mix-and-Match (different input types for source and target)

You can freely combine any input types across source and target:

**cURL - URL source + canonical target:**

```bash
curl -X POST http://localhost:8080/api/drift/repair \
  -H "Content-Type: application/json" \
  -d '{
    "source": { "url": "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json" },
    "target": { "canonical": "http://hl7.org/fhir/StructureDefinition/Patient" }
  }'
```

**CLI - local file source + URL target:**

```powershell
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=repair `
  --source-file=D:\profiles\my-custom-patient.json `
  --target-url=https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json `
  --output=result.json
```

**CLI - canonical source + local file target:**

```powershell
java -jar build/libs/fdd-0.0.1-SNAPSHOT.jar `
  --spring.profiles.active=cli --mode=analyze `
  --source-canonical=http://hl7.org/fhir/StructureDefinition/Patient `
  --target-file=E:\fhir\custom-patient-profile.json
```

**PowerShell - inline JSON source + URL target:**

```powershell
$body = @{
  source = @{ json = (Get-Content -Raw .\my-profile.json) }
  target = @{ url = "https://build.fhir.org/ig/HL7/US-Core/StructureDefinition-us-core-patient.json" }
} | ConvertTo-Json -Depth 3

Invoke-RestMethod -Method Post -Uri "http://localhost:8080/api/drift/repair" `
  -ContentType "application/json" -Body $body
```

---

## API Endpoints Reference

### Core endpoints

| Method | URL                  | Description                                    |
|--------|----------------------|------------------------------------------------|
| `POST` | `/api/drift/analyze` | Detect drift between two profiles              |
| `POST` | `/api/drift/repair`  | Detect drift + generate validated StructureMap |

### Profile validation

| Method | URL                      | Description                    |
|--------|--------------------------|--------------------------------|
| `GET`  | `/api/validate/profiles` | Validate all custom profiles   |
| `GET`  | `/api/validate/standard` | Validate all standard profiles |
| `GET`  | `/api/validate/all`      | Validate all profiles          |

### Cache management

| Method   | URL                    | Description                    |
|----------|------------------------|--------------------------------|
| `GET`    | `/api/cache/llm/size`  | Number of cached LLM responses |
| `DELETE` | `/api/cache/llm`       | Clear LLM response cache       |
| `DELETE` | `/api/cache/templates` | Clear prompt template cache    |
| `DELETE` | `/api/cache`           | Clear all caches               |

### Monitoring & documentation

| Method | URL                    | Description                |
|--------|------------------------|----------------------------|
| `GET`  | `/swagger-ui.html`     | Interactive Swagger UI     |
| `GET`  | `/v3/api-docs`         | OpenAPI 3 spec (JSON)      |
| `GET`  | `/actuator/health`     | Application health check   |
| `GET`  | `/actuator/info`       | Application info           |
| `GET`  | `/actuator/metrics`    | Micrometer metrics         |
| `GET`  | `/actuator/prometheus` | Prometheus scrape endpoint |

---

## Configuration Reference

All configuration lives in `src/main/resources/application.yaml`:

```yaml
fdd:
  ai:
    provider: gemini                    # openai | anthropic | gemini | mistral
    temperature: 0.2                    # LLM temperature (lower = more deterministic)
    openai:
      api-key: ${OPENAI_API_KEY:}
      model: gpt-4o
      base-url: https://api.openai.com
    anthropic:
      api-key: ${ANTHROPIC_API_KEY:}
      model: claude-sonnet-4-20250514
    gemini:
      api-key: ${GEMINI_API_KEY:}
      model: gemini-2.5-pro
      base-url: https://generativelanguage.googleapis.com/v1beta/openai
    mistral:
      api-key: ${MISTRAL_API_KEY:}
      model: mistral-large-latest
      base-url: https://api.mistral.ai/v1
  validation:
    max-attempts: 3                     # Reflexion retries for Trust-but-Verify
  cache:
    enabled: true                       # LLM response caching
    ttl-minutes: 0                      # 0 = no expiry
    directory: .fdd-cache               # Cache file location
```

Gemini and Mistral use the **OpenAI-compatible** chat API, configured via
custom `base-url` properties. No special SDK is needed.

---

## Testing

### Unit tests (no API key required)

```bash
./gradlew test
```

This runs **all** unit tests including:

- `DriftControllerTest` - REST layer with mocked services
- `CacheManagementControllerTest` - Cache admin endpoints (WebMvcTest)
- `GlobalExceptionHandlerTest` - Exception -> HTTP status mapping for all custom exceptions
- `DriftAnalyzerTest` - LLM response parsing with mocked clients
- `RuleBasedDriftDetectorTest` - Deterministic drift rules (25 tests, all 5 categories)
- `ProfileContextBuilderTest` - Element extraction from HAPI StructureDefinitions
- `ProfileLoaderTest` - Profile resolution from canonical/URL/classpath
- `DriftOrchestrationServiceTest` - Pipeline orchestration with mocked components
- `MapGeneratorTest` - FML generation prompt assembly
- `MapValidatorTest` - Trust-but-Verify reflexion loop
- `SpringAiLlmClientTest` - Retry logic, cache integration, error handling
- `PromptTemplateServiceTest` - Template loading, Mustache substitution, caching
- `LlmResponseCacheTest` - File-based cache, TTL eviction, disabled mode
- `FmlUtilsTest` - Code-fence stripping (parameterised tests)
- `CliRunnerTest` - CLI argument parsing, mode dispatch, file I/O, error handling (12 tests)

### Experiment tests (require API key + Docker)

Three experiment test classes validate end-to-end behaviour:

| Experiment | Class                                | What it tests                                 |
|------------|--------------------------------------|-----------------------------------------------|
| 1          | `Experiment1DriftDetectionTest`      | Drift detection accuracy across profile pairs |
| 2          | `Experiment2SyntacticValidityTest`   | FML syntactic validity (HAPI compilation)     |
| 3          | `Experiment3SemanticCorrectnessTest` | Semantic correctness via Testcontainers HAPI  |

Run experiments (requires a valid LLM API key and Docker):

```bash
./gradlew test --tests "com.example.fdd.experiment.*"
```

---

## LLM Provider Selection & Free Tiers

| Provider      | Free Tier / Pricing                                               | Context Window | Recommendation                                                                  |
|---------------|-------------------------------------------------------------------|----------------|---------------------------------------------------------------------------------|
| **Gemini**    | Free tier: 15 RPM, 1M tokens/min, 1500 req/day                    | 1M+ tokens     | **Best for experimentation** - generous limits handle full FHIR profiles easily |
| **OpenAI**    | No free tier; pay-as-you-go (~$2.50/$10 per 1M in/out for gpt-4o) | 128K tokens    | Production quality, but costs add up with large profiles                        |
| **Mistral**   | Free tier available for smaller models                            | 128K tokens    | Good mid-range option                                                           |
| **Anthropic** | No free tier; pay-as-you-go                                       | 200K tokens    | Excellent quality, higher cost                                                  |

A full FHIR profile like US Core Patient can produce **50K-150K tokens** of context
(depending on element count and snapshot depth). Gemini's 1M token context window
handles this comfortably. For OpenAI, gpt-4o's 128K window is sufficient for most
profiles but very large IGs may need truncation.

**Recommendation:** Start with **Gemini** (`provider: gemini`) for development
and experimentation - it's free, has the largest context window, and handles
full profiles without truncation.

---

## Profile Inventory

FDD ships with bundled profiles under `src/main/resources/`:

### Standard Profiles

| Source                                     | Validation Status | Notes                                          |
|--------------------------------------------|-------------------|------------------------------------------------|
| **R4 Base** (`standard-profiles/r4/`)      | All valid         | Full snapshot profiles from `hl7.fhir.r4.core` |
| **R5 Base** (`standard-profiles/r5/`)      | All valid         | Validated with R5 context (not R4)             |
| **US Core** (`standard-profiles/us-core/`) | All valid*        | *HL7 wg extension error downgraded to warning  |
| **AU Core** (`standard-profiles/au-core/`) | Partial*          | *Missing AU Base parent profiles (expected)    |

### Custom Profiles

Three fictional healthcare organisations, each covering the same set of resource types:

| Organisation                               | Validation Status | Description                                         |
|--------------------------------------------|-------------------|-----------------------------------------------------|
| **TK-Soft** (`custom-profiles/tk-soft/`)   | All valid         | Software vendor - research-oriented extensions      |
| **IIT-Proj** (`custom-profiles/iit-proj/`) | All valid         | Academic hospital - strict data quality constraints |
| **Hemas** (`custom-profiles/hemas/`)       | All valid         | Hospital chain - admission/discharge workflows      |

**Resource types covered:** AllergyIntolerance, CarePlan, Condition, DiagnosticReport,
Encounter, Immunization, Location, Medication, MedicationRequest, Observation,
Organization, Patient, Practitioner, Procedure

**Drift types covered across custom profiles:**

- **CARDINALITY** - min/max changes (e.g., performer `min=1` vs `max=0`)
- **TERMINOLOGY** - different ValueSet bindings and binding strengths
- **STRUCTURAL** - mustSupport flags, type restrictions, reference targets, element removal
- **EXTENSION** - unique extension slices per organisation (e.g., chronic-disease, lab-priority)

### Gold Standard Test Data

Located in `src/test/resources/gold-standard/`. Each file contains curated drift
annotations used by Experiment 1 to measure detection accuracy (Precision/Recall/F1).

| Category            | Example                                          |
|---------------------|--------------------------------------------------|
| R4 vs US Core       | `r4-condition-vs-us-core-condition.json`         |
| R4 vs R5            | `r4-vs-r5-patient.json`                          |
| TK-Soft vs Hemas    | `tk-soft-condition-vs-hemas-condition.json`      |
| TK-Soft vs IIT-Proj | `tk-soft-patient-vs-iit-proj-patient.json`       |
| IIT-Proj vs Hemas   | `iit-proj-observation-vs-hemas-observation.json` |
| Hemas vs Standards  | `hemas-encounter-vs-r4-encounter.json`           |

---

## Validation Behaviour

### Profile Validation

FDD validates all profiles at load time using HAPI-FHIR's `FhirValidator`.
Certain known false-positive errors are **downgraded** to warnings instead of
blocking the pipeline:

| Pattern                                                                    | Cause                                            | Action                |
|----------------------------------------------------------------------------|--------------------------------------------------|-----------------------|
| `owning committee must be stated`                                          | US-Core profiles lack HL7 wg extension           | Downgraded to WARNING |
| `Profile reference ... has not been checked because it could not be found` | Parent profiles (e.g., AU Base) not on classpath | Downgraded to WARNING |
| `is not legal because it is not defined in the FHIR specification`         | HAPI R5 validator false positive                 | Downgraded to WARNING |

This applies both to the validation endpoint (`GET /api/validate/*`) and to
the drift analysis pipeline (profiles loaded for `POST /api/drift/analyze`).

### FML Validation Enhancements (Trust-but-Verify)

The reflexion loop includes several cost-saving and quality enhancements:

| Feature | Description | LLM Cost |
|---|---|---|
| **`autoFixRuleNames()`** | Deterministically adds `"rule_lineN"` names to unnamed complex rules - the #1 most common HAPI compilation error | Zero |
| **`collectAllErrors()`** | Scans FML for ALL compilation errors (not just the first) before sending to LLM in one batch call | Zero (scan) + 1 LLM call |
| **Multi-turn conversation** | `chatWithHistory()` maintains full conversation context so LLM sees all prior attempts | Same cost, better quality |
| **Full FML in follow-ups** | Follow-up messages include the full current FML with `>>>` markers on error lines | Same cost, better quality |
| **Anti-oscillation** | Detects when LLM reverts to previously broken code; boosts temperature 0.1->0.4 and injects warning | Same cost, fewer wasted cycles |
| **Line-level regression detection** | Tracks FML before each fix; if a "fix" produces a different error on the same line, injects `REGRESSION DETECTED` with old/new line content and both errors | Same cost, fewer wasted cycles |

---

## Output Files

Every `/api/drift/repair` request writes a timestamped output folder under `output/`:

```
output/20260307-013447-882-repair-19a64dfd/
├── metadata.json          # Request metadata (timestamp, type, labels)
├── request.json           # Original request body
├── drift-report.json      # DriftReport (all detected drift items)
├── response.json          # Full API response (RepairResponse)
├── validation.json        # ValidationSummary (per-cycle error messages)
├── coverage-report.json   # CoverageReport (structured, machine-readable)
├── coverage-report.txt    # Human-readable coverage breakdown (5 categories)
├── structure-map.fml      # Generated FML code
└── error.txt / error.json # Error trace (only if validation failed)
```

### Coverage Report Categories

The `coverage-report.txt` classifies every drift item:

| Status | Meaning | Data Impact | Acceptable? |
|---|---|---|---|
| `MAPPED` | Actively transformed by an FML rule | Preserved | Yes |
| `COVERED_BY_PARENT` | Parent element is mapped; sub-elements carry over | Preserved | Yes |
| `NO_RULE_NEEDED` | Profile metadata (mustSupport, extensions, etc.) | No data | Yes - no data to transfer |
| `SOURCE_DATA_LOSS` | Source element does not exist in target | Lost | Inherently acceptable - the target does not define this field, so no StructureMap can populate it. **Document which fields are dropped for clinical governance review.** |
| `UNMAPPABLE_NO_SOURCE` | Target element has no source equivalent | Empty | Review required - if `min >= 1` in the target profile, the transformed resource **will fail** target validation. Add a default-value rule or enrichment step. |

**Data Shareability %** = (Mapped + Covered by Parent) / (Total - Metadata) × 100

The actual Data Shareability percentage varies per profile pair. FDD now prints an
**INDUSTRY BENCHMARK** verdict directly in `coverage-report.txt`:

| Range | Verdict |
|---|---|
| ≥ 85% | EXCELLENT - meets USCDI/ONC certification requirements |
| ≥ 70% | GOOD - meets HL7 IPS and most national IG standards |
| ≥ 60% | ACCEPTABLE for cross-national or exploratory mapping |
| < 60% | BELOW BASELINE - review profile compatibility |

> **Cross-national mappings (e.g. AU Core ↔ US Core) typically land in the 55-70% range.**
> This is structurally expected - jurisdiction-specific extensions on both sides cannot
> be transferred and are correctly reported as source data loss, not as mapping failures.

---

## Project Structure

```
src/main/kotlin/com/example/fdd/
├── FddApplication.kt              # @SpringBootApplication entry point
├── api/                            # REST controllers and DTOs
│   ├── DriftController.kt         # /api/drift/analyze + /api/drift/repair
│   ├── CacheManagementController.kt # /api/cache/* admin endpoints
│   ├── GlobalExceptionHandler.kt  # @ControllerAdvice (5 exception types -> HTTP codes)
│   ├── ProfileValidationController.kt  # /api/validate/* endpoints
│   └── dto/                       # Request/Response data classes
│       ├── AnalyzeRequest.kt      # ProfileInput, AnalyzeRequest, RepairRequest
│       ├── AnalyzeResponse.kt
│       ├── RepairResponse.kt
│       └── ErrorResponse.kt
├── ai/                             # LLM integration layer
│   ├── LlmClient.kt               # Interface
│   ├── SpringAiLlmClient.kt       # Spring AI ChatModel wrapper (@Retryable)
│   ├── PromptTemplateService.kt   # Mustache-based template loading + caching
│   └── LlmResponseCache.kt       # File-based SHA-256 keyed LLM response cache
├── cli/                            # CLI mode (--spring.profiles.active=cli)
│   └── CliRunner.kt               # ApplicationRunner with argument parsing
├── config/                         # Spring configuration
│   ├── AiConfig.kt                # ChatModel bean creation (4 LLM providers)
│   ├── FddProperties.kt          # Type-safe @ConfigurationProperties binding
│   ├── FhirConfig.kt             # HAPI FhirContext R4 (@Primary) + R5 (@Qualifier) beans
│   ├── MetricsConfig.kt          # Micrometer TimedAspect bean
│   └── OpenApiConfig.kt          # SpringDoc OpenAPI / Swagger configuration
├── exception/                      # Custom exceptions
│   └── Exceptions.kt             # FddException hierarchy (5 exception types)
├── fhir/                           # FHIR-specific services
│   ├── ProfileLoader.kt          # Interface (canonical/URL/classpath/JSON/file)
│   ├── DefaultProfileLoader.kt   # HAPI-FHIR backed implementation + validation downgrading
│   ├── ProfileContextBuilder.kt  # Interface - element extraction & normalisation
│   └── DefaultProfileContextBuilder.kt
├── startup/
│   └── StartupTasks.kt           # Output folder cleanup on boot
├── model/                          # Domain model
│   ├── ProfileContext.kt          # ElementSummary (22 fields), TypeSummary, etc.
│   ├── DriftReport.kt            # Drift analysis output aggregate
│   ├── DriftItem.kt              # Single drift finding
│   ├── DriftType.kt              # Enum: 5 drift categories
│   ├── MapGenerationResult.kt    # FML + validity + messages
│   ├── CoverageReport.kt         # CoverageReport, CoverageItem, CoverageStatus enum
│   └── GoldStandardPair.kt       # Gold-standard annotations for experiments
├── output/                         # File-based output persistence
│   └── OutputStore.kt             # Timestamped output folders, coverage report builder
├── service/                        # Business logic
│   ├── DriftAnalyzer.kt          # Interface - hybrid drift analysis
│   ├── DefaultDriftAnalyzer.kt   # Rules + LLM merge implementation
│   ├── RuleBasedDriftDetector.kt  # Interface - deterministic rules
│   ├── DefaultRuleBasedDriftDetector.kt  # 20+ rules across 5 drift types
│   ├── MapGenerator.kt           # Interface - LLM-powered FML generation
│   ├── DefaultMapGenerator.kt
│   ├── CoverageAnalyzer.kt       # Cross-reference drift vs FML (deterministic, no LLM)
│   ├── ProfileValidationService.kt # Validate all bundled profiles
│   ├── DriftOrchestrationService.kt     # Interface - 4-stage pipeline orchestration
│   └── DefaultDriftOrchestrationService.kt
├── validation/                     # Trust-but-Verify
│   ├── MapValidator.kt           # Interface - FML compilation + reflexion
│   ├── DefaultMapValidator.kt    # HAPI StructureMapUtilities + autoFixRuleNames + reflexion
│   └── DriftProfileValidator.kt  # Pre-flight profile compatibility check
└── util/
    ├── FmlUtils.kt               # Code-fence stripping utility
    └── FhirValidationUtils.kt    # Validation downgrading patterns

src/main/resources/
├── application.yaml               # Main config (4 LLM providers, cache, actuator)
├── application-cli.yaml           # CLI profile (disables web server)
├── application-experiment.yaml    # Experiment profile (temp=0.1, verbose logging)
├── application-test.yaml          # Test profile (temp=0.0, dummy keys)
├── ai/                            # LLM prompt templates
│   ├── drift-analysis-system.txt
│   ├── drift-analysis-user.txt
│   ├── map-generation-system.txt
│   ├── map-generation-user.txt
│   ├── reflexion-system.txt
│   └── reflexion-user.txt
├── logback-spring.xml             # Console + rolling file appender config
├── standard-profiles/             # Standard FHIR profiles
│   ├── r4/                        # R4 base profiles (snapshot)
│   ├── r5/                        # R5 base profiles (snapshot)
│   ├── us-core/                   # US Core profiles
│   └── au-core/                   # AU Core profiles
├── custom-profiles/               # Custom FHIR profiles
│   ├── tk-soft/                   # TK-Soft profiles (differential)
│   ├── iit-proj/                  # IIT-Proj profiles (differential)
│   └── hemas/                     # Hemas profiles (differential)
└── fhir/profiles/                 # Optional: additional bundled profiles

src/test/kotlin/com/example/fdd/
├── FddApplicationTests.kt         # Spring context smoke test
├── ai/                             # AI layer tests
│   ├── SpringAiLlmClientTest.kt  # Retry, caching, error handling
│   ├── PromptTemplateServiceTest.kt  # Template loading, substitution, caching
│   └── LlmResponseCacheTest.kt   # File cache, TTL, disabled mode
├── api/                            # Controller tests
│   ├── DriftControllerTest.kt    # WebMvcTest for /api/drift/*
│   ├── CacheManagementControllerTest.kt  # WebMvcTest for /api/cache/*
│   └── GlobalExceptionHandlerTest.kt    # Exception -> HTTP status mapping
├── fhir/                           # FHIR layer tests
│   ├── ProfileLoaderTest.kt      # Canonical, JSON, URL resolution
│   └── ProfileContextBuilderTest.kt  # Element extraction, drift-focused filtering
├── service/                        # Service layer tests
│   ├── DriftAnalyzerTest.kt      # LLM response parsing, markdown stripping
│   ├── RuleBasedDriftDetectorTest.kt  # 25+ tests across all 5 drift types
│   ├── DriftOrchestrationServiceTest.kt  # Pipeline wiring, error propagation
│   └── MapGeneratorTest.kt       # FML extraction, prompt composition
├── cli/
│   └── CliRunnerTest.kt          # CLI argument parsing, mode dispatch, file I/O
├── util/
│   └── FmlUtilsTest.kt           # Code-fence stripping (parameterised)
├── validation/
│   └── MapValidatorTest.kt       # Trust-but-Verify reflexion loop
└── experiment/                     # Integration experiments (API key + Docker)
    ├── GoldStandardLoader.kt      # Loader + P/R/F1 metrics
    ├── Experiment1DriftDetectionTest.kt
    ├── Experiment2SyntacticValidityTest.kt
    └── Experiment3SemanticCorrectnessTest.kt
```

---

## License

MIT License

Copyright (c) 2026 Tines Kumar

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
