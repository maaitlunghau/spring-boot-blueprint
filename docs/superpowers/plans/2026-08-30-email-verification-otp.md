# Email Verification (OTP) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require a new user's email to be verified via a 6-digit OTP sent by email before the account is treated as confirmed — admin-created users (`POST /api/users`) get an OTP immediately; self-register's own hookup is deferred (no Auth module yet).

**Architecture:** A dedicated `EmailVerificationToken` table (referenced by a plain `userId` FK column, not a JPA relationship) holds a BCrypt-hashed OTP per issuance. Verification always compares against the single most-recent token row for a user, which is what makes "resend invalidates the old OTP" true with no extra state. Issuance reuses the existing transactional outbox → RabbitMQ → email pipeline already built for Ban/Unban/Delete/Restore.

**Tech Stack:** Spring Boot 4.1.0 / Java 21, Spring Data JPA, Flyway (MySQL 8.4), Spring AMQP (RabbitMQ), existing `PasswordEncoder` (BCrypt) bean — no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-30-email-verification-design.md`

## Global Constraints

- Commit format: `type(scope): subject`, single line, ≤70 chars, no body, no trailers — **never** add `Co-Authored-By:` or any "Generated with Claude" line.
- Never bundle unrelated files into one commit — each task below lands as its own commit(s) unless a step says otherwise.
- **No JUnit tests for this feature** — verify every task by hand against the real local stack (`docker compose up -d` + `./mvnw spring-boot:run` + `curl`/RabbitMQ Management UI/real SMTP/direct MySQL queries), consistent with every prior feature in this codebase (see `.claude/rules/tech-defaults.md`).
- Every migration must be verified against Hibernate's live `ddl-auto=update` output before being written by hand — don't guess column types (`users.id`/`enabled` are already confirmed as `binary(16)`/`bit(1)` from prior sessions; confirm the same conventions apply to the new table/columns before finalizing).
- Every controller response stays wrapped in the existing `ApiResponse<T>` envelope.
- OTP is **never** persisted or logged in plaintext — only its BCrypt hash is stored. The plaintext only ever exists in memory and in the outbox/RabbitMQ payload (same treatment as `UserBannedEvent`'s plaintext `reason`).
- `.claude/rules/architecture.md` and `.claude/skills/resume/PROJECT_STATE.md` are gitignored in this repo (`.claude/` is in `.gitignore`) — update them locally for future-session accuracy, but never try to `git add`/commit them. Only `docs/AUTH_MODULE_TODO.md` (outside `.claude/`) is a real trackable file.
- Default config: `app.email-verification.otp-expiration-minutes: 10`, `app.email-verification.resend-cooldown-seconds: 60` — never hardcode either value.

---

### Task 1: Data model — `EmailVerificationToken` entity, migrations, `User` fields, repository

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/entity/EmailVerificationToken.java`
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/repository/EmailVerificationTokenRepository.java`
- Create: `src/main/resources/db/migration/V7__create_email_verification_tokens_table.sql`
- Create: `src/main/resources/db/migration/V8__add_email_verified_to_users.sql`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/entity/User.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/dto/response/UserResponse.java`

**Interfaces:**
- Produces: `User.isEmailVerified(): boolean` / `getEmailVerifiedAt(): Instant` (Lombok `@Getter`), `User.verifyEmail(): void`; `EmailVerificationToken` entity with `getUserId()`, `getOtpHash()`, `getExpiresAt()`, `getUsedAt()`, `getAttemptCount()`, `markUsed()`, `incrementAttempt()`, `isExpired()`, `isUsed()`, `isAttemptsExceeded()`, and a `@Builder` constructor `(UUID userId, String otpHash, Instant expiresAt)`; `EmailVerificationTokenRepository.findTopByUserIdOrderByCreatedAtDesc(UUID): Optional<EmailVerificationToken>`.
- Consumes: nothing new — this task only adds structure, no service/controller wiring yet.

- [ ] **Step 1: Create the `EmailVerificationToken` entity**

