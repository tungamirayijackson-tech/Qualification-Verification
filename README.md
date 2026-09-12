# QVS — Qualification Verification System

A register of **signed assertions** about qualifications, with an append-only, hash-chained
audit trail. It does not decide whether a qualification is real. It proves that a named
institution signed a statement, that the statement has not been revoked, and that no one has
edited the history since — each answer attributable to a key, an actor and a moment in time.

> **The brief.** Educational institutions, professional bodies, and employers increasingly
> require reliable mechanisms to verify academic and professional qualifications. Software
> engineers must develop systems that are secure, scalable, maintainable and supported by modern
> DevOps practices.

> MIM736 practical assignment. The section references (§01 … §18) throughout the code and
> config point back to the build manuscript. [`docs/deviations.md`](docs/deviations.md) records
> where the build departs from that design, and why.

---

## Run it

### Everything, in containers

```bash
docker compose up --build
```

| What | Where |
|------|-------|
| Console and API | <http://localhost:8081> |
| OpenAPI console | <http://localhost:8081/swagger-ui.html> |
| Liveness | <http://localhost:9090/actuator/health/liveness> |
| Readiness | <http://localhost:9090/actuator/health/readiness> |
| Metrics | <http://localhost:9090/actuator/prometheus> |
| Prometheus | <http://localhost:9091> |
| Grafana | <http://localhost:3001> — user `qvs`, password from `.env` |

The API and the console are served from the same origin by one container, which is why the
console uses relative URLs and there is no CORS configuration anywhere.

### The first administrator

A freshly built system has an empty user table, and every account in it is created by an
administrator — so on any profile but `dev` there is nobody to sign in as and no way to make
anybody. Two variables in `.env` solve that once:

```bash
QVS_BOOTSTRAP_ADMIN_EMAIL=you@your-university.ac.zw
QVS_BOOTSTRAP_ADMIN_PASSWORD=$(openssl rand -base64 18)   # at least 12 characters
```

On start-up, and **only while the register holds no accounts at all**, that account is created
as an `ADMIN`, the act is written to the audit ledger as the chain's first entry, and the
password is stored as a bcrypt hash and never logged. The condition is "nobody exists" rather
than "no administrator exists" on purpose: it means this can never be used to add an
administrator to a running system. Sign in, enrol the second factor `ADMIN` requires, then
create everybody else from **Accounts** in the console; after that the two variables are dead
weight and can be removed.

There is deliberately no unauthenticated `/setup` endpoint. It would be a second public write
path on a system built around having exactly one, and its guard — "is the table empty?" — is a
race as soon as more than one instance starts.

### Who may do what, and who creates whom

| Role | Creates | May do |
|------|---------|--------|
| `ADMIN` | every account, including other administrators | admits institutions, issues and rotates signing keys, reads the metrics endpoint, and manages accounts over their life — see below |
| `REGISTRAR` | nobody | records what its institution offers, and records, reads, revokes and shares credentials — **for its own institution only**, which is fixed at creation and cannot be changed by the registrar |
| `AUDITOR` | nobody | reads the ledger, credentials and audit trails across every institution; writes nothing, ever |
| `VERIFIER` | nobody | nothing that needs an account — see below |

Accounts are created from **Accounts** in the console, by an administrator. The initial password
is generated rather than chosen and shown exactly once: it is stored only as a bcrypt hash, so
the system genuinely cannot show it again. A role that requires a second factor collects it on
first sign-in, on the new user's own device — the only place a second factor is worth anything.
Every creation is written to the audit ledger against the administrator who did it.

Two rules are enforced in three places (the form, the use case, and a database CHECK): a
registrar must be bound to exactly one institution, and every other role must not be. An auditor
scoped to one institution is not an auditor.

The order of operations is fixed by the register itself: **an institution is admitted first, then
the people who act for it.** Naming an institution that is not in the register is refused with a
sentence saying so, rather than reaching the administrator as a foreign-key violation.

