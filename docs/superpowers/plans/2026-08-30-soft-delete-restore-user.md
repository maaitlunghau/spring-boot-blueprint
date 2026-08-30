# User Soft Delete & Restore Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `DELETE /api/users/{id}` reversible for a grace period (soft-delete + admin restore) instead of destroying the row immediately, with a scheduled job permanently purging records only after the grace period expires.

**Architecture:** Add a nullable `deletedAt` timestamp to `User` (no new table). All "normal" repository lookups switch to `...AndDeletedAtIsNull` variants so a soft-deleted user is invisible everywhere except two admin-only views (`GET /deleted`, restore, manual purge). Soft-delete and restore each publish a notification through the existing transactional-outbox → RabbitMQ → email pipeline (same pattern as Ban/Unban). A new `@Scheduled` job purges soft-deleted rows past the configurable retention window, reusing the exact same `purgeUser` service method an admin can also call directly.

**Tech Stack:** Spring Boot 4.1.0 / Java 21, Spring Data JPA + `Specification`, Flyway (MySQL 8.4), Spring AMQP (RabbitMQ), Bucket4j/Redis (rate limiting) — no new dependencies.

**Spec:** `docs/superpowers/specs/2026-08-30-soft-delete-restore-user-design.md`

## Global Constraints

- Commit format: `type(scope): subject`, single line, ≤70 chars, no body, no trailers — **never** add `Co-Authored-By:` or any "Generated with Claude" line (Husky `commit-msg` hook enforces the format; the no-trailer rule is a hard project rule on top of it).
- Never bundle unrelated files into one commit — each task below is scoped to land as one commit unless a step says otherwise; if a task's diff spans an unrelated concern, split it.
- **No JUnit tests for this feature** — this project verifies business-logic features (Ban/Unban, Rate Limiting) by hand against the real local stack (`docker compose up -d` + `./mvnw spring-boot:run` + `curl`/RabbitMQ Management UI/Cloudinary dashboard), not automated tests (see `.claude/rules/tech-defaults.md`). Every task's verification step is a live check, not a test file — follow that convention, don't introduce a test file this codebase doesn't otherwise have.
- Every migration must be verified against Hibernate's live `ddl-auto=update` output before being written by hand — don't guess column types.
- Every controller response stays wrapped in the existing `ApiResponse<T>` envelope — no bare DTOs, no bare `204`.
- `AntPathMatcher`'s `*` matches exactly one path segment — when adding rate-limit rules for two-segment paths (`/api/users/{id}/purge`), don't try to reuse a single-segment pattern.
- `SecurityConfig` currently has `.anyRequest().permitAll()` (a known, pre-existing temporary state, unrelated to this feature) — no auth header is needed for any curl verification step below.
- Default retention period is **30 days**, configurable via `app.user.soft-delete.retention-days` — never hardcode it in the scheduler.

---

### Task 1: Data model — `deletedAt` field, migration, repository & specification support

**Files:**
- Create: `src/main/resources/db/migration/V6__add_deleted_at_to_users.sql`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/entity/User.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/dto/response/UserResponse.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/repository/UserRepository.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/repository/spec/UserSpecifications.java`

**Interfaces:**
- Produces: `User.getDeletedAt(): Instant` (via Lombok `@Getter`), `User.softDelete(): void`, `User.restore(): void`; `UserResponse.deletedAt: Instant`; `UserRepository.findByIdAndDeletedAtIsNull(UUID): Optional<User>`, `UserRepository.findByIdAndDeletedAtIsNotNull(UUID): Optional<User>`, `UserRepository.existsByIdAndDeletedAtIsNull(UUID): boolean`, `UserRepository.findByDeletedAtBefore(Instant): List<User>`; `UserSpecifications.notDeleted(): Specification<User>`, `UserSpecifications.onlyDeleted(): Specification<User>`.
- Consumes: nothing new (pure addition, no existing behavior changes yet — `UserServiceImpl` isn't touched in this task, so all existing endpoints keep working exactly as before).

- [ ] **Step 1: Add the `deletedAt` field and business methods to `User`**

Edit `User.java` — add the field after `bannedUntil`, and the two methods after `unban()`:

```java
    @Column(name = "deleted_at", nullable = true)
    private Instant deletedAt;
```

```java
    public void softDelete() {
        this.deletedAt = Instant.now();
    }

    public void restore() {
        this.deletedAt = null;
    }
```

- [ ] **Step 2: Add `deletedAt` to `UserResponse`**

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
        Instant createdAt) {
}
```

