package zw.ac.qvs.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class for integration tests that need the real database.
 *
 * <p>A real PostgreSQL 16, not an in-memory substitute. That distinction is the whole point:
 * the append-only trigger, the trigram index and the CHECK constraints only exist on the real
 * engine, so a test against H2 would pass while proving nothing about what runs in production.
 *
 * <p>The container is static, so one instance is shared across every subclass in the run
 * rather than being started per class.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("integration")
public abstract class PostgresIntegrationTest {

    @SuppressWarnings("resource") // closed by the Testcontainers JVM shutdown hook
    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("qvs")
                    .withUsername("qvs")
                    .withPassword("qvs")
                    .withReuse(false);

    /** Unique per JVM, so repeated and parallel runs never contend over the same key files. */
    private static final java.nio.file.Path VAULT_DIRECTORY;

    static {
        POSTGRES.start();
        try {
            VAULT_DIRECTORY = java.nio.file.Files.createTempDirectory("qvs-vault-");
            VAULT_DIRECTORY.toFile().deleteOnExit();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not create a temporary key vault", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        // A vault directory per run. The key store refuses to overwrite an existing private
        // key -- deliberately, since overwriting one would invalidate every signature already
        // made with it -- so a shared directory would make the second run of a rotation test
        // fail for a reason that has nothing to do with the code under test.
        registry.add("qvs.vault.directory", () -> VAULT_DIRECTORY.toString());
    }
}
