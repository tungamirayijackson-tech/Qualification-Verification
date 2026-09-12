package zw.ac.qvs.verification.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The statement an institution signs, and the exact bytes it signs it over.
 *
 * <p>Canonicalisation is the part that quietly breaks systems like this. If two serialisations
 * of the same claims can differ by a space, a key order or a date format, then a signature made
 * over one will fail against the other — and that failure is indistinguishable from tampering.
 * So this class does not delegate to an object mapper: mappers reorder fields, honour
 * annotations, change behaviour between versions, and are configured in a different file from
 * the one you are reading. The rules here are fixed in code and asserted by a property test.
 *
 * <p>The canonical form is: keys in a fixed alphabetical order, no insignificant whitespace,
 * ISO-8601 dates, integers without decimal points, and only the JSON escapes that RFC 8259
 * requires. Alphabetical order rather than "logical" order is chosen for one reason — it is a
 * rule two independent implementations can follow without consulting each other, which is what
 * an interoperable canonical form needs.
 *
 * <p>Note what is signed but never published. {@code nam}, the holder's full name, is part of
 * the signed statement, because a signature over a nameless award would let anyone attach it
 * to anyone. It is never returned on the public path, which discloses initials at most
 * (NFR-06). Signing and disclosure are different questions and this type answers only the
 * first.
 *
 * @param serial        credential serial, human-quotable
 * @param issuerUrn     institution URN, for example {@code urn:qvs:inst:0142}
 * @param subjectHash   salted hash of the holder's national ID, never the ID itself
 * @param holderName    the holder's full name as conferred
 * @param qualification the qualification title
 * @param nqfLevel      NQF level, 1..10
 * @param credits       credit value
 * @param awardedOn     date the award was conferred
 * @param keyId         id of the signing key, so rotation stays verifiable
 */
public record CredentialClaims(
        String serial,
        String issuerUrn,
        String subjectHash,
        String holderName,
        String qualification,
        int nqfLevel,
        int credits,
        LocalDate awardedOn,
        String keyId) {

    private static final int MIN_NQF = 1;
    private static final int MAX_NQF = 10;

    public CredentialClaims {
        require(serial, "ser");
        require(issuerUrn, "iss");
        require(subjectHash, "sub");
        require(holderName, "nam");
        require(qualification, "qua");
        require(keyId, "kid");
        if (awardedOn == null) {
            throw new IllegalArgumentException("awd is required");
        }
        if (nqfLevel < MIN_NQF || nqfLevel > MAX_NQF) {
            throw new IllegalArgumentException("nqf must be 1..10, got " + nqfLevel);
        }
        if (credits <= 0) {
            throw new IllegalArgumentException("crd must be positive, got " + credits);
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    /**
     * The claims in canonical form.
     *
     * @return canonical JSON, exactly the text that gets signed
     */
    public String canonicalJson() {
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("awd", awardedOn.toString());
        ordered.put("crd", credits);
        ordered.put("iss", issuerUrn);
        ordered.put("kid", keyId);
        ordered.put("nam", holderName);
        ordered.put("nqf", nqfLevel);
        ordered.put("qua", qualification);
        ordered.put("ser", serial);
        ordered.put("sub", subjectHash);

        StringBuilder json = new StringBuilder(256).append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":");
            if (entry.getValue() instanceof Integer number) {
                json.append(number.intValue());
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

    /**
     * Writes a JSON string literal, escaped per RFC 8259.
     *
     * <p>Hand-written because the escaping choices must be deterministic. A mapper may emit a
     * forward slash escaped or bare, and a non-ASCII character either literally or as a
     * {@code u}-escape; both are valid JSON, but only one of them can be the canonical one.
     * Here only the characters that MUST be escaped are escaped, and everything else is
     * written as itself in UTF-8.
     */
    private static void escapeJsonString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
