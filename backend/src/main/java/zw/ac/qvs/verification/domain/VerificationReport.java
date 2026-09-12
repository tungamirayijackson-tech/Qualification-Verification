package zw.ac.qvs.verification.domain;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * FR-12: the statement this system makes about one verification, and the exact bytes it signs.
 *
 * <p>A credential's own signature is the institution's statement that it conferred an award.
 * This is a different statement, by a different party: that at a particular moment, QVS checked
 * a particular token and reached a particular verdict, and recorded doing so at a particular
 * place in its ledger. A verifier keeps this because the verdict on a screen is gone the moment
 * the tab closes, and "the website said it was fine" is not evidence anybody can act on.
 *
 * <p>Canonicalisation follows {@link CredentialClaims} exactly — fixed alphabetical key order,
 * no insignificant whitespace, ISO-8601 dates, integers without decimal points, hand-written
 * rather than delegated to an object mapper. The reasoning there applies here unchanged, and
 * two canonical forms in one system that disagree about their rules would be worse than none.
 *
 * <p><b>Absent fields are omitted, not nulled.</b> That is what makes the not-found report safe
 * by construction rather than by remembering: when nothing was found there is no serial, no
 * institution and no qualification to omit, so the canonical form collapses to the four facts
 * that always exist — verdict, when, and where in the ledger. There is no code path that could
 * accidentally print a holder's initials on a report for a token that matched nothing, because
 * there is nothing to print.
 *
 * <p>Everything the rendered report displays is inside the signed statement. That is the point
 * of listing the qualification and institution here rather than only the identifiers: a
 * signature covering the serial alone would let someone edit the qualification title on the
 * page and still present a report whose signature checks out.
 *
 * @param serial          credential serial; null when nothing was found
 * @param verdict         VALID, REVOKED or NOT_FOUND
 * @param verifiedAt      when the check ran
 * @param ledgerSeq       sequence number of the ledger entry recording this check
 * @param ledgerEntryHash that entry's own hash, so the report can be tied to the chain
 * @param verifyingKeyId  the institution key the signature was checked against; null if none
 * @param institution     who conferred the award; null when nothing was found
 * @param qualification   what was conferred; null when nothing was found
 * @param nqfLevel        the level; null when nothing was found
 * @param awardedOn       when it was conferred; null when nothing was found
 * @param holderInitials  the most this system ever discloses about the person (NFR-06)
 * @param revokedReason   why it was withdrawn, when it was
 * @param revokedAt       when it was withdrawn
 */
public record VerificationReport(
        String serial,
        String verdict,
        Instant verifiedAt,
        long ledgerSeq,
        String ledgerEntryHash,
        String verifyingKeyId,
        String institution,
        String qualification,
        Integer nqfLevel,
        LocalDate awardedOn,
        String holderInitials,
        String revokedReason,
        Instant revokedAt) {

    public VerificationReport {
        if (verdict == null || verdict.isBlank()) {
            throw new IllegalArgumentException("a report must carry a verdict");
        }
        if (verifiedAt == null) {
            throw new IllegalArgumentException("a report must say when it was made");
        }
        if (ledgerEntryHash == null || ledgerEntryHash.isBlank()) {
            throw new IllegalArgumentException(
                    "a report must cite the ledger entry that recorded the check");
        }
    }

    /** Whether this report describes a credential at all. */
    public boolean describesCredential() {
        return serial != null;
    }

    /**
     * The statement in canonical form.
     *
     * @return canonical JSON, exactly the text the signature covers
     */
    public String canonicalJson() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        putIfPresent(ordered, "awd", awardedOn == null ? null : awardedOn.toString());
        ordered.put("ent", ledgerEntryHash);
        putIfPresent(ordered, "hld", holderInitials);
        putIfPresent(ordered, "ins", institution);
        putIfPresent(ordered, "kid", verifyingKeyId);
        ordered.put("led", ledgerSeq);
        putIfPresent(ordered, "nqf", nqfLevel);
        putIfPresent(ordered, "qua", qualification);
        putIfPresent(ordered, "rat", revokedAt == null ? null : revokedAt.toString());
        putIfPresent(ordered, "rea", revokedReason);
        putIfPresent(ordered, "ser", serial);
        ordered.put("vat", verifiedAt.toString());
        ordered.put("vdt", verdict);

        StringBuilder json = new StringBuilder(320).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof Number number) {
                json.append(number);
            } else {
                escapeJsonString(String.valueOf(entry.getValue()), json);
            }
        }
        return json.append('}').toString();
    }

    /**
     * The canonical form as bytes, which is what the signature actually covers.
     *
     * @return UTF-8 encoding of {@link #canonicalJson()}
     */
    public byte[] canonicalBytes() {
        return canonicalJson().getBytes(StandardCharsets.UTF_8);
    }

    private static void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value != null) {
            target.put(key, value);
        }
    }

    /**
     * Escapes exactly what RFC 8259 requires and nothing else.
     *
     * <p>Deliberately not "escape anything that might upset a parser": every optional escape is
     * a way for two implementations to produce different bytes for the same string, and a
     * canonical form exists to make that impossible.
     */
    private static void escapeJsonString(String value, StringBuilder target) {
        target.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> target.append("\\\"");
                case '\\' -> target.append("\\\\");
                case '\b' -> target.append("\\b");
                case '\f' -> target.append("\\f");
                case '\n' -> target.append("\\n");
                case '\r' -> target.append("\\r");
                case '\t' -> target.append("\\t");
                default -> {
                    if (c < 0x20) {
                        target.append(String.format("\\u%04x", (int) c));
                    } else {
                        target.append(c);
                    }
                }
            }
        }
        target.append('"');
    }
}
