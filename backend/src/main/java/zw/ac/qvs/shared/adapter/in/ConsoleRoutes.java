package zw.ac.qvs.shared.adapter.in;

import java.util.List;

/**
 * The URLs the Angular console owns, listed once.
 *
 * <p>Two separate pieces of configuration need this same list, and they need to agree. The view
 * controllers in {@link SpaForwardingConfiguration} forward these paths to {@code index.html} so
 * the bundle can boot and resolve the route itself; the authorisation rules in
 * {@code SecurityConfiguration} have to let those requests through, because a filter chain that
 * refuses them answers 401 before the forward ever happens.
 *
 * <p>They did not agree. The forwarding list had {@code /sign-in} and {@code /audit}; the permit
 * list had {@code /verify/**} and {@code /institutions}. The result was that reloading the page
 * or following a bookmark to {@code /sign-in} returned 401 — the sign-in screen, of all things,
 * unreachable unless you arrived at it by clicking through from somewhere else. Nothing caught
 * it because each file was individually correct and consistent with its own comments. Only the
 * pair was wrong, and no single file was in a position to notice.
 *
 * <p>So the list lives here and both read it. Adding a console screen is one edit, in the place
 * the route already has to be added, and the two configurations cannot drift apart again.
 *
 * <p>Permitting these paths discloses nothing: what they return is the same static bundle every
 * visitor already downloads. The console's data lives behind {@code /api/v1}, which this list
 * deliberately says nothing about — the Angular route guards decide what to render, and the API
 * decides what to answer, and it is the second of those that is the security boundary.
 */
public final class ConsoleRoutes {

    /**
     * Paths served by the console shell, in a syntax both callers accept: {@code *} matches a
     * single path segment.
     *
     * <p>{@code /credentials/*} covers {@code register}, {@code import} and a serial alike,
     * which is why they are not enumerated. It cannot collide with the API, whose routes are
     * all under {@code /api/v1} and {@code /public/v1}.
     */
    private static final List<String> SHELL_PATHS = List.of(
            "/verify",
            "/verify/*",
            "/sign-in",
            "/credentials",
            "/credentials/*",
            "/qualifications",
            "/institutions",
            "/users",
            "/audit",
            "/forbidden");

    private ConsoleRoutes() {
    }

    /** The console's paths. Immutable, so neither caller can quietly edit the other's view. */
    public static List<String> shellPaths() {
        return SHELL_PATHS;
    }

    /** The same list, for the varargs matcher APIs that want an array. */
    public static String[] shellPathArray() {
        return SHELL_PATHS.toArray(String[]::new);
    }
}
