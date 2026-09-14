# Database schema

The register is a small relational schema on PostgreSQL 16, migrated by Flyway. Migrations are
forward-only and additive; `spring.jpa.hibernate.ddl-auto` is set to `validate`, so any drift
between the entities and the migrated schema fails the build rather than being silently
reconciled.

Run order is the `V<n>__` prefix. Each file is immutable once merged — a change to the schema is
a **new** migration, never an edit to an existing one.

| Version | Table / change | Purpose |
|---------|----------------|---------|
| V1  | `institution` | Awarding bodies: name, country, provider number, accreditation date, active key. |
| V2  | `qualification` | What an institution offers: title, NQF level (1–10, CHECK-constrained), credits, SAQA id. |
| V3  | `holder` | A graduate, minimised: a salted hash of the national ID for search, an encrypted display name — never the plaintext ID. |
| V4  | `signing_key` | Per-institution Ed25519 keys with `valid_from` / `valid_until`, so a rotated key still verifies old awards. |
| V5  | `credential` | A signed award: serial, holder, qualification, award date, detached JWS, status. |
| V6  | `app_user`, `refresh_token` | Accounts and the four-role model; rotating, hashed refresh tokens. |
| V7  | `audit_entry` | The append-only, hash-chained ledger, with a trigger that refuses `UPDATE`/`DELETE`. |
| V8  | `share_token`, `verification_request` | Per-request certificate ids and the record of each public check. |
| V9  | `serial_counter` | Monotonic per-institution, per-year credential serial allocation. |
| V10 | `audit_entry` action | Adds `QUALIFICATION_ADDED` to the ledger's action vocabulary. |
| V11 | `audit_entry` action | Adds `USER_CREATED`. |
| V12 | `audit_entry` actions | Adds the account-lifecycle actions: `USER_DISABLED`, `USER_RESTORED`, `USER_PASSWORD_RESET`, `USER_MFA_RESET`. |

## Design notes

- **Scope is a column, not a convention.** `app_user.institution_id` is `NOT NULL` for a
  registrar and `NULL` for an auditor, enforced by a `CHECK` — a registrar with no institution
  could act for anyone, which the trust model forbids.
- **The ledger is append-only in the database, not just in the application.** `audit_entry`
  carries `prev_hash` and `entry_hash`; a `BEFORE UPDATE OR DELETE` trigger raises an exception,
  so tampering needs superuser access, not application access.
- **Identity is minimised.** The national ID is stored only as a salted hash; the display name is
  encrypted at rest. The public verification path never reads the `holder` table.
- **Keys carry validity intervals.** Verification resolves the `signing_key` that was valid on the
  award date, so rotation never invalidates credentials signed under an earlier key.
