package zw.ac.qvs.credential.domain;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The person who holds a credential — as little of them as the system can manage.
 *
 * <p>Three deliberate absences. There is no national ID, only a salted hash of one, so a
 * database disclosure does not yield ID numbers. There is no plaintext name in storage, only
 * ciphertext, so the same disclosure does not yield a directory. And nothing here is ever
 * returned on the public verification path, which sees initials at most (NFR-06).
 *
 * @param id             surrogate identity
 * @param pseudonymRef   stable opaque reference, safe to log and to quote in a ledger entry
 * @param nationalIdHash salted SHA-256 of the national ID, 64 lower-case hex characters
 * @param displayName    the holder's name, in plaintext only while in memory
 * @param dateOfBirth    optional, used only to disambiguate identical names
 */
public record Holder(
        UUID id,
        String pseudonymRef,
        String nationalIdHash,
        String displayName,
        LocalDate dateOfBirth) {

    private static final Pattern HASH_SHAPE = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SEARCHABLE = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern RUNS_OF_SPACE = Pattern.compile("\\s+");

    public Holder {
        if (pseudonymRef == null || pseudonymRef.isBlank()) {
            throw new IllegalArgumentException("pseudonym reference is required");
        }
        if (nationalIdHash == null || !HASH_SHAPE.matcher(nationalIdHash).matches()) {
            throw new IllegalArgumentException(
                    "national ID must be stored as a 64-character lower-case hex hash");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name is required");
        }
    }

    /**
     * The holder's initials, which is the most the public path ever discloses.
     *
     * <p>"Thandeka N. Mahlangu" becomes "T.N.M." — enough for a verifier holding a CV to
     * confirm they are looking at the right person, not enough to harvest into a dataset.
     *
     * @return dotted upper-case initials
     */
    public String initials() {
        StringBuilder out = new StringBuilder(8);
        for (String part : RUNS_OF_SPACE.split(displayName.trim())) {
            if (!part.isEmpty() && Character.isLetter(part.charAt(0))) {
                out.append(Character.toUpperCase(part.charAt(0))).append('.');
            }
        }
        return out.toString();
    }

    /**
     * A normalised copy of the name for trigram search.
     *
     * <p>Accents are folded, case is dropped and punctuation becomes whitespace, so that
     * "Thandeka N. Mahlangu" and "thandeka mahlangu" match.
     *
     * <p>This column is the NFR-06 concession that FR-03 forces, and the report should own it
     * rather than hide it: fuzzy matching cannot run on ciphertext, so a searchable copy of
     * the name has to exist somewhere. The mitigation is scope, not secrecy — every search is
     * restricted to the caller's own institution, so this is never a cross-institution
     * directory of who studied where.
     *
     * @return the searchable form of the name
     */
    public String searchName() {
        return SearchName.normalise(displayName);
    }
}
