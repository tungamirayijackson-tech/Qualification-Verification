package zw.ac.qvs.shared.domain;

/**
 * Decides whether a caller may make one more request.
 *
 * <p>An interface in the domain so the rest of the system can be rate-limited without knowing
 * whether the counting happens in Redis, in memory, or not at all.
 */
public interface RateLimiter {

    /**
     * Consumes one token for a caller under a policy.
     *
     * <p>Implementations must never throw. A limiter that fails loudly takes the endpoint down
     * with it, which converts a dependency outage into an outage of the thing an employer is
     * trying to verify — a far worse outcome than a briefly imprecise limit.
     *
     * @param key    who is being limited; already hashed, never a raw address
     * @param policy the allowance to apply
     * @return whether the request may proceed
     */
    RateLimitDecision consume(String key, RateLimitPolicy policy);
}
