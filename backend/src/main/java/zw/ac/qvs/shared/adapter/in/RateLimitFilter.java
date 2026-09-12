package zw.ac.qvs.shared.adapter.in;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.shared.domain.RateLimitDecision;
import zw.ac.qvs.shared.domain.RateLimitPolicy;
import zw.ac.qvs.shared.domain.RateLimiter;

/**
 * FR-11: throttles the public verification door.
 *
 * <p>Applied only to {@code /public/**}. The authenticated console is not rate-limited here,
 * and that is deliberate rather than an omission: those callers are known, attributable and
 * already constrained by their role, so throttling them would mostly inconvenience a registrar
 * working through a graduation list. The public path is the one a stranger can reach.
 *
 * <p>The filter runs <b>before</b> Spring Security. A limiter that sits behind authentication
 * has already paid for the request it is trying to refuse, and on a path with no authentication
 * at all it would simply be in the wrong place.
 *
 * <p>Report generation gets its own, much smaller allowance. Rendering a signed PDF costs
 * orders of magnitude more than answering a verification, so a single limit generous enough for
 * one leaves the other wide open.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final String PUBLIC_PREFIX = "/public/";
    private static final String REPORT_SUFFIX = "/report";

    private final RateLimiter limiter;
    private final RateLimitPolicy verifyPolicy;
    private final RateLimitPolicy reportPolicy;
    private final String ipSalt;

    /**
     * Refusals, per policy. A rising count here is the earliest signal the public endpoint is
     * being driven harder than a person could drive it, which is why it is on the dashboard
     * next to the verdict mix rather than buried in a log nobody tails.
     */
    private final Counter verifyRefusals;
    private final Counter reportRefusals;

    /** Whether the limiter is running on its in-memory fallback rather than on Redis. */
    private final Counter degradedRefusals;

    public RateLimitFilter(
            RateLimiter limiter,
            RateLimitPolicy verifyPolicy,
            RateLimitPolicy reportPolicy,
            String ipSalt,
            MeterRegistry meters) {
        this.limiter = limiter;
        this.verifyPolicy = verifyPolicy;
        this.reportPolicy = reportPolicy;
        this.ipSalt = ipSalt;

        this.verifyRefusals = refusalCounter(meters, verifyPolicy.name());
        this.reportRefusals = refusalCounter(meters, reportPolicy.name());
        this.degradedRefusals = Counter.builder("qvs.rate.limit.degraded")
                .description("Refusals decided by the in-memory fallback, not by Redis")
                .register(meters);
    }

    private static Counter refusalCounter(MeterRegistry meters, String policy) {
        return Counter.builder("qvs.rate.limit.refusals")
                .description("Requests refused with 429, by policy")
                .tag("policy", policy)
                .register(meters);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        RateLimitPolicy policy = request.getRequestURI().endsWith(REPORT_SUFFIX)
                ? reportPolicy
                : verifyPolicy;

        RateLimitDecision decision = limiter.consume(clientKey(request), policy);

        // Advertised on every response, not only on a refusal, so a well-behaved client can
        // slow down before it is refused rather than after.
        response.setHeader("X-RateLimit-Limit", Long.toString(policy.capacity()));
        if (decision.remaining() >= 0) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(decision.remaining()));
        }

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        refuse(request, response, decision, policy);
    }

    private void refuse(
            HttpServletRequest request,
            HttpServletResponse response,
            RateLimitDecision decision,
            RateLimitPolicy policy) throws IOException {

        log.info("rate limit {} exceeded on {}{}", policy.name(), request.getRequestURI(),
                decision.degraded() ? " (local fallback in use)" : "");

        (policy == reportPolicy ? reportRefusals : verifyRefusals).increment();
        if (decision.degraded()) {
            // Worth its own counter: a refusal decided locally means each instance is
            // enforcing its own budget, so the effective limit across a scaled-out deployment
            // is the published one multiplied by the number of replicas.
            degradedRefusals.increment();
        }

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // Written directly rather than raised as an exception. This filter sits in front of
        // Spring Security and therefore in front of the @RestControllerAdvice that would
        // normally render a problem document, so there is nothing downstream to catch it.
        response.getWriter().write("""
                {"type":"https://qvs.ac.zw/problems/rate-limited",\
                "title":"Too many requests",\
                "status":429,\
                "detail":"Too many verification requests. Please wait %d seconds and try again.",\
                "retryAfterSeconds":%d}"""
                .formatted(decision.retryAfterSeconds(), decision.retryAfterSeconds()));
    }

    /**
     * The identity a limit is applied to: a salted hash of the caller's address.
     *
     * <p>Hashed before it reaches the limiter, so no store the limiter uses ever holds a raw
     * address. Salted, because an unsalted hash of an IPv4 address is not an anonymisation at
     * all — the whole space is four billion entries and reversing it is an afternoon's work.
     */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String address = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();

        return address == null || address.isBlank() ? "" : Hashing.sha256Hex(ipSalt + address);
    }
}
