package zw.ac.qvs.credential;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import zw.ac.qvs.support.PostgresIntegrationTest;
import zw.ac.qvs.testsupport.Requirement;

/**
 * NFR-05, recoverability: the schema must be reconstructible from nothing.
 *
 * <p>This is the test that makes "we can rebuild from an empty database" checkable rather than
 * assumed. It also implicitly proves that Hibernate's {@code ddl-auto: validate} agrees with
 * the migrations, because the Spring context would not have started otherwise.
 */
@Requirement("NFR-05")
class MigrationIT extends PostgresIntegrationTest {

    private static final Duration BUDGET = Duration.ofSeconds(60);
    private static final String SCRATCH_SCHEMA = "rebuild_check";

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("migrates an empty schema from scratch inside the recoverability budget")
    void rebuildsFromEmpty() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCRATCH_SCHEMA + " CASCADE");

        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCRATCH_SCHEMA)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .load();

        Instant started = Instant.now();
        var result = flyway.migrate();
        Duration elapsed = Duration.between(started, Instant.now());

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isPositive();
        assertThat(elapsed)
                .as("NFR-05 budget: rebuild from empty in under %s", BUDGET)
                .isLessThan(BUDGET);

        jdbc.execute("DROP SCHEMA IF EXISTS " + SCRATCH_SCHEMA + " CASCADE");
    }

    @Test
    @DisplayName("V1 leaves the institution table, its constraints and its trigram index in place")
    void schemaShape() {
        Integer tables = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = 'institution'",
                Integer.class);
        assertThat(tables).isEqualTo(1);

        var constraints = jdbc.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'institution'::regclass",
                String.class);
        assertThat(constraints)
                .contains("institution_provider_no_unique", "institution_country_iso");

        var indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'institution'",
                String.class);
        assertThat(indexes).contains("institution_name_trgm");
    }

    @Test
    @DisplayName("the migration history is recorded, so a second start is a no-op")
    void migrationsAreVersioned() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);

        assertThat(applied).isPositive();
    }
}