```java
package com.maaitlunghau.spring_boot_blueprint.module.user.entity;

import java.time.Instant;
import java.util.UUID;

import com.maaitlunghau.spring_boot_blueprint.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Builder;
import lombok.Getter;

@Entity
@Table(name = "email_verification_tokens")
@Getter
public class EmailVerificationToken extends BaseEntity {

    private static final int MAX_ATTEMPTS = 5;

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

    protected EmailVerificationToken() {
    }

    @Builder
    public EmailVerificationToken(UUID userId, String otpHash, Instant expiresAt) {
        this.userId = userId;
        this.otpHash = otpHash;
        this.expiresAt = expiresAt;
        this.attemptCount = 0;
    }

    public void markUsed() {
        this.usedAt = Instant.now();
    }

    public void incrementAttempt() {
        this.attemptCount++;
    }

    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public boolean isAttemptsExceeded() {
        return attemptCount >= MAX_ATTEMPTS;
    }
}
```

- [ ] **Step 2: Create the repository**

```java
package com.maaitlunghau.spring_boot_blueprint.module.user.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.EmailVerificationToken;

public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, UUID> {

    Optional<EmailVerificationToken> findTopByUserIdOrderByCreatedAtDesc(UUID userId);
}
```

- [ ] **Step 3: Add the two new fields + business method to `User`**

Add after the `deletedAt` field:

```java
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified;

    @Column(name = "email_verified_at", nullable = true)
    private Instant emailVerifiedAt;
```

Add after `restore()`:

```java
    public void verifyEmail() {
        this.emailVerified = true;
        this.emailVerifiedAt = Instant.now();
    }
```

- [ ] **Step 4: Add the two new fields to `UserResponse`**

```java
public record UserResponse(
        UUID id,
        String fullName,
        String email,
        Role role,
        boolean enabled,
        String imageUrl,
        String bannedReason,
        Instant bannedUntil,
        Instant deletedAt,
        boolean emailVerified,
        Instant emailVerifiedAt,
        Instant createdAt) {
}
```

- [ ] **Step 5: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 6: Boot the app against `dev` and inspect the generated schema**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run > /tmp/email-verification-task1-boot.log 2>&1 &
```

Poll `/tmp/email-verification-task1-boot.log` for `Started SpringBootBlueprintApplication` (or `APPLICATION FAILED TO START`) before continuing, same pattern used throughout the Soft Delete feature's tasks.

```bash
set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" -e "DESCRIBE email_verification_tokens; DESCRIBE users;" 2>&1 | grep -v Warning
```

Expected: `email_verification_tokens` exists with `id`/`user_id` as `binary(16)`, `otp_hash varchar(255)`, `expires_at`/`used_at` as `datetime(6)`, `attempt_count int`; `users` now also lists `email_verified` as `bit(1)` and `email_verified_at` as `datetime(6)`.

- [ ] **Step 7: Write the two migrations matching the inspected types**

`V7__create_email_verification_tokens_table.sql`:

```sql
CREATE TABLE email_verification_tokens (
    id BINARY(16) NOT NULL PRIMARY KEY,
    user_id BINARY(16) NOT NULL,
    otp_hash VARCHAR(255) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) DEFAULT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    created_at DATETIME(6),
    updated_at DATETIME(6),
    version BIGINT NOT NULL,
    CONSTRAINT fk_email_verification_tokens_user
        FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
```

`V8__add_email_verified_to_users.sql`:

```sql
ALTER TABLE users
    ADD COLUMN email_verified BIT(1) NOT NULL DEFAULT b'0',
    ADD COLUMN email_verified_at DATETIME(6) DEFAULT NULL;
```

- [ ] **Step 8: Regression-check existing endpoints still work**

```bash
curl -s http://localhost:8081/api/users | head -c 600
```

Expected: `200`, existing user(s) returned, each now also showing `"emailVerified":false,"emailVerifiedAt":null`.

- [ ] **Step 9: Verify the `ON DELETE CASCADE` FK actually works**

Insert a token row directly for an existing test user, soft-delete + purge that user via the existing endpoints, and confirm no FK violation and the token row is gone too:

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Cascade Test","email":"cascade-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "USER_ID=$USER_ID"

set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "INSERT INTO email_verification_tokens (id, user_id, otp_hash, expires_at, attempt_count, created_at, updated_at, version) VALUES (UUID_TO_BIN(UUID(), 1), UNHEX(REPLACE('$USER_ID','-','')), 'dummy-hash', NOW() + INTERVAL 10 MINUTE, 0, NOW(), NOW(), 0);" 2>&1 | grep -v Warning

curl -s -X DELETE "http://localhost:8081/api/users/$USER_ID"
echo
curl -s -X DELETE "http://localhost:8081/api/users/$USER_ID/purge"
echo

docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT COUNT(*) AS remaining_tokens FROM email_verification_tokens WHERE user_id = UNHEX(REPLACE('$USER_ID','-',''));" 2>&1 | grep -v Warning
```

