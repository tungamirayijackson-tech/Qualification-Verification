package zw.ac.qvs.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * FR-08's central claim, tested against the real database.
 *
 * <p>"Append-only" is the kind of assertion that is easy to write in a report and easy to leave
 * unenforced. These tests exist because the guarantee lives in a PostgreSQL trigger, and a
 * trigger is exactly the sort of thing a later migration can drop without anybody noticing —
 * the application would carry on working perfectly, and the property the whole audit story
 * rests on would simply be gone.
 *
 * <p>An in-memory database would pass these tests vacuously by not having the trigger at all,
 * which is the argument for Testcontainers in one sentence.
 */
@Requirement("FR-08")
class LedgerImmutabilityIT extends PostgresIntegrationTest {

    private static final String SIXTY_FOUR_A = "a".repeat(64);
    private static final String SIXTY_FOUR_ZERO = "0".repeat(64);
    private static final String SIXTY_FOUR_SEVEN = "7".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    private long appendOne() {
        jdbc.update("""
                INSERT INTO audit_entry
                    (actor_role, action, subject_ref, payload_hash, prev_hash, entry_hash)
                VALUES ('REGISTRAR', 'CREDENTIAL_ISSUED', ?, ?, ?, ?)
                """,
                "ZW-PR0142-2026-" + String.format("%06d", (int) (Math.random() * 999_999)),
                SIXTY_FOUR_A, SIXTY_FOUR_ZERO,
                SIXTY_FOUR_SEVEN.substring(0, 60) + String.format("%04x", (int) (Math.random() * 0xFFFF)));

        Long seq = jdbc.queryForObject("SELECT max(seq) FROM audit_entry", Long.class);
        assertThat(seq).isNotNull();
        return seq;
    }

    @Test
    @DisplayName("an entry can be appended")
    void appendWorks() {
        long seq = appendOne();

        Integer found = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE seq = ?", Integer.class, seq);

        assertThat(found).isEqualTo(1);
    }

    @Test
    @DisplayName("UPDATE is refused by the database, not merely avoided by the application")
    void updateIsRefused() {
        long seq = appendOne();

        assertThatThrownBy(() ->
                jdbc.update("UPDATE audit_entry SET subject_ref = 'tampered' WHERE seq = ?", seq))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        String subject = jdbc.queryForObject(
                "SELECT subject_ref FROM audit_entry WHERE seq = ?", String.class, seq);
        assertThat(subject).isNotEqualTo("tampered");
    }

    @Test
    @DisplayName("DELETE is refused")
    void deleteIsRefused() {
        long seq = appendOne();

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_entry WHERE seq = ?", seq))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        Integer stillThere = jdbc.queryForObject(
                "SELECT count(*) FROM audit_entry WHERE seq = ?", Integer.class, seq);
        assertThat(stillThere).isEqualTo(1);
    }

    @Test
    @DisplayName("TRUNCATE is refused, which a row-level trigger alone would not catch")
    void truncateIsRefused() {
        appendOne();

        // TRUNCATE bypasses row-level triggers entirely. Without the separate statement-level
        // guard, "append-only" would have a one-word workaround -- and it is the workaround
        // somebody reaches for precisely when they want the history gone.
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_entry"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        Integer remaining = jdbc.queryForObject("SELECT count(*) FROM audit_entry", Integer.class);
        assertThat(remaining).isPositive();
    }

    @Test
    @DisplayName("the immutability triggers are actually installed")
    void triggersExist() {
        // Guards against the failure mode these tests exist for: a later migration dropping
        // the trigger. Without this, a schema that had lost it would make every test above
        // fail confusingly rather than pointing at the cause.
        var triggers = jdbc.queryForList(
                "SELECT tgname FROM pg_trigger WHERE tgrelid = 'audit_entry'::regclass "
                        + "AND NOT tgisinternal",
                String.class);

        assertThat(triggers)
                .contains("audit_entry_immutable", "audit_entry_no_truncate");
    }

    @Test
    @DisplayName("a duplicate entry hash is refused, because two entries cannot share one")
    void entryHashIsUnique() {
        jdbc.update("""
                INSERT INTO audit_entry
                    (actor_role, action, subject_ref, payload_hash, prev_hash, entry_hash)
                VALUES ('AUDITOR', 'CHAIN_VERIFIED', 'chain', ?, ?, ?)
                """, SIXTY_FOUR_A, SIXTY_FOUR_ZERO, "b".repeat(64));

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_entry
                    (actor_role, action, subject_ref, payload_hash, prev_hash, entry_hash)
                VALUES ('AUDITOR', 'CHAIN_VERIFIED', 'chain', ?, ?, ?)
                """, SIXTY_FOUR_A, SIXTY_FOUR_ZERO, "b".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("a malformed hash is refused by the column constraints")
    void hashShapeIsChecked() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_entry
                    (actor_role, action, subject_ref, payload_hash, prev_hash, entry_hash)
                VALUES ('AUDITOR', 'CHAIN_VERIFIED', 'chain', ?, ?, ?)
                """, "NOT-A-HASH", SIXTY_FOUR_ZERO, "c".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("an unknown action is refused, so nothing unintended reaches the ledger")
    void actionIsConstrained() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO audit_entry
                    (actor_role, action, subject_ref, payload_hash, prev_hash, entry_hash)
                VALUES ('AUDITOR', 'SOMETHING_INVENTED', 'chain', ?, ?, ?)
                """, SIXTY_FOUR_A, SIXTY_FOUR_ZERO, "d".repeat(64)))
                .isInstanceOf(DataAccessException.class);
    }
}
