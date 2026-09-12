# 0002 — Spring Boot on Java 21, with an Angular 18 console

- **Status:** accepted
- **Date:** 2026-09-05
- **Manuscript:** §03

## Context

The brief permits Java or Python. Three of the graded axes — static analysis and coding
standards, integration testing, and the cryptographic core of verification — are affected by
this choice.

## Decision

Java 21 (LTS) with Spring Boot 3.3, PostgreSQL 16, Gradle (Kotlin DSL), and an Angular 18
console using standalone components and signals.

## Reasoning

**Static analysis is a graded deliverable.** Checkstyle, SpotBugs, JaCoCo and PIT all attach to
a single `gradle check`. The Python equivalent means assembling ruff, mypy, bandit and coverage
and then arguing about which of them counts as static analysis.

**Integration testing is a graded deliverable.** Testcontainers plus `@SpringBootTest` gives a
real PostgreSQL 16 per run, so the integration tests exercise the actual migrations, the actual
CHECK constraints and the actual append-only trigger. This is not theoretical: the very first
integration run caught a `CHAR(2)` versus `varchar(2)` mismatch between the migration and the
entity mapping that an in-memory database would have hidden.

**Verification needs real cryptography.** JCA with Bouncy Castle and Nimbus JOSE provides
Ed25519 and JWS from audited implementations. Java records make the signed payload an immutable
value object, which is exactly the property a canonicalised signing input needs.

**Angular over a lighter framework.** It ships opinionated structure — typed reactive forms, an
HTTP interceptor layer for the JWT, per-role router guards — so the frontend's maintainability
comes from the framework rather than from four students agreeing on conventions under deadline.

## Consequences

- Slower cold CI than a scripting stack; mitigated by the Gradle build cache.
- Node 20 is the pinned CI version (Angular 18's supported range). A developer on a newer Node
  will see engine warnings locally; CI is the source of truth.