Expected: both `DELETE` calls return `200` (no `500`/FK error), and `remaining_tokens` is `0` afterward.

Note: MySQL's `UUID_TO_BIN(uuid, 1)` swap-flag byte ordering may not byte-for-byte match how `UuidV7Generator`/Hibernate stores the id — if the manual insert's id format causes an issue, it only affects this throwaway test row's own `id` column, not the FK column (`user_id`, built via `UNHEX(REPLACE(...))` which matches exactly how `users.id` is stored). If the insert itself fails on the `id` value, simplify by inserting a fixed 16-byte literal via `UNHEX('00000000000000000000000000000001')`-style value instead — the `id` value doesn't need to be a real sortable UUIDv7 for this throwaway test.

- [ ] **Step 10: Stop the app and clean up test data**

```bash
pkill -f "spring-boot:run"; sleep 3
```

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/entity/EmailVerificationToken.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/repository/EmailVerificationTokenRepository.java \
  src/main/resources/db/migration/V7__create_email_verification_tokens_table.sql \
  src/main/resources/db/migration/V8__add_email_verified_to_users.sql \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/entity/User.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/dto/response/UserResponse.java
git commit -m "feat: add email verification token entity and schema"
```

---

### Task 2: OTP issuance on user creation + notification pipeline

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/event/EmailVerificationOtpEvent.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RabbitMQConfig.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/notification/listener/UserAccountNotificationListener.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes (from Task 1): `EmailVerificationTokenRepository`, `EmailVerificationToken.builder()`.
- Produces: `RabbitMQConfig.EMAIL_VERIFICATION_QUEUE`/`EMAIL_VERIFICATION_ROUTING_KEY`; `EmailVerificationOtpEvent(UUID userId, String email, String fullName, String otp)`; a private `UserServiceImpl.issueVerificationOtp(User user)` helper that Task 3/4 do **not** need to call directly (only `createUser` calls it in this task; `resendVerificationOtp` in Task 4 will call the same helper).

- [ ] **Step 1: Create the event record**

```java
package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.util.UUID;

public record EmailVerificationOtpEvent(
    UUID userId,
    String email,
    String fullName,
    String otp
) {}
```

- [ ] **Step 2: Add the routing key, queue, and binding to `RabbitMQConfig`**

Add the constants alongside the existing ones:

```java
    public static final String EMAIL_VERIFICATION_QUEUE = "email.verification.notification.queue";
    public static final String EMAIL_VERIFICATION_ROUTING_KEY = "user.email-verification.otp-issued";
```

Add the beans after `userRestoreBinding()`:

```java
    @Bean
    public Queue emailVerificationNotificationQueue() {
        return QueueBuilder.durable(EMAIL_VERIFICATION_QUEUE)
            .withArgument("x-dead-letter-exchange", NOTIFICATION_DLX)
            .build();
    }

    @Bean
    public Binding emailVerificationBinding() {
        return BindingBuilder.bind(emailVerificationNotificationQueue()).to(notificationExchange()).with(EMAIL_VERIFICATION_ROUTING_KEY);
    }
```

- [ ] **Step 3: Add the listener method**

Add to `UserAccountNotificationListener`:

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

Add the import: `import com.maaitlunghau.spring_boot_blueprint.module.user.event.EmailVerificationOtpEvent;`

- [ ] **Step 4: Add the config property**

`application.yml`, under the existing `app:` block (after `user.soft-delete`):

```yaml
  email-verification:
    otp-expiration-minutes: 10
```

- [ ] **Step 5: Wire OTP issuance into `UserServiceImpl`**

Add the new dependency + config value to the constructor/fields:

```java
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Value("${app.email-verification.otp-expiration-minutes}")
    private int otpExpirationMinutes;
