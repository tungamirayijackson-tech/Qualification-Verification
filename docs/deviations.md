# Deviations from the build manuscript

The manuscript is the design; this file records where the built system departs from it, and
why. Every entry is a decision somebody can argue with — which is the point. A report that
claims the design was implemented exactly is either lucky or not looking.

---

## Technology

### Bouncy Castle dropped; Ed25519 comes from the JDK

**Manuscript:** "JCA + Bouncy Castle, Nimbus JOSE — Ed25519 signing and JWS serialisation."

**Built:** Nimbus JOSE for the JWS envelope, with Tink as its EdDSA provider. No Bouncy Castle.

**Why:** Ed25519 has been native in the JDK since Java 15. Adding a third-party implementation
of a primitive the platform already provides increases the supply-chain surface without
improving the cryptography. The rule applied throughout is: never write or vendor the
primitive, and use an audited library for the envelope.

### TOTP implemented rather than imported

**Manuscript:** implies a library for MFA.

**Built:** `Totp` in `identity/domain`, roughly 120 lines, verified against RFC 6238's own
published test vectors (`TotpTest`).

**Why:** TOTP is short, completely specified, and comes with official vectors, so the
implementation can be *proved* correct rather than trusted. The primitive underneath — HMAC —
is still the JDK's. This is the same rule as above, applied in the other direction: writing the
envelope is acceptable exactly when it is specified tightly enough to test exhaustively.

### No Angular Material

**Manuscript:** "Angular Material 18 — accessible tables, dialogs and form fields out of the
box."

**Built:** hand-written components against a small set of design tokens in `styles.scss`.

**Why:** the console has five screens and needs tables, forms and status pills. Material would
add a large dependency and a theming layer for that. The accessibility Material provides was
kept as a requirement rather than a freebie: semantic elements, real `<label>` associations, a
skip link, `role="alert"` on failures, visible focus rings, and tables that scroll inside their
own container instead of pushing the page sideways. This is the deviation most worth
challenging in a viva, and the honest answer is that it trades a dependency for control and
puts the burden of accessibility on us.

### OWASP Dependency-Check is not a merge gate

**Manuscript (NFR-04):** "Trivy + OWASP DC."

**Built:** Trivy in filesystem mode gates the PR, and Trivy scans the built image again on
`main`. OWASP Dependency-Check is not wired in.

**Why:** since 2024 it requires an NVD API key and takes several minutes on a cold cache, which
would make every pull request slow and would fail for anyone without the secret configured.
Trivy covers the same dependency CVE ground without either problem. Adding OWASP DC as a
scheduled nightly job is the right next step and is not done.

---

## Behaviour

### An integrity failure reports as `NOT_FOUND`, not as its own verdict

**Manuscript (§05):** "the response says which one failed"; **FR-06:** "returns exactly one of
valid / revoked / not found."

**Built:** those two requirements pull against each other, and the resolution is deliberate. A
credential whose signature, issuer standing or ledger presence check fails returns `NOT_FOUND`
with an empty body — identical to an unknown token. The failing check is recorded on the
verification request, appended to the ledger, and visible to auditors.

**Why:** the alternative is telling an unauthenticated caller *which* of their forgeries came
closest to working. Tampering is not hidden; it is reported to the people whose job it is to
act on it rather than to the person who may have caused it. Named here because a reader
comparing §05 to the code will notice, and should find the reasoning rather than an oversight.

### MFA enrolment is a result, not an exception

The first implementation threw `MfaEnrolmentRequired` after saving the freshly generated
secret. The throw rolled back the request's transaction, so the server handed the user a secret
it had immediately forgotten and every enrolment attempt failed. It is now a
`SignInResult.EnrolmentRequired` returned normally. The general lesson is worth keeping:
expected outcomes belong in the return type, and using exceptions for control flow interacts
badly with transactions.

### The dev seeder marks demo accounts MFA-enrolled without a secret

`DevDataSeeder` creates accounts that can sign in with a password alone, so an assessor is not
required to configure an authenticator app. The production enrolment path cannot reach that
state — it always writes a secret before setting the flag — and the seeder is confined to the
`dev` profile.

---

## Environment

### Ports moved off 5432, 6379 and 8080

Compose publishes PostgreSQL on `127.0.0.1:5433`, Redis on `6380` and the API on `8081`.

**Why:** this was found the hard way. The development machine already ran PostgreSQL on
`0.0.0.0:5432` and Apache on `0.0.0.0:8080`. Docker could still bind the *IPv6* address, so
`docker compose up` succeeded and `localhost` then resolved to whichever address family the
client tried first — meaning the application silently connected to a different database and
reported an authentication failure that looked like a wrong password. Binding to `127.0.0.1`
also keeps a seeded development database off the local network.

The Angular dev server has the same characteristic in reverse: it binds IPv6 only, so it
answers on `localhost` and `[::1]` but not `127.0.0.1`.

### Testcontainers needs an explicit Docker API version

Docker Engine 29 refuses API versions below 1.44, while the docker-java client inside
Testcontainers still negotiates an older one and receives a bare HTTP 400 — which surfaces as
the thoroughly misleading "Could not find a valid Docker environment". The `integrationTest`
task pins `api.version` to 1.44, satisfied by every engine from 25.0 onwards.

---

## Not built

Named plainly, because a traceability matrix that quietly omits them would be worse than one
that shows them as planned.

| Requirement | State |
|-------------|-------|
| FR-02 performance clause | **Not met.** The functional half of FR-02 is built and tested; the 30-second budget is not achieved on the development machine. Measured and explained below. |
| NFR-07 "2 replicas" | **Not demonstrated.** The probes are built and tested; the replica count is deployment topology and the Compose demo runs one container. Explained below. |
| Bonus: Terraform, anomaly agent | Not built. |

