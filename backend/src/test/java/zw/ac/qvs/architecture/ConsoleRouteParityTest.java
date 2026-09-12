package zw.ac.qvs.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import zw.ac.qvs.shared.adapter.in.ConsoleRoutes;
import zw.ac.qvs.testsupport.Requirement;

/**
 * Angular's route table and the server's forwarding list must describe the same console.
 *
 * <p>{@link ConsoleRoutes} exists because two pieces of server configuration once disagreed
 * about which paths the console owns, and {@code /sign-in} answered 401 for a fortnight. That
 * fixed the disagreement <em>inside</em> the server. It could not fix the other half: the routes
 * themselves are declared in {@code app.routes.ts}, and adding one there without adding it here
 * produces exactly the original bug — a screen that works when you click to it and 401s when you
 * reload or follow a bookmark.
 *
 * <p>{@code HttpContractIT} iterates {@code ConsoleRoutes} and proves every path in it is
 * served. By construction it cannot notice a path that is missing from it. This test reads the
 * other file.
 *
 * <p>The comparison is one-directional on purpose. Every Angular route must be forwarded; the
 * server may forward a path Angular has not declared yet, because that resolves to the
 * catch-all route and shows the verification page rather than a 404 — untidy, not broken.
 */
@Requirement("FR-06")
class ConsoleRouteParityTest {

    private static final Path ROUTES = Path.of("..", "frontend", "src", "app", "app.routes.ts");

    private static String routeTable;

    static boolean consoleSourcesArePresent() {
        return Files.isRegularFile(ROUTES);
    }

    @BeforeAll
    static void readRouteTable() throws IOException {
        if (consoleSourcesArePresent()) {
            routeTable = Files.readString(ROUTES);
        }
    }

    @Test
    @EnabledIf("consoleSourcesArePresent")
    @DisplayName("every route the console declares is one the server forwards to index.html")
    void everyAngularRouteIsForwarded() {
        Set<String> declared = declaredRoutes();
        List<String> forwarded = ConsoleRoutes.shellPaths();

        assertThat(declared).as("the route table should declare something").isNotEmpty();

        for (String route : declared) {
            assertThat(forwarded.stream().anyMatch(pattern -> matches(pattern, route)))
                    .as("Angular routes %s, and no pattern in ConsoleRoutes matches it — "
                            + "reloading that screen or following a bookmark to it would answer "
                            + "401. Forwarded patterns: %s", route, forwarded)
                    .isTrue();
        }
    }

    /**
     * Whether a forwarding pattern covers a route.
     *
     * <p>Matched rather than compared for equality, because {@code /credentials/*} genuinely
     * does serve {@code /credentials/register}: {@code *} is one path segment in both Spring's
     * pattern language and this test. An earlier version of this test compared the two sets
     * directly and failed on exactly that, reporting a bug in the server that was a bug in
     * itself.
     */
    private static boolean matches(String pattern, String route) {
        String regex = Pattern.quote(pattern).replace("*", "\\E[^/]+\\Q");
        return route.matches(regex);
    }

    /**
     * The paths from {@code app.routes.ts}, in the wildcard form {@code ConsoleRoutes} uses.
     *
     * <p>Angular writes a parameter as {@code :token}; the server matches a segment as
     * {@code *}. Redirects and the catch-all are skipped: neither renders a screen.
     */
    private static Set<String> declaredRoutes() {
        Set<String> routes = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("path:\\s*'([^']*)'").matcher(routeTable);

        while (matcher.find()) {
            String path = matcher.group(1);
            if (path.isEmpty() || path.equals("**")) {
                continue;
            }
            routes.add("/" + path.replaceAll(":[A-Za-z0-9_]+", "*"));
        }
        return routes;
    }
}
