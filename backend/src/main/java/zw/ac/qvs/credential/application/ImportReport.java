package zw.ac.qvs.credential.application;

import java.time.Duration;
import java.util.List;

/**
 * What happened to a cohort import (FR-02).
 *
 * <p>Reports every failure rather than stopping at the first. A registrar handed "row 12 is
 * wrong" fixes row 12, re-uploads, and is told row 19 is wrong — which for a thousand-row
 * graduation list is a morning gone. Reporting all of them at once means one correction pass.
 *
 * @param totalRows     data rows read, excluding the header
 * @param imported      rows that produced a signed credential
 * @param failures      every row that did not, with its line number and reason
 * @param elapsed       how long the import took, so the NFR can be checked rather than assumed
 */
public record ImportReport(
        int totalRows, int imported, List<RowFailure> failures, Duration elapsed) {

    public ImportReport {
        failures = List.copyOf(failures);
    }

    /**
     * One row that could not be imported.
     *
     * @param line   the line in the uploaded file, counting the header as line 1
     * @param reason a stable machine-readable code
     * @param detail a sentence the registrar can act on
     */
    public record RowFailure(long line, String reason, String detail) {
    }

    /** How many rows failed. */
    public int failed() {
        return failures.size();
    }

    /** Whether every row was imported. */
    public boolean isCompleteSuccess() {
        return failures.isEmpty() && totalRows > 0;
    }

    /**
     * A sentence for the ledger entry and the console banner.
     *
     * @return the summary
     */
    public String summary() {
        return "%d of %d rows imported in %d ms, %d failed"
                .formatted(imported, totalRows, elapsed.toMillis(), failed());
    }
}
