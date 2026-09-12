package zw.ac.qvs.credential.adapter.in;

import java.util.List;
import zw.ac.qvs.credential.application.ImportReport;

/**
 * What the console shows after a cohort import (FR-02).
 *
 * @param totalRows  data rows read
 * @param imported   rows that produced a signed credential
 * @param failed     rows that did not
 * @param elapsedMs  how long it took, so the 30-second target can be checked rather than assumed
 * @param failures   every failure, with its line number
 */
public record ImportReportResponse(
        int totalRows, int imported, int failed, long elapsedMs, List<Failure> failures) {

    /**
     * One row that could not be imported.
     *
     * @param line   the line in the uploaded file, counting the header as line 1
     * @param reason a stable machine-readable code
     * @param detail a sentence the registrar can act on
     */
    public record Failure(long line, String reason, String detail) {
    }

    static ImportReportResponse from(ImportReport report) {
        return new ImportReportResponse(
                report.totalRows(),
                report.imported(),
                report.failed(),
                report.elapsed().toMillis(),
                report.failures().stream()
                        .map(f -> new Failure(f.line(), f.reason(), f.detail()))
                        .toList());
    }
}
