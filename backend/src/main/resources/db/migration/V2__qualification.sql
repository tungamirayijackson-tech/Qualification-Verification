-- V2__qualification.sql
-- What an institution offers. Separated from `credential` because many holders receive the
-- same qualification, and because a qualification can be phased out without invalidating the
-- credentials already awarded under it.

CREATE TABLE qualification (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    institution_id UUID        NOT NULL REFERENCES institution (id),
    title          TEXT        NOT NULL,
    nqf_level      SMALLINT    NOT NULL,
    credits        INTEGER     NOT NULL,
    saqa_qual_id   TEXT,
    status         VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The third of the three validation layers. The NqfLevel record refuses 11 in Java and
    -- Bean Validation refuses it at the edge; this refuses it even to a direct SQL writer.
    CONSTRAINT qualification_nqf_range   CHECK (nqf_level BETWEEN 1 AND 10),
    CONSTRAINT qualification_credits_pos CHECK (credits > 0),
    CONSTRAINT qualification_status_enum CHECK (status IN ('ACTIVE', 'PHASED_OUT')),
    CONSTRAINT qualification_title_unique UNIQUE (institution_id, title)
);

CREATE INDEX qualification_institution ON qualification (institution_id);
CREATE INDEX qualification_nqf         ON qualification (nqf_level);
CREATE INDEX qualification_title_trgm  ON qualification USING GIN (title gin_trgm_ops);
