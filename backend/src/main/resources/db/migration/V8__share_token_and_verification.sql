-- V8__share_token_and_verification.sql
-- Consent and its exercise (Decision 09-A).
--
-- There is no anonymous search anywhere in this system. A verifier can only check a credential
-- whose share token the holder gave them. That costs convenience and buys the entire privacy
-- argument: the register cannot be scraped into a dataset of who holds which degree.

CREATE TABLE share_token (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_id UUID        NOT NULL REFERENCES credential (id) ON DELETE CASCADE,
    -- The token is 128 bits of randomness, shown to the holder once and stored only as a
    -- hash. A database disclosure therefore does not yield working share links.
    token_hash    VARCHAR(64) NOT NULL,
    issued_by     UUID        REFERENCES app_user (id),
    issued_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ NOT NULL,
    revoked_at    TIMESTAMPTZ,
    label         TEXT,                            -- "for Acme Ltd", so the holder can revoke one

    CONSTRAINT share_token_hash_unique UNIQUE (token_hash),
    CONSTRAINT share_token_hash_shape  CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT share_token_expiry_sane CHECK (expires_at > issued_at)
);

CREATE INDEX share_token_credential ON share_token (credential_id, issued_at DESC);

-- Every check that was ever made, whether it succeeded or not. This is half of "maintain an
-- auditable history of verification activities": the ledger records that a check happened,
-- and this table records enough about it to answer questions later without keeping anything
-- that identifies the person who asked.
CREATE TABLE verification_request (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    credential_id  UUID        REFERENCES credential (id) ON DELETE SET NULL,
    verifier_id    UUID        REFERENCES app_user (id),    -- null: anonymous, the usual case
    share_token_id UUID        REFERENCES share_token (id) ON DELETE SET NULL,
    channel        VARCHAR(8)  NOT NULL DEFAULT 'WEB',
    -- Hashed with a rotating daily salt: enough to spot one address checking 200 credentials,
    -- not enough to build a history of who looked at what.
    client_ip_hash VARCHAR(64),
    verdict        VARCHAR(16) NOT NULL,
    failed_check   VARCHAR(24),                    -- which conjunct failed, when one did
    requested_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ledger_seq     BIGINT,                         -- citable in a dispute

    CONSTRAINT verification_channel_enum CHECK (channel IN ('WEB', 'API', 'QR')),
    CONSTRAINT verification_verdict_enum CHECK (verdict IN ('VALID', 'REVOKED', 'NOT_FOUND')),
    CONSTRAINT verification_failed_check_enum CHECK (
        failed_check IS NULL OR failed_check IN
            ('SIGNATURE', 'ISSUER_STANDING', 'REVOCATION', 'LEDGER_PRESENCE')
    )
);

CREATE INDEX verification_credential ON verification_request (credential_id, requested_at DESC);
CREATE INDEX verification_requested  ON verification_request (requested_at DESC);
CREATE INDEX verification_ip         ON verification_request (client_ip_hash, requested_at DESC);

COMMENT ON INDEX verification_credential IS
    'Serves the per-credential audit trail export in FR-09.';
