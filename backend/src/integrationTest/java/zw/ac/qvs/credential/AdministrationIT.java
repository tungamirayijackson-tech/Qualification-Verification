package zw.ac.qvs.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import zw.ac.qvs.credential.application.AddQualification;
import zw.ac.qvs.credential.application.InstitutionRepository;
import zw.ac.qvs.credential.application.IssueSigningKey;
import zw.ac.qvs.credential.application.OnboardInstitution;
import zw.ac.qvs.credential.application.QualificationRepository;
import zw.ac.qvs.credential.application.RegisterCredential;
import zw.ac.qvs.credential.application.AtomicRegistration;
import zw.ac.qvs.credential.domain.Credential;
import zw.ac.qvs.credential.domain.Institution;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The administrator's job, end to end against the real schema.
 *
 * <p>The point of this test is the <b>whole path</b>: an institution admitted, given a key,
 * given a qualification, and only then able to issue a credential that verifies. Each step was
 * previously either impossible through the API or possible only by seeding the database by
 * hand, which meant nothing proved they compose.
 *
 * <p>It also pins down the order. An institution with no key cannot issue, and that is a
 * refusal rather than an oversight — the state exists so a half-finished onboarding is visible
 * rather than silently able to sign.
 */
@SpringBootTest
@Requirement({"FR-01", "FR-05", "FR-08"})
class AdministrationIT extends PostgresIntegrationTest {

    private static final UUID ADMIN = UUID.randomUUID();

    @Autowired
    private OnboardInstitution onboardInstitution;

    @Autowired
    private IssueSigningKey issueSigningKey;

    @Autowired
    private AddQualification addQualification;

    @Autowired
    private InstitutionRepository institutions;

    @Autowired
    private QualificationRepository qualifications;

    @Autowired
    private AtomicRegistration registration;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    /** A provider number nothing else in the suite will collide with. */
    private static String uniqueProviderNumber() {
        return "PR-IT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private Institution admit() {
        return transactions.execute(status -> onboardInstitution.onboard(
                new OnboardInstitution.Command("Test University " + UUID.randomUUID(),
                        "ZW", uniqueProviderNumber(), LocalDate.now().plusYears(5), ADMIN)));
    }

    @Test
    @DisplayName("an institution is admitted, keyed, given a qualification, and can then issue")
    void theWholePath() {
        Institution institution = admit();
        assertThat(institution.activeKeyId())
                .as("a newly admitted institution has no key and cannot sign")
                .isNull();

        // 1. The key. Rotation and first issuance are the same act.
        var issued = transactions.execute(status ->
                issueSigningKey.issue(institution.id(), LocalDate.now().minusYears(1), ADMIN));

        assertThat(issued.supersedes()).as("its first key supersedes nothing").isNull();
        assertThat(issued.key().kid()).isNotBlank();

        Institution keyed = institutions.findById(institution.id()).orElseThrow();
        assertThat(keyed.activeKeyId())
                .as("the institution row points at the new key, not just the vault")
                .isEqualTo(issued.key().kid());
        assertThat(keyed.canIssueOn(LocalDate.now())).isTrue();

        // 2. The qualification.
        var qualification = transactions.execute(status -> addQualification.add(
                new AddQualification.Command(institution.id(), "BSc Data Science",
                        7, 360, "SAQA-12345", ADMIN, "ADMIN")));

        assertThat(qualifications.findByInstitution(institution.id()))
                .extracting(q -> q.title())
                .contains("BSc Data Science");

        // 3. And now a credential can actually be issued against both.
        Credential credential = registration.registerInOwnTransaction(
                new RegisterCredential.Command(institution.id(), qualification.id(),
                        "63-6789012V05", "Tafadzwa T. Mutasa", LocalDate.of(1992, 2, 2),
                        LocalDate.now().minusMonths(1), ADMIN));

        assertThat(credential.serial().value()).isNotBlank();
        assertThat(credential.keyId()).isEqualTo(issued.key().kid());
    }

