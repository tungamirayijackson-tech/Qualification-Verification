package zw.ac.qvs.shared.adapter.in;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the single-page console own its own URLs.
 *
 * <p>The console routes {@code /verify/{token}} and {@code /credentials/{serial}} in the
 * browser. Those paths exist only in Angular's route table, so a cold request for one — a
 * holder opening a share link they were emailed, or anybody pressing reload — reaches Spring,
 * which has no handler for it and answers 404. Forwarding them to {@code index.html} lets the
 * bundle boot and resolve the route itself.
 *
 * <p>An earlier version of this class matched broad patterns with a negative lookahead for
 * {@code api|public|actuator}. It worked, but it meant every new API prefix had to be
 * remembered in a regex or it would silently start returning HTML. Enumerating the console's
 * paths instead fails in the safer direction: forget a console route and that one deep link
 * 404s visibly, rather than an API path quietly answering with a web page.
 *
 * <p>The patterns are enumerated rather than expressed as a catch-all. A blanket
 * {@code /**} forward would swallow genuine 404s from the API and hand a JSON client a page of
 * HTML instead of a problem document, which is a maddening thing to debug.
 *
 * <p>The enumeration itself lives in {@link ConsoleRoutes}, because the authorisation rules
 * need exactly the same list and the two used to disagree — see the note there.
 */
@Configuration
public class SpaForwardingConfiguration implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        for (String path : ConsoleRoutes.shellPaths()) {
            registry.addViewController(path).setViewName("forward:/index.html");
        }
    }
}
