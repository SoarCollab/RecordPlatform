# Flyway v0.0.2 Release Compatibility Runbook

## 1. Scope and invariants

This runbook applies when upgrading a RecordPlatform database that may have run migrations from release `v0.0.2` or from the known development rewrite that was formerly on `main`.

The following rules are mandatory:

- Stop every `backend-web` instance before inspection or recovery.
- Never run Flyway `repair` from application startup, a readiness hook, or an unattended deployment job.
- Never edit `flyway_schema_history` directly.
- Never add checksum allowlists or disable validation in normal application configuration.
- Back up the database, routines, triggers, and Flyway history before any recovery action.
- Treat every state not matching one of the exact matrices below as unknown. Restore a reviewed backup or escalate to the database owner; do not infer which migrations ran from table shape alone.

The authoritative byte manifest is `platform-backend/backend-web/src/main/resources/db/released-migration-checksums.csv`. It contains all 18 migrations published by `v0.0.2`. The immutable provenance is annotated tag object `74560fd7aadd50904a0edf901651d4366312f107`, peeled to release commit `e38818199a6f8440b65246a8cf864d20c184c763`. `ReleasedMigrationChecksumTest` verifies both the complete released-range path set and every SHA-256. A simultaneous manifest and test-anchor change still requires explicit code review against that peeled release commit.

## 2. Preflight and backup

Record the application artifact SHA, database endpoint, schema name, and maintenance-window change ID. Then create a full backup from a host with network access to MySQL:

```bash
backup_name="recordplatform-before-flyway-$(date -u +%Y%m%dT%H%M%SZ).sql"
mysqldump -h <mysql-host> -u <mysql-user> -p \
  --single-transaction --routines --triggers --events \
  <database-name> > "$backup_name"
shasum -a 256 "$backup_name" > "$backup_name.sha256"
```

Save a separate, reviewable history snapshot:

```sql
SELECT installed_rank,
       version,
       description,
       type,
       script,
       checksum,
       installed_on,
       success
  FROM flyway_schema_history
 ORDER BY installed_rank;
```

If `flyway_schema_history` does not exist, classify the database as **fresh** and use the normal migration path. Otherwise, run the following focused query:

```sql
SELECT version, description, type, script, checksum, success
  FROM flyway_schema_history
 WHERE version IN ('1.0.0', '1.0.1', '1.5.0', '1.7.0', '1.7.3')
 ORDER BY installed_rank;
```

The focused rows are eligible for the known rewritten procedure only when this exact classifier returns `KNOWN_REWRITTEN_KEY_ROWS`:

```sql
SELECT CASE
         WHEN COUNT(*) = 4
          AND SUM(version = '1.0.0'
                  AND type = 'SQL'
                  AND script = 'V1.0.0__init_schema.sql'
                  AND checksum = 1043684703
                  AND success = 1) = 1
          AND SUM(version = '1.0.1') = 0
          AND SUM(version = '1.5.0'
                  AND type = 'SQL'
                  AND script = 'V1.5.0__add_account_nickname.sql'
                  AND checksum = 529309880
                  AND success = 1) = 1
          AND SUM(version = '1.7.0'
                  AND type = 'SQL'
                  AND script = 'V1.7.0__add_soft_delete_columns.sql'
                  AND checksum = 1607980540
                  AND success = 1) = 1
          AND SUM(version = '1.7.3'
                  AND type = 'SQL'
                  AND script = 'V1.7.3__integrity_alert.sql'
                  AND checksum = 2100652293
                  AND success = 1) = 1
         THEN 'KNOWN_REWRITTEN_KEY_ROWS'
         ELSE 'UNKNOWN_STOP'
       END AS compatibility_state
  FROM flyway_schema_history
 WHERE version IN ('1.0.0', '1.0.1', '1.5.0', '1.7.0', '1.7.3');
```

`UNKNOWN_STOP` is terminal for this runbook. The classifier is necessary but not sufficient: the full history and normal Flyway validation must also satisfy sections 3 and 5.

## 3. Checksum classification matrix

The Flyway checksum values below are the signed CRC values produced by the repository's Flyway 11.7.2 line. SHA-256 remains the authoritative artifact identity.

### 3.1 Canonical v0.0.2 rows