#### What a registrar can reach

Everything a registrar touches is their own institution's and nothing else — the credentials they
search, the one they open, the awards they withdraw, and the share links they publish. The
institution comes from their token, never from the request, so a registrar cannot widen their own
scope by editing one.

Anything outside it answers **404, exactly as an unknown serial does**. That is deliberate rather
than polite: serials are structured — `ZW-PR0142-2026-000001` names the institution's provider
number and counts from one — so a "forbidden" would confirm which serials exist and let anybody
with an account map another university's output.

Two things are *not* a registrar's to do. An administrator admits the institution and issues its
signing keys. An auditor reads across all of them.

#### An account after it is created

An account is never edited in place and never deleted. Four actions are available from
**Accounts**, each a `POST` to its own sub-resource, each writing its own ledger entry naming the
administrator who did it:

| Action | What happens |
|---|---|
| **Suspend** | Sign-in is refused and every refresh token is revoked. The row stays: the ledger names the account in everything it did, and deleting it would leave entries pointing at nobody. Reversible. |
| **Restore** | Sign-in works again, with the password they already had. Deliberately *not* a password reset — that is a separate decision an administrator should have to make. |
| **Reset password** | A new password is generated, shown once, and stored only as a hash. The old one dies immediately and open sessions end. |
| **Reset 2FA** | The stored secret is dropped, not kept, and a new one is enrolled on the next sign-in. This is the answer to a lost phone. |

Two guards on suspending: an administrator cannot suspend themselves, and the last administrator
who can still sign in cannot be suspended at all. Both prevent the same accident — creating an
administrator requires an administrator, so a register with none in it is repairable only by
somebody with database access, which is the position the bootstrap exists to avoid.

> **What suspending does not do, immediately.** Access tokens are short-lived signed JWTs that
> are not checked against the database on each request — that is what keeps authorisation off the
> hot path (NFR-01). So a suspension revokes every refresh token at once, and the access token
> already in the holder's hands stops working when it expires, within fifteen minutes. Making it
> instant would mean a database read on every authorised request, paid always, to close a
> fifteen-minute window on a rare administrative action.

> **`VERIFIER` holds no privileges.** Verifying a credential is a public act — it needs a share
> token, not an account — so the role appears in no authorisation rule and grants nothing beyond
> being signed in. It is kept because the brief names four roles, and it is honest to say that
> the fourth is deliberately powerless rather than to invent work for it. See
> `docs/deviations.md`.

> **Ports.** PostgreSQL is published on `5433`, Redis on `6380` and the API on `8081`, all bound
> to loopback. The defaults collide with services many machines already run, and when they do,
> Docker binds only the IPv6 address — so `localhost` resolves to somebody else's server and the
> failures are baffling. See `docs/deviations.md`.

### Working on it

Three terminals. The API needs its secrets supplied; nothing is defaulted, so a missing one
stops start-up rather than silently falling back to a value a repository could contain.

```bash
# 1. infrastructure
docker compose up -d postgres redis

# 2. API on 8081
export QVS_FIELD_KEY=$(openssl rand -base64 32)
export QVS_NATIONAL_ID_SALT=$(openssl rand -hex 24)
export QVS_IP_SALT=$(openssl rand -hex 24)
export QVS_JWT_SECRET=$(openssl rand -hex 32)
export QVS_DB_URL=jdbc:postgresql://127.0.0.1:5433/qvs
export QVS_VAULT_DIR=./.vault
export PORT=8081 QVS_REDIS_PORT=6380
./gradlew :backend:bootRun --args='--spring.profiles.active=dev'

# 3. console on 4200, proxying the API
cd frontend && npm ci && npm start
```

