-- V4__signing_key.sql
-- Public halves of the institution signing keys, with validity intervals.
--
-- Decision 05-A: rotation is designed in from day one. Verification resolves the key that was
-- valid on the *award date*, not the key that is current, so a credential signed in 2026 still
-- verifies in 2029 after two rotations. Retrofitting this would mean re-signing the register.
--
-- Only public keys live here. Private keys live in the vault (S04); in the Compose profile the
-- "vault" is a local keystore, and the report says so plainly rather than implying an HSM.

CREATE TABLE signing_key (
    kid            VARCHAR(64) PRIMARY KEY,
    institution_id UUID        NOT NULL REFERENCES institution (id),
    algorithm      VARCHAR(16) NOT NULL DEFAULT 'Ed25519',
    public_key     TEXT        NOT NULL,          -- base64, X.509 SubjectPublicKeyInfo
    valid_from     DATE        NOT NULL,
    valid_until    DATE,                          -- null: still current
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT signing_key_algorithm_enum CHECK (algorithm IN ('Ed25519')),
    CONSTRAINT signing_key_interval_sane  CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE INDEX signing_key_institution ON signing_key (institution_id, valid_from DESC);

-- An institution may have at most one key without an end date: the current one. Enforced as a
-- partial unique index because a plain UNIQUE would treat every NULL as distinct.
CREATE UNIQUE INDEX signing_key_one_current
    ON signing_key (institution_id)
    WHERE valid_until IS NULL;

ALTER TABLE institution
    ADD CONSTRAINT institution_active_key_fk
    FOREIGN KEY (active_key_id) REFERENCES signing_key (kid)
    DEFERRABLE INITIALLY DEFERRED;
