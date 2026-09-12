-- V3__holder.sql
-- The person. This is the table the public verification path must never read, and the reason
-- NFR-06 exists.
--
-- Identity is minimised deliberately (S07):
--   national_id_hash  a salted SHA-256. The national ID itself is never stored, so a database
--                     disclosure does not hand an attacker a list of ID numbers.
--   display_name_enc  AES-GCM ciphertext. Readable by the console for a registrar acting
--                     inside their own institution; unreadable to anyone reading the table.
--   initials          derived once at write time. The public verification path reads this and
--                     never the encrypted name, so the column it touches has only ever held
--                     "T.N.M." -- there is no code path on the public side that could decrypt
--                     a full name even if someone later wired one in by mistake.
--   search_name       the tension NFR-06 creates with FR-03, made explicit rather than hidden.
--                     Fuzzy search cannot run on ciphertext, so a normalised, lower-cased,
--                     accent-stripped copy exists for trigram matching. It is a real
--                     concession and the report should own it: the mitigation is that every
--                     search is scoped to the caller's institution, so this column is never a
--                     cross-institution directory.

CREATE TABLE holder (
    id               UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    pseudonym_ref    TEXT        NOT NULL,
    national_id_hash VARCHAR(64) NOT NULL,
    display_name_enc TEXT        NOT NULL,
    initials         TEXT        NOT NULL,
    search_name      TEXT        NOT NULL,
    date_of_birth    DATE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT holder_pseudonym_unique  UNIQUE (pseudonym_ref),
    CONSTRAINT holder_id_hash_unique    UNIQUE (national_id_hash),
    CONSTRAINT holder_id_hash_shape     CHECK (national_id_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX holder_search_name_trgm ON holder USING GIN (search_name gin_trgm_ops);

COMMENT ON COLUMN holder.search_name IS
    'Normalised name for FR-03 trigram search. A documented NFR-06 concession; searches are institution-scoped.';
