-- V1__institution.sql
-- Baseline of the register. Institutions come first because every other table in the
-- schema (S07) hangs off one: qualifications are offered by an institution, credentials
-- are signed by its key, and users are scoped to it.

CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE institution (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT        NOT NULL,
    country          VARCHAR(2)  NOT NULL,
    provider_no      TEXT        NOT NULL,
    accredited_until DATE        NOT NULL,
    active_key_id    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT institution_provider_no_unique UNIQUE (provider_no),
    CONSTRAINT institution_country_iso CHECK (country ~ '^[A-Z]{2}$')
);

-- FR-03 searches institutions by name; trigram index rather than a prefix index because
-- verifiers type partial and misspelled institution names.
CREATE INDEX institution_name_trgm ON institution USING GIN (name gin_trgm_ops);

-- VARCHAR(2) rather than CHAR(2): PostgreSQL reports CHAR as bpchar and blank-pads it, which
-- Hibernate's schema validation rejects against a String field. The length limit plus the CHECK
-- below give the same guarantee without the padding surprise.
COMMENT ON TABLE institution IS
    'Awarding bodies. accredited_until is point-in-time: FR-05 checks standing on the award date.';
