# Design Spec — User Soft Delete & Restore

**Date:** 2026-08-30
**Branch:** `feature/user-management`
**Status:** Approved by user, pending implementation plan

## Goal

Stop `DELETE /api/users/{id}` from permanently destroying data immediately.
Deleting a user should be reversible for a grace period (admin can restore),
with a scheduled job permanently purging records only after that window
expires — matching how a "trash / recycle bin" works in production systems,
rather than an instant, unrecoverable delete.

## Scope decisions (from brainstorming session)

- `DELETE /api/users/{id}` **changes behavior in place** to mean soft-delete
  (sets a timestamp) instead of a real DB row delete. No separate
  soft-delete-only endpoint is added — this keeps the API surface small.
- **Email is locked while soft-deleted.** A soft-deleted user's email cannot
  be reused for a new registration until that user is permanently purged
  (grace period elapses, or an admin purges manually). This means the
  existing DB-level `unique = true` constraint on `users.email` **stays
  exactly as-is** — no relaxation, no partial index, no email mutation on
  delete. Attempting to register with an email that belongs to a
  soft-deleted user returns a dedicated `409` with a message telling the
  caller to contact an administrator, distinct from the ordinary
  already-registered-email `409`.
- Because email stays locked during the grace period, **restore can never
  conflict on email** — nobody else could have taken it in the meantime.
  Restore has no conflict-handling branch.
- Ban/Unban and Soft-Delete/Restore are **fully independent** states. A
  banned user can be soft-deleted without unbanning first, and vice versa.
  (In practice, once soft-deleted, every other action on that user —
  including ban/unban — 404s, since soft-deleted users are hidden from all
  normal lookups; see below.)
- Soft-deleting (or purging) a user with role `ADMIN` is blocked
  (`400 Bad Request`), mirroring the existing ADMIN-ban guard.
- Every "normal" read/write endpoint (`GET` list, `GET /{id}`, profile/role
  update, ban/unban, avatar upload) **hides soft-deleted users by default** —
  a soft-deleted user behaves as if it doesn't exist (`404`) everywhere
  except the dedicated deleted-list/restore/purge endpoints.
- The user's **avatar (Cloudinary asset) is kept untouched** while
  soft-deleted, so a restored user gets their avatar back with no extra
  work. Cloudinary cleanup only happens at actual purge time (scheduled or
  manual), reusing the existing best-effort `try/catch` + `log.warn`
  pattern already established for avatar/user deletion.
- **Manual purge is admin-triggered, not just scheduler-triggered** — but
  only for a user that is *already* soft-deleted. There is exactly one path
  into "gone forever": soft-delete first, then either restore, wait for the
  scheduler, or have an admin purge early. Purging an active (never
  soft-deleted) user directly is not supported — keeps the state machine to
  a single flow instead of two parallel delete paths.
- Soft-delete and restore each publish a notification via the existing
  **transactional outbox → RabbitMQ → email** pipeline (same pattern as
  Ban/Unban), reusing the same `notification.exchange`. Purge does **not**
  notify — the user already received the soft-delete email, and permanent
  removal is an internal consequence, not a new event worth emailing about.
- Grace period length is **configurable** (`app.user.soft-delete.retention-days`,
  default `30`), not hardcoded — same reasoning as every other tunable in
  this codebase (`RateLimitRule.capacity`, avatar size limits, etc.).
- Self-delete (an admin deleting their own account) is **not** prevented in
  this pass — there is still no authenticated-caller identity in the app
  (same root cause as the existing self-ban gap). Tracked as a new entry in
  `docs/AUTH_MODULE_TODO.md`, not solved here.

## Rejected approaches

- **Hibernate `@SQLRestriction` global filter** on `User` to auto-exclude
  soft-deleted rows from every query. Rejected in favor of explicit
  `Specification`/derived-query scoping (see below) — this project has
  consistently favored visible, explicit filtering over ORM-level "magic"
  (the Rate Limiting policy table is an explicit list for the same reason).
  A global filter would also need extra plumbing to selectively disable for
  the deleted-list/restore/purge endpoints, which is more complexity, not
  less.
- **Email mutation on delete** (e.g. renaming the stored email to free the
  slot for reuse) — moot now that email reuse during the grace period is
  disallowed; the DB unique constraint already does the job with no extra
  code.
