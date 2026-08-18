# Infrastructure — spring-boot-mini-project

Full setup/run instructions live in `README.md` — this file is the *why*, not a duplicate *how*.

## Local dev stack

`docker-compose.yml` at the repo root runs infra only (`mysql:8.4`, `redis:7-alpine`, `phpmyadmin`) — the app itself is **not** containerized in this compose file, it runs via `./mvnw spring-boot:run` against those containers over `localhost`, so hot-reload/debugging still works. The standalone `Dockerfile` (multi-stage, non-root) exists for CI's build-check and for a future real deployment — it's intentionally not wired into compose.

Ports: app `8081`, phpMyAdmin `8080`, MySQL `3306`, Redis `6379`.

## Secrets

Never hardcode credentials in `application.yml` — that was the very first thing fixed in this repo's history. Real values live in `.env` (gitignored); `.env.example` is the committed template. `direnv` (`.envrc`) auto-loads `.env` on `cd` if installed; `run-dev.sh` is the fallback for anyone without it. A Maven-side auto-load via `properties-maven-plugin` was tried and reverted — check git history / `docs/superpowers/plans/` for why before retrying that path.

## Schema strategy

`ddl-auto: update` for now — tracked as a known gap, not an oversight (see `docs/known-issues.md` and `README.md`'s roadmap). When Flyway/Liquibase gets added, migration files go in `src/main/resources/db/migration/` and `ddl-auto` changes to `validate`.

## CI/CD

`.github/workflows/ci.yml` builds and runs the full test suite against real MySQL/Redis service containers (not mocks), plus a `docker build` check. `cd.yml` is a deliberate no-op placeholder — no deployment target exists yet, don't fill it in without discussing where it should actually deploy to.
