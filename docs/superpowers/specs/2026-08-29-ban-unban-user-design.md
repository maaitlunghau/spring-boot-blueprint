# Design Spec — Ban/Unban User

**Date:** 2026-08-29
**Branch:** `feature/user-management`
**Status:** Approved by user, pending implementation plan

## Goal

Let an admin ban/unban a user (temporarily or permanently), notify the affected
user by email when it happens, and lay groundwork that a future JWT-based Auth
module can build on to actually enforce the ban at the request layer.

This spec covers **only** ban/unban + email notification. Enforcing the ban at
request time (rejecting API calls from a banned user) is explicitly out of
scope here — see `docs/AUTH_MODULE_TODO.md` for why and what's deferred.

## Scope decisions (from brainstorming session)

- Reuse the existing `User.enabled` field as the ban flag (`enabled == false`
  means banned) instead of adding a separate `banned` boolean — matches the
  Spring Security `UserDetails.isEnabled()` convention this field already
  exists for.
- Bans can be temporary (`bannedUntil` set) or permanent (`bannedUntil` null).
  A scheduled job auto-unbans expired temporary bans.
- No ban-history/audit table — only the current ban state is tracked (reason,
  timestamp, expiry). Re-banning overwrites the previous reason/timestamp.
- Admin cannot ban a user with role `ADMIN` (enforced now). Admin cannot
  self-ban — **deferred**, needs an authenticated caller identity that doesn't
  exist yet (see `docs/AUTH_MODULE_TODO.md`).
- Re-banning an already-banned user, or unbanning an already-active user, is a
  `409 Conflict`, not a no-op.
- Email delivery goes through RabbitMQ (learning goal, explicitly chosen over
  simpler `@Async` + direct SMTP) with a **Transactional Outbox** in front of
  it, so the DB write (ban/unban) and the "intent to notify" are atomic. The
  outbox is deliberately over-engineered relative to this project's current
  scale — accepted as a learning trade-off, not a scope-creep accident.
- RabbitMQ consumer runs in-process (same Spring Boot app) — no separate
  deployable. Exchange/routing-key scheme is designed to be generic so a
  future consumer (order processing, email verification, ...) can reuse the
  same exchange without redesign.

## Data model

### `User` entity additions

New columns on `users` (migration `V3__add_ban_fields_to_users.sql`, verify
against live `ddl-auto=update` schema before writing, per project convention):

```
banned_reason  VARCHAR(255) NULL
banned_at      TIMESTAMP    NULL
banned_until   TIMESTAMP    NULL   -- NULL = permanent ban
```

New business methods on `User` (no generic setters, per coding-standards.md):

```java
public void ban(String reason, Instant until) {
    this.enabled = false;
    this.bannedReason = reason;
    this.bannedAt = Instant.now();
    this.bannedUntil = until;
}

public void unban() {
    this.enabled = true;
    this.bannedReason = null;
    this.bannedAt = null;
    this.bannedUntil = null;
}
```

### `UserResponse` additions

Add `bannedReason` and `bannedUntil` (both nullable) so admins can see ban
state when listing/viewing users. `enabled` is already exposed.

### New table: `outbox_events`

Migration `V4__create_outbox_events_table.sql`. Entity
`common/messaging/outbox/OutboxEvent` extends `BaseEntity` (reuses UUIDv7 id +
`createdAt`/`updatedAt`/`version` — the `version` column gives optimistic
locking for free, relevant if the poller ever runs on more than one instance).

```
aggregate_type  VARCHAR(100) NOT NULL   -- e.g. "User"
aggregate_id    BINARY(16)   NOT NULL   -- UUID of the User
routing_key     VARCHAR(100) NOT NULL   -- e.g. "user.banned"
payload         TEXT         NOT NULL   -- JSON-serialized event DTO
status          VARCHAR(20)  NOT NULL   -- PENDING | PUBLISHED | FAILED
retry_count     INT          NOT NULL DEFAULT 0
published_at    TIMESTAMP    NULL
last_error      VARCHAR(1000) NULL
```

`OutboxStatus` enum: `PENDING`, `PUBLISHED`, `FAILED`.

## API

Both endpoints follow existing `UserController` conventions: constructor
injection, `ResponseEntity<ApiResponse<T>>`, `@Valid @RequestBody` where
applicable. Business-rule-only "admin-only" (not yet enforced by
`@PreAuthorize` — same undone state as `CreateUserRequest.role` today; tracked
in `docs/AUTH_MODULE_TODO.md`).