- **Separate archive table** for soft-deleted rows — rejected as
  over-engineering for this project's scale, consistent with previously
  rejected over-scoped ideas (e.g. client-side direct-to-Cloudinary upload).

## Data model

### `User` entity

New field, same shape as the existing `bannedAt`-style timestamp fields:

```java
@Column(name = "deleted_at", nullable = true)
private Instant deletedAt;
```

`deletedAt == null` → active. `deletedAt != null` → soft-deleted, holding the
moment of deletion (used by the scheduler to compute the purge cutoff).

New business methods (mutation only through named methods, no setters):

```java
public void softDelete() {
    this.deletedAt = Instant.now();
}

public void restore() {
    this.deletedAt = null;
}
```

The existing `@Column(name = "email", nullable = false, unique = true)`
**does not change**.

### Migration

`db/migration/V6__add_deleted_at_to_users.sql` — adds a single nullable
`deleted_at TIMESTAMP` column. No other schema change. As with every prior
migration in this project, verify column type against Hibernate's live
`ddl-auto=update` output before writing it.

### `UserResponse`

Add `Instant deletedAt` (nullable) alongside the existing `bannedReason`/
`bannedUntil` fields, so the admin-facing deleted-list endpoint can show when
a user was deleted. Auto-mapped by MapStruct (matching field name), no new
`@Mapping` needed.

## Components

### `module/user/repository/spec/UserSpecifications`

Two new factory methods, alongside the existing `keywordIn`/`hasRole`:

```java
public static Specification<User> notDeleted() {
    return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
}

public static Specification<User> onlyDeleted() {
    return (root, query, cb) -> cb.isNotNull(root.get("deletedAt"));
}
```

`getAllUsers` composes `.and(UserSpecifications.notDeleted())` into its
existing specification chain.

### `module/user/repository/UserRepository`

New derived-query finders:

```java
Optional<User> findByIdAndDeletedAtIsNull(UUID id);
Optional<User> findByIdAndDeletedAtIsNotNull(UUID id);
List<User> findByDeletedAtBefore(Instant cutoff);
```

`findByEmail` (already exists) is reused as-is in `createUser` — see below.

### `exception/` — new exception types

```java
public class UserNotDeletedException extends AppException {
    public UserNotDeletedException(String identifier) {
        super(HttpStatus.CONFLICT, String.format("User is not deleted: %s", identifier));
    }
}
```

Used by `restoreUser` and `purgeUser` when the target isn't currently
soft-deleted (or doesn't exist at all — the two cases are deliberately not
distinguished here, same as how `UserNotBannedException` doesn't distinguish
"not banned" from "never existed").

```java
public class EmailPendingPurgeException extends AppException {
    public EmailPendingPurgeException() {
        super(HttpStatus.CONFLICT,
            "This email cannot be used for registration at this time. Please contact an administrator for assistance.");
    }
}
```

Used by `createUser` when the requested email belongs to a soft-deleted (not
yet purged) user — distinct from the ordinary `DuplicateResourceException`
thrown when the email belongs to an active user.

No `UserAlreadyDeletedException` is needed: calling soft-delete on an
already-deleted user falls through `findByIdAndDeletedAtIsNull` finding
nothing, producing the existing `ResourceNotFoundException` (404) — the same
"soft-deleted looks like it doesn't exist" rule applied consistently.

### `module/user/service/UserService` / `impl/UserServiceImpl`

**`createUser`** — distinguishes which kind of email conflict occurred:

```java
@Override
@Transactional
public UserResponse createUser(CreateUserRequest request) {
    userRepository.findByEmail(request.email()).ifPresent(existing -> {
        if (existing.getDeletedAt() != null) {
            throw new EmailPendingPurgeException();
        }
        throw new DuplicateResourceException("User", request.email());
    });

    User user = userMapper.toEntity(request);
    user.changePassword(passwordEncoder.encode(request.password()));
    user.updateAvatar(defaultAvatarUrl, null);

    return userMapper.toResponse(userRepository.save(user));
}
```

**`deleteUser`** — now soft-deletes instead of hard-deleting; drops the
`NOT_SUPPORTED` propagation from the old implementation since there's no
external I/O call in this path anymore:

```java
@Override
@Transactional
public void deleteUser(UUID id) {
    User user = userRepository.findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

    if (user.getRole() == Role.ADMIN) {
        throw new BadRequestException("Cannot delete a user with ADMIN role");
    }

    user.softDelete();
    userRepository.save(user);

    outboxEventWriter.write(
        USER_AGGREGATE_TYPE,
        id,
        RabbitMQConfig.USER_DELETED_ROUTING_KEY,
        new UserDeletedEvent(id, user.getEmail(), user.getFullName())
    );
}
```

