# Workflow — spring-boot-mini-project

## Commands

```bash
./mvnw spring-boot:run                          # run the app (needs docker compose up -d first)
./mvnw test                                     # full suite
./mvnw test -Dtest=RefreshTokenServiceImplTest   # one class
./mvnw test -Dtest=ClassName#methodName          # one method
./mvnw clean package -DskipTests                 # build the jar
```

## TDD

Write the failing test first, watch it fail for the *expected* reason, then write the minimal fix, watch it pass. Not aspirational here — every fixed bug in `docs/known-issues.md` was fixed exactly this way, against the real docker-compose MySQL/Redis, not mocks (mocks would have missed the transaction-propagation bugs entirely). Use the `superpowers:test-driven-development` skill for the full cycle discipline.

## Git

Commits are small, one concern each, and validated by a Husky `commit-msg` hook: `type(scope): subject`, all lowercase, single line, ≤60 chars, no body, no trailers. Types: `feat fix docs style refactor perf test chore revert ci`. See `git log --oneline` for the established pattern — feature work first, then separate `fix:` commits each paired with the regression test that caught the bug.

## When something breaks

1. Read the stack trace from the bottom up — the root cause is usually at the end, not the top.
2. Common errors in this codebase's shape:
   - `NoSuchBeanDefinitionException` → missing `@Service`/`@Repository`/`@Component`, or it's outside the component-scan base package.
   - `BeanCreationException` → look at the *inner* exception, the real error is nested.
   - `LazyInitializationException` → accessing a lazy relation outside the transaction that loaded it.
   - `DataIntegrityViolationException` → a DB constraint violation (unique/not-null/FK) — often a check-then-act race that skipped the DB's own constraint check; see bug #6 in `docs/known-issues.md` for a live example.
   - `Connection is read-only` (MySQL) → the exact bug already fixed once in this repo (see `docs/known-issues.md`) — a `@Transactional(readOnly = true)` leaking onto a write path. If it recurs, it's the same root cause.
3. For anything non-obvious, the `superpowers:systematic-debugging` skill has the full root-cause process.