Everything else in the register is built and has a test that owns it. FR-12 and NFR-01 were in
this table until they were done; the entries above are what is left.

### NFR-06 is a rule about the public surface, not about the database

Worth stating precisely, because an earlier code comment of mine overstated it and a test
caught the contradiction.

The public verification response discloses initials and nothing more — no name, no national ID,
no date of birth, no internal identifier, and no signed payload. That is asserted against the
raw response bytes in `PublicDisclosureIT`, deliberately: checking a DTO's fields would prove
the DTO is shaped right and nothing about what Jackson actually serialised.

The **authenticated console** is different, and necessarily so. FR-04 publishes the detached
signature and the exact canonical bytes it covers, so that an auditor can verify the signature
with their own tooling rather than taking this system's word for it. Those bytes include the
holder's name, because the name is part of what the institution signed. You cannot hand
somebody a detached signature and withhold what it is over.

The boundary is therefore between surfaces, not between storage and memory: a stranger with a
share token learns initials; a registrar scoped to that institution, or an auditor, sees a
record they are already entitled to see. An earlier comment in `CredentialDetail` claimed the
full name never comes back out of storage. That was wrong, and the test now pins the true
behaviour down so nobody later "fixes" the apparent leak by stripping the payload and quietly
making independent verification impossible.

### FR-02: the 30-second budget is not met, and here is the number

The functional half of FR-02 works and is tested: a malformed row fails that row only, and is
reported by its line number. The performance half is **not** met, and the honest figure is
**51.6 seconds for 1 000 rows — 51.6 ms per row** on the development machine.

Two real optimisations were made after measuring, and both are worth keeping:

1. **Serial allocation was O(n) per row.** The next sequence number was derived with
   `MAX(...) FROM credential WHERE serial LIKE '%-CODE-YEAR-%'`. A leading wildcard means no
   index can serve it, so every registration scanned every credential already issued — making a
   cohort O(n²). Replaced with a `serial_counter` row and a single atomic UPSERT (migration V9).
   This took the import from 35.6 s to 30.3 s and, more importantly, made it linear.
2. **JPA issued a SELECT before every INSERT**, because entity ids are assigned rather than
   generated, so `save` had to ask whether the row already existed. On the registration path
   that lookup is a guaranteed miss. The ports now distinguish `insert` from `save`.

Chunking the import into 100-row transactions was also tried. It cut commits by a hundredfold
and moved the total by **0.6 seconds** — which is the useful finding: commits were never the
bottleneck. It was kept anyway, because it is strictly better and it is what makes the
row-isolation guarantee affordable.

**What actually dominates is round-trip latency.** A registration issues roughly ten statements
— counter, holder lookup, holder insert, credential insert, four for the ledger append, plus
qualification and key resolution — and this build runs against PostgreSQL in Docker Desktop on
Windows, where a round trip costs milliseconds rather than microseconds. Ten statements at
~5 ms is ~50 ms, which is exactly what is measured.

The route to meeting the budget is therefore fewer statements per row, not faster ones: hoist
the institution, qualification and signing-key resolution out of the per-row loop for imports
(they are identical for every row of a cohort), and batch the holder and credential inserts
through JDBC rather than one at a time. That is a real piece of work and it has not been done.

**The test does not assert 30 seconds.** It asserts two minutes, and prints the measured figure.
That is deliberate and is not the requirement: the integration suite shares one PostgreSQL
container with tests that generate hundreds of requests, and the same import measured 30 s in
isolation and 51 s under that contention. A threshold that passes or fails depending on which
other tests are running measures the suite rather than the code. The two-minute ceiling catches
the failure that actually happened — an O(n²) regression — while the real budget belongs in a
dedicated performance stage alongside NFR-01, which is also not built.

### FR-11 rate limiting: what happens when Redis is down

The limit is a Redis-backed token bucket, so it is one limit across every replica rather than
one per instance. The interesting decision is the failure mode, and there were three candidates:

- **Fail closed** — refuse every verification. A cache outage becomes an outage of the service's
  entire purpose: an employer cannot check a qualification because a cache is down.
- **Fail open** — apply no limit. Anyone who can knock Redis over has also removed the
  protection, which makes the limiter worth least exactly when it matters most.
- **Degrade locally** — what is built. An in-memory bucket per instance takes over, so the
  effective allowance loosens by a factor of the replica count but never disappears. The
  fallback is logged, marked on the decision, and retried every thirty seconds.

Worth stating plainly in the viva: during a Redis outage with two replicas the limit is
effectively 120 a minute rather than 60. That is a real weakening, and it is the price of not
turning a cache failure into a verification outage.

The console is deliberately **not** throttled. Those callers are authenticated, attributable
and already constrained by role; throttling a registrar working through a graduation list would
cost usability for no security gain.

### FR-03 search: one deliberate shape worth defending

Search is built. Two choices in it are arguable and so are recorded here.

**Lookup by national ID is a POST, not a GET.** Every other search criterion is a query
parameter; the identifier is not. Query strings are copied into proxy logs, browser history,
bookmarks and referrer headers, none of which this system controls and all of which outlive the
request. It is a separate endpoint rather than an extra parameter so that it cannot quietly
drift back into the GET later.

**A registrar naming another institution is silently re-scoped rather than refused.** Rejecting
the request would confirm that the other institution exists, and there is nothing useful the
caller could do with either answer. The scope is overwritten in `SearchCredentials` before the
query reaches SQL, so no request a client can construct widens it.

FR-04 **is** built and verified end to end, but its only proof is an out-of-band script rather
than a JUnit test, so `docs/requirements.md` still marks it `planned`. That is the honest
reading of the traceability gate: the gate counts tests, and a requirement without one is not
covered no matter how confident anybody is that it works.

### The coverage gate was measuring nothing

Worth recording because it is the failure mode quality gates are prone to. The JaCoCo rule read

