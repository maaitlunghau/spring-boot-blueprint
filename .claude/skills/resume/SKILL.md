---
name: resume
description: Load this project's persisted state and the latest handoff doc at the start of a new session, instead of re-analyzing the repo from scratch.
disable-model-invocation: true
---

Read `.claude/skills/resume/PROJECT_STATE.md` — the persisted summary kept current by the `project-state-sync` rule.

Check the OS temp directory for a fresher handoff doc: `ls -t /tmp/*handoff* 2>/dev/null` (Windows: `%TEMP%`), newest first — `/handoff` doesn't fix a filename, so match loosely on "handoff" rather than a specific pattern. The temp dir is shared across every project on the machine, so before trusting a match, confirm it actually mentions `spring-boot-mini-project` (filename or first few lines) — skip it silently otherwise, it belongs to a different project's session. If a genuine match postdates PROJECT_STATE.md's `Last synced commit`, read it too — it may carry context the state file hasn't absorbed yet.

Verify both against reality before acting on them:
- `git log --oneline -10` and `git status --short` — compare against what the loaded docs claim.
- If PROJECT_STATE.md's `Last synced commit` is behind current `HEAD`, run `git log <that-commit>..HEAD --oneline` and report what's changed since, rather than treating the doc as current.

Summarize for the user: project goal, current state, and what the last session's next step was — then wait for direction. Don't assume what to do next.

If neither file exists, say so and offer to build `PROJECT_STATE.md` now from `git log`, `README.md`, and `docs/`.
