package zw.ac.qvs.credential.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.CredentialStatus;
import zw.ac.qvs.credential.domain.Serial;
import zw.ac.qvs.testsupport.Requirement;

/**
 * FR-02, with the acceptance criterion as the headline: a malformed row fails that row only,
 * and is reported by line number.
 */
@Requirement("FR-02")
class ImportCohortTest {

    private static final Clock NOW =
            Clock.fixed(Instant.parse("2026-09-06T09:00:00Z"), ZoneOffset.UTC);
    private static final UUID INSTITUTION = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REGISTRAR = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String QUALIFICATION = "aaaaaaaa-0001-4111-8111-aaaaaaaaaaaa";

    private static final String HEADER = "nationalId,holderName,qualificationId,awardedOn";

    /** Records what it was asked to register, and can be told to refuse particular rows. */
    private static final class Registrations implements AtomicRegistration {
        private final List<RegisterCredential.Command> accepted = new ArrayList<>();
        private Predicate<RegisterCredential.Command> refuseWhen = command -> false;
        private RuntimeException refusal =
                new RegistrationRejected(RegistrationRejected.Reason.QUALIFICATION_PHASED_OUT,
                        "that qualification accepts no new awards");

        @Override
        public List<Credential> registerChunkInOwnTransaction(
                List<RegisterCredential.Command> commands) {
            // Mirrors the real transaction: if any row is refused the whole chunk is discarded,
            // so nothing it added may survive. Without restoring the list the stub would report
            // rows as accepted that a real rollback would have removed.
            int before = accepted.size();
            try {
                return commands.stream().map(this::registerOne).toList();
            } catch (RuntimeException e) {
                accepted.subList(before, accepted.size()).clear();
                throw e;
            }
        }

        @Override
        public Credential registerInOwnTransaction(RegisterCredential.Command command) {
            return registerOne(command);
        }

        private Credential registerOne(RegisterCredential.Command command) {
            if (refuseWhen.test(command)) {
                throw refusal;
            }
            accepted.add(command);
            return new Credential(UUID.randomUUID(),
                    Serial.of("ZW", "PR0142", 2026, accepted.size()),
                    command.qualificationId(), UUID.randomUUID(), command.awardedOn(),
                    "kid", "jws", "{}", CredentialStatus.ISSUED, null, null, null, null,
                    Instant.now(NOW));
        }
    }

    private Registrations registrations;
    private ImportCohort importCohort;

    @BeforeEach
    void setUp() {
        registrations = new Registrations();
        importCohort = new ImportCohort(registrations, NOW);
    }

    private ImportReport importCsv(String csv) {
        return importCohort.importFrom(new StringReader(csv), INSTITUTION, REGISTRAR);
    }

    private static String row(String nationalId, String name, String awardedOn) {
        return String.join(",", nationalId, name, QUALIFICATION, awardedOn);
    }

    @Nested
    @DisplayName("a clean file")
    class CleanFile {

        @Test
        @DisplayName("imports every row")
        void importsEveryRow() {
            ImportReport report = importCsv(String.join("\n",
                    HEADER,
                    row("63-1234567K42", "Thandeka N. Mahlangu", "2026-04-11"),
                    row("08-2345678M17", "Sibusiso Ndlovu", "2026-04-11"),
                    row("25-3456789P08", "Renee Botha", "2025-12-05")));

            assertThat(report.totalRows()).isEqualTo(3);
            assertThat(report.imported()).isEqualTo(3);
            assertThat(report.failed()).isZero();
            assertThat(report.isCompleteSuccess()).isTrue();
        }

        @Test
        @DisplayName("passes each row's values through unchanged")
        void passesValuesThrough() {
            importCsv(HEADER + "\n" + row("63-1234567K42", "Thandeka N. Mahlangu", "2026-04-11"));

            RegisterCredential.Command command = registrations.accepted.getFirst();
            assertThat(command.holderNationalId()).isEqualTo("63-1234567K42");
            assertThat(command.holderName()).isEqualTo("Thandeka N. Mahlangu");
            assertThat(command.awardedOn()).isEqualTo(LocalDate.of(2026, 4, 11));
            assertThat(command.actorId()).isEqualTo(REGISTRAR);
        }

