# Tech Defaults — spring-boot-blueprint

The actual stack in `pom.xml` — don't add an alternative without discussing it first.

| Concern | Dependency | Notes |
|---------|-----------|-------|
| Web | `spring-boot-starter-webmvc` | Spring Boot 4 renamed this from `-web` to disambiguate from WebFlux |
| JPA | `spring-boot-starter-data-jpa` | Hibernate |
| Security | `spring-boot-starter-security` | dependency only so far — no `SecurityConfig`/JWT filter implemented yet, despite `app.jwt.*` already present in `application.yml` |
| Validation | `spring-boot-starter-validation` | Jakarta Bean Validation |
| Redis | `spring-boot-starter-data-redis` | password-protected in `docker-compose.yml`, no usage in code yet |
| Database driver | `com.mysql:mysql-connector-j` (runtime) | MySQL 8.4 only — no H2, no alternate drivers |
| Migrations | `flyway-core` + `flyway-mysql` | `src/main/resources/db/migration/`, baseline `V1__init.sql` is still empty (no entities yet) |
| Lombok | `org.projectlombok:lombok` (optional) | not yet used in any class |
| Devtools | `spring-boot-devtools` (runtime, optional) | |

- **Java 21**, **Spring Boot 4.1.0** — parent POM manages transitive versions, don't pin them individually.
- **Build**: Maven via `./mvnw` — never assume a global `mvn` install. `pom.xml` sets `<finalName>app</finalName>` so `target/app.jar` matches what `Dockerfile` copies — don't remove it without updating `Dockerfile` too.
- **Test stack**: JUnit 5 + Mockito + AssertJ, pulled in transitively via the split `spring-boot-starter-*-test` starters this Spring Boot version uses. Only one test exists so far (`SpringBootBlueprintApplicationTests`, context-load only).

## Not used in this project

- H2 / in-memory DB — not added.
- MapStruct / ModelMapper — no DTOs exist yet to map.
- Swagger/OpenAPI — not added.