> **Zimbabwe.** Country codes are `ZW`, addresses are `.ac.zw`, and serials read
> `ZW-PR0142-2026-000001`. Holder identity numbers are Zimbabwean — `63-1234567 K 42`, written
> with hyphens, spaces or neither; the register reduces them to one canonical form before hashing,
> so the same person is the same person however they were typed. The check letter is not
> recomputed, and `docs/deviations.md` says why. Credentials issued before the change keep their
> old serials, because a serial is part of what was signed.

The `dev` profile seeds three institutions, four qualifications, four signed credentials and
one account per role. Sign in as `registrar@example.ac.zw` with
`demo-password-not-for-production`; a registrar must enrol a second factor on first use, and
the console walks you through it.

---

## Verify it

The pipeline is the requirements verification (§11). Everything CI runs, you can run.

| Command | What it gates |
|---------|---------------|
| `./gradlew :backend:check` | Compile, Checkstyle, SpotBugs, unit + ArchUnit tests, JaCoCo ≥ 80%/70%, traceability |
| `./gradlew :backend:integrationTest` | Testcontainers against a real PostgreSQL 16 — migrations, constraints, the append-only trigger |
| `cd frontend && npm run lint && npm run format:check && npm run test:ci` | ESLint, Prettier, Karma |

`integrationTest` needs a running Docker daemon.

---

## How it is put together

One deployable service, five modules whose boundaries **ArchUnit enforces** — cross one and the
build fails in the same stage as a failing unit test. That rule has already earned its keep: it
caught a cycle when the dev seeder was placed in `shared`, and another when a shared exception
handler imported a module's own exception type.

```
backend/src/main/java/za/ac/qvs/
  credential/     register, search, revoke        domain / application / adapter{in,out}
  verification/   signing, verdicts, share tokens
  ledger/         hash chain, audit export
  identity/       users, roles, JWT, TOTP
  shared/         hashing, field encryption, clock, error model
  bootstrap/      composition root; dev seeding only

frontend/src/app/
  core/api/       typed clients and the RFC 9457 problem mapper
  core/auth/      session, bearer interceptor, role guards
  features/       sign-in, search, register, credential detail, public verify, audit
```

Inside each module the dependency points inward: `domain` is plain Java with no Spring, no JPA
and no Jackson, which is what makes the interesting logic — signature verification, chain
recomputation, canonicalisation — unit-testable with no container start.

### What "verified" actually means

A verdict is the conjunction of four independent checks, and the public page shows all four:

1. **Signature** — the detached Ed25519 JWS verifies against the institution's published key.
2. **Issuer standing** — the key and the accreditation were valid *on the award date*, not today.
3. **Revocation** — checked separately, because a revoked credential still has a valid signature.
4. **Ledger presence** — the issuance is in the hash chain and the chain recomputes.

The third is where most implementations go wrong. A revoked credential reports
`signature: PASS` and `revocation: FAIL`, and that is not a contradiction: the institution did
confer the award, and it later withdrew it. Both facts are true and a verifier is shown both.

---

## Requirements and traceability

[`docs/requirements.md`](docs/requirements.md) is the single source of truth, and it is
**parsed by the build**. A test claims coverage by annotating itself:

```java
@Test
@Requirement("FR-06")
void unknownTokenAndUnknownCredentialAreIndistinguishable() { ... }
```

`traceabilityReport` cross-references the two, writes the matrix to
`backend/build/reports/traceability/traceability.md`, posts it as a PR comment, and **fails the
build** when a requirement marked `required` has nothing covering it. Requirements not yet due
are marked `planned` and print a warning — an honest partial beats an implausible green, and
`docs/deviations.md` lists exactly what is and is not built.

## Contributing

Trunk-based (§10). Short branches off `main`, one per issue, squash-merged after review.

```
feat/<issue>-<slug>     fix/<issue>-<slug>     chore/…     docs/…
```

- Conventional Commits; the subject says what, the body says **why**.
- Rebase your branch onto `main`; never merge `main` into your branch.
- `main` is protected: one approving review, all checks green, linear history, no force-push.
- Every PR names the requirement it advances and the test that proves it.
