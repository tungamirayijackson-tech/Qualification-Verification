package zw.ac.qvs.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.DefaultResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import zw.ac.qvs.identity.application.PasswordHasher;
import zw.ac.qvs.identity.application.TokenIssuer;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Suspending, restoring and resetting an account, over HTTP and against the real schema.
 *
 * <p>Each of these closes a hole that was open until the endpoints existed. Nothing could
 * withdraw somebody's access, so a registrar who left kept signing credentials; nothing could
 * replace a lost password; nothing could clear a second factor whose device was gone, which
 * during development had to be repaired with SQL against the database — a repair that is not
 * available to an administrator using the system as built.
 *
 * <p>Two things are only testable here rather than in the unit tests: that the ledger's action
 * constraint actually accepts the four new values (a migration, not Java), and that revoking
 * sessions marks the rows it claims to mark.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement("FR-10")
class AccountLifecycleIT extends PostgresIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private PasswordHasher passwords;

    @Autowired
    private JdbcTemplate jdbc;

    private final RestTemplate signIn = signInTemplate();

    private static RestTemplate signInTemplate() {
        RestTemplate template = new RestTemplate(new JdkClientHttpRequestFactory());
        template.setErrorHandler(new DefaultResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    /**
     * An administrator who is not the only one, so the last-administrator guard is not what any
     * of these tests happens to be measuring.
     */
    private UserAccount anAdministrator() {
        UserAccount admin = insert(Role.ADMIN, null, true);
        insert(Role.ADMIN, null, true);
        return admin;
    }

    private UserAccount insert(Role role, UUID institutionId, boolean enrolled) {
        UUID id = UUID.randomUUID();
        String email = "lifecycle-" + id + "@qvs.ac.zw";
        // The schema refuses an enrolled writer with no secret, so a row inserted by a test
        // cannot sidestep the MFA rule either. Supplied rather than worked around.
        jdbc.update("""
                INSERT INTO app_user
                    (id, email, display_name, role, institution_id, password_hash,
                     mfa_secret, mfa_enrolled)
                VALUES (?, ?, 'Lifecycle Subject', ?, ?, ?, ?, ?)
                """, id, email, role.name(), institutionId,
                passwords.hash("their-original-password"),
                enrolled ? "UNUSED-TEST-SECRET" : null, enrolled);

        return new UserAccount(id, email, "Lifecycle Subject", role, institutionId,
                passwords.hash("their-original-password"),
                enrolled ? "UNUSED-TEST-SECRET" : null, enrolled, false);
    }

    private String tokenFor(UserAccount account) {
        return tokens.issue(account).accessToken();
    }

    private ResponseEntity<String> post(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(headers), String.class);
    }

    private boolean disabledInDatabase(UUID id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT disabled FROM app_user WHERE id = ?", Boolean.class,
                        id));
    }

    private long ledgerCount(String action, UUID subject) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = ? AND subject_ref = ?",
                Long.class, action, subject.toString());
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("suspending an account stops it signing in, and is recorded")
    void suspend() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.REGISTRAR, someInstitution(), false);

        ResponseEntity<String> response =
                post("/api/v1/users/" + target.id() + "/disable", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabledInDatabase(target.id())).isTrue();
        // The migration, not the enum: this fails if V12 did not widen the CHECK constraint.
        assertThat(ledgerCount("USER_DISABLED", target.id())).isEqualTo(1);

        ResponseEntity<String> signIn = login(target.email(), "their-original-password");
        assertThat(signIn.getStatusCode())
                .as("and the account it was done to can no longer sign in")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("suspending revokes the refresh tokens the account is holding")
    void suspendEndsSessions() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.AUDITOR, null, false);
        tokens.issue(target);
        tokens.issue(target);

        assertThat(liveRefreshTokens(target.id())).isEqualTo(2);

        post("/api/v1/users/" + target.id() + "/disable", tokenFor(admin));

        assertThat(liveRefreshTokens(target.id()))
                .as("a withdrawal of access that leaves them able to mint tokens is not one")
                .isZero();
    }

    @Test
    @DisplayName("the account is kept, because the ledger names it in what it did")
    void suspendKeepsTheRow() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.REGISTRAR, someInstitution(), false);

        post("/api/v1/users/" + target.id() + "/disable", tokenFor(admin));

        Long rows = jdbc.queryForObject("SELECT count(*) FROM app_user WHERE id = ?", Long.class,
                target.id());
        assertThat(rows).isEqualTo(1);
    }

    @Test
    @DisplayName("restoring gives the access back, without touching the password")
    void restore() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.AUDITOR, null, false);
        post("/api/v1/users/" + target.id() + "/disable", tokenFor(admin));

        ResponseEntity<String> response =
                post("/api/v1/users/" + target.id() + "/restore", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(disabledInDatabase(target.id())).isFalse();
        assertThat(login(target.email(), "their-original-password").getStatusCode())
                .as("what they had still works, so a return from leave is not a lockout")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a reset password is returned once, works, and kills the old one")
    void resetPassword() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.AUDITOR, null, false);

        ResponseEntity<String> response =
                post("/api/v1/users/" + target.id() + "/reset-password", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String issued = readPassword(response.getBody());
        assertThat(issued).isNotBlank();

        assertThat(login(target.email(), issued).getStatusCode())
                .as("the password an administrator reads out actually signs in")
                .isEqualTo(HttpStatus.OK);
        assertThat(login(target.email(), "their-original-password").getStatusCode())
                .as("and the one that was lost is dead")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("the reset password is stored only as a hash, and is in no ledger entry")
    void resetPasswordIsNeverStoredOrRecorded() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.AUDITOR, null, false);

        String issued = readPassword(
                post("/api/v1/users/" + target.id() + "/reset-password", tokenFor(admin))
                        .getBody());

        String stored = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?",
                String.class, target.id());
        assertThat(stored).startsWith("$2a$").isNotEqualTo(issued);

        // The ledger is readable by every auditor and exportable to CSV. A credential in it
        // would make the audit trail a more attractive target than the account it describes.
        Long leaked = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE subject_ref LIKE ?", Long.class,
                "%" + issued + "%");
        assertThat(leaked).isZero();
        assertThat(ledgerCount("USER_PASSWORD_RESET", target.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("resetting a second factor drops the secret, so the next sign-in enrols again")
    void resetSecondFactor() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.REGISTRAR, someInstitution(), true);

        ResponseEntity<String> response =
                post("/api/v1/users/" + target.id() + "/reset-mfa", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT mfa_secret, mfa_enrolled FROM app_user WHERE id = ?", target.id());
        assertThat(row.get("mfa_secret"))
                .as("a retained secret is one somebody may still hold on the lost device")
                .isNull();
        assertThat(row.get("mfa_enrolled")).isEqualTo(false);
        assertThat(ledgerCount("USER_MFA_RESET", target.id())).isEqualTo(1);

        // 428 is the enrolment demand: the account is asked to set up a new second factor.
        assertThat(login(target.email(), "their-original-password").getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
    }

    @Test
    @DisplayName("the last administrator who can sign in cannot be suspended")
    void theLastAdministratorIsProtected() {
        // Not a nicety. Creating an administrator requires an administrator, so a register with
        // none in it is one only somebody with database access can repair -- the position the
        // bootstrap exists to stop anybody being in.
        //
        // Reaching that state needs an actor who is an administrator and is not themselves the
        // last enabled one. A suspended administrator holding a token issued before they were
        // suspended is exactly that, and is not a contrivance: an access token is a signed JWT
        // that is not checked against the database on each request, so it outlives the
        // suspension by up to fifteen minutes. That trade is documented on TokenIssuer, and
        // this is what it looks like from the outside.
        jdbc.update("UPDATE app_user SET disabled = true WHERE role = 'ADMIN'");
        UserAccount remaining = insert(Role.ADMIN, null, true);
        UserAccount suspended = insert(Role.ADMIN, null, true);
        String tokenOutlivingSuspension = tokenFor(suspended);
        post("/api/v1/users/" + suspended.id() + "/disable", tokenFor(remaining));

        ResponseEntity<String> response = post(
                "/api/v1/users/" + remaining.id() + "/disable", tokenOutlivingSuspension);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("only administrator");
        assertThat(disabledInDatabase(remaining.id()))
                .as("the system is left with somebody who can administer it")
                .isFalse();
    }

    @Test
    @DisplayName("an administrator cannot suspend their own account")
    void selfSuspensionIsRefused() {
        UserAccount admin = anAdministrator();

        ResponseEntity<String> response =
                post("/api/v1/users/" + admin.id() + "/disable", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(disabledInDatabase(admin.id())).isFalse();
    }

    @Test
    @DisplayName("a refusal is a 422 with a sentence, not the catch-all's 500")
    void refusalsAreUnprocessable() {
        UserAccount admin = anAdministrator();
        UserAccount target = insert(Role.AUDITOR, null, false);

        // Never enrolled, so there is no second factor to clear.
        ResponseEntity<String> response =
                post("/api/v1/users/" + target.id() + "/reset-mfa", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("no second factor");
    }

    @Test
    @DisplayName("an account that does not exist is a 404, and changes nothing")
    void unknownAccountIsNotFound() {
        UserAccount admin = anAdministrator();

        ResponseEntity<String> response =
                post("/api/v1/users/" + UUID.randomUUID() + "/disable", tokenFor(admin));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a registrar cannot suspend anybody, including an administrator")
    void onlyAdministratorsMayDoAnyOfThis() {
        UserAccount admin = anAdministrator();
        UserAccount registrar = insert(Role.REGISTRAR, someInstitution(), true);
        String theirToken = tokenFor(registrar);

        for (String action : new String[] {"disable", "restore", "reset-password", "reset-mfa"}) {
            assertThat(post("/api/v1/users/" + admin.id() + "/" + action, theirToken)
                    .getStatusCode())
                    .as("a registrar calling %s", action)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(disabledInDatabase(admin.id())).isFalse();
    }

    /**
     * Signs in, reporting the status rather than throwing on it.
     *
     * <p>Neither {@code TestRestTemplate} nor a plain {@code HttpURLConnection}: both refuse
     * to hand back a 401 answer to a POST whose body they streamed, throwing instead. "The
     * sign-in was refused" is the assertion half these tests are making, so the status has to
     * survive the trip.
     */
    private ResponseEntity<String> login(String email, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"email":"%s","password":"%s"}""".formatted(email, password);
        return signIn.exchange(rest.getRootUri() + "/api/v1/auth/login", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private long liveRefreshTokens(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM refresh_token WHERE user_id = ? AND revoked_at IS NULL",
                Long.class, userId);
        return count == null ? 0 : count;
    }

    /** Reads {@code initialPassword} out of the response without a JSON library. */
    private static String readPassword(String body) {
        int at = body.indexOf("\"initialPassword\":\"");
        if (at < 0) {
            return "";
        }
        int from = at + "\"initialPassword\":\"".length();
        return body.substring(from, body.indexOf('"', from));
    }

    /** A registrar must be bound to one, so the tests that make one need an institution. */
    private UUID someInstitution() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO institution (id, name, country, provider_no, accredited_until)
                VALUES (?, ?, 'ZW', ?, DATE '2032-12-31')
                """, id, "Lifecycle University " + id,
                "PR-LC" + id.toString().substring(0, 6).toUpperCase());
        return id;
    }
}
