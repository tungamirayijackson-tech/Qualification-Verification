# 0001 — Modular monolith, with boundaries enforced by ArchUnit

- **Status:** accepted
- **Date:** 2026-09-05
- **Manuscript:** §04, Decision 04-A

## Context

The system has five natural areas of responsibility: credential registry, verification engine,
audit ledger, identity, and a shared kernel. Four people are building it in five weeks, and
"maintainability" is one of four graded criteria on the working-system deliverable.

Two obvious shapes present themselves. Microservices give crisp boundaries and independent
deployment. A single undifferentiated codebase gives speed.

## Decision

One deployable Spring Boot service, internally partitioned into five modules, with the module
boundaries **enforced by ArchUnit tests that run in the same CI stage as the unit tests**.

Inside each module the layering is hexagonal: `domain` (plain Java, no framework imports),
`application` (use cases and outbound ports), `adapter/in` and `adapter/out` at the edges.

## Rejected alternatives

**Microservices.** Would spend a meaningful fraction of a five-week project on service
discovery, inter-service contracts, distributed tracing and multi-service local startup —
none of which any requirement asks for. The assignment rewards a working, testable, deployable
system; it does not reward orchestration for its own sake.

**Single undifferentiated module.** Fast to start and impossible to defend under the
maintainability criterion. More practically, it makes the coverage gate expensive: without a
framework-free domain layer, testing the interesting logic means loading a Spring context.

## Consequences

- The claim "our modules are real" is checkable. Cross a boundary and the build goes red with a
  named rule, not a code-review opinion.
- Signature verification, chain recomputation and revocation precedence are unit-testable with
  a plain `new`, which is what makes ≥ 80% coverage cheap rather than painful.
- Use cases are plain classes wired by a `@Configuration`, not `@Service` beans. Slightly more
  wiring code, in exchange for an application layer with no Spring annotations in it.
- If the system ever did need to split, the module boundaries are already the seams.
