package zw.ac.qvs.verification.adapter.in;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import zw.ac.qvs.shared.adapter.QvsProperties;
import zw.ac.qvs.shared.domain.Hashing;
import zw.ac.qvs.verification.application.ProduceVerificationReport;
import zw.ac.qvs.verification.application.ReportKeys;
import zw.ac.qvs.verification.application.VerifyCredential;

/**
 * The only unauthenticated data path in the system.
 *
 * <p>Everything about this class is shaped by the fact that strangers can reach it, and some
 * of them are hostile.
 *
 * <ul>
 *   <li>There is <b>one route</b>, and it takes a share token. No route accepts a serial, a
 *       name or an identifier, so the register cannot be browsed or enumerated.</li>
 *   <li>Every outcome returns <b>200</b>. A 404 for an unknown token and a 200 for a known one
 *       would make the endpoint an existence oracle answerable with a status code alone.</li>
 *   <li>The not-found body is <b>identical</b> for an unknown token, an expired token, a
 *       withdrawn token and a credential that failed an integrity check.</li>
 *   <li>Nothing about the holder is returned beyond initials (NFR-06).</li>
 *   <li>Responses are marked <b>no-store</b>, so a shared or proxied browser does not retain
 *       somebody else's verification result.</li>
 * </ul>
 */
@RestController
@RequestMapping("/public/v1/verify")
@Tag(name = "Public verification",
        description = "Check one credential using a share token the holder gave you")
public class PublicVerificationController {

    private final VerifyCredential verifyCredential;
    private final ProduceVerificationReport produceReport;
    private final ReportKeys reportKeys;
    private final VerificationMetrics metrics;
    private final String ipSalt;

    public PublicVerificationController(
            VerifyCredential verifyCredential,
            ProduceVerificationReport produceReport,
            ReportKeys reportKeys,
            VerificationMetrics metrics,
            QvsProperties properties) {
        this.verifyCredential = verifyCredential;
        this.produceReport = produceReport;
        this.reportKeys = reportKeys;
        this.metrics = metrics;
        this.ipSalt = properties.crypto().ipSalt();
    }

    /**
     * Verifies a credential.
     *
     * @param token   the share token
     * @param request the servlet request, for the caller's address
     * @return exactly one of valid, revoked or not-found
     */
    @GetMapping(value = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    // A verification is a write: it appends to the ledger and records the attempt. The
    // ledger port declares Propagation.MANDATORY precisely so that forgetting this
    // annotation fails loudly on the first request rather than silently recording nothing.
    @Transactional
    @Operation(summary = "Verify a credential from a share token",
            description = "Returns VALID, REVOKED or NOT_FOUND. Unknown, expired and withdrawn "
                    + "tokens are indistinguishable in the response.")
    public ResponseEntity<VerificationResponse> verify(
            @PathVariable String token, HttpServletRequest request) {

        var outcome = verifyCredential.verify(
                token, VerifyCredential.Context.web(hashedClientAddress(request)));
        metrics.verificationCompleted(outcome);

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(VerificationResponse.from(outcome));
    }

    /**
     * FR-12: the same check, as a document the verifier keeps.
     *
     * <p>This runs a real verification rather than formatting an earlier one, so the report
     * always describes a check that actually happened and cites its own ledger entry. It is
     * therefore a write, like the JSON route, and is rate-limited by the same filter.
     *
     * <p>The response is an attachment. A PDF rendered inline in a browser tab is a PDF nobody
     * keeps, and the entire point of this route is the keeping.
     *
     * @param token   the share token
     * @param request the servlet request, for the caller's address
     * @return a signed PDF report of the verification
     */
    @GetMapping(value = "/{token}/report", produces = MediaType.APPLICATION_PDF_VALUE)
    @Transactional
    @Operation(summary = "Download a signed report of this verification",
            description = "Runs the check and returns a PDF stating the verdict, when it ran, "
                    + "the audit reference, and the key the signature was checked against. The "
                    + "exact signed bytes are in the file's document properties.")
    public ResponseEntity<byte[]> report(
            @PathVariable String token, HttpServletRequest request) {

        var rendered = produceReport.produce(
                token, VerifyCredential.Context.web(hashedClientAddress(request)));
        metrics.reportProduced();

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition",
                        "attachment; filename=\"" + rendered.filename() + "\"")
                .contentType(MediaType.parseMediaType(rendered.mediaType()))
                .body(rendered.document());
    }

    /**
     * The public key reports are signed with.
     *
     * <p>Published because a signature nobody can check is decoration. It is served from the
     * public path deliberately: the person most in need of checking a report is the one who
     * does not take this system's word for anything, and requiring them to hold an account
     * here in order to do so would defeat the purpose.
     *
     * <p>This is cacheable, unlike everything else on this controller. It changes only when the
     * key is rotated, and a verifier fetching it on every check would be needless traffic.
     *
     * @return the report-signing public key, as a JWK
     */
    @GetMapping(value = "/report-key", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The public key that verification reports are signed with",
            description = "An Ed25519 public key as a JWK. Use it to check the detached JWS "
                    + "stored in a report's document properties.")
    public ResponseEntity<String> reportKey() {
        return ResponseEntity.ok()
                .header("Cache-Control", "public, max-age=3600")
                .body(reportKeys.publicJwk());
    }

    /**
     * Hashes the caller's address before it goes anywhere near storage.
     *
     * <p>Salted, so the stored value cannot be reversed by hashing the whole IPv4 space — four
     * billion entries is an afternoon's work, and an unsalted hash of an IP address is
     * therefore not an anonymisation at all. What survives is enough to notice one address
     * checking two hundred credentials, and not enough to build a history of who looked at
     * whom.
     */
    private String hashedClientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String address = forwarded != null && !forwarded.isBlank()
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return address == null || address.isBlank() ? null : Hashing.sha256Hex(ipSalt + address);
    }
}