```java
PATCH /api/users/{id}/ban
Body: BanUserRequest(
    @NotBlank String reason,
    Instant bannedUntil   // nullable = permanent; if present must be in the future
)
-> 200 + ApiResponse<UserResponse>, message "User banned successfully"

PATCH /api/users/{id}/unban
No body
-> 200 + ApiResponse<UserResponse>, message "User unbanned successfully"
```

## Service layer

`UserService` gains:

```java
UserResponse banUser(UUID id, BanUserRequest request);
UserResponse unbanUser(UUID id);
```

`UserServiceImpl.banUser`/`unbanUser` are **plain `@Transactional`** methods
(not `Propagation.NOT_SUPPORTED`) — unlike avatar upload, there is no external
I/O call inline anymore; the outbox insert is a DB write in the same
transaction as the `User` update. Both:

1. Load the `User` (`ResourceNotFoundException` if missing).
2. `banUser` only: reject if `user.getRole() == Role.ADMIN`
   (`BadRequestException`, "Cannot ban a user with ADMIN role"). Self-ban check
   deferred — see `docs/AUTH_MODULE_TODO.md`.
3. State check: `banUser` throws `UserAlreadyBannedException` (409) if
   `!user.isEnabled()` already; `unbanUser` throws `UserNotBannedException`
   (409) if `user.isEnabled()` already.
4. Call `user.ban(...)`/`user.unban()`, `save(...)`.
5. Build the event DTO (`UserBannedEvent`/`UserUnbannedEvent`) and write it to
   the outbox via `OutboxEventWriter.write("User", id, routingKey, event)` —
   same transaction, so this can never desync from the `User` row.

## New exceptions

`exception/UserAlreadyBannedException` and `exception/UserNotBannedException`,
both extend `AppException` with `HttpStatus.CONFLICT` — picked up
automatically by `GlobalExceptionHandler`, no per-controller handler needed.

## Messaging: Outbox → RabbitMQ → Consumer → Email

### Event DTOs (`module/user/event/`)

```java
public record UserBannedEvent(UUID userId, String email, String fullName, String reason, Instant bannedUntil) {}
public record UserUnbannedEvent(UUID userId, String email, String fullName) {}
```

### Outbox write side (`common/messaging/outbox/`)

`OutboxEventWriter` — injected into `UserServiceImpl`, serializes the event
DTO to JSON (Jackson `ObjectMapper`) and persists an `OutboxEvent` row with
`status = PENDING`. No network call — pure DB write, safe inside any
transaction.

### Outbox poller (`scheduler/OutboxPublisherScheduler`)

