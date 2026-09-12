-- V7__audit_ledger.sql
-- The append-only, hash-chained audit trail (S06, FR-08).
--
-- entry_hash = sha256( seq | occurred_at | actor | role | action | subject | payload_hash | prev_hash )
--
-- Altering entry n invalidates every hash from n onward, so a tamperer must rewrite the whole
-- tail. The chain does not *prevent* tampering -- nothing in a single-writer database can --
-- it makes tampering evident and names the exact row. That distinction belongs in the report
-- and the viva, stated plainly.

CREATE TABLE audit_entry (
    seq          BIGSERIAL   PRIMARY KEY,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    actor_id     UUID,                             -- null for anonymous public checks
    actor_role   VARCHAR(16) NOT NULL,
    action       VARCHAR(32) NOT NULL,
    subject_ref  TEXT        NOT NULL,             -- credential serial, or a query fingerprint
    payload_hash VARCHAR(64) NOT NULL,
    prev_hash    VARCHAR(64) NOT NULL,
    entry_hash   VARCHAR(64) NOT NULL,

    CONSTRAINT audit_entry_hash_unique  UNIQUE (entry_hash),
    CONSTRAINT audit_entry_hash_shape   CHECK (entry_hash   ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_entry_prev_shape   CHECK (prev_hash    ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_entry_payload_shape CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT audit_entry_action_enum CHECK (action IN (
        'CREDENTIAL_ISSUED', 'CREDENTIAL_REVOKED', 'CREDENTIAL_IMPORTED',
        'VERIFICATION_RUN',  'SHARE_TOKEN_ISSUED', 'SHARE_TOKEN_REVOKED',
        'TRAIL_EXPORTED',    'CHAIN_VERIFIED',
        'INSTITUTION_ONBOARDED', 'KEY_ROTATED',
        'USER_LOGGED_IN',    'USER_LOGIN_FAILED', 'REPORT_GENERATED'
    ))
);

CREATE INDEX audit_entry_subject  ON audit_entry (subject_ref, seq DESC);
CREATE INDEX audit_entry_actor    ON audit_entry (actor_id, seq DESC);
CREATE INDEX audit_entry_occurred ON audit_entry (occurred_at DESC);
CREATE INDEX audit_entry_action   ON audit_entry (action, seq DESC);

-- Defence in depth. The application role has no UPDATE or DELETE grant on this table anyway,
-- but a trigger means that even a mistaken migration, an ORM cascade or a careless psql
-- session is refused. Editing history now requires deliberately dropping this trigger as a
-- superuser -- which is itself an act nobody can perform by accident.
CREATE FUNCTION deny_mutation() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_entry is append-only (attempted % on seq %)', TG_OP, OLD.seq
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_entry_immutable
    BEFORE UPDATE OR DELETE ON audit_entry
    FOR EACH ROW EXECUTE FUNCTION deny_mutation();

-- TRUNCATE bypasses row-level triggers entirely, so it needs its own statement-level guard.
-- Without this, "append-only" would have a one-word workaround.
CREATE FUNCTION deny_truncate() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_entry is append-only (TRUNCATE refused)'
        USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_entry_no_truncate
    BEFORE TRUNCATE ON audit_entry
    FOR EACH STATEMENT EXECUTE FUNCTION deny_truncate();

COMMENT ON TABLE audit_entry IS
    'Append-only hash chain. UPDATE, DELETE and TRUNCATE are refused by trigger; VerifyChain recomputes and names the first break.';
