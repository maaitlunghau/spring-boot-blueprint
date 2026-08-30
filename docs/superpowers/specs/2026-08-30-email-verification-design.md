# Design Spec — Email Verification (OTP)

**Date:** 2026-08-30
**Branch:** `feature/user-management`
**Status:** Approved by user, pending implementation plan

## Goal

Require a new user's email address to be verified via a 6-digit OTP sent by
email, before their account is treated as fully confirmed. Admin-created
users (`POST /api/users`) get an OTP issued immediately on creation. A future
self-register flow needs the same mechanism but doesn't exist yet (no Auth
module) — that hookup is deliberately left as a TODO for when Auth lands,
not implemented here.

## Scope decisions (from brainstorming session)

- Verification model is **OTP (6-digit numeric code)**, not a link/long
  opaque token. This was an explicit correction mid-brainstorming — the
  original plan (before the user's correction) was a long-lived
  link-clickable token; OTP changes the threat model (small keyspace) and
  therefore the design choices below.
- **New, independent fields on `User`**: `emailVerified` (boolean) +
  `emailVerifiedAt` (Instant, nullable). Deliberately **not** reusing
  `enabled` — that field already means "not banned" (established in the
  Ban/Unban feature); overloading it with a second, unrelated meaning was
  rejected as confusing.
- **Dedicated `EmailVerificationToken` entity/table**, not fields bolted
  onto `User`. Chosen for auditability (every issued OTP leaves a row) and
  because it mirrors the `RefreshToken` shape already anticipated for the
  future Auth module. References `User` by a plain `userId` UUID column
  with a DB-level foreign key — **not** a JPA `@ManyToOne` object
  association — deliberately, to sidestep the lazy-loading/inverse-mapping
  concerns already documented in the project's own notes for the future
  `RefreshToken` entity. This table only ever needs to be queried by
  `userId`, never navigated from `User`, so a full JPA relationship buys
  nothing.
- **OTP is hashed at rest**, never stored in plaintext — but instead of
  introducing a new hashing utility (e.g. hand-rolled SHA-256), it **reuses
  the existing injected `PasswordEncoder` (BCrypt) bean** already used for
  user passwords. Simpler, no new dependency, and BCrypt's cost is
  irrelevant at this call volume (a handful of verify attempts per user,
  not a login-scale hot path).
- **Brute-force guard**: each `EmailVerificationToken` row tracks
  `attemptCount`; 5 wrong attempts permanently exhausts that OTP (the user
  must resend to get a new one), independent of the existing IP-based rate
  limiting on the endpoint — the two are complementary layers, not
  substitutes for each other.
- **OTP expiry: 10 minutes**, configurable via
  `app.email-verification.otp-expiration-minutes` (never hardcoded).
- **Resend invalidates the previous OTP implicitly**: verification always
  looks up the single *most recent* token row for a user
  (`findTopByUserIdOrderByCreatedAtDesc`) — older rows are simply never
  considered valid again once a newer one exists. No explicit
  "invalidated" flag/column needed; this was chosen specifically to keep
  the brute-force attack surface from multiplying across multiple
  simultaneously-valid OTPs.
- **Resend has its own per-user cooldown** (default 60s,
  `app.email-verification.resend-cooldown-seconds`) **in addition to** the
  existing IP-based rate limiting — the cooldown is a tighter, per-identity
  control that the IP-based limiter alone can't provide (an IP can host
  many users; a user can also switch IPs).
- **Already-verified re-verification attempt → `409 Conflict`**
  (`UserAlreadyVerifiedException`), matching the existing
  `UserAlreadyBannedException` idiom rather than silently succeeding.
