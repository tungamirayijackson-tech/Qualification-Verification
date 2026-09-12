-- V9__serial_counter.sql
-- Makes serial allocation O(1).
--
-- The first implementation derived the next sequence number by scanning the credential table:
--
--     SELECT COALESCE(MAX(CAST(RIGHT(serial, 6) AS INTEGER)), 0)
--     FROM credential WHERE serial LIKE '%-PR0142-2026-%'
--
-- A leading wildcard means no index can serve that predicate, so every registration
-- sequentially scanned every credential already issued. Registering n credentials therefore
-- cost O(n-squared) row reads, and the FR-02 target of 1000 rows in under 30 seconds was
-- measured at 36 seconds -- with almost all of it here rather than in signing or committing.
--
-- A counter row replaces it. Allocation becomes a single UPSERT that returns the new value,
-- and PostgreSQL's row lock provides the serialisation the advisory lock used to: two
-- registrars at the same institution in the same year wait for each other briefly, and
-- registrars elsewhere never contend at all.

CREATE TABLE serial_counter (
    institution_code TEXT     NOT NULL,
    year             SMALLINT NOT NULL,
    next_value       INTEGER  NOT NULL,

    CONSTRAINT serial_counter_pk PRIMARY KEY (institution_code, year),
    CONSTRAINT serial_counter_positive CHECK (next_value > 0)
);

-- Seed the counter from whatever the register already contains, so serials continue rather
-- than restarting at 1 and colliding with the credentials issued before this migration.
INSERT INTO serial_counter (institution_code, year, next_value)
SELECT split_part(serial, '-', 2),
       CAST(split_part(serial, '-', 3) AS SMALLINT),
       MAX(CAST(split_part(serial, '-', 4) AS INTEGER))
FROM credential
GROUP BY split_part(serial, '-', 2), split_part(serial, '-', 3);

COMMENT ON TABLE serial_counter IS
    'Next serial sequence per institution and year. Replaces an O(n) scan of credential per registration.';