```kotlin
element = "BUNDLE"
includes = listOf("za.ac.qvs.*")
```

For a `BUNDLE` rule those patterns match the *bundle name* — which is `backend` — not package
names. Nothing matched, so the rule applied to no bundle and reported success while measuring
nothing. Real coverage at the point it was found was 45% line and 38% branch, against a stated
threshold of 80/70.

Removing the `includes` (scoping is done by filtering `classDirectories`, which does work) made
the gate real and immediately red. It is now green at **85.7% line, 77.1% branch** because the
missing tests were written, not because the threshold moved. A gate that passes vacuously is
worse than no gate: it produces a green tick nobody earned, and every report that cites it is
citing nothing.

### The frontend suite needs a browser with no extensions in it

The Angular unit tests failed roughly two runs in three, always with the same message and never
with a failing spec:

```
Chrome Headless 152.0.0.0 (Windows 10) ERROR
  Some of your tests did a full page reload!
```

No test failed, nothing appeared in the browser console, and the exit code was 1. It looked
like flaky application code, and it was not. Karma's own debug log showed the cause once the
browser was attached by hand with Chrome's logging turned on: an automation extension,
force-installed by enterprise policy (`HKLM\SOFTWARE\Policies\Google\Chrome\
ExtensionInstallForcelist`), loads into every Chrome profile — including the throwaway one
Karma creates for a headless run. It starts a service worker and opens native messaging ports
while the page is loading, the Karma websocket drops with
`Client disconnected from CONNECTED state (transport error)`, the browser reconnects, and Karma
reports the reconnection as a page reload.

The fix is `--disable-extensions` (plus the related component-extension and background
networking flags) on the launcher in `karma.conf.js`, and using that one launcher locally as
well as in CI rather than the two different browsers the project had before. Five consecutive
runs pass where none had before.

Worth writing down for two reasons. The flag list otherwise reads as cargo, and someone would
eventually trim it. And the diagnosis cost a long time precisely because every instinct said
the fault was in the code under test: it was reproduced with the application shell stubbed out,
with the stylesheet reduced to bare tokens, with a pure-logic spec running alone, and with a
pristine Karma config. When a test harness reports a failure that names no test, the harness is
the first thing to doubt.

Two smaller things were corrected alongside it. `karma-jasmine@5.1` pins `jasmine-core ^4.1`,
which npm cannot reconcile with the `~5.2` this project declares, so it installed a second copy
— and the page loaded the 4.6.1 engine together with the 5.2.0 HTML reporter's boot files. An
npm `overrides` entry collapses them onto one version. And `backdrop-filter: blur()` on the
masthead reliably crashed the headless renderer, so the bar is opaque with a soft shadow
instead; a decorative effect is not worth a browser crash.

### FR-12: whose signature is on a verification report

The requirement asks for a *signed* report, and the first question is whose signature. It cannot
be the institution's. A credential carries the institution's signature, which asserts that it
conferred an award; a report asserts only that this system ran a check and got an answer.
Signing the second with the first would put words in a university's mouth, and a document that
looked as though a university had vouched for it would be worse than no document.

So reports are signed by QVS, with an Ed25519 key that belongs to no institution and has no row
in `signing_key` — that table is a register of awarding bodies, and a row there would make this
system appear in the list of institutions permitted to confer qualifications. The private half
lives in the same vault as the institution keys, because that directory is the thing that gets a
restricted mount and a backup policy.

The public half is served from `GET /public/v1/verify/report-key`. Publishing it is not
optional: a signature nobody can check is decoration, and the person most in need of checking a
report is the one who does not take this system's word for anything. Requiring them to hold an
account here would defeat the purpose.

**The signed bytes are in the PDF's document properties, not on the page.** PDF's standard fonts
encode Latin-1 only, so a name or institution containing anything outside it has to be
transliterated to be drawn at all — and a verifier who recomputed the signature over what they
could read off the page would then get a mismatch and conclude the report was forged. The exact
canonical statement goes in the info dictionary, where PDF strings are UTF-16, and the page is
free to be merely legible. Embedding a Unicode font would remove the transliteration but not the
argument: what is displayed is a rendering, and a signature should never be checked against a
rendering.

