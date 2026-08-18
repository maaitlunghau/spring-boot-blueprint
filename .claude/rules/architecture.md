# Architecture — spring-boot-mini-project

## Module layout

Feature-first, not layer-first, at the top level. Each domain gets its own module under `src/main/java/com/maaitlunghau/spring_boot_mini_project/module/<domain>/`, and *within* that module the standard Spring layers apply:

```
module/<domain>/
├── controller/v1/     ← @RestController, versioned (v1/, v2/, ... only when a breaking change needs it)
├── service/           ← interface
│   └── impl/          ← implementation (<Name>ServiceImpl)
├── repository/        ← extends JpaRepository
├── entity/
└── dto/
    ├── request/
    └── response/
```

Cross-cutting code that isn't domain-specific lives at the top level instead: `common/` (shared DTOs like `ApiResponse`/`PageResponse`, `BaseEntity`), `config/` (Spring config + the security filter chain), `exception/` (the `AppException` hierarchy + `GlobalExceptionHandler`), `security/` (JWT + `UserDetailsService`), `util/`.

Existing modules: `module/auth/` (login/refresh/logout, refresh-token rotation with reuse detection), `module/user/` (user CRUD + role management). A new domain follows the same shape — `module/user/` is the simpler reference example.

## Layer responsibilities

- **Controller**: extract request data, call the service, shape the response. No business logic. Constructor injection only — never `@Autowired` field injection (every existing controller follows this).
- **Service interface + impl**: business logic lives in the `*Impl`. Every service in this codebase currently has exactly one implementation — don't add a second interface+impl split for a new service unless you actually expect (or already have) more than one; a single concrete `@Service` class is fine and simpler.
- **Repository**: `extends JpaRepository<Entity, Id>`. Derived query methods first; `@Query` (JPQL) when a derived name would be unreadable; native SQL only when JPQL genuinely can't express it.
- **Entity**: `@Entity`, extends `common/entity/BaseEntity.java` (gives every entity `id`/`createdAt`/`updatedAt`/`version` for free — optimistic locking via `@Version`). Lombok `@Getter`, a protected no-arg constructor for JPA, a public constructor for the fields that matter at creation. Mutation goes through named domain methods (`user.changeRole(...)`, `token.revoke(...)`) — not raw setters.
- **DTO**: Java records, request/response split into their own subpackages. Bean Validation on request records; a static `from(Entity)` factory on response records when the response isn't a 1:1 mirror.

## Exception handling

`exception/AppException` (extends `RuntimeException`) is the base of every domain exception (`ResourceNotFoundException`, `DuplicateResourceException`, `BadRequestException`, `InvalidRefreshTokenException`, `RefreshTokenReuseException`, ...). `exception/GlobalExceptionHandler` (`@RestControllerAdvice`) maps each to its HTTP status via `common/dto/ApiResponse`. A new failure mode gets a new `AppException` subclass plus a handler entry — never a raw `throw new RuntimeException(...)` from a service, and never a bare try/catch in a controller that should instead let the global handler map it.

## Concrete code templates

See `backend-patterns.md` for copy-pasteable controller/service/repository/entity/DTO snippets in this project's actual style — not duplicated here.
