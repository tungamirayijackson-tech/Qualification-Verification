package zw.ac.qvs.shared.adapter;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import zw.ac.qvs.shared.adapter.in.RateLimitFilter;
import zw.ac.qvs.shared.adapter.out.Bucket4jRateLimiter;
import zw.ac.qvs.shared.domain.RateLimitPolicy;
import zw.ac.qvs.shared.domain.RateLimiter;

/**
 * Wires FR-11.
 *
 * <p>The Redis connection is built here rather than reusing Spring's {@code RedisTemplate},
 * because Bucket4j needs a raw Lettuce client with a byte-array codec and the template is
 * configured for a different serialisation entirely. Sharing one would mean fighting both.
 */
@Configuration
public class RateLimitConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfiguration.class);

    /**
     * Short on purpose. Rate limiting sits in front of every public request, so a slow limiter
     * is a slow endpoint; if Redis cannot answer in half a second the right move is to give up
     * and degrade to the local bucket rather than hold the request open.
     */
    private static final Duration COMMAND_TIMEOUT = Duration.ofMillis(500);

    /**
     * The Lettuce client Bucket4j counts through.
     *
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient rateLimitRedisClient(RedisProperties properties) {
        return RedisClient.create(RedisURI.builder()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withTimeout(COMMAND_TIMEOUT)
                .build());
    }

    /**
     * The limiter, backed by Redis when it is reachable.
     *
     * <p>The proxy manager is built here rather than exposed as its own bean, because when
     * Redis is unreachable there is nothing to expose. A {@code @Bean} method that returns
     * null produces a bean Spring will not inject, so the absence has to be handled inside the
     * one place that can cope with it.
     *
     * <p>Failing to connect does not fail start-up. The limiter degrades to a local bucket, and
     * a verification service that refuses to boot because a cache is missing has turned a cache
     * outage into an outage of its own purpose.
     */
    @Bean
    public RateLimiter rateLimiter(RedisClient client) {
        return new Bucket4jRateLimiter(sharedStoreOrNull(client));
    }

    private static ProxyManager<byte[]> sharedStoreOrNull(RedisClient client) {
        try {
            return Bucket4jLettuce.casBasedBuilder(client).build();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable at start-up; rate limits will be per-instance until it "
                    + "returns ({})", e.getMessage());
            return null;
        }
    }

    /**
     * Registers the filter ahead of Spring Security.
     *
     * <p>A limiter that runs after authentication has already paid for the request it is
     * refusing — and on a path with no authentication at all it would simply never be the thing
     * that stops a flood.
     */
    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
            RateLimiter limiter, QvsProperties properties, MeterRegistry meters) {

        var registration = new FilterRegistrationBean<>(new RateLimitFilter(
                limiter,
                new RateLimitPolicy("public-verify",
                        properties.rateLimit().verifyPerMinute(), Duration.ofMinutes(1)),
                new RateLimitPolicy("public-report",
                        properties.rateLimit().reportPerMinute(), Duration.ofMinutes(1)),
                properties.crypto().ipSalt(),
                meters));

        registration.addUrlPatterns("/public/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
