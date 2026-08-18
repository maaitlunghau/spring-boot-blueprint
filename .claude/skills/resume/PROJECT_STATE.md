# Project State — spring-boot-blueprint

**Last synced commit:** 71ea94a2590cac9717220c31e517ccb0eb686d32


## What this is

Spring Boot 4.1.0 / Java 21 starter blueprint. No domain/business code yet — the only application code is `SpringBootBlueprintApplication` and its default context-load test. What exists is scaffolding: dependencies, Docker/compose, env/profile config, Flyway, git hooks, CI/CD.


## Current focus

Just finished a production-readiness/security hardening pass on the scaffold itself (Docker, DB/Redis config, dev/prod profiles, Flyway, CI/CD) — no feature work has started. See `/tmp/spring-boot-blueprint-handoff-2026-08-18.md` for the full session-by-session detail if it still exists; this file is the durable summary.


## Known issues / TODO

- No Spring Boot Actuator (`/health`, `/metrics`).
- `spring-boot-starter-security` + `app.jwt.*` config exist in `application.yml`, but **no `SecurityConfig`/JWT filter is implemented yet** — biggest functional gap before adding real endpoints.
- No logging configuration (levels per profile, structured logging, rotation).
- No fail-fast validation for missing required env vars (`JWT_SECRET`, `DB_PASSWORD`, ...) at startup.
- No rate limiting planned for future auth endpoints yet.
- `db/migration/V1__init.sql` (Flyway) is still an empty baseline — no entities exist to migrate.


## Decisions to respect (don't silently change)

- CD (`.github/workflows/cd.yml`) is a deliberate no-op placeholder — no registry/server/cloud deploy target chosen yet. Don't add a push/deploy step without asking where it should go.
- Commit rules: no `Co-Authored-By` trailer ever; `type(scope): subject` ≤70 chars single line, no body; this repo uses no scope by convention; don't cram unrelated files into one commit. See `.claude/rules/workflow.md` and the `writing-commit-messages` skill.
- `.husky/commit-msg` no longer enforces all-lowercase subjects (only `type`/`scope` must be lowercase) — this was an explicit, deliberate change from the default template.
- CI's redis service has no password (GitHub Actions services can't override the image command to add `--requirepass`) — intentional, not a bug. Local/prod compose still requires `REDIS_PASSWORD`.
- `ddl-auto`: `dev` = `update` (Flyway disabled), `prod` = `validate` (Flyway enabled) — don't flip prod back to `update`.
- MySQL app connects as the non-root `MYSQL_USER`/`DB_USERNAME` — never root. `MYSQL_ROOT_PASSWORD` is a separate credential.
- `.claude/skills/` content is intentionally left alone unless explicitly requested (it was previously found to contain one stale file — `writing-commit-messages/SKILL.md` — which was fixed by explicit request; don't assume the rest needs touching without being asked).


## Next step

Not yet decided — ask the user. Likely candidates: implement `SecurityConfig`/JWT auth (the config already exists and is unused), or start the first real domain module. Don't assume which without checking.
