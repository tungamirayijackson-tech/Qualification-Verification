package zw.ac.qvs.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import zw.ac.qvs.testsupport.Requirement;

/**
 * The console's stylesheet must actually load under this system's content security policy.
 *
 * <p>It did not, for as long as the production build and the CSP have both existed, and the
 * failure was silent in the worst way. Angular's critical-CSS inliner extracts what it thinks
 * is above the fold, inlines that, and defers the rest with
 *
 * <pre>{@code <link rel="stylesheet" href="styles.css" media="print" onload="this.media='all'">}</pre>
 *
 * <p>That {@code onload} is an inline event handler. The policy this application sets is
 * {@code script-src 'self'} with no {@code 'unsafe-inline'} — deliberately — so the browser
 * refuses to run it, {@code media} stays {@code print}, and the entire global stylesheet
 * applies only when somebody prints the page.
 *
 * <p>Nothing caught it. The build succeeded, no test failed, and the development server does
 * not run the inliner at all, so the console looked correct on every developer's machine and
 * shipped in the container with its cards, tables, buttons and badges unstyled. It was found by
 * taking a screenshot of the running container and looking at it.
 *
 * <p>The fix is {@code inlineCritical: false} in the production build. This test asserts the
 * outcome rather than the setting: what matters is the shape of the tag in the file that ships.
 *
 * <p>Skipped when the console has not been built, so the backend suite still runs on its own.
 * It fails, rather than skips, when a build exists and is wrong.
 */
@Requirement("ARCH-03")
class ConsoleStylesheetTest {

    private static final Path INDEX =
            Path.of("..", "frontend", "dist", "qvs-console", "browser", "index.html");

    static boolean consoleHasBeenBuilt() {
        return Files.isRegularFile(INDEX);
    }

    @Test
    @EnabledIf("consoleHasBeenBuilt")
    @DisplayName("the built console loads its stylesheet without an inline event handler")
    void stylesheetLoadsUnderTheContentSecurityPolicy() throws IOException {
        String index = Files.readString(INDEX);

        assertThat(index)
                .as("the console must link a stylesheet at all")
                .containsPattern("<link[^>]*rel=\"stylesheet\"");

        // The two halves of the bug, asserted separately so a failure says which one returned.
        assertThat(index)
                .as("an inline onload handler is blocked by script-src 'self', so whatever it "
                        + "was going to enable never gets enabled")
                .doesNotContain("onload=");

        assertThat(index)
                .as("a stylesheet left at media=print applies only when the page is printed")
                .doesNotContain("media=\"print\"");
    }
}
