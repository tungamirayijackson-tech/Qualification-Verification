package zw.ac.qvs.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * FR-11 through the whole stack.
 *
 * <p>The unit test proves the counting; this proves the wiring — that the filter is registered
 * on the public path, that it runs ahead of Spring Security on a route with no authentication,
 * and that a refusal is a 429 carrying {@code Retry-After} rather than a bare error.
 *
 * <p>Each test presents a <b>different client address</b>. Without that they share one bucket,
 * and whichever test happens to run second finds the allowance already spent — a failure that
 * depends on execution order and looks like a bug in the limiter rather than in the test. It
 * also exercises the {@code X-Forwarded-For} path, which is the one that matters in
 * production: behind a load balancer, {@code getRemoteAddr()} is the balancer, and a limiter
 * keyed on it would throttle the entire internet as one caller.
 *
 * <p>The endpoint is called with a token matching nothing, so every request answers NOT_FOUND
 * until the limit bites. That keeps the test about throttling — and it is exactly the traffic
 * the limit exists to stop, since somebody guessing tokens is what generates it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement("FR-11")
class RateLimitIT extends PostgresIntegrationTest {

    /** Matches the allowance configured for the integration profile. */
    private static final int ALLOWANCE = 60;

    /** Hands each test its own client address, and so its own bucket. */
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    @Autowired
    private TestRestTemplate rest;

    private final String clientAddress = "203.0.113." + NEXT_CLIENT.getAndIncrement();

    private ResponseEntity<String> verify() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", clientAddress);

        return rest.exchange("/public/v1/verify/a-token-that-matches-nothing",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** Spends the allowance and returns the first refusal. */
    private ResponseEntity<String> exhaustAllowance() {
        for (int i = 0; i < ALLOWANCE + 5; i++) {
            ResponseEntity<String> response = verify();
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                return response;
            }
        }
        return null;
    }

    @Test
    @DisplayName("allows exactly the allowance, then refuses with 429 and Retry-After")
    void refusesBeyondTheAllowance() {
        for (int i = 1; i <= ALLOWANCE; i++) {
            assertThat(verify().getStatusCode())
                    .as("request %d of %d should be allowed", i, ALLOWANCE)
                    .isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> refused = verify();

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        String retryAfter = refused.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        assertThat(retryAfter).isNotNull();
        assertThat(Long.parseLong(retryAfter)).isPositive();
    }

    @Test
    @DisplayName("a refusal is a problem document, not a bare error page")
    void refusalIsAProblemDocument() {
        ResponseEntity<String> refused = exhaustAllowance();

        assertThat(refused).isNotNull();
        assertThat(refused.getHeaders().getContentType())
                .hasToString("application/problem+json;charset=UTF-8");
        assertThat(refused.getBody())
                .contains("\"status\":429")
                .contains("rate-limited")
                .contains("retryAfterSeconds");
    }

    @Test
    @DisplayName("advertises the allowance on every response, not only on a refusal")
    void advertisesTheAllowance() {
        // So a well-behaved client can slow down before it is refused rather than after.
        ResponseEntity<String> response = verify();

        assertThat(response.getHeaders().getFirst("X-RateLimit-Limit"))
                .isEqualTo(Long.toString(ALLOWANCE));
        assertThat(response.getHeaders().getFirst("X-RateLimit-Remaining")).isNotNull();
    }

    @Test
    @DisplayName("one exhausted caller does not lock out another")
    void callersAreIndependent() {
        // The failure this guards against turns the protection into the outage it was meant to
        // prevent: one busy address exhausting the allowance for everybody.
        assertThat(exhaustAllowance()).isNotNull();

        HttpHeaders otherCaller = new HttpHeaders();
        otherCaller.set("X-Forwarded-For", "198.51.100.7");

        ResponseEntity<String> unaffected = rest.exchange(
                "/public/v1/verify/a-token-that-matches-nothing",
                HttpMethod.GET, new HttpEntity<>(otherCaller), String.class);

        assertThat(unaffected.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("does not throttle the authenticated console")
    void consoleIsNotThrottled() {
        // The limit is on the public door. Throttling a registrar working through a graduation
        // list would inconvenience a known, attributable, already role-constrained caller for
        // no security gain.
        for (int i = 0; i < ALLOWANCE + 5; i++) {
            ResponseEntity<String> response =
                    rest.getForEntity("/api/v1/credentials", String.class);

            assertThat(response.getStatusCode())
                    .as("the console path should never answer 429")
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
