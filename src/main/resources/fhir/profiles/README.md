# FHIR Profile Resources

This directory is intentionally kept as a documentation placeholder.
Profile files used by FHIR Drift Doctor are stored at the classpath roots below:

## Actual Profile Layout

```
src/main/resources/
├-- custom-profiles/
│   ├-- tk-soft/        # TK-Soft organisation custom profiles (14 profiles)
│   ├-- iit-proj/       # IIT Project custom profiles         (14 profiles)
│   └-- hemas/          # Hemas organisation custom profiles  (14 profiles)
└-- standard-profiles/
    ├-- r4/             # Base FHIR R4 StructureDefinitions   (14 profiles)
    ├-- r5/             # Base FHIR R5 StructureDefinitions   (14 profiles)
    ├-- us-core/        # US Core IG profiles                 (14 profiles)
    └-- au-core/        # AU Core IG profiles                 (11 profiles)
```

## Profile Loading

Profiles are loaded by `DefaultProfileLoader` using Spring's `ClassPathResource`.

- **Custom profiles** loaded via `classpath:custom-profiles/<org>/<file>.json`
- **Standard profiles** loaded via `classpath:standard-profiles/<source>/<file>.json`
- **R5 profiles** (`source = r5`) are validated using the R5 HAPI-FHIR context

## Adding New Profiles

1. Place your StructureDefinition JSON (R4, UTF-8, no BOM) in the appropriate folder.
2. Restart the application - the profiles are auto-discovered on startup.
3. Use `GET /api/validate/profiles` to confirm they are valid before running drift analysis.
