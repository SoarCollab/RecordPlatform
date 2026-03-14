# Contributing Guide

## Getting Started

1. Fork the repository
2. Create a feature branch: `git checkout -b feat/your-feature`
3. Follow the code style guidelines
4. Ensure tests pass: `mvn -f platform-backend/pom.xml test`
5. Open a Pull Request against `main`

## Branch Naming

| Prefix | Purpose |
|--------|---------|
| `feat/` | New feature |
| `fix/` | Bug fix |
| `refactor/` | Refactoring (no behavior change) |
| `docs/` | Documentation update |
| `chore/` | Build, dependency, or maintenance changes |

## Commit Message Convention

Format: `<type>: <subject>` (English, ~80 chars)

```bash
# Good
feat: add file version chain support
fix: resolve chunk decryption order issue
docs: add distributed storage architecture page

# Bad
feat: add feature          # too vague
fix: some bug fixes        # not descriptive
```

## CI Gates

All PRs must pass these checks before merging:

| Check | Description |
|-------|-------------|
| **Backend Tests** | Unit + integration tests with coverage thresholds |
| **Frontend Tests** | lint + type check + vitest coverage |
| **Contract Consistency** | OpenAPI ↔ `generated.ts` diff must be empty |
| **Build Verification** | Backend build + frontend `pnpm build` |

### After modifying REST endpoints

You **must** regenerate and commit the frontend API types:

```bash
# Option 1: Backend running locally
cd platform-frontend && pnpm types:gen

# Option 2: Using exported openapi.json
cd platform-frontend && OPENAPI_SOURCE=path/to/openapi.json pnpm types:gen
```

Commit the updated `platform-frontend/src/lib/api/types/generated.ts`.

## Coverage Requirements

**Backend JaCoCo:**

| Module | Min line coverage |
|--------|------------------|
| backend-web | 40% |
| backend-service | 45% |
| backend-common | 40% |

**Frontend Vitest:**

| Path | Lines / Functions / Branches / Statements |
|------|------------------------------------------|
| `src/lib/utils/**` | 70% / 70% / 60% / 70% |
| `src/lib/api/endpoints/**` | 90% / 90% / 85% / 90% |
| `src/lib/stores/**` | 90% / 90% / 80% / 90% |
| `src/lib/services/**` | 90% / 90% / 85% / 90% |

## Code Style

- **DI**: Prefer `@RequiredArgsConstructor`; never `@Autowired` in production code
- **DTO/VO**: Prefer Java Records
- **REST paths**: kebab-case (`/upload-sessions`, not `/uploadSessions`)
- **Controllers**: Must have `@OperationLog(module, operationType, description)`
- **SQL params**: MyBatis `#{}` only; never `${}` for user input
- **Frontend**: Svelte 5 Runes only (no Svelte 4 stores)
- **Lombok**: Use sparingly — mainly `@Slf4j`, `@Data`, `@Builder`, `@RequiredArgsConstructor`
