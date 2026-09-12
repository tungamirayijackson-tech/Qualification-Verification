import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

/**
 * NFR-01: public verification latency, p95 < 400 ms at 50 concurrent users.
 *
 * Run it with the stack up:
 *
 *   docker compose up -d
 *   docker run --rm -i --network qvs_default \
 *     -e BASE_URL=http://api:8080 -e SHARE_TOKEN=<token> \
 *     grafana/k6 run - < perf/verify-smoke.js
 *
 * See perf/README.md, which also explains how to mint a token.
 *
 * ## Why each virtual user gets its own address
 *
 * The public endpoint is rate-limited to 60 requests a minute per client address (FR-11), and
 * a client is identified by `X-Forwarded-For`. Fifty virtual users hammering it from one
 * address would spend the run being refused, and the p95 that came out would be the latency of
 * producing a 429 — a number that looks wonderful and means nothing.
 *
 * So each VU presents a distinct address, which is not a way around the limit but a fair
 * reading of what the requirement says: *fifty concurrent users*, not one user making fifty
 * times as many requests as the system permits. Each one is then paced at roughly one request
 * a second, inside its own budget, and any 429 at all fails the run — because a 429 means the
 * pacing is wrong and the latency figures are measuring the wrong thing.
 *
 * ## What is being measured
 *
 * A verification is not a read. It resolves a share token, loads the credential, verifies an
 * Ed25519 signature, appends a hash-chained entry to the audit ledger and writes a verification
 * log row. The ledger append is the interesting part under concurrency: each entry hashes its
 * predecessor, so the chain is a serialisation point by construction. If this stage falls over
 * anywhere, that is where it will be.
 */

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8081';
const SHARE_TOKEN = __ENV.SHARE_TOKEN;

/** Refusals, kept separate so a run polluted by rate limiting cannot report a flattering p95. */
const rateLimited = new Counter('rate_limited');
const verdictOk = new Counter('verdict_valid');
const serverLatency = new Trend('verify_latency', true);

export const options = {
  scenarios: {
    verify: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '60s', target: 50 },
        { duration: '5s', target: 0 }
      ],
      gracefulRampDown: '5s'
    }
  },
  thresholds: {
    // The requirement itself.
    'http_req_duration{expected_response:true}': ['p(95)<400'],
    // A run with any refusal or error in it is not a measurement of this requirement.
    http_req_failed: ['rate==0'],
    rate_limited: ['count==0']
  },
  // The figures that matter are the tail ones; the default summary hides p99.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max']
};

export function setup() {
  if (!SHARE_TOKEN) {
    throw new Error(
      'SHARE_TOKEN is required. Mint one with a registrar account — see perf/README.md.'
    );
  }

  // Fail early and clearly rather than discovering mid-run that every response is a 404.
  const probe = http.get(`${BASE_URL}/actuator/health/readiness`);
  if (probe.status !== 200) {
    throw new Error(`${BASE_URL} is not ready: ${probe.status}`);
  }
  return { token: SHARE_TOKEN };
}

export default function (data) {
  // One address per virtual user: 10.<vu/256>.<vu%256>.1, which stays inside a private range
  // so it cannot be confused with a real client if it ends up in a log.
  const vu = __VU;
  const clientAddress = `10.${Math.floor(vu / 256)}.${vu % 256}.1`;

  const response = http.get(`${BASE_URL}/public/v1/verify/${data.token}`, {
    headers: { 'X-Forwarded-For': clientAddress },
    tags: { name: 'verify' }
  });

  serverLatency.add(response.timings.duration);

  if (response.status === 429) {
    rateLimited.add(1);
  }

  const passed = check(response, {
    'answers 200': (r) => r.status === 200,
    'says VALID': (r) => r.status === 200 && r.json('verdict') === 'VALID',
    'discloses no name': (r) => r.status === 200 && !String(r.body).includes('holderName')
  });
  if (passed) {
    verdictOk.add(1);
  }

  // Paced to stay inside this VU's own 60-a-minute budget. Deliberately a shade under one
  // request a second so that ramp-up jitter cannot push a VU over its limit.
  sleep(1.1);
}
