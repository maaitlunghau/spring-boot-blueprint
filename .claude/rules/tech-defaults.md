# Tech Defaults — spring-boot-mini-project

The actual stack already in `pom.xml` — don't add an alternative without discussing it first.

| Concern | Dependency | Notes |
|---------|-----------|-------|
| Web | `spring-boot-starter-webmvc` | Spring Boot 4 renamed this from `-web` to disambiguate from WebFlux |
| JPA | `spring-boot-starter-data-jpa` | Hibernate |
| Security | `spring-boot-starter-security` | + `jjwt-api`/`jjwt-impl`/`jjwt-jackson` 0.13.0 for JWT |
| Validation | `spring-boot-starter-validation` | Jakarta Bean Validation |
| Redis | `spring-boot-starter-data-redis` | token blacklist + rate limiting |
| Database driver | `com.mysql:mysql-connector-j` (runtime) | MySQL 8.4 only — no H2, no alternate drivers |
| API docs | `springdoc-openapi-starter-webmvc-ui` 3.0.0 | public Swagger UI at `/swagger-ui/index.html` — deliberate choice, see `docs/known-issues.md` |
| Lombok | `org.projectlombok:lombok` (optional) | `@Getter` on entities only — see `coding-standards.md` |

- **Java 21**, **Spring Boot 4.1.0** — parent POM manages transitive versions, don't pin them individually.
- **Build**: Maven via `./mvnw` — never assume a global `mvn` install.
- **Test stack**: JUnit 5 + Mockito + AssertJ, pulled in transitively via the split `spring-boot-starter-*-test` starters this Spring Boot version uses.

## Not used in this project

- H2 / in-memory DB — every test that needs a database uses the real docker-compose MySQL.
- MapStruct / ModelMapper — DTOs map by hand (`from(entity)` static factories) so the mapping stays visible and debuggable.
- Flyway / Liquibase — not yet added; `ddl-auto: update` is a known, tracked gap (see `docs/known-issues.md` and `README.md`'s roadmap), not an oversight to silently "fix" without discussing the migration plan first.
