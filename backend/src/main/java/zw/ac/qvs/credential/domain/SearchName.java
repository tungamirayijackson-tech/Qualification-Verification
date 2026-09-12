package zw.ac.qvs.credential.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * How a name is folded before it is stored for searching, and before it is searched for.
 *
 * <p>This exists as one function because the stored column and the query term must be folded
 * <em>identically</em>. If the two ever diverge — one strips accents and the other does not, one
 * lower-cases and the other does not — the search silently stops finding people whose names
 * contain the characters the two implementations disagree about. That failure is invisible: the
 * screen shows no results, which looks exactly like nobody holding that qualification.
 *
 * <p>Folding is deliberately aggressive. "Thandeka N. Mahlangu", "thandeka mahlangu" and
 * "THANDEKA  N MAHLANGU" all reduce to the same text, because a registrar searching for a
 * graduate should not have to reproduce the punctuation somebody else typed.
 *
 * <p>This is also the NFR-06 concession that FR-03 forces, and it is worth being precise about
 * what is conceded. Fuzzy matching cannot run on ciphertext, so a searchable copy of the name
 * exists in the clear. The mitigation is scope rather than secrecy: every search is restricted
 * to the caller's own institution unless they are an auditor, so this column is never a
 * cross-institution directory of who studied where.
 */
public final class SearchName {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_SEARCHABLE = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern RUNS_OF_SPACE = Pattern.compile("\\s+");

    /** Shortest term worth running a trigram search for. */
    public static final int MIN_TERM_LENGTH = 2;

    private SearchName() {
        // utility
    }

    /**
     * Folds a name to its searchable form.
     *
     * @param name the name as written, may be null
     * @return lower-cased, accent-folded, punctuation-stripped text; empty when there is nothing
     */
    public static String normalise(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String decomposed = Normalizer.normalize(name, Normalizer.Form.NFD);
        String withoutMarks = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String lowered = withoutMarks.toLowerCase(Locale.ROOT);
        String simplified = NON_SEARCHABLE.matcher(lowered).replaceAll(" ");
        return RUNS_OF_SPACE.matcher(simplified).replaceAll(" ").trim();
    }

    /**
     * Whether a folded term is long enough to search on.
     *
     * <p>A one-character term matches most of the register and costs a scan to discover that.
     * Refusing it is kinder than returning ten thousand rows.
     *
     * @param normalisedTerm the already-folded term
     * @return true when the term is worth querying
     */
    public static boolean isSearchable(String normalisedTerm) {
        return normalisedTerm != null && normalisedTerm.length() >= MIN_TERM_LENGTH;
    }
}
