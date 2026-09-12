# Load stage (NFR-01)

`verify-smoke.js` measures the public verification endpoint: **p95 < 400 ms at 50 concurrent
users**.

k6 is not a project dependency and does not need installing — it runs from its own image, on the
Compose network, so it reaches the API directly rather than through the host port mapping.

## Running it

```bash
# 1. Bring the stack up.
docker compose up -d

# 2. Mint a share token. The endpoint under test takes one, and there is deliberately no
#    route that accepts a serial, so a token is the only way in. Sign in as a registrar and
#    POST /api/v1/credentials/{serial}/share — the response carries the token.

# 3. Run the stage.
docker run --rm -i --network qvs_default \
  -e BASE_URL=http://api:8080 \
  -e SHARE_TOKEN=<the token> \
  grafana/k6 run - < perf/verify-smoke.js
```

The run takes about 75 seconds: ten seconds ramping to 50 virtual users, sixty at that level,
five winding down.

## Measured

Against the Compose stack on a developer workstation (Docker Desktop on Windows 11, PostgreSQL
16 and Redis in containers alongside the API):

Two runs, one before and one after the NFR-07 authorisation change, so the figure quoted is a
range rather than a single sample:

| | Run 1 | Run 2 |
|---|---|---|
| **p95** | **111 ms** | **77 ms** |
| p50 | 27 ms | 37 ms |
| p90 | 68 ms | 63 ms |
| p99 | 270 ms | 170 ms |
| max | 433 ms | 523 ms |
| Requests | 2 983 | 2 970 |
| Failures | 0 | 0 |
| Rate-limited | 0 | 0 |

Target: p95 < 400 ms.

**NFR-01 is met**, with room. Worth noting the shape rather than only the headline: the
distribution has a long tail — the median is a fraction of the p95, and in both runs the
slowest single request exceeded the 400 ms target on its own. That is what a JVM under a
ramping load looks like (class loading, JIT, connection pool growth, the occasional GC pause),
and it is why the
requirement is written as a percentile. A target expressed as a maximum would fail on one
request in three thousand and tell you nothing useful.

## Why each virtual user gets its own address

The endpoint is rate-limited to 60 requests a minute per client address (FR-11), keyed on
`X-Forwarded-For`. Fifty virtual users sharing one address would spend the run being refused,
and the resulting p95 would be the latency of producing a 429 — a number that looks excellent
and measures nothing.

Each VU therefore presents a distinct address, which is not a way around the limit but a fair
reading of the requirement: *fifty concurrent users*, not one user exceeding the published quota
fiftyfold. Each is paced at roughly one request a second, inside its own budget, and **any 429
at all fails the run**, because a refusal means the pacing is wrong and the latency figures are
measuring the wrong thing.

## What is actually being measured

A verification is not a read. It resolves a share token, loads the credential, verifies an
Ed25519 signature, appends a hash-chained entry to the audit ledger, and writes a verification
log row — two writes and a signature check on every request.

The ledger append is the part worth watching under concurrency: each entry hashes its
predecessor, so the chain is a serialisation point by construction. It did not become one at
this load, but it is the first thing to look at if this stage ever regresses.

## In the build

`PublicVerificationLatencyIT` runs a smaller concurrent load inside the normal integration test
run and fails on a generous ceiling. It exists so the requirement is traceable and so a
catastrophic regression — an N+1 query, a lost index, a lock introduced on the verify path —
fails the build rather than waiting for someone to run this stage by hand. It does **not**
assert the 400 ms figure: it shares a JVM and a Docker host with the rest of the suite, and a
wall-clock assertion in that setting measures the machine's mood as much as the code. The number
this requirement is judged on is the one produced here.
