package zw.ac.qvs.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import java.util.UUID;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.shared.adapter.in.ConsoleRoutes;
import zw.ac.qvs.identity.application.TokenIssuer;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * What the server answers to requests that are not well-formed API calls.
 *
 * <p>Two behaviours are pinned here, both of which were wrong and both of which were invisible
 * to every other test because no test asked the server anything malformed.
 *
 * <p><b>The console's own URLs must reach the console.</b> FR-06's share link is a URL a holder
 * receives and opens cold — no page has loaded, no Angular router exists yet, and the request
 * goes to Spring. The same is true of pressing reload anywhere in the console. That worked for
 * {@code /verify} and returned <em>401</em> for {@code /sign-in}, because the list of paths
 * forwarded to {@code index.html} and the list of paths the filter chain permitted were
 * maintained separately and had drifted. The sign-in screen was unreachable by bookmark.
 * Iterating {@link ConsoleRoutes} here means a route added to the console without being
 * permitted fails this test rather than failing a user.
 *
 * <p><b>A bad request is not a server error.</b> Every framework-level complaint — unknown URL,
 * wrong method, wrong content type, unparseable body — was answered <em>500</em> and logged
 * with a stack trace, because the catch-all handler caught them all. These tests assert the
 * status each one deserves. The register has no row for this; it is the kind of thing a
 * requirements table takes for granted, which is precisely why it went unnoticed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement("FR-06")
class HttpContractIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private JdbcTemplate jdbc;

    static java.util.stream.Stream<String> consolePaths() {
        // The wildcard patterns are templates, not URLs, so give each a plausible segment.
        return ConsoleRoutes.shellPaths().stream()
                .map(pattern -> pattern.replace("/*", "/a-segment"));
    }

    @ParameterizedTest(name = "{0} serves the console")
    @MethodSource("consolePaths")
    @DisplayName("every console route is forwarded to the bundle, not refused")
    void consoleRoutesServeTheBundle(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);

        assertThat(response.getStatusCode())
                .as("%s must reach the console shell; 401 here means the permit list and the "
                        + "forwarding list have drifted apart again", path)
                .isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("<app-root>");
    }

    @Test
    @DisplayName("an unknown URL is 404, not 500")
    void unknownUrlIsNotFound() {
        // A hashed bundle name that no longer exists is the realistic version of this: a stale
        // page asking for last deploy's chunk. It answered 500 and logged a stack trace.
        ResponseEntity<String> response = rest.getForEntity("/main-DEADBEEF.js", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("qvs.ac.zw/problems/not-found");
    }

    @Test
    @DisplayName("the wrong method is 405")
    void wrongMethodIsNotAllowed() {
        ResponseEntity<String> response = rest.exchange(
                "/public/v1/verify/anything", HttpMethod.DELETE, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("qvs.ac.zw/problems/method-not-allowed");
    }

    @Test
    @DisplayName("the wrong content type is 415")
    void wrongContentTypeIsUnsupported() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("email=x", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    @DisplayName("a body that is not JSON is 400")
    void unparseableBodyIsBadRequest() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = rest.exchange("/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>("{ this is not json", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("errors are problem documents, never the console's HTML")
    void errorsAreNotHtml() {
        // The forwarding rules are enumerated rather than a catch-all precisely so that an API
        // client gets a problem document. Handing JSON tooling a web page to parse is a
        // miserable thing to debug, and it is what a blanket /** forward would do.
        //
        // Asked of a public path, because that is the one an unauthenticated client reaches
        // through to MVC -- behind the API's authentication the answer is an empty 401, which
        // proves nothing about the body's media type.
        ResponseEntity<String> response =
                rest.getForEntity("/public/v1/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain("<app-root>");
        assertThat(response.getHeaders().getContentType())
                .hasToString(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    }

    @Test
    @DisplayName("a domain refusal reaches the client as 422, not as the catch-all's 500")
    void moduleAdviceOutranksTheCatchAll() {
        // CredentialExceptionHandler turns a RegistrationRejected into a 422 explaining what
        // was wrong. It and the shared ApiExceptionHandler both sat at LOWEST_PRECEDENCE,
        // which is a tie and not an ordering: the catch-all won, and every domain refusal
        // reached the client as "the request could not be completed" with a stack trace in the
        // operator's log. Ordering only the catch-all last, as was done first, orders one end
        // of a tie.
        //
        // This needs a real token, because an unauthenticated request is refused before any
        // advice runs -- which is exactly why the first version of this test passed while the
        // bug was still there.
        String provider = "PR-ADV" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        String body = """
                {"name":"Advice Ordering University","country":"ZW",
                 "providerNumber":"%s","accreditedUntil":"2032-12-31"}
                """.formatted(provider);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(adminToken());

        assertThat(rest.exchange("/api/v1/institutions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class).getStatusCode())
                .as("the first one is admitted")
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = rest.exchange("/api/v1/institutions", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(duplicate.getStatusCode())
                .as("a duplicate provider number is a refusal the caller can act on")
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(duplicate.getBody())
                .as("and it says which institution already holds the number")
                .contains("Advice Ordering University");
    }

    /**
     * A real administrator token, minted in process rather than by signing in over HTTP.
     *
     * <p>The account has to exist: issuing a token also stores a refresh token, and that row
     * has a foreign key to {@code app_user}. A fabricated identity got as far as the insert and
     * failed there, which is the database doing its job.
     */
    private String adminToken() {
        UUID id = UUID.randomUUID();
        // The schema refuses an enrolled writer with no secret, independently of the login
        // flow -- so even a row inserted by a test cannot sidestep the MFA rule. Supplied
        // rather than worked around.
        jdbc.update("""
                INSERT INTO app_user
                    (id, email, display_name, role, password_hash, mfa_secret, mfa_enrolled)
                VALUES (?, ?, 'Advice Test', 'ADMIN', 'unused-hash', 'UNUSED-TEST-SECRET', true)
                """, id, "advice-test-" + id + "@qvs.ac.zw");

        return tokens.issue(new UserAccount(id, "advice-test-" + id + "@qvs.ac.zw", "Advice Test",
                Role.ADMIN, null, "unused-hash", null, true, false)).accessToken();
    }

    @Test
    @DisplayName("an unknown API path is 401 to a stranger, not 404")
    void unknownApiPathDoesNotMapTheApi() {
        // Deliberate: `anyRequest().authenticated()` runs before dispatch, so an anonymous
        // caller cannot tell a route that exists from one that does not. Enumerating the API
        // is a reconnaissance step, and there is no reason to make it free.
        ResponseEntity<String> response = rest.getForEntity("/api/v1/does-not-exist", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
