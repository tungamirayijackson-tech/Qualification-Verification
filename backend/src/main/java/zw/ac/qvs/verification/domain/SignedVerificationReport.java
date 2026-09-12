package zw.ac.qvs.verification.domain;

/**
 * A verification report with this system's own signature over it.
 *
 * <p>The signature is detached, in the same form and for the same reason as a credential's: the
 * bytes it covers are {@link VerificationReport#canonicalBytes()}, of which there is exactly one
 * copy, so there is no second copy to drift away from the first.
 *
 * <p>Note whose signature this is. A credential carries the <em>institution's</em> signature,
 * asserting that it conferred an award. This carries <em>QVS's</em>, asserting only that it ran
 * a check and got a particular answer. Conflating the two would be a serious misrepresentation:
 * this system is not in a position to vouch for a qualification, and a report that looked as
 * though it were would be worse than no report.
 *
 * @param report      the statement
 * @param reportKeyId id of the key that signed it, so the signature can be checked after rotation
 * @param detachedJws the compact detached JWS: protected header, empty payload, signature
 */
public record SignedVerificationReport(
        VerificationReport report, String reportKeyId, String detachedJws) {

    public SignedVerificationReport {
        if (report == null) {
            throw new IllegalArgumentException("there is no signature without a statement");
        }
        if (reportKeyId == null || reportKeyId.isBlank()) {
            throw new IllegalArgumentException(
                    "a signature that does not say which key made it cannot be checked");
        }
        if (detachedJws == null || detachedJws.isBlank()) {
            throw new IllegalArgumentException("detachedJws is required");
        }
    }
}
