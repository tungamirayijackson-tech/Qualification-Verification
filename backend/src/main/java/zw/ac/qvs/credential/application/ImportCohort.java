package zw.ac.qvs.credential.application;

import java.io.IOException;
import java.io.Reader;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * FR-02: import a graduation cohort from CSV.
 *
 * <p>The design turns on one clause of the requirement — <em>a malformed row fails that row
 * only</em> — and everything else follows from it.
 *
 * <p>Each row is registered in its own transaction via {@link AtomicRegistration}, so a bad row
 * rolls back itself and nothing else. Every failure is collected and reported with its line
 * number rather than aborting the run, because a registrar handed one error at a time on a
 * thousand-row list is being asked to make a thousand round trips.
 *
 * <p>Parsing failures and registration failures are treated identically from the caller's point
 * of view. A row with a malformed date and a row naming a phased-out qualification are both
 * "line 14 could not be imported, here is why" — the registrar does not care which layer
 * objected, only which line to fix.
 */
public class ImportCohort {

    /** Columns the file must carry. Order is irrelevant; the header names them. */
    public static final List<String> REQUIRED_COLUMNS =
            List.of("nationalId", "holderName", "qualificationId", "awardedOn");

    /** Optional column. */
    public static final String DATE_OF_BIRTH_COLUMN = "dateOfBirth";

    /**
     * Ceiling on rows accepted in one upload.
     *
     * <p>The requirement sizes a cohort at a thousand. Ten thousand is generous headroom and
     * still bounded — an unbounded import is a way to hold a request open for as long as the
     * uploader likes.
     */
    public static final int MAX_ROWS = 10_000;

    /**
     * Rows committed together on the optimistic path.
     *
     * <p>A trade-off with a measurable middle. Larger chunks mean fewer commits and a faster
     * clean import; they also mean a single bad row forces more rows to be replayed
     * individually. A hundred keeps a thousand-row cohort down to ten commits while capping the
     * cost of one bad row at a hundred replayed rows — which is still far cheaper than the
     * thousand commits the per-row-only version paid on every import, good or bad.
     */
    public static final int CHUNK_SIZE = 100;

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreSurroundingSpaces(true)
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .build();

    private final AtomicRegistration registration;
    private final Clock clock;

    public ImportCohort(AtomicRegistration registration, Clock clock) {
        this.registration = registration;
        this.clock = clock;
    }

