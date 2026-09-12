package zw.ac.qvs.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.Ed25519Verifier;
import com.nimbusds.jose.jwk.OctetKeyPair;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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

/**
 * FR-12 against a running server, asserted on the bytes the verifier actually receives.
 *
 * <p>The interesting assertions here are not that a PDF comes back. They are that the signature
 * in it verifies against a key published on the public path, that altering the statement breaks
 * it, and that the document discloses no more than the JSON route does. A report is a document
 * that leaves this system and gets filed, mailed and attached to disputes; whatever is in it is
 * out for good.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Requirement({"FR-12", "NFR-06"})
class VerificationReportIT extends PostgresIntegrationTest {

    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");
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

    private String serial;
    private String shareToken;
    private String institutionKeyId;

    @BeforeEach
    void registerAndShare() {
        jdbc.execute("TRUNCATE credential, holder, qualification, app_user, serial_counter "
                + "CASCADE");

        jdbc.update("""
                INSERT INTO institution (id, name, country, provider_no, accredited_until)
                VALUES (?, 'Example University', 'ZW', 'PR-0142', DATE '2030-12-31')
                ON CONFLICT (id) DO NOTHING
                """, INSTITUTION);

        var key = keyVault.currentKeyFor(INSTITUTION)
                .orElseGet(() -> keyVault.rotate(INSTITUTION, LocalDate.of(2020, 1, 1)));
        institutionKeyId = key.kid();
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

        shareToken = transactions.execute(status ->
                issueShareToken.issue(serial, 30, "report test", null, null)).secret();
    }

    private ResponseEntity<byte[]> downloadReport(String token) {
        return rest.getForEntity("/public/v1/verify/" + token + "/report", byte[].class);
    }

    /** The visible page, as text. */
    private static String pageText(byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    /** A value from the document properties, which is where the signed copy lives. */
    private static String metadata(byte[] pdf, String key) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getDocumentInformation().getCustomMetadataValue(key);
        }
    }

    @Test
    @DisplayName("the report is a PDF, delivered as an attachment")
    void isAPdfAttachment() {
        ResponseEntity<byte[]> response = downloadReport(shareToken);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).hasToString("application/pdf");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .startsWith("attachment;")
                .contains("qvs-verification-");

        // %PDF- is the file's magic number. Asserting it means a body that merely claims to be
        // a PDF in a header does not pass.
        assertThat(new String(response.getBody(), 0, 5, StandardCharsets.UTF_8)).isEqualTo("%PDF-");
    }

    @Test
    @DisplayName("FR-12: the page carries the serial, verdict, time, audit reference and key id")
    void carriesEverythingTheCriterionNames() throws Exception {
        String text = pageText(downloadReport(shareToken).getBody());

        assertThat(text).contains(serial);
        // The headline, in the words the reader sees. Asserting "VALID" would have passed on
        // the explanatory paragraph below it, which names the verdict code -- so the test would
        // have stayed green through any change to the sentence a verifier actually reads.
        assertThat(text).contains("This Certificate is Valid");
        assertThat(text).contains("2026");
        assertThat(text).contains("Audit reference");
        assertThat(text).contains(institutionKeyId);

        // And enough for the reader to know what was checked, not only that something was.
        assertThat(text).contains("BSc Computer Science", "Example University");
    }

    @Test
    @DisplayName("the signature verifies against the published key")
    void signatureVerifies() throws Exception {
        byte[] pdf = downloadReport(shareToken).getBody();

        String statement = metadata(pdf, "QVS-Signed-Statement");
        String detachedJws = metadata(pdf, "QVS-Detached-JWS");
        assertThat(statement).isNotBlank();
        assertThat(detachedJws).isNotBlank();

        // Fetched the way an outsider would: from the public path, with no account.
        String jwk = rest.getForObject("/public/v1/verify/report-key", String.class);
        OctetKeyPair publicKey = OctetKeyPair.parse(jwk).toPublicJWK();

        JWSObject jws = JWSObject.parse(
                detachedJws, new Payload(statement.getBytes(StandardCharsets.UTF_8)));

        assertThat(jws.verify(new Ed25519Verifier(publicKey)))
                .as("a report nobody can check is decoration")
                .isTrue();
        assertThat(jws.getHeader().getKeyID()).isEqualTo(metadata(pdf, "QVS-Report-Key-Id"));
    }

    @Test
    @DisplayName("editing the statement breaks the signature")
    void tamperingIsDetected() throws Exception {
        byte[] pdf = downloadReport(shareToken).getBody();

        String statement = metadata(pdf, "QVS-Signed-Statement");
        String detachedJws = metadata(pdf, "QVS-Detached-JWS");

        // The single most attractive edit: turn a revoked or unknown answer into a good one.
        String forged = statement.replace("\"vdt\":\"VALID\"", "\"vdt\":\"REVOKED\"");
        assertThat(forged).isNotEqualTo(statement);

        OctetKeyPair publicKey = OctetKeyPair.parse(
                rest.getForObject("/public/v1/verify/report-key", String.class)).toPublicJWK();

        JWSObject tampered = JWSObject.parse(
                detachedJws, new Payload(forged.getBytes(StandardCharsets.UTF_8)));

        assertThat(tampered.verify(new Ed25519Verifier(publicKey))).isFalse();
    }

    @Test
    @DisplayName("the published key is the public half only")
    void publishesNoPrivateMaterial() {
        String jwk = rest.getForObject("/public/v1/verify/report-key", String.class);

        assertThat(jwk).contains("\"kty\":\"OKP\"", "\"crv\":\"Ed25519\"", "\"x\":");
        // "d" is the private scalar. Publishing it would let anyone sign reports as this
        // system, which is the whole game.
        assertThat(jwk).doesNotContain("\"d\":");
    }

    @Test
    @DisplayName("NFR-06: the report discloses no name and no national ID")
    void disclosesNoIdentity() throws Exception {
        byte[] pdf = downloadReport(shareToken).getBody();

        // Against the whole file, not the extracted text: metadata, streams and all. A name
        // sitting in the document properties is just as disclosed as one printed on the page.
        String everything = new String(pdf, StandardCharsets.ISO_8859_1);
        assertThat(everything).doesNotContain("Zwelibanzi", "Ntshangase", NATIONAL_ID);

        assertThat(pageText(pdf)).contains("Z.Q.N.");
    }

    @Test
    @DisplayName("a token matching nothing yields a report that describes nothing")
    void notFoundReportDescribesNothing() throws Exception {
        byte[] pdf = downloadReport("a-token-that-matches-nothing").getBody();

        String text = pageText(pdf);
        // The words a verifier reads. The verdict code the signature covers is asserted
        // separately below -- the two are allowed to differ, and do.
        assertThat(text).contains("No Certificate was Found");
        assertThat(text).doesNotContain(serial, "BSc Computer Science", "Example University");

        // And the signed statement is likewise empty of them, so there is nothing to leak even
        // to someone reading the file rather than the page.
        assertThat(metadata(pdf, "QVS-Signed-Statement"))
                .contains("\"vdt\":\"NOT_FOUND\"")
                .doesNotContain("\"ser\"", "\"ins\"", "\"qua\"", "\"hld\"", "\"kid\"");
    }

    @Test
    @DisplayName("a revoked credential says so, inside the signature")
    void revokedReportStatesTheWithdrawal() throws Exception {
        jdbc.update("""
                UPDATE credential SET status = 'REVOKED', revoked_at = now(),
                       revoked_reason = 'ISSUED_IN_ERROR'
                WHERE serial = ?
                """, serial);

        byte[] pdf = downloadReport(shareToken).getBody();

        assertThat(pageText(pdf)).contains("This Certificate has been Withdrawn",
                "ISSUED_IN_ERROR");
        assertThat(metadata(pdf, "QVS-Signed-Statement"))
                .contains("\"vdt\":\"REVOKED\"", "\"rea\":\"ISSUED_IN_ERROR\"");
    }

    @Test
    @DisplayName("downloading a report is itself recorded, and each one cites its own check")
    void everyReportIsAudited() throws Exception {
        Long before = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'VERIFICATION_RUN'", Long.class);

        String first = metadata(downloadReport(shareToken).getBody(), "QVS-Signed-Statement");
        String second = metadata(downloadReport(shareToken).getBody(), "QVS-Signed-Statement");

        Long after = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE action = 'VERIFICATION_RUN'", Long.class);

        // A report describes a check that actually ran, so producing one appends to the ledger.
        assertThat(after - before).isEqualTo(2);

        // And the two reports are distinguishable: each cites the entry recording its own
        // check, which is what lets an institution trace a disputed report back to a moment.
        assertThat(first).isNotEqualTo(second);
    }
}