`@Scheduled(fixedDelay = 5000)` (5s — fine for a learning project's volume):

1. Fetch a page of `PENDING` rows (`OutboxEventRepository`, ordered by
   `createdAt`, limited — e.g. `findTop50ByStatusOrderByCreatedAtAsc`).
2. For each: deserialize `payload`, call `EventPublisher.publish(routingKey, payload)`.
3. On success: mark `PUBLISHED`, set `publishedAt`.
4. On failure: increment `retryCount`; if `retryCount >= 5`, mark `FAILED` +
   `lastError` (needs manual inspection); otherwise leave `PENDING` for the
   next poll (the poll interval itself is the retry backoff — no need for
   per-row scheduling at this scale).

Known accepted limitation: at-least-once delivery — a crash between a
successful broker publish and marking the row `PUBLISHED` would cause a
duplicate publish on the next poll. Not mitigated (no consumer-side dedup
table) — acceptable for a non-critical notification email at this project's
scale; noted here rather than silently ignored.

### RabbitMQ topology (`config/RabbitMQConfig.java`)

Generic topic exchange, reusable by future producers (order events, email
verification, ...) without redesign:

```
Exchange:  notification.exchange           (topic)
Queue:     user.ban.notification.queue     bound to routing key "user.banned"
Queue:     user.unban.notification.queue   bound to routing key "user.unbanned"
DLX:       notification.dlx                (topic, for dead-lettered messages)
DLQ:       notification.dlq                bound to DLX with "#"
```

Both main queues declare `x-dead-letter-exchange = notification.dlx` so a
message that exhausts consumer-side retries (see below) lands in `notification.dlq`
for manual inspection, rather than being silently dropped or looping forever.

`Jackson2JsonMessageConverter` for message (de)serialization — JSON, not Java
serialization, so messages stay human-readable and language-agnostic.

### Publisher (`common/messaging/`)

```java
public interface EventPublisher {
    void publish(String routingKey, Object payload);
}
```

`RabbitEventPublisher` — the only class importing `RabbitTemplate`/Spring AMQP
types, same "one wrapper class knows the vendor" pattern as
`CloudinaryStorageService`. Called only by `OutboxPublisherScheduler`, never
directly by module services.

### Consumer (`module/notification/listener/`)

Two `@RabbitListener` methods (one per queue — no if/else branching on event
type in a shared listener):

```java
@RabbitListener(queues = "user.ban.notification.queue")
void onUserBanned(UserBannedEvent event) { ... build subject/body, call EmailService.send(...) }

@RabbitListener(queues = "user.unban.notification.queue")
void onUserUnbanned(UserUnbannedEvent event) { ... }
```

Container factory configured with Spring Retry (`RetryInterceptorBuilder`,
stateless, e.g. 3 attempts with backoff) so a transient SMTP failure doesn't
immediately dead-letter the message; only after retries are exhausted does
the listener let the message be rejected, routing it to `notification.dlq` via
the queue's DLX binding.

### Email (`common/notification/`)

```java
public interface EmailService {
    void send(String to, String subject, String body);
}
```

`SmtpEmailService` (`common/notification/mail/`) — the only class importing
`JavaMailSender`, same provider-agnostic-interface pattern as `StorageService`.
Config via `app.mail.*` (`@Value`, backed by `.env`: `MAIL_HOST`, `MAIL_PORT`,
`MAIL_USERNAME`, `MAIL_PASSWORD`), same shape as `CloudinaryConfig`.

## Scheduler: auto-unban on expiry

`scheduler/BanExpirationScheduler` — `@Scheduled(fixedDelay = ...)` (e.g. every
5 minutes):

1. `userRepository.findByEnabledFalseAndBannedUntilBefore(Instant.now())`.
2. For each: call the same `unbanUser`-equivalent logic (entity `unban()` +
   save + outbox write) — reuse `UserServiceImpl.unbanUser` directly rather
   than duplicating the logic.

## Infrastructure additions

- `docker-compose.yml`: new `rabbitmq:3-management-alpine` service, ports
  `5672` (AMQP) + `15672` (management UI), bound to `127.0.0.1` only (matches
  existing mysql/redis convention). Env: `RABBITMQ_DEFAULT_USER`,
  `RABBITMQ_DEFAULT_PASS`.
- `pom.xml`: add `spring-boot-starter-amqp`, `spring-boot-starter-mail`,
  `spring-retry` (+ `spring-aspects` if required by the retry interceptor).
- `application.yml`: `app.rabbitmq.*` (host/port/username/password via `.env`),
  `app.mail.*` (host/port/username/password via `.env`).
- `.env.example`: add `RABBITMQ_HOST`, `RABBITMQ_PORT`, `RABBITMQ_USERNAME`,
  `RABBITMQ_PASSWORD`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`,
  `MAIL_PASSWORD`.
- `.github/workflows/ci.yml`: test job needs a RabbitMQ service container
  (`rabbitmq:3-management-alpine`) alongside the existing mysql/redis ones, plus
  dummy `MAIL_*`/`RABBITMQ_*` env vars — same pattern as the Cloudinary dummy
  vars fix (`67a9ef6`).

## Testing approach

This project's established practice so far has been live/manual verification
rather than automated tests (per `tech-defaults.md`). Given the amount of
timing- and failure-mode-dependent logic here (outbox retry/backoff, DLQ
routing, scheduler expiry), that practice alone is a weaker fit than for CRUD
+ Cloudinary. Recommend adding automated tests specifically for:

- `UserServiceImpl.banUser`/`unbanUser` — state-check/409 paths, admin-target
  rejection, outbox row gets written in the same transaction.
- `OutboxPublisherScheduler` — success/failure/retry-exhaustion transitions.
- `BanExpirationScheduler` — picks up expired bans, ignores non-expired ones.

Everything else (RabbitMQ topology, actual email delivery) still gets live
manual verification against the docker-compose RabbitMQ + a real/dev SMTP
target, matching how Cloudinary was verified.

## Deferred to Auth module

See `docs/AUTH_MODULE_TODO.md` for the full, authoritative list. Summary:
request-time ban enforcement, and self-ban prevention, both need an
authenticated-caller identity this codebase doesn't have yet.