```

Update the constructor to take and assign the new dependency:

```java
    public UserServiceImpl(
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        UserMapper userMapper,
        StorageService storageService,
        OutboxEventWriter outboxEventWriter,
        EmailVerificationTokenRepository emailVerificationTokenRepository
    ) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.userMapper = userMapper;
        this.storageService = storageService;
        this.outboxEventWriter = outboxEventWriter;
        this.emailVerificationTokenRepository = emailVerificationTokenRepository;
    }
```

Add the OTP-generation constant/helper and the issuance helper (near `validateAvatarFile`):

```java
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private String generateOtp() {
        return String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
    }

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

Call it from `createUser`, right after `save`:

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

        User saved = userRepository.save(user);
        issueVerificationOtp(saved);

        return userMapper.toResponse(saved);
    }
```

Add imports: `java.security.SecureRandom`, `java.time.temporal.ChronoUnit`, `com.maaitlunghau.spring_boot_blueprint.module.user.entity.EmailVerificationToken`, `com.maaitlunghau.spring_boot_blueprint.module.user.event.EmailVerificationOtpEvent`, `com.maaitlunghau.spring_boot_blueprint.module.user.repository.EmailVerificationTokenRepository`.

- [ ] **Step 6: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 7: Live-verify OTP issuance end-to-end**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run > /tmp/email-verification-task2-boot.log 2>&1 &
```

Poll for `Started SpringBootBlueprintApplication`, then:

```bash
curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"OTP Test","email":"otp-test@test.com","password":"Password123!","role":"USER"}' | python3 -m json.tool
```

Expected: `201`, `data.emailVerified: false`, `data.emailVerifiedAt: null`.

```bash
set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT otp_hash, expires_at, attempt_count FROM email_verification_tokens ORDER BY created_at DESC LIMIT 1;" 2>&1 | grep -v Warning
```

Expected: one row, `otp_hash` a BCrypt string (starts `$2a$`/`$2b$`), `attempt_count = 0`, `expires_at` ~10 minutes in the future.

```bash
sleep 6
curl -s -u "$RABBITMQ_USERNAME:$RABBITMQ_PASSWORD" http://localhost:15672/api/queues/%2F/email.verification.notification.queue | python3 -c "import sys,json; d=json.load(sys.stdin); print('messages:', d.get('messages'))"
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT routing_key, status FROM outbox_events ORDER BY created_at DESC LIMIT 1;" 2>&1 | grep -v Warning
curl -s -u "$RABBITMQ_USERNAME:$RABBITMQ_PASSWORD" http://localhost:15672/api/queues/%2F/notification.dlq | python3 -c "import sys,json; d=json.load(sys.stdin); print('dlq messages:', d.get('messages'))"
```

Expected: `email.verification.notification.queue` messages `0` (consumed), outbox row `routing_key=user.email-verification.otp-issued`, `status=PUBLISHED`, DLQ `0`. Confirm the real inbox behind `otp-test@test.com` (or the shared test SMTP account) received an email containing a 6-digit code — **write that code down**, it's needed to test Task 3 without waiting to create another user.

- [ ] **Step 8: Stop the app** (keep the test user + OTP — Task 3 will consume it)

```bash
pkill -f "spring-boot:run"; sleep 3
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/event/EmailVerificationOtpEvent.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RabbitMQConfig.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/notification/listener/UserAccountNotificationListener.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java \
  src/main/resources/application.yml
git commit -m "feat: issue email verification otp on user creation"
```

---