Two smaller consequences worth stating. A report is produced by **running a verification**, not
by formatting an earlier one — otherwise it would carry today's date over a verdict from last
week, and would keep saying "valid" about a credential revoked an hour ago. Downloading one is
therefore itself an audited event, which is intended. And the report route has its own, tighter
rate-limit bucket (10/minute against the JSON check's 60), because a report costs a render and a
signature where a check costs a query.

### The vault volume was not writable by the container's user

Found by FR-12 and worth recording because it had nothing to do with FR-12. The runtime image is
distroless and runs as `nonroot`, but `/vault` was never created in it, so Docker initialised the
named volume with a root-owned mount point and the application could not write to it:

```
Caused by: java.nio.file.AccessDeniedException: /vault/qvs-report-1.jwk.enc
```

The report key is generated eagerly at startup, which turned this into a refusal to boot rather
than a failure on the first verifier who asked for a report — the reason for generating it
eagerly in the first place. But the same wall was there for **institution key rotation**, which
would have failed the first time anyone onboarded an institution or rotated a key in a
container. Nothing had exercised that path in Compose, so it sat undiscovered.

Docker initialises a fresh named volume from whatever the image has at that path, ownership
included, so the fix is to ship the directory with the right owner. The runtime stage has no
shell, so it is created in the builder stage and copied across with `--chown=65532:65532`.

### NFR-01 is met, and the number came from a load stage rather than a guess

`perf/verify-smoke.js` drives 50 virtual users against the public verification endpoint on the
Compose stack: **p95 111 ms against a 400 ms target**, 2 983 requests, no failures and no
refusals. The full figures and the method are in `perf/README.md`.

Two things about how it is set up are decisions rather than details. Each virtual user presents
its own `X-Forwarded-For`, because the endpoint allows 60 requests a minute per address (FR-11)
and fifty users sharing one address would have spent the run being refused — producing an
excellent p95 that measured the cost of returning 429. And **any 429 at all fails the run**, so
a future change to the pacing cannot quietly turn the measurement into that.

`PublicVerificationLatencyIT` runs a smaller unpaced burst inside the ordinary build. Its
threshold is derived from measurement: three consecutive runs produced p95 figures of 1101, 1951
and 1586 ms — a twofold spread for identical code, which is what twenty unpaced clients against
a Testcontainers PostgreSQL sharing a JVM with the server looks like. The first ceiling written
was 1500 ms and would have failed two of those three runs. It is now 5 000 ms, and the class
comment says plainly what that buys: it catches a regression that *serialises* the verify path,
and it will not catch an extra query costing thirty milliseconds. Nothing gated by a wall clock
on shared infrastructure could.

### NFR-07: the probes are met, the replica count is not

Liveness and readiness are implemented, reachable without credentials — a kubelet holds no
token — and tested by `ProbesIT`, which also asserts that they disclose nothing but a status and
that the rest of the actuator tree stays shut.

**The "2 replicas" half of the criterion is not demonstrated.** The Compose demo runs a single
API container bound to a fixed host port, and running two would need either a port range and a
reverse proxy in front, or a real orchestrator. Both are deployment topology rather than
application behaviour, and neither is in the submitted stack. The application itself is
stateless and holds no session — sessions are `STATELESS` and every request carries its own
bearer token — so nothing in it prevents a second replica; that is a claim about the design, not
a demonstration, and it is written here as the former.

#### A leak found while testing the probes

The authorisation rule was `/actuator/health/**`, which reads as "the probes" and also matches
the aggregate `/actuator/health`. Under the `dev` profile — the one `docker compose up` runs —
that endpoint had `show-details: always`, so the demo stack answered any anonymous caller with:

```json
{"status":"UP","components":{"db":{"details":{"database":"PostgreSQL"}},
 "diskSpace":{"details":{"total":1081101176832,"free":1009817587712,"path":"/home/nonroot/."}},
 "redis":{"details":{"version":"7.4.9"}}}}
```

A published inventory of what to attack and which versions to look up. Nothing caught it because
the matcher and the comment above it agreed with each other, and both were about a different
endpoint from the one the pattern actually covered. The probes are now permitted by name, and
the dev profile shows details only `when-authorized`.

### Bonus: monitoring, and the two things it exposed

Prometheus and Grafana run alongside the stack, with the datasource, the dashboard and the alert
rules all provisioned from files in `ops/` rather than clicked together in a running container —
a dashboard configured by hand lives only in that container's database and is gone the moment it
is recreated.

The application emits `qvs_verifications_total{verdict,failed_check}`,
`qvs_verification_reports_total`, `qvs_rate_limit_refusals_total{policy}` and
`qvs_rate_limit_degraded_total`, all registered at startup so a panel reads `0` rather than
"No data" before the first verification of the day. The distinction matters: a panel that cannot
tell "nothing happened" from "this panel has never worked" gets ignored.

The `failed_check` dimension is the interesting one. A verification that fails its signature,
issuer-standing or ledger check reports `NOT_FOUND` to the caller, so an attacker learns nothing
about how close a forgery came — and increments a counter here, where the people whose job it is
to act on tampering can see it. The information is not suppressed, it is routed away from the
person who may have caused it. `CredentialIntegrityFailure` in `ops/prometheus/alerts.yml` fires
on it.

**`MetricsIT` reads the dashboard JSON, extracts the metric names from its queries, and requires
every one to be present in the scrape this application actually produces.** That is not
ceremony. A Grafana panel referring to a metric nobody emits does not error; it draws an empty
chart, indistinguishable from a chart of something that has not happened, and an operator reads a
flat line meaning "no integrity failures" when it means "this panel never worked". Monitoring
that lies in the reassuring direction is worse than none.

It caught two things immediately.

**Micrometer emits no histogram buckets by default.** `http_server_requests_seconds` is a count,
a sum and a max — and nothing else — so every `histogram_quantile()` over `_bucket` returns
empty. The NFR-01 latency panel and the alert watching it would both have drawn nothing and been
read as good news. Fixed by enabling `percentiles-histogram` for that meter with explicit
boundaries around the 400 ms target.

**The metrics endpoint had never existed.** `micrometer-registry-prometheus` was not on the
classpath, so `/actuator/prometheus` was listed in the exposure config, permitted in the security
rules for ADMIN, and answered 404 to the one role allowed to ask for it. An earlier version of
this document called it "the groundwork", which it was not.

#### Where the actuator lives, and why

Prometheus holds no bearer token and cannot obtain one — this system issues short-lived tokens to
people, not long-lived ones to scrapers. The alternatives were to serve metrics on the
application's own public port or to give the actuator a port of its own. Metrics disclose request
rates, error counts, JVM internals and the shape of a deployment, so it got its own port: 9090,
bound to loopback and the Compose network, with `/actuator/**` no longer served on 8080 at all.

The authorisation rule that permits it is conditional on the two ports actually differing. When
they are the same — the default, and what every test and local run uses — no such rule is added
and `/actuator/prometheus` stays behind ADMIN. The permit cannot leak by someone unsetting a
variable.

One mistake worth recording, because the wrong version looked correct. The management port was
first defaulted in `application.yml` to `${PORT:8080}`, on the reasoning that this was the same
as leaving it unset. It is not: under `@SpringBootTest(RANDOM_PORT)` the application binds a port
that is not known when the property is resolved, so the actuator went to 8080 while the
application listened elsewhere, and every probe test 404'd. The property is now not declared at
all — unset genuinely means "the application's port" — and Compose sets
`MANAGEMENT_SERVER_PORT` in the environment instead.

### Bonus: the anomaly agent reports, and does not act

Three detectors, chosen because each one is defensible: a run of unknown tokens from one address
(enumeration), one address checking many different credentials (harvesting), and any credential
failing an integrity check (tampering). A fourth would have been easy and none of the candidates
survived the question "what ordinary behaviour does this fire on?".

**It reports and never acts.** No address is blocked, no credential withdrawn, no account
locked. That is a limit rather than an unfinished feature. Every pattern here has an innocent
explanation that is more likely than the guilty one — an employer checking a morning's shortlist
is indistinguishable from somebody working through stolen tokens, and a university that emailed
four hundred graduates a broken link produces a wall of unknown-token checks. A system acting on
those would deny service to the people it exists to serve, automatically, at scale. The one
finding that is not a judgement call, tampering, is also the one where acting automatically
would be worst: the right response is a person reading the audit ledger, not a machine deciding
whose qualification to distrust.

Two details worth stating. Subjects are the **salted hash** of a client address, never the
address — enough to say two hundred checks came from one place, not enough to say where; an
anomaly report that deanonymised the people it described would be a worse privacy problem than
the one it was written to catch. And findings are computed on demand rather than stored: they
are a view of the verification log, and a second table of conclusions drawn from it would be a
copy that could disagree with its source.

The detection rules are plain functions over counts, in the domain, with the thresholds passed
in — which is what lets the interesting question be settled in a unit test with a handful of
numbers. Several of those tests exist to pin down what the agent must *not* report: two mistyped
links, one employer checking three candidates, and twelve graduates each fumbling the same
broken link. The last is the reason the rule counts per address rather than in total.

Thresholds are configuration, not constants, and the values shipped are set for a demo. A real
deployment would choose them after watching a fortnight of ordinary traffic, which is the only
honest way to pick them.

### Bonus: Terraform, and two descriptions of one system

`infra/terraform/` describes the deployment with the Docker provider rather than a cloud one.
Cloud HCL would look more impressive and could not be run by anybody marking it — no account, no
credentials, no way to tell working code from code that merely parses. This can be planned and
applied on the same machine as everything else, so it is checked: `fmt -check` and `validate`
both pass, run from the Terraform image so nothing needs installing.

The trade-off is stated in the directory's README rather than hidden: this describes a
single-host deployment and does no load balancing, managed backups or multi-zone anything.

Compose and Terraform now both describe the same system, which is a real cost. Compose exists so
the demo is one command; Terraform exists so the deployment is a reviewable artefact with
explicit state and a plan you can read first. `InfrastructureParityTest` runs in the ordinary
build and fails when they disagree about images, published ports, loopback binding or which
secrets reach the container — the parts where drift silently produces a wrong deployment. It
deliberately allows the differences that are considered: Compose publishes PostgreSQL and Redis
on the host for a developer's client, and the Terraform does not, because a deployment has no
reason to put a database on a host interface.

That test also caught its own first version. The assertion "no secret variable has a default"
was written as a substring check and failed on a description reading "Set rather than
defaulted" — prose that said exactly the right thing. It now matches the attribute rather than
the word.

### The console shipped with its stylesheet disabled, and a screenshot is what found it

The production build served the console's entire global stylesheet as `media="print"`. It applied
when somebody printed the page and at no other time. Cards had no background, tables no borders,
badges no fill — in the container, which is the thing that gets deployed and marked.

Angular's build inlines what it judges to be critical CSS and defers the rest:

```html
<link rel="stylesheet" href="styles.css" media="print" onload="this.media='all'">
```

That `onload` is an inline event handler. The policy this application sets is `script-src 'self'`
with no `'unsafe-inline'` — deliberately, and correctly — so the browser refuses to run it,
`media` stays `print`, and the stylesheet never applies.

Everything about the failure was quiet. The build succeeded. No test failed. The development
server does not run the inliner at all, so the console looked right on every developer's machine.
The container served a page whose HTML, data and behaviour were all correct and whose appearance
was not. It was found by taking a screenshot of the running container and looking at it, which
is not something any of the existing checks did.

The fix is `inlineCritical: false` in the production build. That costs a little first-paint
performance and buys a stylesheet that loads, which is not a close trade.

`ConsoleStylesheetTest` now asserts the outcome rather than the setting: the built `index.html`
must link a stylesheet, must contain no `onload=`, and must not leave one at `media="print"`.
Asserting the tag rather than the build flag means a future Angular version reintroducing the
pattern under a different option still fails.

The general lesson is the one worth keeping. Every check in this project reads text — HTTP
status codes, JSON bodies, PDF metadata, scrape output, SQL results. None of them can see a
page. A rendering bug is invisible to all of them, and this one had been shipping for as long as
the production build and the content security policy had both existed.

### The console follows the supplied design

`frontend/src/uidesign.png` sets the visual language and the console now follows it: a blue
accent on a near-white page, white cards with a soft shadow, filled status badges, and a deep
navy sidebar whose active item is a solid blue block.

One change is worth singling out because it is not only cosmetic. The palette used to use a
single accent colour for both "you can do this" and "this checked out", so a button and a verdict
were the same green. They are now separate tokens — `--accent` blue for anything interactive,
`--good` green for anything that verified — because on the one screen a stranger uses, a button
that looks like a verdict is a genuinely bad idea.

Two things the design shows that are **not** built, rather than quietly approximated: the
marketing landing page with its hero, and the screens behind it that this system has no features
for — a holder's "My qualifications", an issuer dashboard, reports and settings. Building
screens for functionality that does not exist would misrepresent what the system does.

The design is set in Inter. The content security policy blocks remote fonts, and self-hosting a
variable font to gain a slightly different "g" is weight on every visit; the system stack renders
as Segoe UI on Windows and San Francisco on macOS, both close relatives.

### The administrator role had no functionality, and the tests could not see it

The authorisation matrix carried rules for `POST /api/v1/institutions`,
`POST /api/v1/institutions/{id}/keys` and `POST /api/v1/qualifications`. None of the three
existed. Signing in as an administrator gave a console with two read-only screens and nothing
the role could do that another role could not.

Worth being precise about why nothing caught it. The role-matrix tests assert that an endpoint is
**refused to the wrong roles** — and a 403 for a registrar is exactly what a missing route
returns once `anyRequest().authenticated()` has run, because authorisation is evaluated before
dispatch. A test suite built around "who is refused" cannot distinguish a well-protected
endpoint from an endpoint that was never written. It took signing in as each seeded account and
probing what each could actually reach.

All three are now built, with the acts audited (`INSTITUTION_ONBOARDED`, `KEY_ROTATED`, and
`QUALIFICATION_ADDED`, added by `V10`). Two design decisions in them:

**Onboarding does not issue a key.** An institution is admitted with none and cannot issue
anything until a key is issued separately. The half-finished state is the correct one — a
mistyped provider number would otherwise leave behind a key that cannot be deleted, because the
vault refuses to overwrite private material.

**Adding a qualification is open to registrars too, and scoped.** A registrar may add only to
their own institution; the institution comes from their token and any value in the body is
ignored, which is the rule search already follows. This is also a usability fix: registering an
award needs a qualification's identity, and the console previously asked a registrar to type a
UUID they had no way of looking up.

#### Three bugs found by driving it as a real administrator

**Every domain refusal was a 500.** `CredentialExceptionHandler` turns a `RegistrationRejected`
into a 422 explaining what was wrong. It and the shared `ApiExceptionHandler` both sat at
`LOWEST_PRECEDENCE` — this one by omission — and that is a tie, not an ordering. It resolved in
favour of the catch-all, so a duplicate provider number reached the administrator as "the request
could not be completed" with a stack trace in the operator's log. When the catch-all was ordered
last in earlier work, only one end of the tie was ordered; the fix is `@Order` on both.

This is worth flagging honestly: the integration test written for it passes with and without the
annotation, because advice ordering resolves differently in a test context than in the container.
It documents the intent and did not prove the fix. The container did, and that is where the
before-and-after was observed.

**Rotating a key twice in one day was a constraint violation.** Rotation closes the outgoing
key's window the day before the new one opens, so a replacement starting on the current key's own
first day gives it a `valid_until` before its `valid_from`. The database refused it — correctly —
and the refusal surfaced as a 500 with a constraint name in the log. It is now refused in words,
naming the earliest date that would work.

**A hand-written stub found nothing because it did not exist.** Adding `save` to
`InstitutionRepository` broke two test stubs that implement the port by hand. That is the cost of
having no mocking framework and it is the right cost: the compiler asked what those two tests
should do about a method that can now change the register, and one of them answers by refusing to
implement it.

---

## Accounts

### The first administrator comes from configuration, not from a setup page

**Manuscript:** four roles, and an administrator who admits institutions and hands out
accounts. It does not say where the first administrator comes from.

**Built:** two environment variables, read once at start-up and only while the user table is
completely empty. `BootstrapAdmin` creates the account, writes the act to the audit ledger as
the chain's first entry, and logs that it happened without ever logging the password.

**Why:** the gap was real and total. Until this existed, the only code that could create an
account was the `dev` seeder, so a deployment on any other profile came up with an empty
register, no way to sign in, and therefore no way to create the account that would have let
somebody sign in. NFR-05 asks the system to be rebuildable from nothing; it was not.

The obvious alternative — an unauthenticated `POST /setup` that works while the register is
empty — was rejected. It adds a second public write path to a system whose whole shape is built
around having exactly one, and its guard is a race: two instances starting together both find
the table empty. Configuration has neither problem, and the operator who can set an environment
variable already owns the deployment.

The condition is deliberately "no accounts exist at all", not "no administrator exists". The
narrower check would let this add an administrator to a system that already had users, which is
a standing back door rather than a bootstrap. `BootstrapAdminIT` tests that specifically, by
seeding a single auditor and asserting nothing is created.

The account arrives **unenrolled**, and `ADMIN` requires a second factor. Pre-loading one would
mean the operator's environment file held the second factor, which is not a second factor; the
enrolment flow collects it on first sign-in instead.

### `VERIFIER` is a role that grants nothing, and is kept anyway

**Manuscript:** four roles — registrar, verifier, auditor, admin.

**Built:** `VERIFIER` exists in the enum, can be assigned to an account, and appears in no
authorisation rule anywhere in `SecurityConfiguration`.

**Why:** verification is public by design (FR-06). It is reached with a share token and no
account at all, which is the property that makes it useful to an employer who will never be
issued credentials of their own. Once that is true, there is nothing left for a signed-in
verifier to be allowed to do that an anonymous caller cannot already do.

Two honest options followed: delete the role, or keep it and say plainly that it is powerless.
Deleting it would depart from a brief that names four roles. Inventing a privilege for it —
a rate-limit exemption, say, or a verification history — would be building a feature to justify
a word. So it is kept, assignable, and documented as conferring nothing beyond being signed in.
A reviewer looking for `VERIFIER` in the role matrix and not finding it is looking at a
deliberate absence, not an omission.

### An account has a life after creation, and none of it is an UPDATE

**Manuscript:** roles and accounts; it says nothing about what happens to an account afterwards.

**Built:** four actions — suspend, restore, reset password, reset second factor — each a POST to
its own sub-resource, each writing a ledger entry. No PUT, no PATCH, no DELETE anywhere in the
system, for anybody.

**Why:** the absence was not a design position, it was a hole, and it had three consequences
worth naming. `UserAccount.disabled` was checked at every sign-in and set by nothing, so a
registrar who left the university kept signing in and kept being able to sign credentials on its
behalf. An initial password lost before it was used left a dead account and no way back, because
the password is shown once by design. And a lost second factor was a permanent lockout — which
happened during development and had to be repaired with SQL against the container, a repair not
available to an administrator using the system as built.

The shape is deliberate. A PATCH of `{"disabled": true}` would be shorter and would say nothing:
what is being recorded is not that a field changed but that somebody withdrew somebody else's
access, and the ledger entry is the point. So the request names an action, and the action is the
thing audited. Nothing is deleted for the same reason the ledger is append-only: the account is
named in every entry it took part in.

**The guards are the interesting part.** An administrator cannot suspend their own account, and
the last administrator who can still sign in cannot be suspended by anyone. Both prevent the same
accident — an empty administrator register, which nothing inside the system can repair, because
creating an administrator requires an administrator. That state was reachable in one click before
these existed.

**What is honestly not instant.** Suspending revokes every refresh token, so no new tokens can be
minted; the access token already issued keeps working until it expires, at most fifteen minutes
later. Access tokens are signed JWTs checked without a database read, which is what keeps
authorisation off the hot path for NFR-01. Closing that window would mean a lookup on every
authorised request — a cost paid on every request, always, against a rare administrative action.
The integration test for the last-administrator guard uses that window rather than working around
it, so the behaviour is pinned down rather than merely described here.

#### An auditor could not open the qualifications screen at all

The screen asked `isAdmin` when the question it meant was "is this account bound to one
institution?". Those are the same for a registrar and an administrator, and different for an
auditor — who is cross-institution by design, exactly like an administrator, and is not one. So
an auditor fell into the registrar's branch, sent no `institutionId`, and the API refused every
request: `400 institutionId is required for a role that is not scoped to one institution`. The
page was broken for that role from the day it was written, and no test noticed, because every
test signed in as an administrator or a registrar.

The fix is one condition — `mustChooseInstitution = !hasRole('REGISTRAR')` — plus the two things
that followed from asking the right question in the first place: an auditor is not offered the
**Add a qualification** button (the POST is REGISTRAR/ADMIN, so it could only have produced a
403), and is no longer told to copy an identity "to register an award against it", which only a
registrar can do. `QualificationListComponent`'s new spec runs the screen as all three roles.

The general lesson is worth recording, because the same shape can recur anywhere: **a screen
should branch on the capability it needs, not on the role that usually has it.** "Is an
administrator" was a stand-in for "is not scoped to one institution", and the stand-in was wrong
for exactly one role.

#### A mistyped share token answered 401

Pasting a token with a semicolon in it — `…nblsn;lbn;lds;…` — produced `401 Unauthorized` from
the public verification endpoint, which needs no account at all. The cause is Spring Security's
path matching: a semicolon introduces a path parameter, the request stops matching
`/public/v1/**`, and it falls through to `anyRequest().authenticated()`.

**This is left as it is on the server**, deliberately. The failure is in the safe direction: an
unrecognised path shape becomes *more* protected, not less. The dangerous version of this bug is
the mirror image — a path parameter making a protected route match a permitted pattern — and
loosening the firewall or the matcher to make this case prettier is exactly how a system
acquires that. The console now checks the shape instead: a token is base64url, so anything
holding a semicolon, a slash or a space is refused in the browser with a sentence saying so, and
no request is sent. A token that merely does not exist is still asked of the server, because
whether it exists is the server's answer to give — and the check is recorded in the ledger.

---

## The institution boundary

### A registrar was confined on two paths out of five

**Manuscript:** a registrar acts for one institution.

**Built, until now:** true where the institution came from the token — issuing a credential, and
listing qualifications — and **not true** on the three paths that took a *serial*:
`GET /api/v1/credentials/{serial}`, `POST /{serial}/revoke` and `POST /{serial}/share`. Each
checked that the caller held the REGISTRAR role and stopped there. Every registrar holds the
REGISTRAR role.

**Why it mattered more than it looks.** Serials are structured and are meant to be:
`ZW-PR0142-2026-000001` carries the institution's provider number, the year, and a counter that
starts at one. So the identifiers of another university's awards are not secret — they are
derivable from its provider number. Any registrar account could therefore read another
institution's records in full, withdraw an award it had made — with the audit ledger recording
the wronged institution as the actor, which is worse than the revocation itself — or mint a share
token, publishing that record to anybody with the link and no account at all.

This is the classic shape of the flaw: authorisation asked *what role is this* and never *whose
record is this*. Role checks are cheap and visible in one file, which is exactly why they get
mistaken for the whole answer.

**Built now:** each of the three takes the caller's institution from the token and refuses
anything else. The refusal is deliberately **indistinguishable from "no such serial"** — the same
404 on the read, the same `no credential X` on the two writes, asserted as identical in
`RevokeCredentialTest` and `InstitutionBoundaryIT`. Distinguishing them would answer "does this
serial exist?" for anybody with an account, which is the one question a guessable identifier must
not answer. Auditors are unchanged: reading across institutions is what an auditor is for.

The check lives in the use cases rather than the controllers, so a second adapter cannot forget
it; the read is filtered at the controller because the port it uses is shared with the public
verification path, which is anonymous by design and has no actor to scope by.

### An administrator no longer records qualifications

**Manuscript:** the administrator onboards institutions; the registrar registers qualifications
and credentials.

**Built, until now:** `POST /api/v1/qualifications` accepted REGISTRAR **or** ADMIN, with an
`institutionId` in the body that an administrator had to supply and a registrar had theirs taken
from the token.

**Built now:** registrars only, and the request has no institution field at all. An administrator
admits an institution to the register and issues its signing keys; what that institution awards
is the institution's own business, declared by the registrar who speaks for it. An administrator
recording qualifications against somebody else's institution is a central authority deciding what
a university offers, which is the opposite of what a federated register is for.

Removing the field is the stronger half of this change. A rule that says "ignore this field for
one role and require it for another" can be got wrong; a field that does not exist cannot be sent.

### An account cannot name an institution that has not been admitted

`CreateUser` checked that a registrar was given *an* institution, not that the institution was
real. Any UUID passed. The foreign key refused it a moment later, which reached the administrator
as a failure with no sentence in it and an account they believed they had created.

It now asks first, through a deliberately narrow port — `KnownInstitutions.exists` — and refuses
with the order of operations spelled out: admit the institution, then create the people who act
for it. The port is one method because identity has no business reading an institution's
accreditation date or its keys; it is implemented by an outbound adapter delegating to the
credential module's repository, so the coupling is visible to the architecture tests rather than
hidden behind a table name.

---

## Localisation

### The system is Zimbabwean, and says so everywhere

**Manuscript:** written against a South African setting — `ZA` country codes, serials reading
`ZA-PR0142-2026-000001`, a 13-digit South African national ID, `.ac.za` addresses, and a Java
package rooted at `za.ac.qvs`.

**Built:** the same system for Zimbabwe. `ZW` throughout, addresses at `.ac.zw`, demo
institutions and people renamed, and the package moved to `zw.ac.qvs` — 210 source files across
four source sets, plus the Gradle group, the SpotBugs exclusions and the CODEOWNERS paths that
name it. Serials follow automatically: `Serial.of` takes the country from the institution, so a
Zimbabwean institution issues `ZW-…` without the format knowing anything about a country.

**Two things the rename taught, worth writing down.** The first is that a case-sensitive
find-and-replace breaks precisely the tests that matter most: three failed, and all three were
tests whose *point* is that input is normalised — `"  SomeOne@Example.AC.ZA  "` folded to lower
case, a country code given as `"za"` stored as `"ZA"`. The odd-case half of each pair does not
match the pattern, so the input changed and the expectation did not, or the reverse. They failed
loudly, which is what they are for.

The second is that already-issued credentials keep their `ZA-` serials. That is correct and not
an oversight: a serial is part of what was signed, and rewriting it would invalidate the
signature on an award that was genuinely made. The country code in a serial records where the
credential came from, not where the system is running today.

### The national ID format is unchanged, and the message no longer claims otherwise

Validation is still `^[0-9]{13}$`. A Zimbabwean national ID is not 13 digits — it is written
`63-1234567 K 42`: a registration-office prefix, a serial, a check letter and a district code.
Changing the pattern would touch registration, holder search, the CSV import path and every
fixture carrying an identity number, so it was not done as part of a rename.

What *was* changed is the claim. The validation message said "a South African national ID is 13
digits"; it now says "a national ID is 13 digits", which is true of what the code enforces. A
message that names a country whose format it does not implement is worse than a vague one.

### Two labels, in the words a verifier uses

The public verification page asked for a **Share token**; it now asks for a **Certificate id**.
"Share token" describes the mechanism — a minted, expiring, revocable secret — and the person
reading that screen is an employer holding a string a graduate sent them. The mechanism is still
a share token everywhere inside the system, where that name is the accurate one.

The downloadable report's headline read `VALID — this qualification stands`; it now reads
**This Certificate is Valid**. The other two verdicts were rewritten to match — *This Certificate
has been Withdrawn*, *No Certificate was Found* — because a document that changes voice depending
on its answer reads as though two people wrote it.

### The national ID is Zimbabwean, and is one value type rather than two regexes

**Built:** `NationalId`, a value type in the credential domain. It reads the forms people
actually write — `63-1234567 K 42`, `631234567K42`, `63 1234567 k 42` — and produces one
canonical form, which is the only thing that is ever hashed.

**Why a type and not a corrected pattern on two DTOs.** The number is never stored; a salted hash
of it is. A hash is useful only if the same person hashes identically every time, and the two
paths that hash one already disagreed — `SearchCredentials` trimmed its input, `RegisterCredential`
did not. With thirteen bare digits that difference was invisible. With a format carrying optional
hyphens, optional spaces and a letter of either case, it becomes a graduate who is plainly in the
register and a search that reports nobody by that number, with nothing anywhere logging a
problem. Both paths now hash `NationalId.parse(raw).canonical()`, and a test asserts that three
punctuations of one number hash the same.

**The check letter is not recomputed.** Zimbabwe's check letter is derived from the digits, so a
wrong one could in principle be refused here. The published descriptions of that derivation do
not agree with each other, and a validator built on the wrong one refuses genuine identity
numbers held by real graduates — a worse failure than accepting a mistyped one, and a much harder
one for a registrar to argue with. The shape is checked; the arithmetic is not claimed. That is
recorded here rather than left for a reader to discover.

**Two things `toString` and the refusal message do not do:** repeat the number. Both end up in
logs and 4xx responses, and an identity number is the one field in this system that must not
travel in either. Tests assert it, using a number distinct from the example the message carries —
because the first version of that test failed on the helpful part of the sentence.

#### What a blanket find-and-replace cost, twice

Changing every fixture from thirteen digits to the new format was done with a mapping applied
across the tree, and it damaged two things that had nothing to do with identity numbers.

The RFC 6238 test vector for TOTP is the ASCII secret `12345678901234567890`. It contains a
thirteen-digit run, so it was rewritten, and every time-based one-time password test failed —
against a *correct* implementation. Six failing assertions about authentication, caused by a
change to demo identity numbers.

A set of 64-character payload-hash fixtures contained runs of thirteen zeros, which were likewise
rewritten. Those tests kept passing, because the strings are opaque to them — which is the worse
outcome of the two. A green suite hiding a corrupted fixture is found later, by someone who
cannot see why the constant looks like that.

Both were reverted. The lesson is in the tooling, not the care taken: a substitution over a whole
tree should be scoped to files that have a reason to contain the thing being replaced, and a
numeric literal is never a safe anchor.