    @Test
    @DisplayName("rotating a key supersedes the old one and leaves it verifiable")
    void rotationKeepsOldCredentialsVerifiable() {
        Institution institution = admit();

        var first = transactions.execute(status ->
                issueSigningKey.issue(institution.id(), LocalDate.now().minusYears(2), ADMIN));
        var second = transactions.execute(status ->
                issueSigningKey.issue(institution.id(), LocalDate.now(), ADMIN));

        assertThat(second.supersedes()).isEqualTo(first.key().kid());
        assertThat(second.key().kid()).isNotEqualTo(first.key().kid());

        // The outgoing key keeps its row, with a closed validity window. Verification resolves
        // the key valid on the *award date*, so a credential signed last year still checks out.
        Map<String, Object> outgoing = jdbc.queryForMap(
                "SELECT valid_from, valid_until FROM signing_key WHERE kid = ?", first.key().kid());

        assertThat(outgoing.get("valid_until"))
                .as("the old key's window is closed, not deleted")
                .isNotNull();

        Long stillThere = jdbc.queryForObject(
                "SELECT count(*) FROM signing_key WHERE institution_id = ?", Long.class,
                institution.id());
        assertThat(stillThere).isEqualTo(2L);
    }

    @Test
    @DisplayName("every administrative act lands in the audit ledger")
    void administrationIsAudited() {
        Long before = jdbc.queryForObject("""
                SELECT count(*) FROM audit_entry
                WHERE action IN ('INSTITUTION_ONBOARDED', 'KEY_ROTATED', 'QUALIFICATION_ADDED')
                """, Long.class);

        Institution institution = admit();
        transactions.execute(status -> issueSigningKey.issue(institution.id(), null, ADMIN));
        transactions.execute(status -> addQualification.add(new AddQualification.Command(
                institution.id(), "MSc Audited", 9, 180, null, ADMIN, "ADMIN")));

        Long after = jdbc.queryForObject("""
                SELECT count(*) FROM audit_entry
                WHERE action IN ('INSTITUTION_ONBOARDED', 'KEY_ROTATED', 'QUALIFICATION_ADDED')
                """, Long.class);

        // Three acts, three entries. Asserted as a delta because the ledger is append-only and
        // shared with every other test in the run.
        assertThat(after - before).isEqualTo(3);

        List<String> actions = jdbc.queryForList("""
                SELECT action FROM audit_entry
                WHERE subject_ref = ? OR subject_ref IN (
                    SELECT id::text FROM qualification WHERE institution_id = ?)
                ORDER BY seq
                """, String.class, institution.id().toString(), institution.id());

        assertThat(actions).containsExactly(
                "INSTITUTION_ONBOARDED", "KEY_ROTATED", "QUALIFICATION_ADDED");
    }

    @Test
    @DisplayName("a key cannot be replaced on the day it started")
    void sameDayRotationIsRefusedInWords() {
        // Rotation closes the outgoing key's window the day before the new one opens, so a
        // replacement starting the same day would give it a window ending before it began. The
        // database refuses that, and the refusal used to reach the administrator as a 500 with
        // a constraint name in the log.
        Institution institution = admit();
        transactions.execute(status -> issueSigningKey.issue(institution.id(), null, ADMIN));

        assertThat(catching(() -> transactions.execute(status ->
                issueSigningKey.issue(institution.id(), LocalDate.now(), ADMIN))))
                .isInstanceOf(zw.ac.qvs.credential.application.RegistrationRejected.class)
                .hasMessageContaining("must start after");
    }

    @Test
    @DisplayName("a duplicate provider number is refused, naming the institution that holds it")
    void duplicateProviderNumberIsRefused() {
        String providerNumber = uniqueProviderNumber();

        transactions.execute(status -> onboardInstitution.onboard(
                new OnboardInstitution.Command("First University", "ZW", providerNumber,
                        LocalDate.now().plusYears(5), ADMIN)));

        assertThat(catching(() -> transactions.execute(status -> onboardInstitution.onboard(
                new OnboardInstitution.Command("Second University", "ZW", providerNumber,
                        LocalDate.now().plusYears(5), ADMIN)))))
                .hasMessageContaining("First University");
    }

    @Test
    @DisplayName("the same qualification title twice at one institution is refused")
    void duplicateQualificationIsRefused() {
        Institution institution = admit();
        transactions.execute(status -> addQualification.add(new AddQualification.Command(
                institution.id(), "BA Fine Art", 7, 360, null, ADMIN, "ADMIN")));

        // Case-insensitively: two entries differing only in capitals would be ambiguous exactly
        // where a registrar has to choose between them.
        assertThat(catching(() -> transactions.execute(status -> addQualification.add(
                new AddQualification.Command(institution.id(), "ba fine art", 7, 360, null,
                        ADMIN, "ADMIN")))))
                .hasMessageContaining("already offers");
    }

    private static Throwable catching(Runnable action) {
        try {
            action.run();
            throw new AssertionError("expected this to be refused, and it was not");
        } catch (RuntimeException e) {
            return e;
        }
    }
}
