-- V6__app_user.sql
-- Four roles, each with a different power (S01, FR-10).
--
--   REGISTRAR  records and revokes on behalf of exactly one institution
--   VERIFIER   asks; asserts nothing
--   AUDITOR    reads the ledger across institutions; writes nothing at all
--   ADMIN      onboards institutions and keys
--
-- The institution scope is a column, not a convention. A registrar with a null institution is
-- a registrar who could act for anyone, so the constraint forbids it.

CREATE TABLE app_user (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email          TEXT        NOT NULL,
    display_name   TEXT        NOT NULL,
    role           VARCHAR(16) NOT NULL,
    institution_id UUID        REFERENCES institution (id),
    password_hash  TEXT        NOT NULL,
    mfa_secret     TEXT,
    mfa_enrolled   BOOLEAN     NOT NULL DEFAULT false,
    disabled       BOOLEAN     NOT NULL DEFAULT false,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT app_user_email_unique UNIQUE (email),
    CONSTRAINT app_user_role_enum CHECK (role IN ('REGISTRAR', 'VERIFIER', 'AUDITOR', 'ADMIN')),

    -- A registrar acts for one institution and only one. An auditor is deliberately
    -- cross-institution, so it must not be pinned to one.
    CONSTRAINT app_user_scope_matches_role CHECK (
        (role = 'REGISTRAR' AND institution_id IS NOT NULL)
        OR (role = 'AUDITOR' AND institution_id IS NULL)
        OR role IN ('VERIFIER', 'ADMIN')
    ),

    -- S09: MFA is mandatory for the roles that can change the register. Enforced here as well
    -- as in the login flow, so a user inserted by a migration cannot bypass it.
    CONSTRAINT app_user_mfa_required_for_writers CHECK (
        role NOT IN ('REGISTRAR', 'ADMIN') OR mfa_enrolled = false OR mfa_secret IS NOT NULL
    )
);

CREATE INDEX app_user_institution ON app_user (institution_id);

-- Refresh tokens are rotated on use and stored hashed, so a database read does not yield a
-- usable session. A reused token is evidence of theft; the family is revoked on detection.
CREATE TABLE refresh_token (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    token_hash   VARCHAR(64) NOT NULL,
    family_id    UUID        NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL,
    used_at      TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,

    CONSTRAINT refresh_token_hash_unique UNIQUE (token_hash)
);

CREATE INDEX refresh_token_user   ON refresh_token (user_id);
CREATE INDEX refresh_token_family ON refresh_token (family_id);