        @Test
        @DisplayName("takes the institution from the caller, never from the file")
        void institutionComesFromTheCaller() {
            // There is no institution column, deliberately. A registrar uploading a file cannot
            // register awards on behalf of somewhere they are not bound to.
            importCsv(HEADER + "\n" + row("63-1234567K42", "Someone", "2026-04-11"));

            assertThat(registrations.accepted.getFirst().institutionId()).isEqualTo(INSTITUTION);
        }

        @Test
        @DisplayName("accepts the optional date of birth")
        void optionalDateOfBirth() {
            ImportReport report = importCsv(
                    HEADER + ",dateOfBirth\n"
                            + row("63-1234567K42", "Someone", "2026-04-11") + ",1998-01-15");

            assertThat(report.imported()).isEqualTo(1);
            assertThat(registrations.accepted.getFirst().holderDateOfBirth())
                    .isEqualTo(LocalDate.of(1998, 1, 15));
        }

        @Test
        @DisplayName("tolerates a blank optional column and surrounding whitespace")
        void tolerantParsing() {
            ImportReport report = importCsv(
                    HEADER + ",dateOfBirth\n"
                            + " 63-1234567K42 , Thandeka N. Mahlangu ," + QUALIFICATION
                            + " , 2026-04-11 ,");

            assertThat(report.imported()).isEqualTo(1);
            assertThat(registrations.accepted.getFirst().holderName())
                    .isEqualTo("Thandeka N. Mahlangu");
            assertThat(registrations.accepted.getFirst().holderDateOfBirth()).isNull();
        }

        @Test
        @DisplayName("handles a quoted name containing a comma")
        void quotedFields() {
            // The reason a real CSV parser is used rather than split(","). A name like
            // "Mahlangu, Thandeka" silently becomes two fields otherwise, and the row after it
            // shifts by one column without anything looking wrong.
            ImportReport report = importCsv(
                    HEADER + "\n63-1234567K42,\"Mahlangu, Thandeka\"," + QUALIFICATION
                            + ",2026-04-11");

            assertThat(report.imported()).isEqualTo(1);
            assertThat(registrations.accepted.getFirst().holderName())
                    .isEqualTo("Mahlangu, Thandeka");
        }
    }

    @Nested
    @DisplayName("a malformed row fails that row only")
    class PartialFailure {

        @Test
        @DisplayName("the good rows still import")
        void badRowDoesNotStopTheRest() {
            // The acceptance criterion. Row 3 has an unparseable date; rows 2 and 4 must land.
            ImportReport report = importCsv(String.join("\n",
                    HEADER,
                    row("63-1234567K42", "First Graduate", "2026-04-11"),
                    row("08-2345678M17", "Second Graduate", "not-a-date"),
                    row("25-3456789P08", "Third Graduate", "2025-12-05")));

            assertThat(report.totalRows()).isEqualTo(3);
            assertThat(report.imported()).isEqualTo(2);
            assertThat(report.failed()).isEqualTo(1);
            assertThat(registrations.accepted)
                    .extracting(RegisterCredential.Command::holderName)
                    .containsExactly("First Graduate", "Third Graduate");
        }

        @Test
        @DisplayName("the failure names the line in the uploaded file")
        void reportsTheLineNumber() {
            // Counting the header as line 1, the bad row is line 3. A registrar looking at a
            // spreadsheet needs the number they can see in the row gutter.
            ImportReport report = importCsv(String.join("\n",
                    HEADER,
                    row("63-1234567K42", "First Graduate", "2026-04-11"),
                    row("08-2345678M17", "Second Graduate", "not-a-date"),
                    row("25-3456789P08", "Third Graduate", "2025-12-05")));

            assertThat(report.failures()).hasSize(1);
            assertThat(report.failures().getFirst().line()).isEqualTo(3);
            assertThat(report.failures().getFirst().reason()).isEqualTo("MALFORMED_ROW");
            assertThat(report.failures().getFirst().detail()).contains("awardedOn");
        }

