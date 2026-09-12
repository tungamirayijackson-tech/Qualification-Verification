# Requirement register

Single source of truth for the functional and non-functional requirements. This file is
**parsed by the build** (`./gradlew :backend:traceabilityReport`), so the table shapes below
are load-bearing — keep the column order and the `FR-nn` / `NFR-nn` id format.

Coverage is claimed by annotating a test with `@Requirement("FR-06")`. The task cross-references
the annotations against this register and fails when a `required` row has no covering test.

| Status   | Meaning                                                                              |
|----------|--------------------------------------------------------------------------------------|
| required | Must have at least one covering test. No test ⇒ **build fails**.                      |
| planned  | Not yet due in the schedule (§16). No test ⇒ warning, printed in the report.          |
| gate     | Enforced by a pipeline stage rather than a test case (coverage, static analysis, CVE scanning). Listed for completeness; the gate itself is the evidence. |

## Functional requirements

| ID | Requirement | Acceptance criterion (machine-checkable) | Origin | Status |
|----|-------------|------------------------------------------|--------|--------|
| FR-01 | Register a qualification against an accredited institution | POST returns 201 + credential serial; rejects an institution whose `accredited_until` has passed with 422 | Register | required |
| FR-02 | Bulk import a graduation cohort from CSV | A malformed row fails that row only and is reported by line number — **met and tested**. 1 000-row file in < 30 s — **not met**: measured 51.6 s (51.6 ms/row) on the development machine, see `docs/deviations.md` | Register | required |
| FR-03 | Search records by holder name, national-ID hash, institution or NQF level | Paged results, p95 < 400 ms over 10 000 seeded records; results scoped to the caller's institution unless role is AUDITOR | Search | required |
| FR-04 | Retrieve one credential in full, with its issuance chain | Response contains institution, qualification, award date, signature status, revocation status | `PublicDisclosureIT` | required |
| FR-05 | Sign each credential at issuance with the institution's Ed25519 key | Detached JWS verifies against the published public key; mutating any claim invalidates it | Verify | required |
| FR-06 | Verify a credential publicly, without an account | GET with a share token returns exactly one of valid / revoked / not-found; no other data leaks on the not-found path | Verify | required |
| FR-07 | Revoke a credential with a reason and an actor | Subsequent verification returns revoked with reason code; the original signature remains valid | Verify | required |
| FR-08 | Append every state change and every verification to a tamper-evident ledger | Each entry stores `prev_hash`; the chain recomputes end-to-end; an UPDATE on the table is refused by a DB trigger | Audit | required |
| FR-09 | Export an audit trail for a credential or a date range | CSV + PDF export; row count equals the ledger query count; the export itself is logged | Audit | required |
| FR-10 | Enforce four roles: registrar, verifier, auditor, admin | Parameterised test asserts the full role/endpoint matrix; a missing entry fails the build | Derived | required |
| FR-11 | Rate-limit the public verification endpoint | 61st request from one IP inside 60 s returns 429 with `Retry-After` | Derived | required |
| FR-12 | Produce a signed verification report a verifier can keep | PDF embeds credential serial, verdict, verified-at, ledger sequence number and the verifying key ID | `VerificationReportIT`, `VerificationReportTest` | required |

## Non-functional requirements

| ID | Quality attribute | Target | Enforced by | Status |
|----|-------------------|--------|-------------|--------|
| NFR-01 | Public verification latency | p95 < 400 ms at 50 concurrent users — **met: p95 111 ms and 77 ms over two runs** (`perf/verify-smoke.js`, 50 VUs, ~2 980 requests each, 0 failures, 0 refusals). `PublicVerificationLatencyIT` guards against regression in the build. | `perf/verify-smoke.js`, `PublicVerificationLatencyIT` | required |
| NFR-02 | Domain-layer test coverage | ≥ 80% line, ≥ 70% branch | JaCoCo verification rule | gate |
| NFR-03 | Static-analysis cleanliness | 0 SpotBugs high, 0 Checkstyle errors | Gradle `check` | gate |
| NFR-04 | Dependency and image vulnerabilities | 0 critical, 0 high | Trivy (+ OWASP DC nightly) | gate |
| NFR-05 | Recoverability | Rebuild from an empty DB via Flyway in < 60 s | Testcontainers migration test | required |
| NFR-06 | Data minimisation (POPIA) | Public path returns no name, no ID number | `PublicDisclosureIT`, asserted against the raw response bytes | required |
| NFR-07 | Availability during the demo window | Liveness + readiness probes — **met and tested** (`ProbesIT`: both reachable unauthenticated, both disclosing nothing but a status, the rest of the actuator tree shut). 2 replicas — **not demonstrated**: the Compose demo runs one container on a fixed host port; see `docs/deviations.md`. | `ProbesIT` | required |

## Architecture rules

These are not requirements from the brief; they are the constraints the design rests on, and
they are enforced the same way — by a test that fails the build.

| ID | Rule | Enforced by | Status |
|----|------|-------------|--------|
| ARCH-01 | The domain layer has no framework dependencies | ArchUnit `domainIsFrameworkFree` | required |
| ARCH-02 | Application depends inward on ports, never on adapters | ArchUnit `applicationDoesNotSeeAdapters` | required |
| ARCH-03 | Every HTTP entry point lives in `adapter.in` | ArchUnit `controllersOnlyAtTheInboundEdge` | required |
| ARCH-04 | The five modules are free of cycles | ArchUnit `modulesAreAcyclic` | required |

## Bonus features

Optional in the brief, and held to the same standard as everything above: each row is either
built with a test that owns it, or listed here as not built. Work that is not in this matrix is
work nobody checks.

| ID | Feature | Acceptance | Verified by | Status |
|----|---------|------------|-------------|--------|
| BONUS-01 | Monitoring: Prometheus metrics, provisioned Grafana dashboard, alert rules | Every metric the dashboard queries exists in the scrape; integrity failures are visible to an operator though invisible to the caller | `MetricsIT` | required |
| BONUS-02 | Anomaly-detection agent | Reports token enumeration, credential harvesting and tampering; does not fire on a mistyped link or an employer checking a shortlist; never discloses a raw address | `AnomalyRulesTest`, `AnomalyDetectionIT` | required |
| BONUS-03 | Infrastructure as code (Terraform) | `fmt -check` and `validate` pass; the Compose and Terraform descriptions agree on images, published ports and secrets, and neither binds a port beyond loopback | `InfrastructureParityTest`, `terraform validate` | required |