| Version | Script | Flyway checksum | SHA-256 |
|---|---|---:|---|
| `1.0.0` | `V1.0.0__init_schema.sql` | `989275442` | `2d62aa70b0f58851579db62034ad12556409201a3cd2e7ad036d709348150645` |
| `1.0.1` | `V1.0.1__add_account_nickname.sql` | `529309880` | `72e68808690ae1a44f22181349d1bae83bae3a7466898d02e5b5d6ef798d2b49` |
| `1.5.0` | `V1.5.0__integrity_alert.sql` | `2001697290` | `f0b90f912d9778ff1712c65e52c4f159dfa0a5c8fdf0c1fe68562a5f29c9edff` |
| `1.7.0` | `V1.7.0__add_soft_delete_columns.sql` | `1349292868` | `6d43fb11866fa6b4a2c749c736be3612feb941b30d389fa4d253fb317a7a414c` |
| `1.7.3` | absent | n/a | n/a |

All other `v0.0.2` rows must match the released manifest, all rows must have `success = 1`, and there must be no failed row. This state can use the normal migration path without repair.

### 3.2 Known rewritten development rows

| Version | Script | Flyway checksum | SHA-256 |
|---|---|---:|---|
| `1.0.0` | `V1.0.0__init_schema.sql` | `1043684703` | `c11f6518144797c7206ce987c9afeba0ecec491acca9dde4f684acee45f9b979` |
| `1.0.1` | absent | n/a | n/a |
| `1.5.0` | `V1.5.0__add_account_nickname.sql` | `529309880` | `72e68808690ae1a44f22181349d1bae83bae3a7466898d02e5b5d6ef798d2b49` |
| `1.7.0` | `V1.7.0__add_soft_delete_columns.sql` | `1607980540` | `4aa571d76a83325eeb7d41cc95a267f7896d4f11f39102af85113461897ead6a` |
| `1.7.3` | `V1.7.3__integrity_alert.sql` | `2100652293` | `fcf6750113ce50ab50ebcc398709636e4dd97b7f75bc2b7a041feafbe7152584` |

This state must fail normal Flyway validation. Do not weaken validation. The recovery in section 5 is allowed only when the exact classifier returns `KNOWN_REWRITTEN_KEY_ROWS`, every other applied migration matches the candidate artifact, and every schema precondition matches exactly.

### 3.3 Unknown or previously repaired state

Examples include a mixture of canonical and rewritten checksums, a different script name, a missing successful row other than the known `1.0.1`, a failed row, a checksum of `NULL`, or a row already marked `DELETE` by an earlier repair.

Stop. Preserve the backup and query output. Do not run the recovery commands below. A database owner must compare the database with its deployment artifact and change history, then either restore the last known backup or approve a separate, database-specific reconciliation plan.

## 4. Schema equivalence preconditions

For the known rewritten state, run these queries before requesting approval:

```sql
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE
  FROM INFORMATION_SCHEMA.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND (
        (TABLE_NAME = 'file' AND COLUMN_NAME IN ('contract_hash', 'transaction_hash'))
     OR (TABLE_NAME = 'account' AND COLUMN_NAME = 'nickname')
     OR (TABLE_NAME = 'integrity_alert' AND COLUMN_NAME IN ('deleted', 'update_time'))
   )
 ORDER BY TABLE_NAME, COLUMN_NAME;

SELECT ROUTINE_NAME, ROUTINE_TYPE, ROUTINE_DEFINITION
  FROM INFORMATION_SCHEMA.ROUTINES
 WHERE ROUTINE_SCHEMA = DATABASE()
   AND ROUTINE_NAME IN ('proc_clean_processed_messages', 'proc_clean_old_operation_logs')
 ORDER BY ROUTINE_NAME;
```

Approval requires all of the following:

- `file.transaction_hash` exists and `file.contract_hash` does not.
- `account.nickname` exists.
- `integrity_alert.deleted` and `integrity_alert.update_time` exist.
- Both cleanup procedures exist and delete only from their expected table using the supplied retention interval.
- Every other applied migration resolves to the same script and checksum in the candidate artifact.
- The full backup and its SHA-256 have been copied to protected storage and a restore command has been reviewed.

If any precondition fails, classify the database as unknown.

## 5. Explicit recovery for the exact known rewritten state

Use a dedicated operator shell with the candidate repository checkout. Keep application instances stopped. Export the database connection values expected by the Maven Flyway plugin without placing the password in command history:

