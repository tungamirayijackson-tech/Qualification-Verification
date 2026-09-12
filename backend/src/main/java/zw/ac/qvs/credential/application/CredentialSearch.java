package zw.ac.qvs.credential.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The vocabulary of FR-03: what may be asked, and what comes back.
 *
 * <p>Grouped in one file because the query, the row and the page only make sense together, and
 * splitting three small records across three files would be filing rather than design.
 */
public final class CredentialSearch {

    /** Largest page the API will return, however large a page the caller asks for. */
    public static final int MAX_PAGE_SIZE = 100;

    /** Page size used when the caller does not say. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    private CredentialSearch() {
        // namespace
    }

    /**
     * What a caller is asking for.
     *
     * <p>Note what {@code institutionId} is <em>not</em>: it is not taken from the request. The
     * use case overwrites it with the caller's own institution unless they hold a
     * cross-institution role, so a registrar cannot widen their own scope by editing a query
     * parameter.
     *
     * @param holderName     part of a name; folded before matching
     * @param nationalIdHash exact salted hash of a national ID
     * @param institutionId  institution to search within, or null for all
     * @param nqfLevel       exact NQF level, or null
     * @param status         ISSUED or REVOKED, or null for both
     * @param awardedFrom    inclusive lower bound on the award date, or null
     * @param awardedTo      inclusive upper bound on the award date, or null
     * @param page           zero-based page number
     * @param size           rows per page
     */
    public record Query(
            String holderName,
            String nationalIdHash,
            UUID institutionId,
            Integer nqfLevel,
            String status,
            LocalDate awardedFrom,
            LocalDate awardedTo,
            int page,
            int size) {

        public Query {
            if (page < 0) {
                throw new IllegalArgumentException("page cannot be negative");
            }
            if (size <= 0) {
                size = DEFAULT_PAGE_SIZE;
            }
            // Clamped rather than rejected. A caller asking for ten thousand rows has made a
            // reasonable request badly; refusing it outright helps nobody, and honouring it
            // would let one query hold a connection long enough to matter.
            if (size > MAX_PAGE_SIZE) {
                size = MAX_PAGE_SIZE;
            }
            if (status != null && !status.equals("ISSUED") && !status.equals("REVOKED")) {
                throw new IllegalArgumentException("status must be ISSUED or REVOKED");
            }
            if (nqfLevel != null && (nqfLevel < 1 || nqfLevel > 10)) {
                throw new IllegalArgumentException("NQF level must be between 1 and 10");
            }
            if (awardedFrom != null && awardedTo != null && awardedFrom.isAfter(awardedTo)) {
                throw new IllegalArgumentException("the award-date range is inverted");
            }
        }

        /** Whether this query narrows anything at all. */
        public boolean hasAnyCriterion() {
            return holderName != null
                    || nationalIdHash != null
                    || nqfLevel != null
                    || status != null
                    || awardedFrom != null
                    || awardedTo != null;
        }

        /** A copy pinned to one institution, whatever the caller asked for. */
        public Query scopedTo(UUID institution) {
            return new Query(holderName, nationalIdHash, institution, nqfLevel, status,
                    awardedFrom, awardedTo, page, size);
        }
    }

    /**
     * One result row.
     *
     * <p>The holder appears as initials. A search screen is exactly where a full-name column
     * would be most convenient and most damaging: it turns one query into a list of people, and
     * a list of people is the thing NFR-06 exists to prevent.
     *
     * @param serial         the credential reference
     * @param holderInitials the most that is disclosed about the person
     * @param qualification  what was conferred
     * @param nqfLevel       the level
     * @param institution    who conferred it
     * @param awardedOn      when
     * @param status         ISSUED or REVOKED
     */
    public record Row(
            String serial,
            String holderInitials,
            String qualification,
            int nqfLevel,
            String institution,
            LocalDate awardedOn,
            String status) {
    }

    /**
     * A page of results.
     *
     * @param rows       the rows on this page
     * @param page       zero-based page number
     * @param size       rows per page
     * @param totalRows  how many rows match in total
     */
    public record Page(List<Row> rows, int page, int size, long totalRows) {

        public Page {
            rows = List.copyOf(rows);
        }

        /** How many pages the full result set spans. */
        public int totalPages() {
            return size == 0 ? 0 : (int) Math.ceil((double) totalRows / size);
        }

        /** Whether another page follows this one. */
        public boolean hasMore() {
            return (long) (page + 1) * size < totalRows;
        }
    }
}
