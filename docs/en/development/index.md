# Development Guide

This section is for engineers who want to contribute to RecordPlatform or set up a local development environment.

## Contents

- [Local Development](local-dev) — set up the full local dev stack
- [Contributing](contributing) — branch conventions, PR workflow, CI gates
- [Testing](testing) — test layers, coverage requirements, run commands

## Quick Reference

| Question | Answer |
|----------|--------|
| Dependency injection | Prefer `@RequiredArgsConstructor`; never use `@Autowired` in production code |
| DTOs/VOs | Prefer Java Records |
| REST path style | kebab-case (e.g., `/upload-sessions`, not `/uploadSessions`) |
| Exception handling | `GeneralException(ResultEnum)` for business errors |
| Cross-tenant operations | `@TenantScope(ignoreIsolation = true)` |
| Frontend state | Svelte 5 Runes (`$state`, `$derived`, `$effect`); no Svelte 4 stores |
| Audit tracking | Controllers must have `@OperationLog(module, operationType, description)` |
| SQL parameters | MyBatis `#{}` only; never `${}` for user input |
