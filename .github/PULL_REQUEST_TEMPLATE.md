<!-- Delete sections that do not apply. -->

## What & why

<!-- One or two sentences. Link the story / issue. -->

## Checklist

- [ ] Tests added or updated for the change; targeted tests pass locally.
- [ ] No new Spring test context (reuse an existing IT base).

### Database migrations (only if this PR touches `src/main/resources/db/migration/`)

- [ ] Follows [`docs/deployment/migration-conventions.md`](../docs/deployment/migration-conventions.md):
      additive first; guarded `DROP` last with `IF EXISTS`; FK/`CHECK` on non-trivial
      tables added `NOT VALID` and `VALIDATE`d in a later migration; indexes on hot/large
      tables use `CREATE INDEX CONCURRENTLY`; enum/`CHECK` widening lands one release
      ahead of the first write; long backfills are batched.
- [ ] `MigrationConventionLintTest` passes (runs in the `test` phase).
- [ ] Any `-- migration-lint: allow-*` opt-out carries a real reason.