```bash
export DB_URL='jdbc:mysql://<mysql-host>:3306/<database-name>'
export DB_USERNAME='<mysql-user>'
read -rs DB_PASSWORD
export DB_PASSWORD
```

The Maven plugin runs with `backend-web` as its project base directory even when Maven is launched from `platform-backend`, so every filesystem location below is intentionally relative to that module.

First confirm that normal validation fails. A successful validation here means the state was classified incorrectly:

```bash
cd platform-backend
mvn -pl backend-web flyway:validate \
  -Dflyway.locations=filesystem:src/main/resources/db/migration
```

Review the complete validation output. It may identify only the already-classified differences for rewritten `1.0.0`, missing canonical `1.0.1`, rewritten/renumbered `1.5.0`, rewritten `1.7.0`, and removed never-released `1.7.3`. Any additional checksum, description, type, failed, missing, ignored, or unresolved migration makes the database unknown and prohibits both `skipExecutingMigrations` and `repair`.

After the backup, schema-equivalence evidence, and change approval are attached to the change record, register only canonical `V1.0.1` as already executed. Its nickname DDL is already represented by the known rewritten `V1.5.0` row. `target=1.0.1` bounds the operation; `skipExecutingMigrations=true` prevents duplicate-column DDL; validation is disabled for this one command only because the next step repairs the exact reviewed checksum set:

```bash
mvn -pl backend-web flyway:migrate \
  -Dflyway.locations=filesystem:src/main/resources/db/migration \
  -Dflyway.validateOnMigrate=false \
  -Dflyway.outOfOrder=true \
  -Dflyway.skipExecutingMigrations=true \
  -Dflyway.target=1.0.1
```

Immediately compare the complete history with the saved preflight snapshot and require exactly one new successful `1.0.1` row whose script is `V1.0.1__add_account_nickname.sql` and checksum is `529309880`. No other row may change. Then run the explicit repair against the same canonical location:

```bash
mvn -pl backend-web flyway:repair \
  -Dflyway.locations=filesystem:src/main/resources/db/migration
```

For this exact state, repair is expected to align only the reviewed `1.0.0`, `1.5.0`, and `1.7.0` checksums/descriptions/types, mark only the removed, never-released `1.7.3` script as deleted, and remove zero failed rows. Compare the complete before/after history and the repair output with that four-version expectation before continuing. If repair reports or changes any other version, restore the preflight backup and escalate; do not treat a successful command exit as approval. Repair must not be used to bless any checksum outside the two matrices.

Restore normal settings and require validation before applying any later pending migration:

```bash
mvn -pl backend-web flyway:validate \
  -Dflyway.locations=filesystem:src/main/resources/db/migration

mvn -pl backend-web flyway:migrate \
  -Dflyway.locations=filesystem:src/main/resources/db/migration

mvn -pl backend-web flyway:validate \
  -Dflyway.locations=filesystem:src/main/resources/db/migration
```

Re-run the history and schema queries from sections 2 and 4. Start one canary instance only after validation succeeds and the final schema contains `transaction_hash`, `nickname`, the two integrity columns, and both cleanup procedures.

## 6. Normal fresh and v0.0.2 upgrade path

For a fresh database or an exact canonical v0.0.2 database:

1. Back up the database if it already exists.
2. Run `flyway:validate` for an existing database.
3. Run the normal migration with validation enabled and `outOfOrder=false`.
4. Run `flyway:validate` again.
5. Verify the final schema contract from section 4 before starting the application fleet.

`V1.7.4__rename_file_contract_hash_to_transaction_hash.sql` remains a guarded forward migration for older pre-release schemas that still contain `contract_hash`. `V1.7.5__replace_clean_log_procedures.sql` replaces the released procedure definitions without modifying released bytes.

## 7. Rollback and evidence

- Before any recovery command changes history, rollback means restoring the reviewed full backup and redeploying the previously compatible application artifact.
- After a new forward migration executes, never rewrite a released migration or manually reverse Flyway history. Use a reviewed forward correction, or restore the complete pre-change backup when application rollback is not schema-compatible.
- Retain the database backup hash, full before/after Flyway history, schema preflight output, Flyway command logs, candidate commit SHA, manifest SHA-256, approver, and canary result.
- Remove exported credentials from the operator shell after completion: `unset DB_PASSWORD DB_USERNAME DB_URL`.
