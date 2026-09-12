package zw.ac.qvs.credential.adapter.in;

import java.time.LocalDate;
import java.util.List;
import zw.ac.qvs.credential.application.CredentialSearch;

/**
 * A page of search results (FR-03).
 *
 * <p>Carries the paging metadata rather than only the rows, because a console that cannot tell
 * the user how many matches there are has to choose between showing a "next" button that
 * sometimes leads nowhere and not offering paging at all.
 *
 * @param results    the rows on this page
 * @param page       zero-based page number
 * @param size       rows per page
 * @param totalRows  total matches
 * @param totalPages pages the full result set spans
 * @param hasMore    whether another page follows
 */
public record CredentialSearchResponse(
        List<Match> results,
        int page,
        int size,
        long totalRows,
        int totalPages,
        boolean hasMore) {

    /**
     * One match.
     *
     * <p>Initials, never a name. A search screen is exactly where a full-name column would be
     * most convenient and most damaging: it turns one query into a list of people.
     *
     * @param serial         the credential reference
     * @param holderInitials the most that is disclosed about the person
     * @param qualification  what was conferred
     * @param nqfLevel       the level
     * @param institution    who conferred it
     * @param awardedOn      when
     * @param status         ISSUED or REVOKED
     */
    public record Match(
            String serial,
            String holderInitials,
            String qualification,
            int nqfLevel,
            String institution,
            LocalDate awardedOn,
            String status) {
    }

    static CredentialSearchResponse from(CredentialSearch.Page page) {
        return new CredentialSearchResponse(
                page.rows().stream()
                        .map(row -> new Match(row.serial(), row.holderInitials(),
                                row.qualification(), row.nqfLevel(), row.institution(),
                                row.awardedOn(), row.status()))
                        .toList(),
                page.page(),
                page.size(),
                page.totalRows(),
                page.totalPages(),
                page.hasMore());
    }
}
