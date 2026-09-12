-- V5__credential.sql
-- The signed assertion itself. This row is what the whole system exists to make trustworthy.
--
-- Note what is NOT here: no verdict column, no "is_valid" flag. Validity is computed at
-- verification time from four independent checks (S05). A cached boolean would be a fifth
-- source of truth and the first thing to go stale.

CREATE TABLE credential (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    serial              VARCHAR(32) NOT NULL,
    qualification_id    UUID        NOT NULL REFERENCES qualification (id),
    holder_id           UUID        NOT NULL REFERENCES holder (id),
    awarded_on          DATE        NOT NULL,
    kid                 VARCHAR(64) NOT NULL REFERENCES signing_key (kid),
    detached_jws        TEXT        NOT NULL,
    payload_canonical   TEXT        NOT NULL,     -- exact bytes that were signed
    status              VARCHAR(16) NOT NULL DEFAULT 'ISSUED',
    revoked_at          TIMESTAMPTZ,
    revoked_reason      VARCHAR(32),
    revoked_by          UUID,
    document_object_key TEXT,
    issued_at           TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- Human-quotable and unique. Carries security weight: FR-06's not-found path must not be
    -- able to confirm that a serial exists, and a duplicate would make a serial ambiguous in
    -- a dispute. Negative-tested.
    CONSTRAINT credential_serial_unique UNIQUE (serial),
    CONSTRAINT credential_status_enum   CHECK (status IN ('ISSUED', 'REVOKED')),

    -- Revocation is all-or-nothing: a REVOKED row must say when and why, and an ISSUED row
    -- must not pretend to. Half-revoked states are how audit trails start disagreeing with
    -- the register.
    CONSTRAINT credential_revocation_complete CHECK (
        (status = 'ISSUED'  AND revoked_at IS NULL     AND revoked_reason IS NULL)
        OR
        (status = 'REVOKED' AND revoked_at IS NOT NULL AND revoked_reason IS NOT NULL)
    ),
    CONSTRAINT credential_revoked_reason_enum CHECK (
        revoked_reason IS NULL OR revoked_reason IN (
            'ADMINISTRATIVE_ERROR', 'ACADEMIC_MISCONDUCT', 'QUALIFICATION_WITHDRAWN',
            'ISSUED_IN_ERROR', 'HOLDER_REQUEST', 'OTHER'
        )
    )
);

CREATE INDEX credential_holder        ON credential (holder_id);
CREATE INDEX credential_qualification ON credential (qualification_id);
CREATE INDEX credential_awarded_on    ON credential (awarded_on DESC);
CREATE INDEX credential_status        ON credential (status) WHERE status = 'REVOKED';

COMMENT ON COLUMN credential.payload_canonical IS
    'The exact canonical bytes the signature covers. Stored so verification never re-derives them and risks a different serialisation.';
