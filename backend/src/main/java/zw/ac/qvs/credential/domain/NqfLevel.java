package zw.ac.qvs.credential.domain;

/**
 * A Zimbabwean National Qualifications Framework level, 1 through 10.
 *
 * <p>The invariant lives in the constructor, so an {@code NqfLevel} holding 11 cannot exist
 * anywhere in the system. That is the first of the three validation layers described in the
 * verification strategy: domain invariant, bean validation on the DTO, and a database CHECK.
 *
 * @param value the level, 1..10
 */
public record NqfLevel(int value) {

    /** Lowest level the framework defines. */
    public static final int MIN = 1;

    /** Highest level the framework defines (doctoral). */
    public static final int MAX = 10;

    public NqfLevel {
        if (value < MIN || value > MAX) {
            throw new IllegalArgumentException(
                    "NQF level must be between " + MIN + " and " + MAX + ", got " + value);
        }
    }

    /**
     * Whether this level is a postgraduate one (8 and above).
     *
     * @return true for honours, masters and doctoral levels
     */
    public boolean isPostgraduate() {
        return value >= 8;
    }
}
