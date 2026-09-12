package zw.ac.qvs.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import zw.ac.qvs.credential.application.AtomicRegistration;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;
import zw.ac.qvs.verification.application.IssueShareToken;
import zw.ac.qvs.verification.application.KeyVault;
import zw.ac.qvs.verification.application.VerificationReadModel;

/**
 * NFR-06, asserted against the bytes that actually leave the server.
 *
 * <p>This is a data-minimisation guarantee, and the only way to check one honestly is to look
 * at the raw response. Asserting on a DTO's fields proves the DTO is shaped correctly and
 * nothing about what Jackson serialised; a field added later, an {@code @JsonAnyGetter}, a
 * changed inclusion rule, or a nested object pulled in for convenience would all slip past it.
 * So these tests search the response body as text for values that must never appear.
 *
 * <p>The holder's name and national ID are both <em>signed</em> — they are part of the
 * statement the institution vouches for — which is precisely why the disclosure boundary needs
 * its own test. The data exists, the server knows it, and nothing but this rule stops it being
 * returned.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement({"NFR-06", "FR-04"})
class PublicDisclosureIT extends PostgresIntegrationTest {

    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");

    /** Distinctive enough that a substring search cannot match by accident. */
    private static final String HOLDER_NAME = "Zwelibanzi Q. Ntshangase";
    private static final String NATIONAL_ID = "44-4567890R31";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KeyVault keyVault;

    @Autowired
    private AtomicRegistration registration;

    @Autowired
    private IssueShareToken issueShareToken;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private VerificationReadModel readModel;

    private String serial;
    private String shareToken;

    @BeforeEach
    void registerAndShare() {
        jdbc.execute("TRUNCATE credential, holder, qualification, app_user, serial_counter "
                + "CASCADE");

        jdbc.update("""
                INSERT INTO institution (id, name, country, provider_no, accredited_until)
                VALUES (?, 'Example University', 'ZW', 'PR-0142', DATE '2030-12-31')
                ON CONFLICT (id) DO NOTHING
                """, INSTITUTION);

        // The vault refuses to overwrite private key material, so an existing key is reused
        // rather than rotated over.
        var key = keyVault.currentKeyFor(INSTITUTION)
                .orElseGet(() -> keyVault.rotate(INSTITUTION, LocalDate.of(2020, 1, 1)));
        jdbc.update("UPDATE institution SET active_key_id = ? WHERE id = ?",
                key.kid(), INSTITUTION);

        UUID qualificationId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qualification (id, institution_id, title, nqf_level, credits, status)
                VALUES (?, ?, 'BSc Computer Science', 7, 360, 'ACTIVE')
                """, qualificationId, INSTITUTION);

        Credential credential = registration.registerInOwnTransaction(
                new RegisterCredential.Command(INSTITUTION, qualificationId, NATIONAL_ID,
                        HOLDER_NAME, LocalDate.of(1991, 5, 12), LocalDate.of(2026, 4, 11), null));
        serial = credential.serial().value();

        // Minting appends to the ledger, which requires a transaction.
        shareToken = transactions.execute(status ->
                issueShareToken.issue(serial, 30, "disclosure test", null, null)).secret();
    }

    private ResponseEntity<String> verifyPublicly(String token) {
        return rest.getForEntity("/public/v1/verify/" + token, String.class);
    }

    @Test
    @DisplayName("a valid public verification discloses no name and no national ID")
    void validResponseIsMinimal() {
        ResponseEntity<String> response = verifyPublicly(shareToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull().contains("\"verdict\":\"VALID\"");

        // The whole of NFR-06 in four assertions, against the raw bytes.
        assertThat(body).doesNotContain(HOLDER_NAME);
        assertThat(body).doesNotContain("Zwelibanzi");
        assertThat(body).doesNotContain("Ntshangase");
        assertThat(body).doesNotContain(NATIONAL_ID);
    }

    @Test
    @DisplayName("and discloses no date of birth or internal identifier")
    void noInternalIdentifiersLeak() {
        String body = verifyPublicly(shareToken).getBody();

        assertThat(body).isNotNull();
        assertThat(body).doesNotContain("1991-05-12");
        // Nothing that would let a caller address the record by anything but their own token.
        assertThat(body).doesNotContain("credentialId");
        assertThat(body).doesNotContain("holderId");
        assertThat(body).doesNotContain("nationalIdHash");
        assertThat(body).doesNotContain("payloadCanonical");
        assertThat(body).doesNotContain("detachedJws");
    }

    @Test
    @DisplayName("discloses initials, which is the most it may")
    void disclosesInitialsOnly() {
        String body = verifyPublicly(shareToken).getBody();

        assertThat(body).contains("\"holderInitials\":\"Z.Q.N.\"");
    }

    @Test
    @DisplayName("the not-found path returns the verdict and nothing else")
    void notFoundLeaksNothing() {
        // An unknown, an expired and a withdrawn token must be indistinguishable, so this body
        // must carry nothing that could vary between them.
        ResponseEntity<String> response = verifyPublicly("a-token-that-matches-nothing");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();

        assertThat(body).isNotNull().contains("\"verdict\":\"NOT_FOUND\"");
        assertThat(body).doesNotContain("institution");
        assertThat(body).doesNotContain("qualification");
        assertThat(body).doesNotContain("checks");
        assertThat(body).doesNotContain("holderInitials");
        assertThat(body).doesNotContain("ledgerSeq");
    }

    @Test
    @DisplayName("the public path never reveals whether a serial exists")
    void serialIsNotAnEntryPoint() {
        // Decision 09-A: there is no route that takes a serial, so knowing one buys nothing.
        ResponseEntity<String> bySerial = verifyPublicly(serial);

        assertThat(bySerial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bySerial.getBody()).contains("\"verdict\":\"NOT_FOUND\"");
    }

    @Test
    @DisplayName("the console detail path is unreachable without a token")
    void consoleDetailRequiresAuthentication() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/credentials/" + serial, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("FR-04: one credential in full, with everything the acceptance criterion names")
    void consoleDetailCarriesTheWholeRecord() {
        // FR-04 asks for institution, qualification, award date, signature status and
        // revocation status. Read through the same port the controller uses, so this asserts
        // the projection the console actually receives rather than a hand-built object.
        var detail = readModel.findBySerialForConsole(serial).orElseThrow();

        assertThat(detail.serial()).isEqualTo(serial);
        assertThat(detail.institutionName()).isEqualTo("Example University");
        assertThat(detail.qualificationTitle()).isEqualTo("BSc Computer Science");
        assertThat(detail.nqfLevel()).isEqualTo(7);
        assertThat(detail.awardedOn()).isEqualTo(LocalDate.of(2026, 4, 11));
        assertThat(detail.revoked()).isFalse();

        // The signature and the exact bytes it covers, so an auditor can verify it with their
        // own tooling instead of taking this system's word for it.
        assertThat(detail.detachedJws()).isNotBlank();
        assertThat(detail.payloadCanonical()).contains(serial);
        assertThat(detail.keyId()).isNotBlank();

        // The displayed holder field is initials: the projection never reads the encrypted
        // name column.
        assertThat(detail.holderInitials()).isEqualTo("Z.Q.N.");

        // But the full name IS present, inside the canonical payload — and that is unavoidable
        // rather than an oversight. The signature covers the holder's name, so publishing the
        // signature for independent verification means publishing what it signed. You cannot
        // hand somebody a detached signature and withhold the bytes it is over.
        //
        // The boundary this system actually draws is therefore between the public path, which
        // discloses neither the name nor the signed bytes, and the authenticated console, where
        // a registrar scoped to that institution or an auditor can already see the record. This
        // assertion pins that reading down so nobody later "fixes" the leak by stripping the
        // payload and quietly making independent verification impossible.
        assertThat(detail.payloadCanonical()).contains(HOLDER_NAME);
    }

    @Test
    @DisplayName("FR-04: an unknown serial is empty rather than an error")
    void unknownSerial() {
        assertThat(readModel.findBySerialForConsole("ZW-PR0142-2099-999999")).isEmpty();
        assertThat(readModel.findBySerialForConsole("")).isEmpty();
        assertThat(readModel.findBySerialForConsole(null)).isEmpty();
    }
}
