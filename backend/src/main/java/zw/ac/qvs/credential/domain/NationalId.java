package zw.ac.qvs.credential.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Zimbabwean national identity number, in one canonical form.
 *
 * <p>Written on a card as {@code 63-1234567 K 42}: a two-digit registration office, a serial of
 * six or seven digits, a check letter, and a two-digit district of origin. People write it a
 * dozen ways — with hyphens, with spaces, with neither, in either case — and every one of those
 * is the same person.
 *
 * <h2>Why this is a type and not a regex on two DTOs</h2>
 *
 * <p>The identity number is never stored. What is stored is a salted hash of it, and a hash is
 * only useful if the same person hashes the same way every time. Before this existed, the two
 * paths that hash one disagreed: search trimmed its input and registration did not. With a
 * format that has optional separators and a letter that may arrive in either case, that
 * disagreement stops being cosmetic — a graduate registered as {@code 63-1234567K42} would not
 * be found by a search for {@code 63 1234567 k 42}, and nothing anywhere would report a problem.
 * Both paths now hash {@link #canonical()}, so the question cannot come up again.
 *
 * <h2>The check letter is not recomputed</h2>
 *
 * <p>Zimbabwe's check letter is derived from the digits, so in principle a wrong one could be
 * refused here. It is not, deliberately. The published descriptions of that derivation do not
 * agree with one another, and a validator built on the wrong one would refuse genuine identity
 * numbers held by real graduates — which is a worse failure than accepting a mistyped one, and a
 * far harder one for a registrar to argue with. The shape is checked; the arithmetic is not
 * claimed.
 */
public record NationalId(String canonical) {

    /**
     * Registration office, serial, check letter, district — with any mix of hyphens and spaces
     * between them, or none at all.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "^\\s*(\\d{2})\\s*[-\\s]?\\s*(\\d{6,7})\\s*[-\\s]?\\s*([A-Za-z])\\s*[-\\s]?\\s*(\\d{2})\\s*$");

    /** What a person is told when theirs does not fit. */
    public static final String EXPECTED =
            "a national ID looks like 63-1234567 K 42: two digits, six or seven digits, "
                    + "a letter, then two digits";

    public NationalId {
        if (canonical == null || canonical.isBlank()) {
            throw new IllegalArgumentException("a national ID is required");
        }
    }

    /**
     * Reads an identity number in any of the forms people write it in.
     *
     * @param raw as typed, or as it arrived in a CSV column
     * @return the same number in the one form this system hashes
     * @throws IllegalArgumentException when it is not shaped like a national ID
     */
    public static NationalId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("a national ID is required");
        }
        var match = SHAPE.matcher(raw);
        if (!match.matches()) {
            // The value itself is deliberately not echoed: this message reaches logs and error
            // responses, and an identity number is the one field here that must not.
            throw new IllegalArgumentException(EXPECTED);
        }
        return new NationalId(match.group(1)
                + match.group(2)
                + match.group(3).toUpperCase(Locale.ROOT)
                + match.group(4));
    }

    /**
     * Whether a string is shaped like a national ID, without throwing.
     *
     * @param raw as typed
     * @return true when {@link #parse} would accept it
     */
    public static boolean isWellFormed(String raw) {
        return raw != null && SHAPE.matcher(raw).matches();
    }

    /**
     * The number as it should be shown back to a person, rather than as it is hashed.
     *
     * @return the canonical digits punctuated the way the card is
     */
    public String formatted() {
        int serialLength = canonical.length() - 5;
        return canonical.substring(0, 2) + "-"
                + canonical.substring(2, 2 + serialLength) + " "
                + canonical.charAt(2 + serialLength) + " "
                + canonical.substring(canonical.length() - 2);
    }

    @Override
    public String toString() {
        // Never the number itself. This type ends up inside exception messages and log lines
        // by accident more often than by design, and that is exactly when it must not leak.
        return "NationalId(hidden)";
    }
}
