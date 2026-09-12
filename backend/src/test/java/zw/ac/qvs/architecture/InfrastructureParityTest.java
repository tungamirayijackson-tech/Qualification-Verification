package zw.ac.qvs.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The Compose file and the Terraform describe one system, and must not disagree about it.
 *
 * <p>Two descriptions of the same deployment is a real cost, and it is worth being plain about
 * why it is paid. Compose exists so the demo is one command and needs nothing installed;
 * Terraform exists so the deployment is a reviewable artefact with explicit state and a plan
 * you can read before anything changes. Neither replaces the other, and both are wanted.
 *
 * <p>What is not wanted is silent drift. Someone bumping PostgreSQL in one file and not the
 * other produces a deployment that works on their machine and fails in the pipeline, or worse,
 * one that runs a version nobody reviewed. This test compares the parts where a disagreement
 * changes what actually runs: the images, and the ports a user connects to.
 *
 * <p>It deliberately does <b>not</b> demand that the two files agree about everything. Compose
 * publishes PostgreSQL and Redis on the host so a developer can attach a client; the Terraform
 * does not, because a deployment has no reason to put a database on a host interface. That is a
 * considered difference rather than drift, and a test that forbade it would be pushing the two
 * files towards being the same file, which would defeat the point of having both.
 */
@Requirement("BONUS-03")
class InfrastructureParityTest {

    private static final Path COMPOSE = Path.of("..", "docker-compose.yml");
    private static final Path VARIABLES = Path.of("..", "infra", "terraform", "variables.tf");
    private static final Path MAIN = Path.of("..", "infra", "terraform", "main.tf");

    private static String compose;
    private static String variables;
    private static String main;

    @BeforeAll
    static void readTheFiles() throws IOException {
        compose = Files.readString(COMPOSE);
        variables = Files.readString(VARIABLES);
        main = Files.readString(MAIN);
    }

    @Test
    @DisplayName("both descriptions run the same images, at the same versions")
    void imagesAgree() {
        // Compose writes the application image as ${QVS_IMAGE:-qvs-api:local}; the default is
        // the part that has to match, since that is what runs when nobody overrides it.
        Set<String> fromCompose = matches(compose,
                "image:\\s*(?:\\$\\{[A-Z_]+:-)?([A-Za-z0-9./_-]+:[A-Za-z0-9._-]+)\\}?");
        Set<String> fromTerraform = matches(variables,
                "default\\s*=\\s*\"([A-Za-z0-9./_-]+:[A-Za-z0-9._-]+)\"");

        assertThat(fromCompose)
                .as("every image Compose runs must be one Terraform knows about")
                .isNotEmpty()
                .isEqualTo(fromTerraform);
    }

    @Test
    @DisplayName("both bind the ports a user connects to, on loopback, at the same numbers")
    void publishedPortsAgree() {
        // The four a person actually opens. PostgreSQL and Redis are excluded on purpose --
        // see the class comment.
        assertThat(compose).contains("127.0.0.1:8081:8080");
        assertThat(variables).contains("default     = 8081");

        assertThat(compose).contains("127.0.0.1:9090:9090");
        assertThat(variables).contains("default     = 9090");

        assertThat(compose).contains("127.0.0.1:9091:9090");
        assertThat(main).contains("external = 9091");

        assertThat(compose).contains("127.0.0.1:3001:3000");
        assertThat(main).contains("external = 3001");
    }

    @Test
    @DisplayName("neither publishes the database or the rate limiter beyond loopback")
    void nothingIsBoundToEveryInterface() {
        // A port written as "5433:5432" with no address binds to every interface the host has.
        // On a laptop on a conference network that is a database on the internet.
        Matcher unbound = Pattern.compile("^\\s*-\\s*\"(\\d+):\\d+\"", Pattern.MULTILINE)
                .matcher(compose);

        assertThat(unbound.find())
                .as("every published port in Compose must name 127.0.0.1")
                .isFalse();

        Matcher terraformPorts = Pattern.compile("ports \\{[^}]*}", Pattern.DOTALL).matcher(main);
        int found = 0;
        while (terraformPorts.find()) {
            found++;
            assertThat(terraformPorts.group())
                    .as("every published port in Terraform must name 127.0.0.1")
                    .contains("127.0.0.1");
        }
        assertThat(found).as("the port blocks should have been found at all").isPositive();
    }

    @Test
    @DisplayName("both pass the application the same secrets, and default none of them")
    void secretsAgree() {
        for (String secret : new String[] {"QVS_FIELD_KEY", "QVS_NATIONAL_ID_SALT",
                "QVS_IP_SALT", "QVS_JWT_SECRET"}) {
            assertThat(compose).as("%s reaches the container under Compose", secret)
                    .contains(secret);
            assertThat(main).as("%s reaches the container under Terraform", secret)
                    .contains(secret);
        }

        // Every secret variable is sensitive and none has a default. A default here would be a
        // credential in the repository, which is how a project ships a key everybody knows.
        for (String variable : new String[] {"field_key", "national_id_salt", "ip_salt",
                "jwt_secret", "database_password", "grafana_password"}) {
            String block = blockFor(variable);
            assertThat(block).as("%s must be marked sensitive", variable).contains("sensitive");

            // The attribute, not the word. Matching the substring caught "Set rather than
            // defaulted" in a description and failed on prose that said the right thing.
            assertThat(block)
                    .as("%s must have no default: a default here is a credential in the "
                            + "repository", variable)
                    .doesNotContainPattern("(?m)^\\s*default\\s*=");
        }
    }

    /** The body of one `variable "name" { ... }` block. */
    private static String blockFor(String name) {
        Matcher matcher = Pattern.compile(
                        "variable\\s+\"" + Pattern.quote(name) + "\"\\s*\\{(.*?)\\n}",
                        Pattern.DOTALL)
                .matcher(variables);
        assertThat(matcher.find()).as("variables.tf should declare %s", name).isTrue();
        return matcher.group(1);
    }

    private static Set<String> matches(String text, String pattern) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile(pattern).matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
