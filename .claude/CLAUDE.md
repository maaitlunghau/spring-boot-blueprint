# spring-boot-blueprint

Spring Boot 4.1.0 / Java 21 starter blueprint. No domain/business code yet — the only application code is `SpringBootBlueprintApplication` and its default context-load test. What exists so far is scaffolding: dependencies, Docker/compose, env/profile config, Flyway, git hooks. Details live in `.claude/rules/` (loaded on demand); keep those files matching reality as real features land instead of leaving them stale.

## Hard rules

- Never commit `.env` or print its contents — real secrets live there; `.env.example` is the committed template.
- Commits must pass the Husky `commit-msg` hook: `type(scope): subject`, single line, ≤70 chars, no body — see `.claude/rules/workflow.md`.
- Never add a `Co-Authored-By:` trailer (or any "Generated with Claude" line) to a commit.
- Don't bundle unrelated files into one commit — group by concern, split the rest.
