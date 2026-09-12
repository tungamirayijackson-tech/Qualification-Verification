package zw.ac.qvs.shared.domain;

import java.time.Duration;

/**
 * How many requests a caller may make, and over what window.
 *
 * <p>Named policies rather than one global number, because the two limited surfaces have very
 * different costs. A verification is a handful of queries and a hash; generating a signed PDF
 * report is orders of magnitude more work, so it gets its own, much smaller allowance. A single
 * limit generous enough for the first would leave the second wide open.
 *
 * @param name     identifies the policy in the bucket key and in metrics
 * @param capacity requests permitted per window
 * @param window   the window over which the allowance refills
 */
public record RateLimitPolicy(String name, long capacity, Duration window) {

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a rate-limit policy needs a name");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("a rate-limit capacity must be positive");
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("a rate-limit window must be positive");
        }
    }

    /**
     * How long a caller who has exhausted this policy should wait, in whole seconds.
     *
     * <p>Rounded up and never zero: a {@code Retry-After: 0} invites an immediate retry, which
     * is exactly the behaviour the limit exists to prevent.
     *
     * @return seconds to advertise in the Retry-After header
     */
    public long retryAfterSeconds() {
        return Math.max(1, window.toSeconds());
    }
}
