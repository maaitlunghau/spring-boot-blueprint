# Auth Module TODO — items deferred by other features

Living document. Any feature built before the Auth (JWT) module exists that
has to leave something half-done because it needs an authenticated-caller
identity or a request-time auth check should add an entry here — one section
per originating feature — so nothing gets silently forgotten or duplicated
when the Auth module finally lands.

When the Auth module is built: work through every open item below, check it
off, and note the commit/PR that closed it. Don't close an item just because
JWT now exists — verify the specific gap described is actually fixed.

---

## From: Ban/Unban User feature (spec: `docs/superpowers/specs/2026-08-29-ban-unban-user-design.md`)

### 1. Request-time ban enforcement — NOT IMPLEMENTED

**Gap:** A banned user (`enabled == false`) can still successfully call any
API endpoint. Nothing currently checks ban state per-request.

**Why deferred:** There is no JWT filter yet (`module/auth`, `filter/`,
`security/` are all empty scaffolding). `SecurityConfig` has no per-request
hook that runs application logic — enforcing this needs a JWT filter that, on
every authenticated request, either (a) checks `user.isEnabled()` against the
DB/cache, or (b) relies on short-lived access tokens + a token-revocation
mechanism so a banned user's existing token stops working quickly.

**What to do when Auth lands:**
- Decide the enforcement point: custom `OncePerRequestFilter` in `filter/`
  that loads the `User` by the JWT's subject claim and checks `isEnabled()`,
  OR make `UserDetailsService.loadUserByUsername` return a `UserDetails` whose
  `isEnabled()` reflects the DB value (Spring Security already short-circuits
  disabled accounts at authentication time) plus a short access-token TTL so
  an already-issued token expires quickly after a ban.
- Decide what a blocked request returns — likely `403 Forbidden` via
  `GlobalExceptionHandler`, with a clear message (don't leak whether the
  account exists vs. is banned vs. bad credentials — same spirit as the
  existing `BadCredentialsException` → generic-401 handling).
- If going the token-revocation route (rather than per-request DB check),
  decide where revoked/short-lived token state lives — Redis is already wired
  into this project (`spring-boot-starter-data-redis`) and unused, a natural
  fit for a ban-revocation blacklist keyed by user id or token jti.

### 2. Self-ban prevention — NOT IMPLEMENTED

**Gap:** An admin can currently ban any user including themselves (in code —
no check exists). "Cannot ban a user with role ADMIN" **is** implemented
(`UserServiceImpl.banUser`), but that only blocks admin-bans-admin, not
admin-bans-self, since self-ban needs to compare the *target* id against the
*caller's* id.

**Why deferred:** There is no authenticated-caller identity available inside
`UserServiceImpl` today — `SecurityContext` isn't populated from the `User`
entity at all (Spring Security currently either uses its own auto-generated
dev user, or is fully open via the temporary `permitAll()` in
`SecurityConfig`). There's nothing to compare the target id against.

**What to do when Auth lands:**
- Once `SecurityContextHolder` reliably holds the calling `User`'s id (via
  whatever `UserDetails`/`Authentication` principal type the Auth module
  settles on), add a check at the top of `UserServiceImpl.banUser`:
  `if (id.equals(currentUserId)) throw new BadRequestException("Cannot ban yourself")`.
- Get the current user id via constructor-injected access to
  `SecurityContextHolder` (or whatever helper the Auth module introduces —
  check for a `CurrentUserProvider`-style bean before rolling a new one).

### 3. Ban/Unban admin-only — documented business rule, not enforced

**Gap:** `PATCH /api/users/{id}/ban` and `/unban` are intended to be
admin-only, but there is no `@PreAuthorize`/method-security check — anyone
who can reach the endpoint at all can call it. This mirrors the exact same
already-accepted gap on `POST /api/users` (`CreateUserRequest.role` is
"admin-only by convention", not enforced — see `.claude/rules/coding-standards.md`
"Decisions to respect").

**Why deferred:** No role-based authorization exists anywhere yet — `SecurityConfig`
only has `.anyRequest().authenticated()` (or currently `.permitAll()`
temporarily), no `@EnableMethodSecurity`, no `@PreAuthorize` usage anywhere in
the codebase.

**What to do when Auth lands:**
- Enable method security (`@EnableMethodSecurity` on a config class).
- Add `@PreAuthorize("hasRole('ADMIN')")` to `banUser`/`unbanUser` in
  `UserController` (and audit every other endpoint documented as "admin-only
  by convention" at the same time — `POST /api/users` at minimum — since
  they share the same gap and should close together, not piecemeal).

---

## From: API Rate Limiting feature (spec: `docs/superpowers/specs/2026-08-30-rate-limiting-design.md`)

### 1. Rate limiting is IP-only — no per-user tier

**Gap:** `filter/RateLimitFilter` keys every bucket on `request.getRemoteAddr()`
only. Every client behind the same IP (NAT, corporate network, public WiFi,
mobile carrier-grade NAT) shares one bucket — a legitimate burst of different
users can trip the limit for all of them, and there's no way to give a known,
authenticated user a more generous (or stricter, e.g. for a flagged account)
allowance than an anonymous caller.

**Why deferred:** Same root cause as the Ban/Unban gaps above — no
authenticated-caller identity exists yet (`SecurityContext` isn't populated
from `User`).

**What to do when Auth lands:**
- Add a second bucket key dimension for authenticated requests: once a
  request has a resolved `User` id (from the JWT), key its bucket on
  `userId` instead of (or in addition to) IP — e.g.
  `rate-limit:user:{userId}:{tier}` vs. the current `rate-limit:{ip}:{tier}`.
- Don't remove IP-based limiting outright — unauthenticated endpoints (public
  registration, login itself) still need it, and it's the only defense until
  a request is authenticated.
- Decide whether authenticated users get a *looser* tier (trusted, accountable
  identity) — this was the direction discussed during brainstorming but not
  finalized since Auth didn't exist yet to design against.
- A future `POST /api/auth/login` endpoint (once it exists) will need its own
  strict IP-based tier here regardless of the above — brute-force protection
  on login is independent of per-user limiting and should be added to
  `RateLimitConfig.SENSITIVE_RULES` when that endpoint is built, not
  forgotten because "rate limiting already exists."

---

## From: Soft Delete & Restore User feature (spec: `docs/superpowers/specs/2026-08-30-soft-delete-restore-user-design.md`)

### 1. Self-delete prevention — NOT IMPLEMENTED

**Gap:** An admin can currently soft-delete or purge their own account — nothing
compares the target id against the caller's id, same root cause as the
existing self-ban gap.

**Why deferred:** There is no authenticated-caller identity available inside
`UserServiceImpl` today — see the identical reasoning already written up for
self-ban prevention above.

**What to do when Auth lands:** Once `SecurityContextHolder` reliably holds
the calling `User`'s id, add the same guard used for self-ban to
`UserServiceImpl.deleteUser` and `purgeUser`:
`if (id.equals(currentUserId)) throw new BadRequestException("Cannot delete yourself")`.

---

## From: (add future entries here, one `## From: <feature>` section per feature)
