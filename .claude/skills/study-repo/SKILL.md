---
name: study-repo
description: Clone and deeply analyze a GitHub repo to learn its folder structure, tech stack, coding patterns, conventions, and architecture. Produces a structured reference report so you can replicate the same setup in your own project. Use when the user wants to study, learn from, or replicate the structure of an existing open-source repo.
---

# Study Repo

Clone a GitHub repo into a temp directory and produce a structured analysis covering: project structure, tech stack, configuration, coding patterns, naming conventions, and architecture. The goal is to give the user a complete reference they can follow to set up their own project the same way.

## Input

The user provides a GitHub repo URL (e.g. `https://github.com/org/repo`) or shorthand (`org/repo `). Optionally a branch name. If not provided, ask.

## Process

### 1. Clone the repo

Clone to a temp directory so nothing lands in the current project:

- Windows: `%TEMP%\study-repo\<repo-name>`
- macOS/Linux: `/tmp/study-repo/<repo-name>`

Use `--depth 1` for speed unless the user asks for full history. If repo already exists in temp dir, `git pull` instead.

```
git clone --depth 1 <url> <temp-path>
```

### 2. Explore — top-down, broad to narrow

Use the Agent tool with `subagent_type=Explore` for each phase. Run independent explorations in parallel where possible.

#### 2a. Project overview (parallel group 1)

Spawn these three sub-agents in parallel:

**Agent 1 — Structure & Config:**
- Map the full folder tree (depth 3-4 levels)
- Identify root config files: `package.json`, `tsconfig.json`, `vite.config.*`, `next.config.*`, `tailwind.config.*`, `.eslintrc.*`, `.prettierrc`, `Dockerfile`, `docker-compose.yml`, `Makefile`, etc.
- Read each config file and note key settings
- Identify monorepo tools if any (`turbo.json`, `pnpm-workspace.yaml`, `lerna.json`, `nx.json`)

**Agent 2 — Tech stack & Dependencies:**
- Read `package.json` (or equivalent: `requirements.txt`, `go.mod`, `Cargo.toml`, `Gemfile`, etc.)
- List major dependencies and their roles (framework, UI lib, state management, testing, build tool, etc.)
- Note dev dependencies separately (linting, testing, build tooling)
- Identify the runtime/language version constraints

**Agent 3 — CI/CD & Tooling:**
- Check `.github/workflows/`, `.gitlab-ci.yml`, `Jenkinsfile`, etc.
- Read deployment configs, environment files (`.env.example`)
- Check for pre-commit hooks (`.husky/`, `.pre-commit-config.yaml`)
- Note scripts in `package.json` or `Makefile`

#### 2b. Code patterns (parallel group 2)

After group 1 completes, spawn these in parallel:

**Agent 4 — Folder conventions & Architecture:**
- Identify architectural pattern (feature-based, layer-based, domain-driven, etc.)
- Map how folders relate to each other (e.g. `components/` vs `features/` vs `pages/`)
- Note barrel files (`index.ts`), re-export patterns
- Check for shared/common/utils organization
- Identify testing folder structure (colocated vs separate `__tests__/`)

**Agent 5 — Coding patterns & Style:**
- Sample 3-5 representative source files across different areas
- Note naming conventions: files (kebab-case, PascalCase), functions, variables, types/interfaces
- Note import ordering patterns
- Check component patterns (if frontend): props typing, hooks usage, composition
- Check API patterns (if backend): route definition, middleware, error handling
- Note error handling patterns, logging approach
- Check for TypeScript strictness, type patterns (Zod, io-ts, etc.)

**Agent 6 — Domain & Data patterns:**
- Check for data models, schemas, migrations
- Note state management approach
- Check API layer: REST, GraphQL, tRPC, etc.
- Note authentication/authorization patterns
- Check for i18n, theming, feature flags

### 3. Generate the reference report

Write a self-contained HTML file to the OS temp directory: `<tmpdir>/study-repo-<repo-name>-<timestamp>.html`. Use **Tailwind via CDN** for styling.

Open it for the user (`start <path>` on Windows, `open` on macOS, `xdg-open` on Linux) and tell them the absolute path.

The report must include these sections:

#### Header
- Repo name, URL, description (from GitHub or README first line)
- Tech stack summary badges

#### 1. Project Structure
- Visual folder tree (collapsible if possible)
- Purpose annotation for each top-level folder
- Key config files table: file → what it configures → notable settings

#### 2. Tech Stack
- Table: dependency → version → role/purpose
- Split: core deps vs dev deps
- Runtime/language version

#### 3. Architecture
- Identified pattern name (feature-based, layer-based, etc.)
- Diagram showing folder relationships (use Mermaid via CDN)
- Data flow diagram if applicable

#### 4. Coding Conventions
- File naming convention with examples
- Function/variable naming convention
- Import ordering pattern
- Component/module template — show a representative file as a "golden example"
- TypeScript patterns (if applicable)

#### 5. Testing Strategy
- Testing framework and tools
- Test file location convention
- Example test structure

#### 6. CI/CD & Tooling
- Pipeline overview
- Scripts table: script name → what it does
- Pre-commit hooks

#### 7. Quick-start checklist
A numbered list the user can follow to set up a new project with the same structure:
1. Init project with X tool
2. Install these deps
3. Create these folders
4. Add these configs
5. Set up these scripts
...

### 4. Cleanup

Ask the user if they want to keep the cloned repo in temp dir or delete it.

## Important rules

- **Read-only analysis.** Never modify the cloned repo.
- **Stay factual.** Report what IS, not what should be. No opinions on whether patterns are good or bad unless the user asks.
- **Show real examples.** Quote actual file paths and code snippets from the repo — don't generalize.
- **Respect .gitignore.** Don't analyze `node_modules/`, `dist/`, build artifacts.
- **Large repos.** If repo has 100+ source files, sample representative files per folder rather than reading everything. Note what was sampled.
- **Private repos.** If clone fails, tell the user to authenticate (`gh auth login`) and retry.
