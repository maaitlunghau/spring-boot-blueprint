# Keeping /resume Project State in Sync

`.claude/skills/resume/PROJECT_STATE.md` is the persisted context the `/resume` skill loads so Claude doesn't have to re-analyze this repo from scratch each session. It currently starts as an empty template — fill it in as real state accumulates.

After doing any of the following in this repo, update the relevant section of that file directly — don't wait to be asked:

- Adding the first domain module (controller/service/repository/entity) — this is when `architecture.md`, `backend-patterns.md`, and `coding-standards.md` also stop being placeholders.
- Changing the infra/config setup (Docker, compose, env vars, profiles, Flyway) in a way future sessions need to know about.
- A decision the next session shouldn't silently revert.
- Anything that would make a future `/resume` hand out stale advice.

Update the `Last synced commit` line at the top of `PROJECT_STATE.md` to the current `git rev-parse HEAD` each time you touch it. Keep edits surgical — patch the specific section that changed, don't regenerate the whole file.
