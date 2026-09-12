package zw.ac.qvs.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import zw.ac.qvs.credential.application.AddQualification;
import zw.ac.qvs.credential.application.AtomicRegistration;
import zw.ac.qvs.credential.application.IssueSigningKey;
import zw.ac.qvs.credential.application.OnboardInstitution;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.identity.application.TokenIssuer;
import zw.ac.qvs.identity.domain.Role;
import zw.ac.qvs.identity.domain.UserAccount;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The wall between one institution and another, over HTTP.
 *
 * <p>A registrar acts for exactly one institution. That was true of the two places that took an
 * institution from the token — issuing a credential, and listing qualifications — and quietly
 * untrue of the three that took a <b>serial</b>: reading one credential, revoking it, and
 * minting a public share token for it. Each of those checked that the caller was a registrar and
 * stopped there. Every registrar is a registrar.
 *
 * <p>It matters because serials are guessable by construction. {@code ZW-PR0142-2026-000001}
 * names the institution's provider number and counts from one, so anybody with any registrar
 * account could read another university's awards in full, withdraw them — with the ledger
 * recording the wronged institution as the actor — or publish them through a share link.
 *
 * <p>The other half of this class is the order of operations an administrator works in: admit
 * the institution, then create the people who act for it. A user naming an institution that is
 * not in the register is refused with a sentence, not a foreign-key violation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement({"FR-04", "FR-06", "FR-07", "FR-10"})
class InstitutionBoundaryIT extends PostgresIntegrationTest {

    private static final UUID ADMIN_ACTOR = UUID.randomUUID();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private OnboardInstitution onboardInstitution;

    @Autowired
    private IssueSigningKey issueSigningKey;

    @Autowired
    private AddQualification addQualification;

    @Autowired
    private AtomicRegistration registration;

    @Autowired
    private TokenIssuer tokens;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    /** Two universities, each with a key, a qualification and one award of its own. */
    private Institution ours;
    private Institution theirs;
    private Credential ourCredential;
    private Credential theirCredential;
    private String ourRegistrar;

    @BeforeEach
    void twoUniversities() {
        ours = admitted("Our University");
        theirs = admitted("Their University");
        ourCredential = award(ours);
        theirCredential = award(theirs);
        ourRegistrar = tokenFor(Role.REGISTRAR, ours.id());
    }

    private Institution admitted(String name) {
        Institution institution = transactions.execute(status -> onboardInstitution.onboard(
                new OnboardInstitution.Command(name + " " + UUID.randomUUID(), "ZW",
                        "PR-B" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(),
                        LocalDate.now().plusYears(5), ADMIN_ACTOR)));
        transactions.execute(status ->
                issueSigningKey.issue(institution.id(), LocalDate.now().minusYears(1),
                        ADMIN_ACTOR));
        return institution;
    }

    private Credential award(Institution institution) {
        var qualification = transactions.execute(status -> addQualification.add(
                new AddQualification.Command(institution.id(), "BSc Computer Science", 7, 360,
                        null, ADMIN_ACTOR, "REGISTRAR")));

        return registration.registerInOwnTransaction(new RegisterCredential.Command(
                institution.id(), qualification.id(), nationalId(), "A. Person",
                LocalDate.of(1992, 2, 2), LocalDate.now().minusMonths(1), ADMIN_ACTOR));
    }

    /** A well-formed Zimbabwean identity number, distinct on every call. */
    private static String nationalId() {
        return String.format("63-%07d K 42", (int) (Math.random() * 10_000_000));
    }

    private String tokenFor(Role role, UUID institutionId) {
        UUID id = UUID.randomUUID();
        String email = "boundary-" + id + "@qvs.ac.zw";
        jdbc.update("""
                INSERT INTO app_user
                    (id, email, display_name, role, institution_id, password_hash,
                     mfa_secret, mfa_enrolled)
                VALUES (?, ?, 'Boundary Subject', ?, ?, 'unused-hash', 'UNUSED-TEST-SECRET', true)
                """, id, email, role.name(), institutionId);

        return tokens.issue(new UserAccount(id, email, "Boundary Subject", role, institutionId,
                "unused-hash", null, true, false)).accessToken();
    }

