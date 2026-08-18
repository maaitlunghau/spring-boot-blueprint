# Workflow — spring-boot-blueprint

## Commands

```bash
docker compose up -d                    # mysql + redis (add --profile dev for phpmyadmin too)
./mvnw spring-boot:run                  # run the app (needs the containers above + .env loaded)
./mvnw test                             # full suite
./mvnw clean package -DskipTests        # build the jar (target/app.jar)
docker build -t spring-boot-blueprint . # build the Dockerfile image
```

`.env` isn't auto-loaded in a non-interactive shell — use `set -a; source .env; set +a` before running Maven commands outside an interactive `direnv`-hooked shell.

## Git commits

Validated by the Husky `commit-msg` hook (`.husky/commit-msg`): `type(scope): subject`, single line, ≤70 chars, no body, no trailers. Types: `feat fix docs style refactor perf test chore revert ci`. Scope is optional — commits so far use none (`git log --oneline` shows plain `type: subject`), match that unless a real reason to add one comes up.

Rules on top of what the hook enforces (from explicit user instruction — always follow these):

- **Never** add a `Co-Authored-By:` trailer or any "Generated with Claude" line — the hook would reject it anyway (more than one non-blank line), but don't even try.
- **Don't bundle unrelated files into one commit.** Group changes by concern/feature; split anything unrelated into its own commit even if it means several small commits for one task.
- Run `git status` / `git diff` / `git log --oneline -10` before committing, every time — see the `writing-commit-messages` skill for the full checklist.

## When something breaks

1. Read the stack trace from the bottom up — the root cause is usually at the end, not the top.
2. Common Spring Boot errors:
   - `NoSuchBeanDefinitionException` → missing `@Service`/`@Repository`/`@Component`, or it's outside the component-scan base package.
   - `BeanCreationException` → look at the *inner* exception, the real error is nested.
   - `Unable to determine Dialect without JDBC metadata` → datasource URL/credentials aren't resolving — check the active Spring profile and that `.env` is actually loaded in the current shell.
3. For anything non-obvious, use the `superpowers:systematic-debugging` skill.
