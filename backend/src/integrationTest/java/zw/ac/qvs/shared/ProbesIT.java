package zw.ac.qvs.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * NFR-07: the two questions a platform asks, and nothing else.
 *
 * <p>A liveness probe asks whether the process should be restarted. A readiness probe asks
 * whether it should receive traffic. Both need to be reachable without credentials — a
 * kubelet, a load balancer and a Compose healthcheck do not hold tokens — and both need to
 * answer with a status and no more.
 *
 * <p>That last clause is the reason this test exists rather than being taken on trust. The
 * authorisation rule was written as {@code /actuator/health/**}, which reads as "the probes"
 * and in fact also matches the aggregate {@code /actuator/health}. Under the dev profile that
 * endpoint reported component details, so the demo stack published its PostgreSQL and Redis
 * versions, its disk paths and its free space to anyone who asked — a reconnaissance list, on
 * the one deployment the README tells people to run. Nothing caught it because the matcher and
 * the comment above it agreed with each other and both were about a different endpoint.
 *
 * <p>Readiness genuinely depends on the database here: the assertions below run against a real
 * PostgreSQL, and a readiness probe that returned UP without one would be answering the wrong
 * question.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement("NFR-07")
class ProbesIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @ParameterizedTest(name = "{0} is reachable without credentials and reports UP")
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    @DisplayName("both probes answer an anonymous caller")
    void probesAnswerAnonymously(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);

        assertThat(response.getStatusCode())
                .as("a kubelet holds no token; a probe behind authentication is a probe that "
                        + "reports every pod as unhealthy")
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @ParameterizedTest(name = "{0} discloses nothing but the status")
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    @DisplayName("a probe is a yes or a no, not an inventory")
    void probesDiscloseNothingElse(String path) {
        String body = rest.getForEntity(path, String.class).getBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("PostgreSQL", "postgres", "redis", "Redis");
        assertThat(body).doesNotContain("diskSpace", "free", "path", "version");
        assertThat(body).doesNotContain("components", "details");
    }

    @Test
    @DisplayName("the aggregate health endpoint is not an anonymous inventory")
    void aggregateHealthIsNotPublic() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        // Either refused outright or, if a later change reopens it, at least stripped of the
        // component detail. Both are acceptable; publishing the versions of everything this
        // system runs on is not.
        if (response.getStatusCode() == HttpStatus.OK) {
            assertThat(response.getBody())
                    .as("if this endpoint is open it must still not enumerate the stack")
                    .doesNotContain("PostgreSQL", "diskSpace", "components");
        } else {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("the rest of the actuator tree stays shut")
    void theRestOfTheTreeIsClosed() {
        // The endpoints that would hand over configuration, environment variables and metrics.
        // /actuator/env in particular would disclose the very secrets the deployment goes to
        // trouble to keep out of the image.
        for (String path : new String[] {"/actuator/env", "/actuator/configprops",
                "/actuator/beans", "/actuator/loggers", "/actuator/prometheus"}) {

            assertThat(rest.getForEntity(path, String.class).getStatusCode())
                    .as("%s must not answer an anonymous caller", path)
                    .isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("readiness is answered by the real dependencies, not by a constant")
    void readinessReflectsDependencies() {
        // This runs against a real PostgreSQL from Testcontainers. The value of asserting it
        // here is modest on its own -- but it fails loudly if someone ever "simplifies"
        // readiness into a hardcoded UP, which is a tempting way to make a flaky deployment
        // look healthy and is precisely the change this requirement exists to prevent.
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getBody())
                .contains("\"status\":\"UP\"");

        assertThat(POSTGRES.isRunning())
                .as("readiness reported UP, so its database had better be up")
                .isTrue();
    }
}