### Task 3: Verify-email endpoint

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/UserAlreadyVerifiedException.java`
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/InvalidOtpException.java`
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/dto/request/VerifyEmailRequest.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java`

**Interfaces:**
- Consumes (from Task 1/2): `EmailVerificationTokenRepository.findTopByUserIdOrderByCreatedAtDesc`, `EmailVerificationToken.isUsed/isExpired/isAttemptsExceeded/incrementAttempt/markUsed`, `User.verifyEmail()`.
- Produces: `UserService.verifyEmail(UUID id, String otp): UserResponse`; `POST /api/users/{id}/verify-email`.

- [ ] **Step 1: Create the two new exceptions**

```java
package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class UserAlreadyVerifiedException extends AppException {

    public UserAlreadyVerifiedException(String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("User email is already verified: %s", identifier)
        );
    }
}
```

```java
package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class InvalidOtpException extends AppException {

    public InvalidOtpException() {
        super(HttpStatus.BAD_REQUEST, "Invalid or expired verification code");
    }
}
```

- [ ] **Step 2: Create the request DTO**

```java
package com.maaitlunghau.spring_boot_blueprint.module.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record VerifyEmailRequest(
    @NotBlank(message = "OTP is required")
    @Pattern(regexp = "\\d{6}", message = "OTP must be exactly 6 digits")
    String otp
) {}
```

- [ ] **Step 3: Add `verifyEmail` to the `UserService` interface**

```java
    UserResponse verifyEmail(UUID id, String otp);
```

(Add it after `restoreUser(UUID id);`.)

- [ ] **Step 4: Implement it in `UserServiceImpl`**

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

Add the import: `import com.maaitlunghau.spring_boot_blueprint.exception.InvalidOtpException;` and `import com.maaitlunghau.spring_boot_blueprint.exception.UserAlreadyVerifiedException;`

- [ ] **Step 5: Add the controller endpoint**

```java
    @PostMapping("/{id}/verify-email")
    public ResponseEntity<ApiResponse<UserResponse>> verifyEmail(
        @PathVariable UUID id,
        @Valid @RequestBody VerifyEmailRequest request
    ) {
        return ResponseEntity.ok(
            ApiResponse.of(
                HttpStatus.OK.value(),
                "Email verified successfully",
                userService.verifyEmail(id, request.otp())
            )
        );
    }
```

(Place it after the `restoreUser` endpoint, before `updateAvatar`.) Add the import for `VerifyEmailRequest`.

- [ ] **Step 6: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 7: Live-verify the full flow**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run > /tmp/email-verification-task3-boot.log 2>&1 &
```

Poll for `Started SpringBootBlueprintApplication`, then get the test user's id from Task 2 back:

```bash
USER_ID=$(curl -s "http://localhost:8081/api/users?keyword=otp-test" | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['content'][0]['id'])")
echo "USER_ID=$USER_ID"

echo "--- wrong OTP (expect 400) ---"
curl -s -X POST "http://localhost:8081/api/users/$USER_ID/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"000000"}'
echo

echo "--- correct OTP from Task 2's email (expect 200) ---"
curl -s -X POST "http://localhost:8081/api/users/$USER_ID/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"<PASTE_REAL_OTP_HERE>"}'
echo

echo "--- verify again on now-verified user (expect 409) ---"
curl -s -X POST "http://localhost:8081/api/users/$USER_ID/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"000000"}'
echo
```

Expected: `400` → `200` (`emailVerified: true`, `emailVerifiedAt` populated) → `409 UserAlreadyVerifiedException`.

