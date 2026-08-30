# Design Spec — API Rate Limiting

**Date:** 2026-08-30
**Branch:** `feature/user-management`
**Status:** Approved by user, pending implementation plan

## Goal

Protect the API from being overwhelmed by too many requests in a short window,
using Redis-backed Bucket4j rate limiting enforced in a servlet filter that
runs before Spring Security — so an over-limit request is rejected as cheaply
as possible, before any auth/controller/DB work happens.

## Scope decisions (from brainstorming session)

- Not a single global limit for every endpoint. Limits are **tiered by path**:
  a stricter tier for sensitive/write-heavy endpoints, a looser default tier
  for everything else.
- Rate-limit key is the **client IP only** — there is no authenticated-caller
  identity yet (no JWT/SecurityContext tied to `User`, same gap already
  tracked for self-ban in `docs/AUTH_MODULE_TODO.md`). Per-user rate limiting
  is deferred until the Auth module exists — new entry added to
  `docs/AUTH_MODULE_TODO.md` for this.
- Algorithm/library: **Bucket4j** (token bucket — smooths bursts better than
  fixed-window counters) backed by **Redis** via `bucket4j-redis`'s
  `LettuceBasedProxyManager` — matches the Lettuce client the project already
  pulls in via `spring-boot-starter-data-redis`. This is the **first real use
  of Redis** in the codebase (previously wired in but unused).
- Enforcement point: a `OncePerRequestFilter` (`filter/RateLimitFilter`, the
  `filter/` package was scaffolded for exactly this kind of cross-cutting
  concern) registered via `SecurityConfig.addFilterBefore(...)` so it runs
  ahead of the entire Spring Security chain — an over-limit request never
  reaches authentication, authorization, or the controller.
- Policy is defined as a small config list of `(path pattern, HTTP method
  pattern, capacity, refill period)` matched with `AntPathMatcher` — not
  annotation-based (`@RateLimit` on controller methods). Simpler, keeps the
  whole policy visible in one place, sufficient for this project's current
  endpoint count. Revisit only if the number of distinct policies grows large
  enough that a central list becomes unwieldy.

## Default policy (tunable, not load-bearing)

| Tier | Applies to | Limit |
|---|---|---|
| Sensitive | `POST /api/users`, `PATCH /api/users/*/ban`, `PATCH /api/users/*/unban`, `POST /api/users/*/avatar` | 10 requests / minute / IP |
| Default | Everything else | 100 requests / minute / IP |

## Components

### `config/RateLimitConfig.java`

- Defines the policy table (a small `record RateLimitRule(String pathPattern, String httpMethod, int capacity, Duration refillPeriod)` list, or equivalent) as a `private static final List<RateLimitRule>`.
- Builds the `bucket4j-redis` `LettuceBasedProxyManager` bean from the existing autoconfigured Redis connection (`spring.data.redis.*` — no new connection config needed, reuses what's already there for CI/local dev).

### `filter/RateLimitFilter.java`

- `OncePerRequestFilter`. For each request:
  1. Resolve client IP (`request.getRemoteAddr()` — no reverse-proxy `X-Forwarded-For` trust logic yet since there's no reverse proxy in front of this app currently; note this as a known simplification, not a bug, if a proxy/load balancer is added later).
  2. Match the request path+method against `RateLimitConfig`'s rule list (`AntPathMatcher`), falling back to the default tier if nothing matches.
  3. Build/derive a Bucket4j bucket key `"rate-limit:{ip}:{tierName}"`, get or create the bucket from the `LettuceBasedProxyManager` with that tier's capacity/refill.
  4. `tryConsume(1)` — if allowed, call `filterChain.doFilter(...)` and continue. If not allowed, write the 429 response directly (see below) and return without calling the chain.

### Response on rejection

Because this filter runs **before** Spring's `DispatcherServlet`/`@RestControllerAdvice` machinery, `GlobalExceptionHandler` cannot intercept anything thrown here — the filter must build and write the JSON response itself:

- Status `429 Too Many Requests`.
- Body: the same `ApiResponse` envelope shape used everywhere else (`status`, `message`, `data: null`, `timestamp`) — written via the already-autoconfigured Jackson `ObjectMapper` injected into the filter, `response.getWriter().write(...)`, `response.setContentType("application/json")`.
- `Retry-After` header (seconds until the bucket has a token again, from Bucket4j's `ConsumptionProbe.getNanosToWaitForRefill()`).

## Infrastructure additions

- `pom.xml`: `bucket4j-redis` (and its `bucket4j-core` transitive dependency).
- No new environment variables — reuses the existing `REDIS_HOST`/`REDIS_PASSWORD` already in `.env`/`docker-compose.yml`/`application.yml`.
- No CI changes needed: Redis is already a CI service container (`.github/workflows/ci.yml` already has it, added long before this feature).

## Deferred to Auth module

New entry to add to `docs/AUTH_MODULE_TODO.md`: once an authenticated-caller
identity exists, add a per-user rate-limit tier (looser than the anonymous/IP
tier, since a logged-in user is a known, accountable identity) layered on top
of the existing IP-based limiting — don't replace IP-based limiting outright,
since unauthenticated endpoints still need it.

## Testing approach

Following the project's established practice (live/manual verification, per
`tech-defaults.md`) — verify by hammering an endpoint past its limit with
repeated `curl` calls and confirming the 429 response, headers, and that the
bucket correctly refills after the window. No automated tests planned unless
the user asks for them, consistent with how Ban/Unban's RabbitMQ/outbox
plumbing was verified.