**`restoreUser`** (new) — no conflict branch, per the scope decision above:

```java
@Override
@Transactional
public UserResponse restoreUser(UUID id) {
    User user = userRepository.findByIdAndDeletedAtIsNotNull(id)
        .orElseThrow(() -> new UserNotDeletedException(id.toString()));

    user.restore();
    UserResponse response = userMapper.toResponse(userRepository.save(user));

    outboxEventWriter.write(
        USER_AGGREGATE_TYPE,
        id,
        RabbitMQConfig.USER_RESTORED_ROUTING_KEY,
        new UserRestoredEvent(id, user.getEmail(), user.getFullName())
    );

    return response;
}
```

**`getDeletedUsers`** (new) — paginated admin view of the "trash":

```java
@Override
public PageResponse<UserResponse> getDeletedUsers(Pageable pageable) {
    Page<User> page = userRepository.findAll(UserSpecifications.onlyDeleted(), pageable);
    return PageResponse.from(page.map(userMapper::toResponse));
}
```

**`purgeUser`** (new) — hard-deletes; callable by both the admin endpoint and
the scheduler (single implementation, no duplication, same pattern as
`BanExpirationScheduler` reusing `unbanUser`). Requires the target to already
be soft-deleted:

```java
@Override
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void purgeUser(UUID id) {
    User user = userRepository.findByIdAndDeletedAtIsNotNull(id)
        .orElseThrow(() -> new UserNotDeletedException(id.toString()));

    userRepository.deleteById(id);

    if (user.getImagePublicId() != null) {
        try {
            storageService.delete(user.getImagePublicId());
        } catch (Exception e) {
            log.warn("Failed to delete avatar '{}' for purged user {}", user.getImagePublicId(), id, e);
        }
    }
}
```

`NOT_SUPPORTED` is used here (not plain `@Transactional`) for the same reason
as the old `deleteUser`/`updateAvatar` — the method makes a slow external
Cloudinary call and must not hold a DB connection open across it.

**Existing methods updated** to hide soft-deleted users: `getUserById`,
`updateProfile`, `updateRole`, `banUser`, `unbanUser`, `updateAvatar` all
switch their `findById`/`existsById` calls to the
`...AndDeletedAtIsNull` variants. A soft-deleted user hitting any of these
now gets `ResourceNotFoundException` (404), same as a truly nonexistent id.

### `module/user/controller/UserController`

| Method | Path | Behavior |
|---|---|---|
| `DELETE` | `/api/users/{id}` | Soft-delete (behavior change, same route) |
| `PATCH` | `/api/users/{id}/restore` | Restore; no request body, mirrors `/unban` |
| `GET` | `/api/users/deleted` | Paginated list of soft-deleted users |
| `DELETE` | `/api/users/{id}/purge` | Hard-delete; only valid on an already soft-deleted user |

`GET /api/users/deleted` does not collide with `GET /api/users/{id}` — Spring
MVC matches the literal `/deleted` segment before falling back to the
`{id}` path variable pattern.

All responses follow the existing `ApiResponse<T>` envelope convention —
`restore` returns `200` + the updated `UserResponse`; `purge` returns `200` +
a message-only body, same shape as the existing `deleteUser` response.

### Notification (`module/user/event/`, `config/RabbitMQConfig`, `module/notification/listener/`)

New event records, same shape as `UserBannedEvent`/`UserUnbannedEvent`:

```java
public record UserDeletedEvent(UUID userId, String email, String fullName) {}
public record UserRestoredEvent(UUID userId, String email, String fullName) {}
```

`RabbitMQConfig` additions — two new routing keys and two new queues, bound
to the existing `notification.exchange` (already designed to be reusable
across event types), both wired to the existing `notification.dlx`/DLQ:

```java
public static final String USER_DELETED_ROUTING_KEY = "user.deleted";
public static final String USER_RESTORED_ROUTING_KEY = "user.restored";
public static final String USER_DELETE_QUEUE = "user.deleted.notification.queue";
public static final String USER_RESTORE_QUEUE = "user.restored.notification.queue";
```

