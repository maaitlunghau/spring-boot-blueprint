# spring-boot-mini-project

Spring Boot 4.1.0 / Java 21 practice project — production-grade patterns, not a toy. Full context: `README.md` (setup/run), `docs/known-issues.md` (known bugs, what's fixed vs open), `.claude/rules/` (architecture/coding/infra conventions, loaded on demand).

## Hard rules

- Never commit `.env`, print its contents, or reuse the JWT secret documented as leaked in this repo's git history.
- Commits must pass the Husky `commit-msg` hook: `type(scope): subject`, lowercase, single line, ≤70 chars, no body — see `.claude/rules/workflow.md`.
- Swagger being public and CD being a no-op are deliberate decisions — don't "fix" either without asking first.
- Bug #5 (last-admin race) has a documented failed fix attempt — read the `project_last-admin-race-deferred` memory and the disabled test's Javadoc before retrying it.
