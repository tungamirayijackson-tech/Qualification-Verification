package zw.ac.qvs.credential.domain;

import java.util.regex.Pattern;

/**
 * A credential serial: the string a holder reads out over the telephone.
 *
 * <p>Format {@code ZW-UNI-2026-000481} — country, a short institution code, the award year and
 * a zero-padded sequence. Two properties matter and both are deliberate. It is
 * <b>human-quotable</b>, because a dispute is settled by a person reading a reference aloud.
 * And it is <b>never sufficient on its own</b> to verify a credential — a share token is
 * (Decision 09-A) — so its predictability costs nothing.
 *
 * @param value the serial text
 */
public record Serial(String value) implements Comparable<Serial> {

    private static final Pattern SHAPE =
            Pattern.compile("^[A-Z]{2}-[A-Z0-9]{2,8}-\\d{4}-\\d{6}$");

    /** Maximum length, matching the database column. */
    public static final int MAX_LENGTH = 32;

    /** Highest sequence number a single institution can issue in one year. */
    public static final int MAX_SEQUENCE = 999_999;

    public Serial {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "serial must look like ZW-UNI-2026-000481, got: " + value);
        }
    }

    /**
     * Builds a serial from its parts.
     *
     * @param country         ISO 3166-1 alpha-2 code
     * @param institutionCode short institution code, 2 to 8 upper-case alphanumerics
     * @param year            award year
     * @param sequence        per-institution, per-year sequence number
     * @return the serial
     */
    public static Serial of(String country, String institutionCode, int year, int sequence) {
        if (sequence < 0 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("sequence out of range: " + sequence);
        }
        return new Serial("%s-%s-%04d-%06d".formatted(country, institutionCode, year, sequence));
    }

    /**
     * The year encoded in the serial.
     *
     * @return the four-digit award year
     */
    public int year() {
        String[] parts = value.split("-");
        return Integer.parseInt(parts[2]);
    }

    @Override
    public int compareTo(Serial other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
