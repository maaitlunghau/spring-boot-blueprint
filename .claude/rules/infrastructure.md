# Infrastructure — spring-boot-blueprint

## Local dev stack

`docker-compose.yml` at the repo root runs infra only: `mysql:8.4`, `redis:7-alpine`, and `phpmyadmin` (gated behind the `dev` compose profile — `docker compose --profile dev up -d` to include it; plain `docker compose up -d` only starts `mysql`+`redis`). The app itself is **not** wired into this compose file — run it via `./mvnw spring-boot:run` against those containers over `localhost`.

`mysql`/`redis` ports are bound to `127.0.0.1` only, not `0.0.0.0` — don't remove that binding without a reason, it's what keeps the DB/cache off the network in anything other than local dev.

The standalone `Dockerfile` (multi-stage: `maven:3.9-eclipse-temurin-21` builder → `eclipse-temurin:21-jre-alpine` runtime, non-root `spring` user) is separate from compose. `pom.xml` sets `<finalName>app</finalName>` specifically so `target/app.jar` matches what the `Dockerfile`'s `COPY` expects — keep those two in sync if either changes. `.dockerignore` excludes `.env`, `.git`, `node_modules`, `target` from the build context.

Ports: app `8081`, phpMyAdmin `8080`, MySQL `3306`, Redis `6379`.

## Secrets

Never hardcode credentials in `application.yml` / `application-*.yml` — use `${VAR}` placeholders. Real values live in `.env` (gitignored); `.env.example` is the committed template with the current full var list (`MYSQL_HOST`, `MYSQL_ROOT_PASSWORD`, `DB_USERNAME`, `DB_PASSWORD`, `REDIS_HOST`, `REDIS_PASSWORD`, `JWT_SECRET`, `SPRING_PROFILES_ACTIVE`). `direnv` (`.envrc`, containing just `dotenv`) auto-loads `.env` on `cd` if installed and hooked into the shell — note it only fires in an interactive shell session, not in a plain non-interactive subshell (e.g. `bash -c '...'`), which needs `set -a; source .env; set +a` instead.

MySQL now runs with a non-root app user (`MYSQL_USER`/`MYSQL_PASSWORD` in compose, sourced from `DB_USERNAME`/`DB_PASSWORD`) separate from `MYSQL_ROOT_PASSWORD` — the app should never connect as root. Redis requires a password (`--requirepass` from `REDIS_PASSWORD`) — connecting without one fails with `NOAUTH`.

## Schema strategy

Flyway (`flyway-core` + `flyway-mysql`) is wired in. `application-dev.yml` uses `ddl-auto: update` with Flyway disabled for fast local iteration; `application-prod.yml` uses `ddl-auto: validate` with Flyway enabled. `src/main/resources/db/migration/V1__init.sql` is currently an empty baseline — add real migrations there (`V2__...`, `V3__...`) as entities get added, don't rely on `ddl-auto` to shape the prod schema.

## CI/CD

`.github/workflows/ci.yml` runs on push/PR to `main`: a `test` job spins up `mysql:8.4` + `redis:7-alpine` as service containers (dummy CI-only credentials, redis has no password — GitHub Actions service containers can't override the image's command to add `--requirepass`) and runs `./mvnw test`; a `docker-build` job (depends on `test`) builds the `Dockerfile` image to catch build breakage, without pushing it anywhere.

`.github/workflows/cd.yml` is a deliberate no-op placeholder: it triggers after `CI` succeeds on `main` and just rebuilds the docker image to prove it still builds — no registry push, no deploy step. No deployment target (registry/server/cloud) exists yet; don't wire one in without discussing where it should actually go first.

(`.github/modernize/java-upgrade/` is unrelated scaffolding from GitHub's Copilot Java-upgrade assistant feature, not a CI pipeline.)