    private ResponseEntity<String> get(String path, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, String body, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(bearer);
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private boolean revokedInDatabase(Credential credential) {
        String status = jdbc.queryForObject("SELECT status FROM credential WHERE serial = ?",
                String.class, credential.serial().value());
        return "REVOKED".equals(status);
    }

    @Test
    @DisplayName("a registrar reads their own institution's credential")
    void readsOwn() {
        ResponseEntity<String> response =
                get("/api/v1/credentials/" + ourCredential.serial().value(), ourRegistrar);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(ourCredential.serial().value());
    }

    @Test
    @DisplayName("and cannot read another institution's, which is absent rather than forbidden")
    void cannotReadAnother() {
        ResponseEntity<String> response =
                get("/api/v1/credentials/" + theirCredential.serial().value(), ourRegistrar);

        // 404 and not 403: a serial is guessable, and "forbidden" would confirm it exists.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // Asserted as "nothing came back" rather than "the name is absent": the reply carries
        // no body at all, so there is nothing to search, and a search that passes because the
        // body is null is a test that would keep passing if a body appeared.
        assertThat(response.getBody()).isNull();
    }

    @Test
    @DisplayName("an unknown serial answers exactly as another institution's does")
    void unknownAndForeignAreIndistinguishable() {
        ResponseEntity<String> foreign =
                get("/api/v1/credentials/" + theirCredential.serial().value(), ourRegistrar);
        ResponseEntity<String> unknown =
                get("/api/v1/credentials/ZW-PR9999-2026-000999", ourRegistrar);

        assertThat(foreign.getStatusCode()).isEqualTo(unknown.getStatusCode());
        assertThat(foreign.getBody()).isEqualTo(unknown.getBody());
    }

    @Test
    @DisplayName("a registrar cannot revoke another institution's award")
    void cannotRevokeAnother() {
        String body = """
                {"reason":"ISSUED_IN_ERROR","note":"not mine to withdraw"}""";

        ResponseEntity<String> response = post(
                "/api/v1/credentials/" + theirCredential.serial().value() + "/revoke",
                body, ourRegistrar);

        assertThat(response.getStatusCode().is2xxSuccessful())
                .as("withdrawing somebody else's award is refused")
                .isFalse();
        assertThat(revokedInDatabase(theirCredential))
                .as("and their award still stands")
                .isFalse();
    }

    @Test
    @DisplayName("the refused revocation is not recorded against the wronged institution")
    void refusedRevocationIsNotAudited() {
        Long before = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'CREDENTIAL_REVOKED'",
                Long.class);

        post("/api/v1/credentials/" + theirCredential.serial().value() + "/revoke",
                """
                {"reason":"ISSUED_IN_ERROR","note":"not mine"}""", ourRegistrar);

        Long after = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'CREDENTIAL_REVOKED'",
                Long.class);
        assertThat(after).isEqualTo(before);
    }

    @Test
    @DisplayName("a registrar can still revoke their own")
    void revokesOwn() {
        ResponseEntity<String> response = post(
                "/api/v1/credentials/" + ourCredential.serial().value() + "/revoke",
                """
                {"reason":"ISSUED_IN_ERROR","note":"ours to withdraw"}""", ourRegistrar);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(revokedInDatabase(ourCredential)).isTrue();
    }

    @Test
    @DisplayName("a registrar cannot mint a public share token for another institution's award")
    void cannotShareAnother() {
        // The worst of the three: a share token is a door anybody can walk through without an
        // account, so this would publish another university's record on their behalf.
        ResponseEntity<String> response = post(
                "/api/v1/credentials/" + theirCredential.serial().value() + "/share",
                """
                {"ttlDays":30,"label":"not mine to publish"}""", ourRegistrar);

        assertThat(response.getStatusCode().is2xxSuccessful()).isFalse();

        Long minted = jdbc.queryForObject(
                "SELECT count(*) FROM share_token WHERE credential_id = ?", Long.class,
                theirCredential.id());
        assertThat(minted).isZero();
    }

    @Test
    @DisplayName("and can still mint one for their own")
    void sharesOwn() {
        ResponseEntity<String> response = post(
                "/api/v1/credentials/" + ourCredential.serial().value() + "/share",
                """
                {"ttlDays":30,"label":"ours to publish"}""", ourRegistrar);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("token");
    }

    @Test
    @DisplayName("an auditor still reads across institutions, which is what an auditor is for")
    void auditorIsNotNarrowed() {
        String auditor = tokenFor(Role.AUDITOR, null);

        assertThat(get("/api/v1/credentials/" + ourCredential.serial().value(), auditor)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get("/api/v1/credentials/" + theirCredential.serial().value(), auditor)
                .getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an administrator cannot record a qualification; that is the registrar's act")
    void administratorsDoNotDecideWhatIsOffered() {
        String admin = tokenFor(Role.ADMIN, null);
        String body = """
                {"title":"BA Administration","nqfLevel":7,"credits":360}""";

        assertThat(post("/api/v1/qualifications", body, admin).getStatusCode())
                .as("admitting an institution is administration; what it awards is not")
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(post("/api/v1/qualifications", body, ourRegistrar).getStatusCode())
                .as("a registrar records one for their own institution")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("a registrar's new qualification lands on their own institution")
    void qualificationLandsOnTheCallersInstitution() {
        post("/api/v1/qualifications", """
                {"title":"BSc Scoped To Us","nqfLevel":8,"credits":480}""", ourRegistrar);

        UUID owner = jdbc.queryForObject(
                "SELECT institution_id FROM qualification WHERE title = 'BSc Scoped To Us'",
                UUID.class);
        assertThat(owner)
                .as("taken from the token, with no field in the request to say otherwise")
                .isEqualTo(ours.id());
    }

    @Test
    @DisplayName("an account cannot name an institution that has not been admitted")
    void institutionMustBeAdmittedBeforeItsPeople() {
        String admin = tokenFor(Role.ADMIN, null);
        String body = """
                {"email":"nobody@nowhere.ac.zw","displayName":"Nobody",
                 "role":"REGISTRAR","institutionId":"%s"}""".formatted(UUID.randomUUID());

        ResponseEntity<String> response = post("/api/v1/users", body, admin);

        // A sentence, not a foreign-key violation surfacing as a 500.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("Admit the institution first");
    }

    @Test
    @DisplayName("and can name one that has")
    void admittedInstitutionIsAccepted() {
        String admin = tokenFor(Role.ADMIN, null);
        String body = """
                {"email":"new-registrar-%s@ours.ac.zw","displayName":"New Registrar",
                 "role":"REGISTRAR","institutionId":"%s"}"""
                .formatted(UUID.randomUUID().toString().substring(0, 8), ours.id());

        assertThat(post("/api/v1/users", body, admin).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }
}