`module/notification/listener/UserBanNotificationListener` is **renamed** to
`UserAccountNotificationListener` and gains two more `@RabbitListener`
methods (`onUserDeleted`, `onUserRestored`), following the existing
one-method-per-event-type convention (no shared listener with `instanceof`
branching). The rename reflects that this listener now covers the account
lifecycle broadly, not just ban/unban; no behavioral change to the existing
ban/unban methods.

### Scheduler (`scheduler/UserPurgeScheduler`)

New class, same shape as `BanExpirationScheduler`:

```java
@Scheduled(fixedDelay = 3600000) // 1 hour — background cleanup, doesn't need BanExpirationScheduler's 5-minute cadence
public void purgeExpiredSoftDeletes() {
    Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
    List<User> expired = userRepository.findByDeletedAtBefore(cutoff);

    for (User user : expired) {
        try {
            userService.purgeUser(user.getId());
        } catch (Exception e) {
            log.warn("Failed to purge expired soft-deleted user {}", user.getId(), e);
        }
    }
}
```

### Config

`application.yml`:

```yaml
app:
  user:
    soft-delete:
      retention-days: 30
```

`UserPurgeScheduler` injects this via `@Value("${app.user.soft-delete.retention-days}")`.

### Rate limiting

Add the three new sensitive routes to `RateLimitConfig.SENSITIVE_RULES`
(10 req/min, same tier as `/ban`/`/unban`/avatar upload):
`DELETE /api/users/*` (soft-delete), `PATCH /api/users/*/restore`,
`DELETE /api/users/*/purge`. Note `AntPathMatcher`'s `*` matches exactly one
path segment, so the soft-delete and purge rules need separate entries — a
single `/api/users/*` pattern would not also match the two-segment
`/api/users/{id}/purge`. `GET /api/users/deleted` stays on `DEFAULT_RULE`.
Adding new rules doesn't
require bumping `configVersion` on the *existing* rules — only a rule whose
`capacity`/`refillPeriod` changes needs that; brand new rules get their
initial version for free.

## Error handling summary

- Soft-deleted user hit through any normal endpoint → `404 ResourceNotFoundException`.
- Create with an email belonging to an active user → `409 DuplicateResourceException` (unchanged).
- Create with an email belonging to a soft-deleted user → `409 EmailPendingPurgeException`.
- Restore/purge on a user that isn't currently soft-deleted (or doesn't exist) → `409 UserNotDeletedException`.
- Soft-delete or purge targeting an `ADMIN` role user → `400 BadRequestException`.
- Concurrent writes to the same user row are still caught by the existing `@Version` optimistic lock on `BaseEntity` — no new handling added, falls through to the existing catch-all `500` like every other entity today.

## Deferred to Auth module

New entry to add to `docs/AUTH_MODULE_TODO.md`: prevent an authenticated
admin from soft-deleting/purging their own account, once an
authenticated-caller identity exists — same root cause and shape as the
existing self-ban gap.

## Testing approach

Following the project's established practice (live/manual verification, no
JUnit suite for feature logic):

1. **Migration verify**: compare `V6` against Hibernate's live `ddl-auto=update` output before finalizing.
2. **Soft-delete**: delete a regular user → `deletedAt` set, disappears from `GET` list/by-id, "account deleted" email received, outbox row `PENDING` → `PUBLISHED`.
3. **Email locked while soft-deleted**: attempt to register a new user with that same email → `409 EmailPendingPurgeException`.
4. **Restore**: restore the same user → `deletedAt` back to `null`, reappears in normal `GET`s, "account restored" email received.
5. **Email unlocked after purge**: purge the user (manually) → register a new user with that email → succeeds.
6. **Manual purge guard**: `DELETE /{id}/purge` on an active (never-deleted) user → `409 UserNotDeletedException`.
7. **Purge cleanup**: purge a soft-deleted user with a real Cloudinary avatar → verify the asset is removed (Cloudinary dashboard/API), same check used for the original `deleteUser`.
8. **Scheduler**: temporarily lower `retention-days`/`fixedDelay` (same trick used for `BanExpirationScheduler`) → confirm expired soft-deletes are auto-purged, avatar cleaned up, without manual intervention; restore to real values before committing.
9. **ADMIN guard**: attempt to soft-delete a user with role `ADMIN` → `400 BadRequestException`.
10. **Ban/delete independence**: soft-delete a currently-banned user → succeeds; confirm ban fields are left untouched on the row (not cleared).