- **All OTP-validation failure modes collapse into one generic
  `InvalidOtpException` (400)** — wrong code, expired, already used, and
  attempts-exhausted are never distinguished in the response. This is a
  deliberate security choice (don't leak which specific failure occurred),
  same spirit as the existing generic `BadCredentialsException` → 401
  handling.
- **Notification reuses the existing transactional outbox → RabbitMQ →
  email pipeline** (same pattern as Ban/Unban/Delete/Restore) rather than
  sending synchronously inside the request — consistency with every other
  notification in this codebase, and it means a slow/flaky SMTP call never
  blocks or fails the `createUser`/resend request itself.
- **No "email verified" confirmation email** is sent after successful
  verification — out of scope, not requested; only the OTP-issuance email
  exists.
- **Self-register's own verification hookup is out of scope** — tracked in
  `docs/AUTH_MODULE_TODO.md` for when the Auth module's register endpoint
  is built, reusing the same OTP-issuing logic rather than duplicating it.
- **No request-time enforcement** of `emailVerified` anywhere yet (e.g.
  blocking login) — there is no login/auth flow to gate in the first place.
  Also tracked in `docs/AUTH_MODULE_TODO.md`.

## Data model

### `User` entity

```java
@Column(name = "email_verified", nullable = false)
private boolean emailVerified;

@Column(name = "email_verified_at", nullable = true)
private Instant emailVerifiedAt;
```

```java
public void verifyEmail() {
    this.emailVerified = true;
    this.emailVerifiedAt = Instant.now();
}
```

`createUser` leaves `emailVerified` at its default (`false`) — no setter
call needed for the initial state.

### `EmailVerificationToken` (new entity, `module/user/entity/`)

Extends `BaseEntity` (gets `id`/`createdAt`/`updatedAt`/`version` for free)
but — like `OutboxEvent` — deliberately does **not** override
`equals`/`hashCode`: it's an append-only log row with no natural business
key.

```java
@Entity
@Table(name = "email_verification_tokens")
@Getter
public class EmailVerificationToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "used_at", nullable = true)
    private Instant usedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    protected EmailVerificationToken() {}

    @Builder
    public EmailVerificationToken(UUID userId, String otpHash, Instant expiresAt) {
        this.userId = userId;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
        this.attemptCount = 0;
    }

    public void markUsed() { this.usedAt = Instant.now(); }
    public void incrementAttempt() { this.attemptCount++; }

    public boolean isExpired() { return Instant.now().isAfter(expiresAt); }
    public boolean isUsed() { return usedAt != null; }
    public boolean isAttemptsExceeded() { return attemptCount >= 5; }
}
```

### Migrations

Two migrations (verify each against Hibernate's live `ddl-auto=update`
output before finalizing, per this project's established habit):

- `V7__create_email_verification_tokens_table.sql` — new table, `id`/
  `user_id` as `BINARY(16)` (matching `users.id`'s UUID-v7 storage), a
  `FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE`, `otp_hash
  VARCHAR(255)`, `expires_at`/`used_at` as `DATETIME(6)`, `attempt_count INT
  NOT NULL DEFAULT 0`, plus the standard `created_at`/`updated_at`/`version`
  columns every `BaseEntity`-backed table has. **`ON DELETE CASCADE` is not
  optional here**: `UserService.purgeUser` hard-deletes the `users` row
  directly (`userRepository.deleteById`), and MySQL's default FK behavior
  (`RESTRICT`) would make that `DELETE` fail with a constraint violation the
  moment a user being purged has any token history — cascading lets purge
  keep working exactly as the Soft Delete feature already built it, with no
  extra cleanup code needed in `purgeUser` itself.
- `V8__add_email_verified_to_users.sql` — `email_verified` (boolean,
  `NOT NULL DEFAULT false` — matches Hibernate's `bit(1)` mapping for
  primitive `boolean`, same as the existing `enabled` column) and
  `email_verified_at DATETIME(6) DEFAULT NULL` on `users`.

## Components

### `module/user/repository/EmailVerificationTokenRepository`

```java
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {
    Optional<EmailVerificationToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
```

One query — always fetch the newest token row for a user. This single
method is what makes "resend invalidates the previous OTP" true without any
extra state: an older row is simply never looked up again once a newer one
exists.

### `exception/` — new exception types

```java
public class UserAlreadyVerifiedException extends AppException {
    public UserAlreadyVerifiedException(String identifier) {
        super(HttpStatus.CONFLICT, String.format("User email is already verified: %s", identifier));
    }
}

public class InvalidOtpException extends AppException {
    public InvalidOtpException() {
        super(HttpStatus.BAD_REQUEST, "Invalid or expired verification code");
    }
}

public class ResendCooldownException extends AppException {
    public ResendCooldownException(long secondsRemaining) {
        super(HttpStatus.TOO_MANY_REQUESTS, String.format("Please wait %d seconds before requesting another code", secondsRemaining));
    }
}
```

### `module/user/service/UserService` / `impl/UserServiceImpl`

Added directly to the existing `UserService`/`UserServiceImpl` — no new
service class — matching how Ban/Unban/Restore were added to the same
service rather than split out.

```java
UserResponse verifyEmail(UUID id, String otp);
void resendVerificationOtp(UUID id);
```

OTP generation (`SecureRandom`, not `Random` — this is security-sensitive):

```java
private static final SecureRandom SECURE_RANDOM = new SecureRandom();

private String generateOtp() {
    return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
}
```

Shared issuance helper, called from both `createUser` and
`resendVerificationOtp`:

```java
private void issueVerificationOtp(User user) {
    String otp = generateOtp();

    EmailVerificationToken token = EmailVerificationToken.builder()
        .userId(user.getId())
        .otpHash(passwordEncoder.encode(otp))
        .expiresAt(Instant.now().plus(otpExpirationMinutes, ChronoUnit.MINUTES))
        .build();
    emailVerificationTokenRepository.save(token);

    outboxEventWriter.write(
        USER_AGGREGATE_TYPE,
        user.getId(),
        RabbitMQConfig.EMAIL_VERIFICATION_ROUTING_KEY,
        new EmailVerificationOtpEvent(user.getId(), user.getEmail(), user.getFullName(), otp)
    );
}
```

The outbox payload carries the OTP in **plaintext** (only the DB-persisted
`otpHash` is hashed) — the event is what becomes the email body, same as
`UserBannedEvent` already carrying a plaintext `reason`.

`createUser` calls `issueVerificationOtp(saved)` right after saving the new
user, inside the same transaction.

```java
@Override
@Transactional
public UserResponse verifyEmail(UUID id, String otp) {
    User user = userRepository.findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

    if (user.isEmailVerified()) {
        throw new UserAlreadyVerifiedException(id.toString());
    }

    EmailVerificationToken token = emailVerificationTokenRepository.findTopByUserIdOrderByCreatedAtDesc(id)
        .filter(t -> !t.isUsed() && !t.isExpired() && !t.isAttemptsExceeded())
        .orElseThrow(InvalidOtpException::new);

    if (!passwordEncoder.matches(otp, token.getOtpHash())) {
        token.incrementAttempt();
        emailVerificationTokenRepository.save(token);
        throw new InvalidOtpException();
    }

    token.markUsed();
    emailVerificationTokenRepository.save(token);

    user.verifyEmail();
    return userMapper.toResponse(userRepository.save(user));
}
```

```java
@Override
@Transactional
public void resendVerificationOtp(UUID id) {
    User user = userRepository.findByIdAndDeletedAtIsNull(id)
        .orElseThrow(() -> new ResourceNotFoundException("User", id.toString()));

    if (user.isEmailVerified()) {
        throw new UserAlreadyVerifiedException(id.toString());
    }

    emailVerificationTokenRepository.findTopByUserIdOrderByCreatedAtDesc(id).ifPresent(latest -> {
        Instant cooldownEnds = latest.getCreatedAt().plusSeconds(resendCooldownSeconds);
        if (Instant.now().isBefore(cooldownEnds)) {
            throw new ResendCooldownException(Duration.between(Instant.now(), cooldownEnds).getSeconds());
        }
    });

    issueVerificationOtp(user);
}
```

### `module/user/dto/request/VerifyEmailRequest`

```java
public record VerifyEmailRequest(
    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "\\d{6}", message = "OTP must be exactly 6 digits")
    String otp
) {}
```

### `module/user/dto/response/UserResponse`

Add `boolean emailVerified` + `Instant emailVerifiedAt`, auto-mapped by
MapStruct (matching field names), same treatment as `deletedAt`.

### `module/user/controller/UserController`

```java
@PostMapping("/{id}/verify-email")
public ResponseEntity<ApiResponse<UserResponse>> verifyEmail(
    @PathVariable UUID id,
    @Valid @RequestBody VerifyEmailRequest request
) {
    return ResponseEntity.ok(
        ApiResponse.of(HttpStatus.OK.value(), "Email verified successfully", userService.verifyEmail(id, request.otp()))
    );
}

@PostMapping("/{id}/resend-verification-otp")
public ResponseEntity<ApiResponse<Void>> resendVerificationOtp(@PathVariable UUID id) {
    userService.resendVerificationOtp(id);
    return ResponseEntity.ok(ApiResponse.message(HttpStatus.OK.value(), "Verification code resent"));
}
```

### Notification (`module/user/event/`, `config/RabbitMQConfig`, `module/notification/listener/`)

New event record:

```java
public record EmailVerificationOtpEvent(
    UUID userId,
    String email,
    String fullName,
    String otp
) {}
```

`RabbitMQConfig` additions — one routing key, one queue, bound to the
existing `notification.exchange`/`notification.dlx`:

```java
public static final String EMAIL_VERIFICATION_QUEUE = "email.verification.notification.queue";
public static final String EMAIL_VERIFICATION_ROUTING_KEY = "user.email-verification.otp-issued";
```

`UserAccountNotificationListener` gains one more `@RabbitListener` method
(now covers 5 event types — Ban/Unban/Deleted/Restored/EmailVerificationOtp
— still one method per event type, no shared branching):

```java
@RabbitListener(queues = RabbitMQConfig.EMAIL_VERIFICATION_QUEUE)
public void onEmailVerificationOtpIssued(EmailVerificationOtpEvent event) {
    emailService.send(
        event.email(),
        "Verify your email address",
        "Hi %s,\n\nYour verification code is: %s\n\nThis code will expire in 10 minutes. If you did not request this, please ignore this email."
            .formatted(event.fullName(), event.otp())
    );
}
```

### Config

`application.yml`:

```yaml
app:
  email-verification:
    otp-expiration-minutes: 10
    resend-cooldown-seconds: 60
```

`UserServiceImpl` injects both via `@Value`, same pattern as
`defaultAvatarUrl`.

### Rate limiting

Two new entries in `RateLimitConfig.SENSITIVE_RULES`:

```java
new RateLimitRule("/api/users/*/verify-email", "POST", 10, Duration.ofMinutes(1), 1),
new RateLimitRule("/api/users/*/resend-verification-otp", "POST", 5, Duration.ofMinutes(1), 1)
```

`resend` gets a stricter 5/min tier since it already has its own 60s
per-user cooldown as the primary control — the IP-based limit here is
strictly a secondary layer, not the main defense.

## Error handling summary

- Verify/resend on a nonexistent or soft-deleted user id → `404 ResourceNotFoundException` (same `findByIdAndDeletedAtIsNull` scoping every other endpoint already uses).
- Verify/resend on an already-verified user → `409 UserAlreadyVerifiedException`.
- Verify with a wrong/expired/already-used/attempts-exhausted OTP → `400 InvalidOtpException` (single generic message across all four causes, by design).
- Resend called before the per-user cooldown elapses → `429 ResendCooldownException`, message states exact seconds remaining.
- Either endpoint hit past its IP-based rate-limit tier → `429` from `filter/RateLimitFilter`, same envelope as every other rate-limited route.

## Deferred to Auth module

New entry to add to `docs/AUTH_MODULE_TODO.md`:

1. **Self-register OTP flow** — a future register endpoint must call the
   same `issueVerificationOtp`-equivalent logic this feature builds; don't
   duplicate the OTP-generation/hashing/outbox code, reuse it.
2. **No request-time enforcement of `emailVerified`** — e.g. whether login
   should require a verified email is an Auth-module-time decision, not
   addressed here (there's no login flow yet to gate).

## Testing approach

Following the project's established practice (live/manual verification, no
JUnit suite for feature logic):

1. Create a user → real OTP email received via SMTP, outbox row `PENDING` → `PUBLISHED`.
2. Verify with a wrong OTP → `400`, confirm `attempt_count` incremented in the DB.
3. Verify with the correct OTP → `200`, `emailVerified=true`, `emailVerifiedAt` populated.
4. Verify again on the same (now-verified) user → `409 UserAlreadyVerifiedException`.
5. Exhaust attempts: 5 wrong tries on one OTP → 6th attempt (even with the *correct* code) still `400` (`isAttemptsExceeded()`).
6. Resend: request a new OTP, then confirm the *old* OTP no longer verifies and the *new* one does.
7. Cooldown: call resend twice in quick succession → second call `429` with the correct remaining-seconds message.
8. Expiry: temporarily lower `otp-expiration-minutes` (same trick used for `retention-days` in the Soft Delete feature) → confirm an expired OTP is rejected with `400`.
9. Rate limiting: hammer both new endpoints past their tier and confirm they don't share a bucket with each other or with unrelated routes, same verification style used for the Soft Delete rate-limit rules.
10. Purge cascade: soft-delete then purge (`DELETE /api/users/{id}/purge`) a user that has at least one `email_verification_tokens` row → confirm the purge still succeeds (no FK constraint error) and the token row is gone too (`ON DELETE CASCADE` actually fired).
