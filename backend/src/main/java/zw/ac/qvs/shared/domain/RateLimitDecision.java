package zw.ac.qvs.shared.domain;

/**
 * Whether one request may proceed.
 *
 * @param allowed           whether the caller is within their allowance
 * @param remaining         tokens left in the window; -1 when unknown
 * @param retryAfterSeconds how long to wait, meaningful only when refused
 * @param degraded          true when the shared limiter was unreachable and a local one answered
 */
public record RateLimitDecision(
        boolean allowed, long remaining, long retryAfterSeconds, boolean degraded) {

    /** A request within its allowance. */
    public static RateLimitDecision allow(long remaining) {
        return new RateLimitDecision(true, remaining, 0, false);
    }

    /** A request that has exhausted its allowance. */
    public static RateLimitDecision refuse(long retryAfterSeconds) {
        return new RateLimitDecision(false, 0, retryAfterSeconds, false);
    }

    /** The same decision, marked as having come from the local fallback. */
    public RateLimitDecision asDegraded() {
        return new RateLimitDecision(allowed, remaining, retryAfterSeconds, true);
    }
}
