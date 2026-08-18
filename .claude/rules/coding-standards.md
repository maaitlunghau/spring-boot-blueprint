# Coding Standards — spring-boot-mini-project

## Naming

| Kind | Convention | Example |
|------|-----------|---------|
| Controller | `<Domain>Controller` | `AuthController` |
| Service interface | `<Domain>Service` | `RefreshTokenService` |
| Service impl | `<Domain>ServiceImpl` | `RefreshTokenServiceImpl` |
| Repository | `<Domain>Repository` | `UserRepository` |
| Entity | `<Domain>` (no suffix) | `User`, `RefreshToken` |
| Request DTO | `<Verb><Domain>Request` | `CreateUserRequest`, `LoginRequest` |
| Response DTO | `<Domain>Response` | `UserResponse`, `AuthResponse` |
| Exception | `<Reason>Exception`, extends `AppException` | `DuplicateResourceException` |
| Config | `<Concern>Config` | `SecurityConfig` |
| Unit test | `<ClassName>Test` | `RefreshTokenServiceImplTest` |
| Integration test | `<ClassName>IntegrationTest` | `AuthServiceImplIntegrationTest` |

Classes `PascalCase`, methods/variables `camelCase`, constants `UPPER_SNAKE_CASE`, packages `lowercase.no_separators`.

## Java patterns actually used in this codebase

- **Constructor injection only.** No `@Autowired` field injection anywhere — keep it that way.
- **Records for every DTO.** No Lombok on DTOs — records already give boilerplate-free immutability.
- **Lombok `@Getter` on entities** (not `@Data`, not `@Setter`) — mutation goes through named domain methods (`revoke()`, `changeRole()`), not setters. See any entity under `module/*/entity/` for the pattern.
- **`Optional` from repositories, never a bare null return.** Callers either `.map(...)` or `.orElseThrow(() -> new ResourceNotFoundException(...))`.
- **`@Transactional(readOnly = true)` at class level, override per write method** — see the gotcha callout in `backend-patterns.md`. Not optional — skipping the override is exactly the bug already fixed once in this repo.

## Comments

Only when the *why* isn't obvious from the code — a workaround, a non-obvious constraint, a subtle invariant. Never a comment restating what a well-named method already says. `RefreshTokenServiceImpl`'s existing inline comments (explaining intent for the reuse-detection/rotation logic) are the right calibration — imitate that, don't add more than that.

## Tests

Name tests for the behavior, not the method under test: `loginPersistsARefreshTokenRow`, `rotateDetectsReuseEvenWhenTheRevokedTokenIsAlsoExpired` — see the existing test classes under `src/test/java` for the house style. Arrange/Act/Assert; no comment headers needed if the test is short enough to read as one block.