        @Test
        @DisplayName("every failure is reported, not just the first")
        void reportsEveryFailure() {
            // Otherwise a thousand-row list becomes a thousand upload-and-fix round trips.
            ImportReport report = importCsv(String.join("\n",
                    HEADER,
                    row("63-1234567K42", "Good", "2026-04-11"),
                    row("", "Missing Id", "2026-04-11"),
                    row("08-2345678M17", "", "2026-04-11"),
                    row("25-3456789P08", "Bad Date", "31-12-2025"),
                    row("12-5678901T26", "Bad Qualification", "2026-04-11")
                            .replace(QUALIFICATION, "not-a-uuid")));

            assertThat(report.imported()).isEqualTo(1);
            assertThat(report.failures()).hasSize(4);
            assertThat(report.failures()).extracting(ImportReport.RowFailure::line)
                    .containsExactly(3L, 4L, 5L, 6L);
        }

        @Test
        @DisplayName("a row the register refuses is reported with its reason code")
        void registrationRejectionIsReported() {
            registrations.refuseWhen = command -> command.holderName().equals("Refused Graduate");

            ImportReport report = importCsv(String.join("\n",
                    HEADER,
                    row("63-1234567K42", "Accepted Graduate", "2026-04-11"),
                    row("08-2345678M17", "Refused Graduate", "2026-04-11")));

            assertThat(report.imported()).isEqualTo(1);
            assertThat(report.failures().getFirst().reason())
                    .isEqualTo("QUALIFICATION_PHASED_OUT");
            assertThat(report.failures().getFirst().line()).isEqualTo(3);
        }

        @Test
        @DisplayName("an unexpected database failure fails one row, not the run")
        void unexpectedFailureIsContained() {
            // The row's own transaction has rolled back, so the run continues from a clean
            // state. That is the entire reason each row gets one.
            registrations.refusal = new IllegalStateException("duplicate key value");
            registrations.refuseWhen = command -> command.holderName().equals("Collides");

            ImportReport report = importCsv(String.join("\n",
                    HEADER,
                    row("63-1234567K42", "Collides", "2026-04-11"),
                    row("08-2345678M17", "Fine", "2026-04-11")));

            assertThat(report.imported()).isEqualTo(1);
            assertThat(report.failures().getFirst().reason()).isEqualTo("REGISTRATION_FAILED");
            assertThat(report.failures().getFirst().detail()).contains("duplicate key");
        }
    }

    @Nested
    @DisplayName("a file that cannot be used at all")
    class WholeFileFailure {

        @Test
        @DisplayName("a missing required column is one error, not one per row")
        void missingColumn() {
            // Reporting it per row would bury the single thing the registrar needs to know
            // under a thousand identical messages.
            assertThatThrownBy(() -> importCsv(
                    "nationalId,holderName,awardedOn\n63-1234567K42,Someone,2026-04-11"))
                    .isInstanceOf(ImportCohort.UnreadableFile.class)
                    .hasMessageContaining("qualificationId");
        }

        @Test
        @DisplayName("names every missing column at once")
        void namesAllMissingColumns() {
            assertThatThrownBy(() -> importCsv("nationalId,holderName\n63-1234567K42,Someone"))
                    .isInstanceOf(ImportCohort.UnreadableFile.class)
                    .hasMessageContaining("qualificationId")
                    .hasMessageContaining("awardedOn");
        }

        @Test
        @DisplayName("a header with no rows imports nothing and fails nothing")
        void headerOnly() {
            ImportReport report = importCsv(HEADER);

            assertThat(report.totalRows()).isZero();
            assertThat(report.imported()).isZero();
            assertThat(report.failed()).isZero();
            assertThat(report.isCompleteSuccess()).isFalse();
        }
    }

    @Test
    @DisplayName("the report says how long the import took, so the target can be checked")
    void reportsElapsedTime() {
        // NFR: a thousand rows in under thirty seconds. A report that does not carry the
        // measurement leaves the target unverifiable.
        ImportReport report = importCsv(HEADER + "\n" + row("63-1234567K42", "X", "2026-04-11"));

        assertThat(report.elapsed()).isNotNull();
        assertThat(report.summary()).contains("1 of 1 rows imported");
    }

    @Test
    @DisplayName("imports a thousand rows without stopping")
    void handlesACohort() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int i = 0; i < 1_000; i++) {
            csv.append('\n').append(row("80010150090" + (10 + i % 90),
                    "Graduate " + i, "2026-04-11"));
        }

        ImportReport report = importCsv(csv.toString());

        assertThat(report.totalRows()).isEqualTo(1_000);
        assertThat(report.imported()).isEqualTo(1_000);
    }
}
