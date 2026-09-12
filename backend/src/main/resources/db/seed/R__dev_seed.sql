-- Repeatable dev seed. Loaded only when spring.profiles.active includes 'dev' or 'seed',
-- never in production -- the flyway locations list is what gates it (see application.yml).
-- active_key_id is left null here on purpose. A signing key is not a row you can write in
-- SQL: the private half has to be generated and stored in the vault at the same moment, or
-- the register would reference a key nothing can sign with. DevDataSeeder does that on
-- startup and sets this column afterwards.
INSERT INTO institution (id, name, country, provider_no, accredited_until)
VALUES
    ('11111111-1111-4111-8111-111111111111', 'Example University',          'ZW', 'PR-0142', DATE '2030-12-31'),
    ('22222222-2222-4222-8222-222222222222', 'Harare Institute of Technology', 'ZW', 'PR-0873', DATE '2029-06-30'),
    ('33333333-3333-4333-8333-333333333333', 'Lapsed College',              'ZW', 'PR-0999', DATE '2024-01-31')
ON CONFLICT (id) DO UPDATE SET
    name             = EXCLUDED.name,
    country          = EXCLUDED.country,
    provider_no      = EXCLUDED.provider_no,
    accredited_until = EXCLUDED.accredited_until;

-- Qualifications. Example University offers two; the lapsed college offers one, so the
-- "institution was not accredited on the award date" refusal has something to refuse.
INSERT INTO qualification (id, institution_id, title, nqf_level, credits, saqa_qual_id, status)
VALUES
    ('aaaaaaaa-0001-4111-8111-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111',
     'BSc Computer Science', 7, 360, 'SAQA-62115', 'ACTIVE'),
    ('aaaaaaaa-0002-4111-8111-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111',
     'MSc Information Systems', 9, 180, 'SAQA-62116', 'ACTIVE'),
    ('aaaaaaaa-0003-4111-8111-aaaaaaaaaaaa', '11111111-1111-4111-8111-111111111111',
     'National Diploma Bookkeeping', 5, 240, 'SAQA-48736', 'PHASED_OUT'),
    ('aaaaaaaa-0004-4111-8111-aaaaaaaaaaaa', '22222222-2222-4222-8222-222222222222',
     'BEng Mechanical Engineering', 8, 480, 'SAQA-62201', 'ACTIVE')
ON CONFLICT (id) DO UPDATE SET
    title     = EXCLUDED.title,
    nqf_level = EXCLUDED.nqf_level,
    credits   = EXCLUDED.credits,
    status    = EXCLUDED.status;