Now test attempts-exhaustion with a **fresh** user (the previous one is already verified and can't be reused for this):

```bash
USER_ID2=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Attempts Test","email":"attempts-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

for i in 1 2 3 4 5 6; do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8081/api/users/$USER_ID2/verify-email" \
    -H "Content-Type: application/json" -d '{"otp":"111111"}')
  echo "attempt $i -> $code"
done
```

Expected: all 6 attempts return `400` (wrong code every time — attempt 6 is `400` for a *different* reason internally, exhausted-attempts, but the response is identically generic by design). Confirm via DB that `attempt_count` capped/stayed reflecting 5 real increments:

```bash
set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT attempt_count FROM email_verification_tokens WHERE user_id = UNHEX(REPLACE('$USER_ID2','-',''));" 2>&1 | grep -v Warning
```

Expected: `attempt_count = 5` (the 6th attempt short-circuited on the `isAttemptsExceeded()` filter before incrementing further).

Now test expiry. Temporarily edit `application.yml`'s `otp-expiration-minutes` to a value effectively `0`... but `0` may round oddly with `plus(0, MINUTES)` landing exactly "now" (a race with the immediate curl call). Instead, keep the config at `10` and just **wait it out is impractical for a test** — use this approach: temporarily change `Instant.now().plus(otpExpirationMinutes, ChronoUnit.MINUTES)` is not something to hack per-run; simplest reliable approach is a direct DB edit on a real issued token:

```bash
USER_ID4=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Expiry Test","email":"expiry-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "UPDATE email_verification_tokens SET expires_at = NOW() - INTERVAL 1 MINUTE WHERE user_id = UNHEX(REPLACE('$USER_ID4','-',''));" 2>&1 | grep -v Warning

curl -s -X POST "http://localhost:8081/api/users/$USER_ID4/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"000000"}'
echo
```

Expected: `400 InvalidOtpException` — rejected as expired (via `isExpired()`) regardless of what OTP value is sent, since the token row itself is now past `expires_at`.

- [ ] **Step 8: Stop the app and clean up test data**

```bash
pkill -f "spring-boot:run"; sleep 3
set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "DELETE FROM users WHERE email IN ('otp-test@test.com','attempts-test@test.com','expiry-test@test.com');" 2>&1 | grep -v Warning
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/UserAlreadyVerifiedException.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/InvalidOtpException.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/dto/request/VerifyEmailRequest.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java
git commit -m "feat: add verify-email endpoint for otp verification"
```

---

### Task 4: Resend OTP endpoint

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/ResendCooldownException.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes (from Task 2): the private `issueVerificationOtp(User user)` helper. (from Task 3): `UserAlreadyVerifiedException`.
- Produces: `UserService.resendVerificationOtp(UUID id): void`; `POST /api/users/{id}/resend-verification-otp`.

- [ ] **Step 1: Create `ResendCooldownException`**

```java
package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class ResendCooldownException extends AppException {

    public ResendCooldownException(long secondsRemaining) {
        super(
            HttpStatus.TOO_MANY_REQUESTS,
            String.format("Please wait %d seconds before requesting another code", secondsRemaining)
        );
    }
}
```

- [ ] **Step 2: Add the config property**

`application.yml`, under `app.email-verification` (after `otp-expiration-minutes`):

```yaml
    resend-cooldown-seconds: 60
```

- [ ] **Step 3: Add `resendVerificationOtp` to the `UserService` interface**

```java
    void resendVerificationOtp(UUID id);
```

(Add it after `verifyEmail(UUID id, String otp);`.)

- [ ] **Step 4: Implement it in `UserServiceImpl`**

Add the field:

```java
    @Value("${app.email-verification.resend-cooldown-seconds}")
    private long resendCooldownSeconds;
```

Add the method:

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

Add imports: `java.time.Duration`, `com.maaitlunghau.spring_boot_blueprint.exception.ResendCooldownException`.

- [ ] **Step 5: Add the controller endpoint**

```java
    @PostMapping("/{id}/resend-verification-otp")
    public ResponseEntity<ApiResponse<Void>> resendVerificationOtp(@PathVariable UUID id) {
        userService.resendVerificationOtp(id);
        return ResponseEntity.ok(
            ApiResponse.message(HttpStatus.OK.value(), "Verification code resent")
        );
    }
```

(Place it after the `verifyEmail` endpoint.)

- [ ] **Step 6: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 7: Live-verify resend + cooldown + old-OTP-invalidated**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run > /tmp/email-verification-task4-boot.log 2>&1 &
```

Poll for startup, then:

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Resend Test","email":"resend-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
echo "USER_ID=$USER_ID"
```

Check the real inbox for the first OTP, note it down, then immediately request a resend and confirm cooldown blocks it:

```bash
echo "--- resend immediately (expect 429) ---"
curl -s -X POST "http://localhost:8081/api/users/$USER_ID/resend-verification-otp"
echo
```

Expected: `429 ResendCooldownException` with a `~60` seconds-remaining message.

Verify the **first** OTP still works right now (cooldown blocked the resend, so no new OTP was issued yet):

```bash
curl -s -X POST "http://localhost:8081/api/users/$USER_ID/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"<FIRST_OTP_FROM_EMAIL>"}'
echo
```

Expected: `200`, verified.

For the old-OTP-invalidation check, repeat with a **new** user, wait out the 60s cooldown (or temporarily lower `resend-cooldown-seconds` to `5` for this run only, reverting before committing — same technique as prior scheduler tests), request resend, then confirm the **first** OTP no longer works and the **second** one does:

```bash
USER_ID3=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Resend Test 2","email":"resend-test-2@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
# note the first OTP from email, then wait/resend:
sleep 6
curl -s -X POST "http://localhost:8081/api/users/$USER_ID3/resend-verification-otp"
echo
# note the second OTP from email

echo "--- old OTP should now fail ---"
curl -s -X POST "http://localhost:8081/api/users/$USER_ID3/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"<FIRST_OTP>"}'
echo

echo "--- new OTP should succeed ---"
curl -s -X POST "http://localhost:8081/api/users/$USER_ID3/verify-email" \
  -H "Content-Type: application/json" -d '{"otp":"<SECOND_OTP>"}'
echo
```

Expected: old OTP → `400 InvalidOtpException`; new OTP → `200`.

If `resend-cooldown-seconds` was temporarily lowered for this run, revert it to `60` before committing.

Also confirm resend on an already-verified user is blocked:

```bash
curl -s -X POST "http://localhost:8081/api/users/$USER_ID3/resend-verification-otp"
```

Expected: `409 UserAlreadyVerifiedException`.

- [ ] **Step 8: Stop the app and clean up test data**

```bash
pkill -f "spring-boot:run"; sleep 3
set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "DELETE FROM users WHERE email IN ('resend-test@test.com','resend-test-2@test.com');" 2>&1 | grep -v Warning
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/ResendCooldownException.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java \
  src/main/resources/application.yml
git commit -m "feat: add resend verification otp endpoint"
```

---

### Task 5: Rate limiting for the two new endpoints

**Files:**
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RateLimitConfig.java`

**Interfaces:** None — pure config addition on top of the existing `RateLimitConfig`/`RateLimitFilter`.

- [ ] **Step 1: Add the two new sensitive rules**

```java
        new RateLimitRule(
            "/api/users/*/verify-email",
            "POST",
            10,
            Duration.ofMinutes(1),
            1
        ),
        new RateLimitRule(
            "/api/users/*/resend-verification-otp",
            "POST",
            5,
            Duration.ofMinutes(1),
            1
        )
```

(Add after the `/purge` rule.)

- [ ] **Step 2: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 3: Live-verify both tiers independently**

`resend-verification-otp`'s own 60s per-user cooldown (Task 4) would otherwise mask the rate-limit tier (every call past the first on one user gets `429` from the cooldown, not from `RateLimitFilter`, making the two indistinguishable by status code alone). To isolate the rate-limit tier cleanly, **before booting**, temporarily lower `application.yml`'s `resend-cooldown-seconds` to `0` for this run only (revert before committing, same technique used for other scheduler/expiry tests):

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run > /tmp/email-verification-task5-boot.log 2>&1 &
```

Poll for `Started SpringBootBlueprintApplication`, then hammer resend on **one** user 6 times in the same minute:

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"RL Verify Test","email":"rl-verify-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

for i in $(seq 1 6); do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8081/api/users/$USER_ID/resend-verification-otp")
  echo "resend $i -> $code"
done
```

Expected: requests 1-5 → `200` (cooldown disabled, so each succeeds), request 6 → `429` from `RateLimitFilter` (the 5/min tier, not the cooldown — confirmed by `resend-cooldown-seconds: 0` making the cooldown a non-factor for this run). Revert `resend-cooldown-seconds` back to `60` before committing.

Separately, confirm `verify-email`'s 10/min tier (unaffected by any cooldown, no config change needed):

```bash
for i in $(seq 1 11); do
  UID_I=$(curl -s -X POST http://localhost:8081/api/users \
    -H "Content-Type: application/json" \
    -d "{\"fullName\":\"RL Test $i\",\"email\":\"rl-otp-$i@test.com\",\"password\":\"Password123!\",\"role\":\"USER\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "http://localhost:8081/api/users/$UID_I/verify-email" \
    -H "Content-Type: application/json" -d '{"otp":"000000"}')
  echo "verify $i -> $code"
done
```

Expected: requests 1-10 → `400` (wrong OTP, but request went through), request 11 → `429` (rate-limited before even reaching the controller).

- [ ] **Step 4: Stop the app, revert the temporary config, and clean up test data**

```bash
pkill -f "spring-boot:run"; sleep 3
set -a; source .env; set +a
docker exec spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "DELETE FROM users WHERE email LIKE 'rl-verify-%' OR email LIKE 'rl-otp-%';" 2>&1 | grep -v Warning
```

Revert `application.yml`'s `resend-cooldown-seconds` back to `60` — confirm with `git diff src/main/resources/application.yml` that it shows no change before committing (this task doesn't otherwise touch `application.yml`, so any diff there means the revert was missed).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RateLimitConfig.java
git commit -m "feat: rate-limit verify-email and resend-otp endpoints"
```

---

### Task 6: Sync docs

**Files:**
- Modify: `.claude/rules/architecture.md` (local only — gitignored, do not commit)
- Modify: `.claude/skills/resume/PROJECT_STATE.md` (local only — gitignored, do not commit)
- Modify: `docs/AUTH_MODULE_TODO.md` (tracked — commit)

**Interfaces:** None — documentation only.

- [ ] **Step 1: Update `architecture.md`'s migration list**

Add the two new migrations after `V6`:

```
    ├── V6__add_deleted_at_to_users.sql                  ← deleted_at column (soft delete)
    ├── V7__create_email_verification_tokens_table.sql   ← email_verification_tokens table
    └── V8__add_email_verified_to_users.sql              ← email_verified/email_verified_at columns
```

- [ ] **Step 2: Add a bullet describing the Email Verification subsystem**

In "Cross-cutting pieces already in place", after the Soft Delete & Restore bullet:

```
- **Email Verification (OTP)** — `POST /api/users` now also issues a 6-digit OTP (`User.emailVerified`/`emailVerifiedAt`, independent of the ban flag `enabled`), hashed with the existing `PasswordEncoder` (BCrypt) bean and stored in a dedicated `EmailVerificationToken` table (referenced by a plain `userId` FK column, not a JPA relationship — `ON DELETE CASCADE` so `purgeUser` keeps working). Verification (`POST /api/users/{id}/verify-email`) always checks the single most-recent token row for a user (`findTopByUserIdOrderByCreatedAtDesc`), which is what makes a resend (`POST /api/users/{id}/resend-verification-otp`, its own 60s per-user cooldown on top of IP rate limiting) implicitly invalidate the previous code with no extra state. Max 5 wrong attempts permanently exhausts an OTP. All OTP failure modes (wrong/expired/used/exhausted) collapse into one generic `InvalidOtpException` (400) by design — never distinguished in the response. Issuance reuses the existing outbox → RabbitMQ → email pipeline; `UserAccountNotificationListener` gained a 5th `@RabbitListener` method for it. Full rationale: `docs/superpowers/specs/2026-08-30-email-verification-design.md`.
```

- [ ] **Step 3: Add a new section to `docs/AUTH_MODULE_TODO.md`**

Find the line `## From: (add future entries here, one \`## From: <feature>\` section per feature)` and replace it with:

```
## From: Email Verification feature (spec: `docs/superpowers/specs/2026-08-30-email-verification-design.md`)

### 1. Self-register OTP flow — NOT IMPLEMENTED (by design, Auth module doesn't exist)

**Gap:** Only admin-created users (`POST /api/users`) get an OTP issued today.
A future self-register endpoint needs the same flow wired in at registration
time.

**What to do when Auth lands:** Reuse `UserServiceImpl`'s private
`issueVerificationOtp` logic (promote it to a shared/injectable method if the
register endpoint lives in a different service) from the new register
endpoint — same event/queue/listener already built, no new infrastructure
needed, just another caller.

### 2. Unverified users are not blocked from anything — NOT IMPLEMENTED

**Gap:** `emailVerified=false` is tracked but nothing currently prevents an
unverified user from being fully functional (no login exists yet to gate).

**What to do when Auth lands:** Decide whether login should require
`emailVerified=true` (common production choice) — if so, check it in the
authentication flow and return a clear error directing the user to verify,
similar to the generic `BadCredentialsException` handling already in place.

---

## From: (add future entries here, one `## From: <feature>` section per feature)
```

- [ ] **Step 4: Commit** (only the tracked file)

```bash
git add docs/AUTH_MODULE_TODO.md
git commit -m "docs: add email verification gaps to auth module todo"
```