    /** Raised when the file itself cannot be read, as opposed to one row being wrong. */
    public static class UnreadableFile extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public UnreadableFile(String message) {
            super(message);
        }
    }

    /**
     * Imports a cohort.
     *
     * @param csv           the uploaded file
     * @param institutionId the registrar's own institution, from their token
     * @param actorId       the registrar
     * @return what happened to every row
     */
    public ImportReport importFrom(Reader csv, UUID institutionId, UUID actorId) {
        Instant started = Instant.now(clock);
        List<ImportReport.RowFailure> failures = new ArrayList<>();
        List<ParsedRow> parsed = new ArrayList<>();
        int total = 0;

        try (CSVParser parser = FORMAT.parse(csv)) {

            requireColumns(parser);

            for (CSVRecord row : parser) {
                if (total >= MAX_ROWS) {
                    failures.add(new ImportReport.RowFailure(parser.getCurrentLineNumber(),
                            "TOO_MANY_ROWS",
                            "This file exceeds the " + MAX_ROWS + "-row limit; split it and "
                                    + "upload the remainder separately."));
                    break;
                }
                total++;

                // The line number is captured per row and reported as-is. It is what the
                // registrar sees in their spreadsheet, which is the only number that helps
                // them find the row they need to fix.
                long line = parser.getCurrentLineNumber();

                try {
                    parsed.add(new ParsedRow(line, toCommand(row, institutionId, actorId)));
                } catch (IllegalArgumentException | DateTimeParseException e) {
                    // A parsing failure needs no transaction at all, so it is settled here and
                    // the row simply never reaches the database.
                    failures.add(new ImportReport.RowFailure(line, "MALFORMED_ROW",
                            e.getMessage()));
                }
            }
        } catch (IOException e) {
            throw new UnreadableFile("The file could not be read as CSV: " + e.getMessage());
        }

        int imported = registerAll(parsed, failures);

        return new ImportReport(total, imported, failures,
                Duration.between(started, Instant.now(clock)));
    }

    /** A row that parsed cleanly, with the line it came from. */
    private record ParsedRow(long line, RegisterCredential.Command command) {
    }

    /**
     * Registers every parsed row, a chunk at a time.
     *
     * <p>The optimistic path commits a whole chunk at once, which is what makes a clean
     * thousand-row file cost ten commits rather than a thousand. A chunk that fails is replayed
     * one row at a time, so the isolation FR-02 requires is preserved exactly — it is simply
     * only paid for when something actually goes wrong.
     *
     * @return how many rows were registered
     */
    private int registerAll(List<ParsedRow> rows, List<ImportReport.RowFailure> failures) {
        int imported = 0;

        for (int start = 0; start < rows.size(); start += CHUNK_SIZE) {
            List<ParsedRow> chunk = rows.subList(start, Math.min(start + CHUNK_SIZE, rows.size()));

            try {
                registration.registerChunkInOwnTransaction(
                        chunk.stream().map(ParsedRow::command).toList());
                imported += chunk.size();
            } catch (RuntimeException e) {
                // The whole chunk rolled back, so nothing in it landed. Replaying row by row
                // finds the offending one and lets the rest through.
                imported += replayIndividually(chunk, failures);
            }
        }

        return imported;
    }

    private int replayIndividually(
            List<ParsedRow> chunk, List<ImportReport.RowFailure> failures) {
        int imported = 0;

        for (ParsedRow row : chunk) {
            try {
                if (registration.registerInOwnTransaction(row.command()) != null) {
                    imported++;
                }
            } catch (RegistrationRejected e) {
                failures.add(new ImportReport.RowFailure(
                        row.line(), e.reason().name(), e.getMessage()));
            } catch (RuntimeException e) {
                // A duplicate serial, a constraint violation, anything the database refused.
                // The row's own transaction has rolled back, so the run continues from a clean
                // state -- which is the entire reason the fallback exists.
                failures.add(new ImportReport.RowFailure(row.line(), "REGISTRATION_FAILED",
                        e.getMessage() == null
                                ? "The register refused this row."
                                : e.getMessage()));
            }
        }

        return imported;
    }

    private RegisterCredential.Command toCommand(
            CSVRecord row, UUID institutionId, UUID actorId) {

        String nationalId = required(row, "nationalId");
        String holderName = required(row, "holderName");
        UUID qualificationId = uuid(required(row, "qualificationId"), "qualificationId");
        LocalDate awardedOn = date(required(row, "awardedOn"), "awardedOn");
        LocalDate dateOfBirth = optionalDate(row);

        return new RegisterCredential.Command(institutionId, qualificationId, nationalId,
                holderName, dateOfBirth, awardedOn, actorId);
    }

    private static void requireColumns(CSVParser parser) {
        List<String> header = parser.getHeaderNames();

        List<String> missing = REQUIRED_COLUMNS.stream()
                .filter(column -> !header.contains(column))
                .toList();

        if (!missing.isEmpty()) {
            // A whole-file problem, not a row problem. Reporting it as a thousand identical
            // row failures would bury the one thing the registrar needs to know.
            throw new UnreadableFile(
                    "The file is missing required column(s): " + String.join(", ", missing)
                            + ". Expected header: " + String.join(",", REQUIRED_COLUMNS)
                            + " and optionally " + DATE_OF_BIRTH_COLUMN + ".");
        }
    }

    private static String required(CSVRecord row, String column) {
        if (!row.isSet(column)) {
            throw new IllegalArgumentException(column + " is missing from this row");
        }
        String value = row.get(column);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(column + " is empty");
        }
        return value;
    }

    private static UUID uuid(String value, String column) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " is not a valid identifier: " + value);
        }
    }

    private static LocalDate date(String value, String column) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    column + " must be an ISO date such as 2026-04-11, got: " + value);
        }
    }

    private static LocalDate optionalDate(CSVRecord row) {
        if (!row.isSet(DATE_OF_BIRTH_COLUMN)) {
            return null;
        }
        String value = row.get(DATE_OF_BIRTH_COLUMN);
        return value == null || value.isBlank() ? null : date(value, DATE_OF_BIRTH_COLUMN);
    }
}