(MapStruct's `UserMapper.toResponse` auto-maps by field name — no `@Mapping` annotation needed for this new field.)

- [ ] **Step 3: Add the new finder methods to `UserRepository`**

```java
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findByEnabledFalseAndBannedUntilBefore(Instant instant);

    Optional<User> findByIdAndDeletedAtIsNull(UUID id);

    Optional<User> findByIdAndDeletedAtIsNotNull(UUID id);

    boolean existsByIdAndDeletedAtIsNull(UUID id);

    List<User> findByDeletedAtBefore(Instant cutoff);
}
```

- [ ] **Step 4: Add the new specification factories to `UserSpecifications`**

```java
    public static Specification<User> notDeleted() {
        return (root, query, cb) -> cb.isNull(root.get("deletedAt"));
    }

    public static Specification<User> onlyDeleted() {
        return (root, query, cb) -> cb.isNotNull(root.get("deletedAt"));
    }
```

- [ ] **Step 5: Compile to catch mistakes early**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 6: Boot the app against `dev` (ddl-auto=update) and inspect the generated column**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

In a second terminal, once the app has started (`Started SpringBootBlueprintApplication` in the logs):

```bash
set -a; source .env; set +a
docker exec -it spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" -e "DESCRIBE users;"
```

Expected: a `deleted_at` row with type `datetime(6)`, `Null: YES` — matches the existing `banned_at`/`banned_until` columns' type exactly. Stop the app (`Ctrl+C`) once confirmed.

- [ ] **Step 7: Write `V6` matching the inspected type**

```sql
ALTER TABLE users
    ADD COLUMN deleted_at DATETIME(6) DEFAULT NULL;
```

- [ ] **Step 8: Regression-check existing endpoints still work**

With the app still running (or restarted), verify search/read still work exactly as before this change (no behavior change was introduced yet — this only confirms nothing broke):

```bash
curl -s http://localhost:8081/api/users | head -c 400
```

Expected: `200` with the existing `ApiResponse` envelope and user list, `deletedAt` field present and `null` for every existing user.

- [ ] **Step 9: Commit**

```bash
git add src/main/resources/db/migration/V6__add_deleted_at_to_users.sql \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/entity/User.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/dto/response/UserResponse.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/repository/UserRepository.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/repository/spec/UserSpecifications.java
git commit -m "feat: add deletedAt field and soft-delete query support"
```

---

### Task 2: Soft-delete behavior — `deleteUser` rewritten, soft-deleted users hidden everywhere

**Files:**
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`

**Interfaces:**
- Consumes (from Task 1): `UserRepository.findByIdAndDeletedAtIsNull`, `existsByIdAndDeletedAtIsNull`, `UserSpecifications.notDeleted()`, `User.softDelete()`.
- Produces: `deleteUser(UUID id)` now soft-deletes instead of hard-deleting (signature unchanged: `void deleteUser(UUID id)` — no `UserService`/controller changes needed in this task).

- [ ] **Step 1: Compose `notDeleted()` into the search query**

In `getAllUsers`:

```java
    @Override
    public PageResponse<UserResponse> getAllUsers(String keyword, Role role, Pageable pageable) {
        Page<User> page = userRepository.findAll(
            UserSpecifications.keywordIn(keyword).and(UserSpecifications.hasRole(role)).and(UserSpecifications.notDeleted()),
            pageable
        );

        return PageResponse.from(page.map(userMapper::toResponse));
    }
```

- [ ] **Step 2: Scope every other "normal" lookup to exclude soft-deleted users**

Replace `userRepository.findById(id)` with `userRepository.findByIdAndDeletedAtIsNull(id)` in: `getUserById`, `updateProfile`, `updateRole`, `banUser`, `unbanUser`, and the second `findById` call inside `updateAvatar`. Replace the `!userRepository.existsById(id)` check at the top of `updateAvatar` with `!userRepository.existsByIdAndDeletedAtIsNull(id)`. Every one of these calls already throws `ResourceNotFoundException` in its `.orElseThrow(...)` — leave that exception type unchanged, only swap the finder method being called.

- [ ] **Step 3: Rewrite `deleteUser` to soft-delete**

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
    }
```

This replaces the old hard-delete body (`userRepository.deleteById(id)` + the Cloudinary cleanup `try/catch`) entirely — the `@Transactional(propagation = Propagation.NOT_SUPPORTED)` annotation on this method also changes back to plain `@Transactional`, since there's no external I/O call left in this method (avatar cleanup now only happens at purge time, Task 7). If the `Propagation` import becomes unused after this change, remove it — check the other methods in the file (`updateAvatar`, and later `purgeUser` in Task 7) before removing the import; `updateAvatar` still uses `Propagation.NOT_SUPPORTED`, so the import stays either way.

- [ ] **Step 4: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 5: Live-verify soft-delete + hiding behavior**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

In another terminal, create a throwaway user, then delete it and confirm it disappears everywhere:

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Temp User","email":"temp-softdelete@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID
# Expected: 200, ApiResponse message "User deleted successfully"

curl -s http://localhost:8081/api/users/$USER_ID
# Expected: 404, ResourceNotFoundException body

curl -s "http://localhost:8081/api/users?keyword=temp-softdelete"
# Expected: 200, empty page — the deleted user does not appear
```

Also confirm the row still physically exists (soft, not hard, delete):

```bash
set -a; source .env; set +a
docker exec -it spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT id, email, deleted_at FROM users WHERE email='temp-softdelete@test.com';"
```

Expected: one row, `deleted_at` populated with a real timestamp.

Try deleting a real `ADMIN` user (if one exists from earlier manual testing) or create one and attempt delete:

```bash
curl -s -X DELETE http://localhost:8081/api/users/<an-admin-id>
# Expected: 400 BadRequestException "Cannot delete a user with ADMIN role"
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java
git commit -m "feat: change user delete to soft-delete"
```

---

### Task 3: Notification infrastructure for delete & restore + wire soft-delete notification

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/event/UserDeletedEvent.java`
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/event/UserRestoredEvent.java`
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/notification/listener/UserAccountNotificationListener.java`
- Delete: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/notification/listener/UserBanNotificationListener.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RabbitMQConfig.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`

**Interfaces:**
- Consumes (from Task 2): the rewritten `deleteUser` method body (this task adds one more statement to its end).
- Produces: `RabbitMQConfig.USER_DELETED_ROUTING_KEY`, `USER_RESTORED_ROUTING_KEY`, `USER_DELETE_QUEUE`, `USER_RESTORE_QUEUE` constants; `UserDeletedEvent(UUID userId, String email, String fullName)`; `UserRestoredEvent(UUID userId, String email, String fullName)`. Task 4 (`restoreUser`) will call `outboxEventWriter.write(..., RabbitMQConfig.USER_RESTORED_ROUTING_KEY, new UserRestoredEvent(...))` using these.

- [ ] **Step 1: Create the two event records**

`UserDeletedEvent.java`:
```java
package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.util.UUID;

public record UserDeletedEvent(
    UUID userId,
    String email,
    String fullName
) {}
```

`UserRestoredEvent.java`:
```java
package com.maaitlunghau.spring_boot_blueprint.module.user.event;

import java.util.UUID;

public record UserRestoredEvent(
    UUID userId,
    String email,
    String fullName
) {}
```

- [ ] **Step 2: Add routing keys, queues, and bindings to `RabbitMQConfig`**

Add these constants alongside the existing ban/unban ones:

```java
    public static final String USER_DELETE_QUEUE = "user.deleted.notification.queue";
    public static final String USER_RESTORE_QUEUE = "user.restored.notification.queue";

    public static final String USER_DELETED_ROUTING_KEY = "user.deleted";
    public static final String USER_RESTORED_ROUTING_KEY = "user.restored";
```

Add these beans after `userUnbanBinding()`:

```java
    @Bean
    public Queue userDeleteNotificationQueue() {
        return QueueBuilder.durable(USER_DELETE_QUEUE)
            .withArgument("x-dead-letter-exchange", NOTIFICATION_DLX)
            .build();
    }

    @Bean
    public Binding userDeleteBinding() {
        return BindingBuilder.bind(userDeleteNotificationQueue()).to(notificationExchange()).with(USER_DELETED_ROUTING_KEY);
    }

    @Bean
    public Queue userRestoreNotificationQueue() {
        return QueueBuilder.durable(USER_RESTORE_QUEUE)
            .withArgument("x-dead-letter-exchange", NOTIFICATION_DLX)
            .build();
    }

    @Bean
    public Binding userRestoreBinding() {
        return BindingBuilder.bind(userRestoreNotificationQueue()).to(notificationExchange()).with(USER_RESTORED_ROUTING_KEY);
    }
```

- [ ] **Step 3: Rename the listener and add the two new methods**

Delete `UserBanNotificationListener.java` and create `UserAccountNotificationListener.java` in the same package with the same ban/unban methods plus two new ones:

```java
package com.maaitlunghau.spring_boot_blueprint.module.notification.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.common.notification.EmailService;
import com.maaitlunghau.spring_boot_blueprint.config.RabbitMQConfig;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserBannedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserDeletedEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserRestoredEvent;
import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserUnbannedEvent;

@Component
public class UserAccountNotificationListener {

    private final EmailService emailService;

    public UserAccountNotificationListener(EmailService emailService) {
        this.emailService = emailService;
    }

    @RabbitListener(queues = RabbitMQConfig.USER_BAN_QUEUE)
    public void onUserBanned(UserBannedEvent event) {
        String until = event.bannedUntil() == null ? "permanently" : "until " + event.bannedUntil();

        emailService.send(
            event.email(),
            "Your account has been banned",
            "Hi %s,\n\nYour account has been banned %s.\nReason: %s\n\nIf you believe this is a mistake, please contact support."
                .formatted(event.fullName(), until, event.reason())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.USER_UNBAN_QUEUE)
    public void onUserUnbanned(UserUnbannedEvent event) {
        emailService.send(
            event.email(),
            "Your account has been unbanned",
            "Hi %s,\n\nYour account has been unbanned and you can now sign in again."
                .formatted(event.fullName())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.USER_DELETE_QUEUE)
    public void onUserDeleted(UserDeletedEvent event) {
        emailService.send(
            event.email(),
            "Your account has been deleted",
            "Hi %s,\n\nYour account has been deleted. It will be permanently removed in 30 days unless restored by an administrator."
                .formatted(event.fullName())
        );
    }

    @RabbitListener(queues = RabbitMQConfig.USER_RESTORE_QUEUE)
    public void onUserRestored(UserRestoredEvent event) {
        emailService.send(
            event.email(),
            "Your account has been restored",
            "Hi %s,\n\nYour account has been restored and you can sign in again."
                .formatted(event.fullName())
        );
    }
}
```

- [ ] **Step 4: Wire the outbox call into `deleteUser`**

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

Add the new import: `import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserDeletedEvent;`

- [ ] **Step 5: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 6: Live-verify end-to-end via RabbitMQ + real email**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

Create a user, then delete it:

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Notify Test","email":"notify-delete@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID
```

Check RabbitMQ Management UI (`http://localhost:15672`, login from `RABBITMQ_USERNAME`/`RABBITMQ_PASSWORD` in `.env`) → `user.deleted.notification.queue` should show the message consumed (0 ready after a moment). Check the outbox row transitioned `PENDING` → `PUBLISHED`:

```bash
set -a; source .env; set +a
docker exec -it spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT routing_key, status FROM outbox_events ORDER BY created_at DESC LIMIT 1;"
```

Expected: `routing_key = user.deleted`, `status = PUBLISHED`. Confirm the real inbox behind `notify-delete@test.com` (or the shared test SMTP account normally used for this project) received the "Your account has been deleted" email.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/event/UserDeletedEvent.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/event/UserRestoredEvent.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/notification/listener/UserAccountNotificationListener.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RabbitMQConfig.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java
git rm src/main/java/com/maaitlunghau/spring_boot_blueprint/module/notification/listener/UserBanNotificationListener.java
git commit -m "feat: add email notification on user soft-delete"
```

---

### Task 4: Restore endpoint

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/UserNotDeletedException.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java`

**Interfaces:**
- Consumes (from Task 1): `UserRepository.findByIdAndDeletedAtIsNotNull`, `User.restore()`. (from Task 3): `RabbitMQConfig.USER_RESTORED_ROUTING_KEY`, `UserRestoredEvent`.
- Produces: `UserService.restoreUser(UUID id): UserResponse`; `UserNotDeletedException(String identifier)` (409); `PATCH /api/users/{id}/restore` route.

- [ ] **Step 1: Create `UserNotDeletedException`**

```java
package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class UserNotDeletedException extends AppException {

    public UserNotDeletedException(String identifier) {
        super(
            HttpStatus.CONFLICT,
            String.format("User is not deleted: %s", identifier)
        );
    }
}
```

- [ ] **Step 2: Add `restoreUser` to the `UserService` interface**

```java
    UserResponse restoreUser(UUID id);
```

(Add it after `unbanUser(UUID id);`, before `deleteUser(UUID id);`.)

- [ ] **Step 3: Implement `restoreUser` in `UserServiceImpl`**

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

Add the new imports: `import com.maaitlunghau.spring_boot_blueprint.exception.UserNotDeletedException;` and `import com.maaitlunghau.spring_boot_blueprint.module.user.event.UserRestoredEvent;`

- [ ] **Step 4: Add the controller endpoint**

```java
    @PatchMapping("/{id}/restore")
    public ResponseEntity<ApiResponse<UserResponse>> restoreUser(@PathVariable UUID id) {
        return ResponseEntity.ok(
            ApiResponse.of(
                HttpStatus.OK.value(),
                "User restored successfully",
                userService.restoreUser(id)
            )
        );
    }
```

(Place it after the `unbanUser` endpoint, before `updateAvatar`.)

- [ ] **Step 5: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 6: Live-verify restore end-to-end**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Restore Test","email":"restore-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID
curl -s -X PATCH http://localhost:8081/api/users/$USER_ID/restore
# Expected: 200, ApiResponse "User restored successfully", data.deletedAt == null

curl -s http://localhost:8081/api/users/$USER_ID
# Expected: 200, user visible again
```

Try restoring a user that was never deleted:

```bash
curl -s -X PATCH http://localhost:8081/api/users/$USER_ID/restore
# Expected: 409 UserNotDeletedException, since it's already active from the previous restore
```

Check RabbitMQ Management UI's `user.restored.notification.queue` and confirm the "Your account has been restored" email arrived, same verification style as Task 3.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/UserNotDeletedException.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java
git commit -m "feat: add restore endpoint for soft-deleted users"
```

---

### Task 5: Lock email while a user is soft-deleted

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/EmailPendingPurgeException.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`

**Interfaces:**
- Consumes: `UserRepository.findByEmail` (already existed before this plan).
- Produces: `EmailPendingPurgeException` (409, fixed message, no constructor args); `createUser` now distinguishes an active-user email conflict from a soft-deleted-user email conflict.

- [ ] **Step 1: Create `EmailPendingPurgeException`**

```java
package com.maaitlunghau.spring_boot_blueprint.exception;

import org.springframework.http.HttpStatus;

public class EmailPendingPurgeException extends AppException {

    public EmailPendingPurgeException() {
        super(
            HttpStatus.CONFLICT,
            "This email cannot be used for registration at this time. Please contact an administrator for assistance."
        );
    }
}
```

- [ ] **Step 2: Update `createUser`'s email-conflict check**

Replace the existing `if (userRepository.existsByEmail(request.email())) { throw new DuplicateResourceException(...); }` block with:

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

Add the import: `import com.maaitlunghau.spring_boot_blueprint.exception.EmailPendingPurgeException;`. `existsByEmail` becomes unused in this class after this change — leave the method on `UserRepository` itself alone (removing an interface method used elsewhere is out of scope; check no other caller exists before removing it, and if none do, it's safe to leave unused rather than risk an unrelated deletion this task doesn't need to make).

- [ ] **Step 3: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 4: Live-verify the email lock**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Lock Test","email":"lock-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID

curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Someone Else","email":"lock-test@test.com","password":"Password123!","role":"USER"}'
# Expected: 409, message "This email cannot be used for registration at this time. Please contact an administrator for assistance."
```

Confirm the ordinary duplicate-email path (against an active user) still returns the original message:

```bash
curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Dup","email":"lock-test@test.com","password":"Password123!","role":"USER"}' \
  # (repeat immediately without deleting first, against a fresh active user's email)
```
Expected: `409` with `DuplicateResourceException`'s existing message shape (`Duplicate resource: User with identifier: ...`) when the email belongs to an active (non-deleted) user.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/exception/EmailPendingPurgeException.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java
git commit -m "feat: block email reuse while user is soft-deleted"
```

---

### Task 6: Admin view of soft-deleted users

**Files:**
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java`

**Interfaces:**
- Consumes (from Task 1): `UserSpecifications.onlyDeleted()`.
- Produces: `UserService.getDeletedUsers(Pageable pageable): PageResponse<UserResponse>`; `GET /api/users/deleted` route.

- [ ] **Step 1: Add `getDeletedUsers` to the `UserService` interface**

```java
    PageResponse<UserResponse> getDeletedUsers(Pageable pageable);
```

(Add it right after `getAllUsers(...)`.)

- [ ] **Step 2: Implement it in `UserServiceImpl`**

```java
    @Override
    public PageResponse<UserResponse> getDeletedUsers(Pageable pageable) {
        Page<User> page = userRepository.findAll(UserSpecifications.onlyDeleted(), pageable);
        return PageResponse.from(page.map(userMapper::toResponse));
    }
```

(Place it right after `getAllUsers`.)

- [ ] **Step 3: Add the controller endpoint**

Add this **before** the existing `@GetMapping("/{id}")` method in the file (ordering in the source file doesn't affect Spring's routing — the literal `/deleted` segment always wins over the `{id}` variable — but placing it near the other `GET` methods keeps the file readable):

```java
    @GetMapping("/deleted")
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getDeletedUsers(Pageable pageable) {
        return ResponseEntity.ok(
            ApiResponse.ok(userService.getDeletedUsers(pageable))
        );
    }
```

- [ ] **Step 4: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 5: Live-verify**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Deleted List Test","email":"deleted-list@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID

curl -s "http://localhost:8081/api/users/deleted"
# Expected: 200, page containing exactly the soft-deleted user(s), each with a non-null deletedAt

curl -s "http://localhost:8081/api/users"
# Expected: 200, this user does NOT appear here (still hidden from the normal list)
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java
git commit -m "feat: add endpoint to list soft-deleted users"
```

---

### Task 7: Manual purge endpoint

**Files:**
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java`
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java`

**Interfaces:**
- Consumes (from Task 1): `UserRepository.findByIdAndDeletedAtIsNotNull`. (from Task 4): `UserNotDeletedException`.
- Produces: `UserService.purgeUser(UUID id): void` — hard-deletes a soft-deleted user and best-effort cleans up its Cloudinary avatar. Task 8 (scheduler) calls this exact method.

- [ ] **Step 1: Add `purgeUser` to the `UserService` interface**

```java
    void purgeUser(UUID id);
```

(Add it after `deleteUser(UUID id);`, at the end of the interface.)

- [ ] **Step 2: Implement it in `UserServiceImpl`**

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

(Place it at the end of the class, after `deleteUser`. `Propagation` and `storageService` are already imported/injected in this class from the existing avatar-upload code — no new imports needed beyond `UserNotDeletedException`, which Task 4 already added.)

- [ ] **Step 3: Add the controller endpoint**

```java
    @DeleteMapping("/{id}/purge")
    public ResponseEntity<ApiResponse<Void>> purgeUser(@PathVariable UUID id) {
        userService.purgeUser(id);
        return ResponseEntity.ok(
            ApiResponse.message(HttpStatus.OK.value(), "User permanently deleted")
        );
    }
```

(Place it after the existing `deleteUser` endpoint.)

- [ ] **Step 4: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 5: Live-verify**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Purge Test","email":"purge-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID/purge
# Expected: 409 UserNotDeletedException — user is still active, hasn't been soft-deleted yet

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID
curl -s -X DELETE http://localhost:8081/api/users/$USER_ID/purge
# Expected: 200 "User permanently deleted"

set -a; source .env; set +a
docker exec -it spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT * FROM users WHERE email='purge-test@test.com';"
# Expected: empty result — the row is actually gone now, not just deletedAt-flagged

curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Purge Test Again","email":"purge-test@test.com","password":"Password123!","role":"USER"}'
# Expected: 201 — the email is free again after the real purge
```

If you have a real avatar-upload flow available from earlier manual Cloudinary testing, repeat this against a user with a real `imagePublicId` and confirm the asset disappears from the Cloudinary dashboard after purge — same check used for the original `deleteUser` before this feature existed.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/UserService.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/service/impl/UserServiceImpl.java \
  src/main/java/com/maaitlunghau/spring_boot_blueprint/module/user/controller/UserController.java
git commit -m "feat: add manual purge endpoint for soft-deleted users"
```

---

### Task 8: Scheduler for automatic purge

**Files:**
- Create: `src/main/java/com/maaitlunghau/spring_boot_blueprint/scheduler/UserPurgeScheduler.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Consumes (from Task 1): `UserRepository.findByDeletedAtBefore`. (from Task 7): `UserService.purgeUser`.
- Produces: nothing consumed by later tasks — this is a leaf.

- [ ] **Step 1: Add the retention config to `application.yml`**

Add under the existing `app:` block, after `avatar`:

```yaml
  user:
    soft-delete:
      retention-days: 30
```

- [ ] **Step 2: Create `UserPurgeScheduler`**

```java
package com.maaitlunghau.spring_boot_blueprint.scheduler;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.maaitlunghau.spring_boot_blueprint.module.user.entity.User;
import com.maaitlunghau.spring_boot_blueprint.module.user.repository.UserRepository;
import com.maaitlunghau.spring_boot_blueprint.module.user.service.UserService;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class UserPurgeScheduler {

    private final UserRepository userRepository;
    private final UserService userService;

    @Value("${app.user.soft-delete.retention-days}")
    private int retentionDays;

    public UserPurgeScheduler(UserRepository userRepository, UserService userService) {
        this.userRepository = userRepository;
        this.userService = userService;
    }

    @Scheduled(fixedDelay = 3600000)
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
}
```

- [ ] **Step 3: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 4: Live-verify with a temporarily shortened window**

Temporarily edit `application.yml`'s `retention-days` to `0` and this class's `@Scheduled(fixedDelay = 3600000)` to `@Scheduled(fixedDelay = 5000)` **locally only** — do not commit these test values (same technique already used for `BanExpirationScheduler` during its own development).

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

```bash
USER_ID=$(curl -s -X POST http://localhost:8081/api/users \
  -H "Content-Type: application/json" \
  -d '{"fullName":"Scheduler Test","email":"scheduler-test@test.com","password":"Password123!","role":"USER"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")

curl -s -X DELETE http://localhost:8081/api/users/$USER_ID
```

Wait ~10 seconds, then confirm the row is gone without any manual purge call:

```bash
set -a; source .env; set +a
docker exec -it spring-boot-blueprint mysql -u"$DB_USERNAME" -p"$DB_PASSWORD" "spring-boot-blueprint" \
  -e "SELECT * FROM users WHERE email='scheduler-test@test.com';"
```

Expected: empty result. Check the app log for the scheduler's warn line if it ever fails, to confirm the `try/catch` per-row isolation is working as intended (one failure shouldn't be expected here, but confirm no stack trace appeared).

- [ ] **Step 5: Revert the temporary test values back to `retention-days: 30` and `fixedDelay = 3600000`**

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/scheduler/UserPurgeScheduler.java \
  src/main/resources/application.yml
git commit -m "feat: add scheduler to auto-purge expired soft-deletes"
```

---

### Task 9: Rate limiting for the new sensitive endpoints

**Files:**
- Modify: `src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RateLimitConfig.java`

**Interfaces:**
- Consumes: none from this plan (pure config addition on top of the existing `RateLimitConfig`/`RateLimitFilter` from the already-shipped Rate Limiting feature).
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Add three new sensitive rules**

Add to the `SENSITIVE_RULES` list (after the existing avatar rule), each starting at `configVersion` `1` since these are brand-new rules, not edits to an existing one:

```java
        new RateLimitRule(
            "/api/users/*",
            "DELETE",
            10,
            Duration.ofMinutes(1),
            1
        ),
        new RateLimitRule(
            "/api/users/*/restore",
            "PATCH",
            10,
            Duration.ofMinutes(1),
            1
        ),
        new RateLimitRule(
            "/api/users/*/purge",
            "DELETE",
            10,
            Duration.ofMinutes(1),
            1
        )
```

Note: the `"/api/users/*"` / `"DELETE"` rule matches the soft-delete route (`/api/users/{id}`) only — it does **not** also match `/api/users/{id}/purge`, since `AntPathMatcher`'s `*` matches exactly one path segment. That's why `/purge` needs its own separate rule above rather than trying to reuse this one.

- [ ] **Step 2: Compile**

Run: `./mvnw compile -q`
Expected: no errors.

- [ ] **Step 3: Live-verify the two DELETE tiers don't cross-match**

```bash
docker compose up -d
set -a; source .env; set +a
./mvnw spring-boot:run
```

Create 11 throwaway users, soft-delete 10 of them in a loop, and confirm the 11th soft-delete in that same minute gets `429`:

```bash
for i in $(seq 1 11); do
  UID_I=$(curl -s -X POST http://localhost:8081/api/users \
    -H "Content-Type: application/json" \
    -d "{\"fullName\":\"RL Test $i\",\"email\":\"rl-test-$i@test.com\",\"password\":\"Password123!\",\"role\":\"USER\"}" \
    | python3 -c "import sys,json; print(json.load(sys.stdin)['data']['id'])")
  echo "user $i: $UID_I"
  curl -s -o /dev/null -w "delete $i -> %{http_code}\n" -X DELETE http://localhost:8081/api/users/$UID_I
done
```

Expected: requests 1–10 return `200`, request 11 returns `429` with the `Retry-After` header and the `ApiResponse` envelope, matching the exact behavior already verified for the `/ban`/`/unban` tier when Rate Limiting first shipped.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/maaitlunghau/spring_boot_blueprint/config/RateLimitConfig.java
git commit -m "feat: rate-limit delete, restore, and purge endpoints"
```

---

### Task 10: Sync docs

**Files:**
- Modify: `.claude/rules/architecture.md`
- Modify: `.claude/skills/resume/PROJECT_STATE.md`
- Modify: `docs/AUTH_MODULE_TODO.md`

**Interfaces:** None — documentation only, no code.

- [ ] **Step 1: Update `architecture.md`'s migration list**

Find:
```
    └── V5__add_publish_tracking_to_outbox_events.sql    ← published_at/last_error columns
```
Replace with:
```
    ├── V5__add_publish_tracking_to_outbox_events.sql    ← published_at/last_error columns
    └── V6__add_deleted_at_to_users.sql                  ← deleted_at column (soft delete)
```

- [ ] **Step 2: Update `architecture.md`'s `module/user` description**

Find:
```
│   ├── user/             ← first real domain module, fully built including ban/unban (see below)
```
Replace with:
```
│   ├── user/             ← first real domain module, fully built including ban/unban and soft-delete/restore (see below)
```

- [ ] **Step 3: Add a bullet describing the Soft Delete & Restore subsystem**

In the "Cross-cutting pieces already in place" section, add a new bullet after the Rate Limiting one (find the line starting with `- **\`config/RateLimitConfig\` + \`filter/RateLimitFilter\`**` and its full paragraph, then insert after it):

```
- **Soft Delete & Restore (`User.deletedAt`)** — `DELETE /api/users/{id}` sets `deletedAt` instead of removing the row; every normal lookup (`getUserById`, `updateProfile`, `updateRole`, `banUser`, `unbanUser`, `updateAvatar`, search) is scoped via `...AndDeletedAtIsNull`/`UserSpecifications.notDeleted()` so a soft-deleted user is invisible everywhere except `GET /api/users/deleted`, `PATCH /api/users/{id}/restore`, and `DELETE /api/users/{id}/purge`. While soft-deleted, the user's email is locked (can't be reused for a new registration — `createUser` throws `EmailPendingPurgeException`, distinct from the ordinary `DuplicateResourceException`) until the row is actually purged, so `users.email`'s DB-level unique constraint never needed to change. Soft-delete and restore both publish through the same outbox → RabbitMQ → email pipeline as Ban/Unban (`UserAccountNotificationListener`, renamed from `UserBanNotificationListener` to reflect covering the whole account lifecycle now). `scheduler/UserPurgeScheduler` (`@Scheduled(fixedDelay=3600000)`) hard-deletes soft-deleted rows past `app.user.soft-delete.retention-days` (default 30), reusing the exact same `UserService.purgeUser` method an admin can also call directly via `DELETE /api/users/{id}/purge`. Full rationale: `docs/superpowers/specs/2026-08-30-soft-delete-restore-user-design.md`.
```

- [ ] **Step 4: Add a new section to `docs/AUTH_MODULE_TODO.md`**

Find the line:
```
## From: (add future entries here, one `## From: <feature>` section per feature)
```
Replace it with:
```
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
```

- [ ] **Step 5: Update `PROJECT_STATE.md`**

Update the `Last synced commit` line at the top to the output of `git rev-parse HEAD` after Task 9's commit. Add a new subsection under "What's built (User module)" (after the "API Rate Limiting" subsection) summarizing this feature in the same style as the existing subsections — cover: `deletedAt` field/migration `V6`, the four new/changed endpoints (`DELETE /{id}` behavior change, `PATCH /{id}/restore`, `GET /deleted`, `DELETE /{id}/purge`), the email-lock-while-soft-deleted rule and `EmailPendingPurgeException`, the `UserAccountNotificationListener` rename, and `UserPurgeScheduler`. Update the "Next step" section to reflect that this feature is now done and ask the user what's next (Auth module is still the most likely candidate, per the existing "Next step" list).

- [ ] **Step 6: Commit**

```bash
git add .claude/rules/architecture.md .claude/skills/resume/PROJECT_STATE.md docs/AUTH_MODULE_TODO.md
git commit -m "docs: sync rules and project state for soft delete feature"
```
