# Coding Standards — spring-boot-blueprint

No domain code exists yet to derive real naming/style conventions from — `SpringBootBlueprintApplication` is the only class, and it's the default Spring Initializr boilerplate. Once real classes exist (controllers, services, entities, DTOs...), replace this file with the conventions actually followed, matching `architecture.md`.

## What's already decided (from `pom.xml` / this repo's tooling)

- Classes `PascalCase`, methods/variables `camelCase`, packages `lowercase.no_separators` — standard Java, nothing project-specific yet.
- Lombok is on the classpath (optional) but unused so far — no class currently has a Lombok annotation.
