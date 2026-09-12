package zw.ac.qvs.shared.adapter;

import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import zw.ac.qvs.shared.adapter.in.ConsoleRoutes;

/**
 * Who may reach what.
 *
 * <p>The rule this configuration exists to enforce is that there is exactly one unauthenticated
 * data path — {@code /public/v1/verify/**} — and it takes a share token. Everything else that
 * touches the register requires a bearer token and a role.
 *
 * <p>Sessions are stateless. There is no server-side session to fixate, no session cookie to
 * steal, and no CSRF surface on the API, which is why CSRF protection is disabled here rather
 * than left on as cargo: a stateless bearer-token API is not vulnerable to it, and leaving it
 * enabled would break every client without protecting anything.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private final String jwtSecret;

    /**
     * The management port, when it is a different port from the application's; otherwise -1.
     *
     * <p>This distinction is the whole safety argument for the rule below. When the actuator
     * has a port of its own, that port is bound to loopback and the Compose network and the
     * application's port does not serve the actuator at all — so permitting the endpoints on
     * it cannot expose them to anybody who was not already inside. When the two are the same
     * port, which is the default and what every test and local run uses, no such rule is added
     * and {@code /actuator/prometheus} stays behind ADMIN.
     */
    private final int isolatedManagementPort;

    public SecurityConfiguration(
            QvsProperties properties,
            @Value("${server.port:8080}") int serverPort,
            @Value("${management.server.port:${server.port:8080}}") int managementPort) {
        this.jwtSecret = properties.crypto().jwtSecret();
        this.isolatedManagementPort = managementPort == serverPort ? -1 : managementPort;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> {
                    if (isolatedManagementPort > 0) {
                        // Prometheus holds no bearer token and cannot obtain one. What keeps
                        // this honest is `management.endpoints.web.exposure.include`, which
                        // maps only health, info and prometheus: /actuator/env and the rest of
                        // the tree are not merely refused here, they do not exist.
                        auth.requestMatchers(
                                request -> request.getLocalPort() == isolatedManagementPort)
                                .permitAll();
                    }
                    authorise(auth);
                })
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .headers(SecurityConfiguration::securityHeaders);

        return http.build();
    }

    /**
     * The whole authorisation policy, in two readable blocks.
     *
     * <p>Split only by audience -- what needs no credentials, then what each role may reach --
     * and read top to bottom in that order, because these matchers are evaluated in the order
     * they are added. An assessor reviewing who may reach what still reads one file. The
     * per-endpoint {@code @PreAuthorize} annotations restate the same rules as a second guard
     * that survives a URL being remapped.
     */
    private static void authorise(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        permitWithoutCredentials(auth);
        authoriseByRole(auth);
        auth.anyRequest().authenticated();
    }

    /**
     * What is reachable with no credentials at all: the public verification door, signing in,
     * the probes, the API documentation, and the console's own static bundle.
     */
    private static void permitWithoutCredentials(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        auth
                        // The public door. Rate-limited, token-scoped, and returning a
                        // deliberately thin response (S04).
                        .requestMatchers("/public/v1/**").permitAll()

                        // Signing in cannot itself require being signed in.
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/**").permitAll()

                        // Probes, named individually: `/actuator/health/**` also matches the
                        // aggregate, which was publishing the stack (see deviations.md).
                        .requestMatchers("/actuator/health/liveness",
                                "/actuator/health/readiness", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // The generated API documentation, so an assessor can read the
                        // contract without credentials.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()

                        // The console itself is a static bundle; its data calls are what
                        // require authorisation. Angular emits its chunks flat at the root
                        // with content hashes in the filenames, so the wildcards are what
                        // match main-D6KEB3QB.js and its siblings.
                        .requestMatchers(HttpMethod.GET, "/", "/index.html", "/favicon.ico",
                                "/*.js", "/*.css", "/*.js.map", "/assets/**", "/media/**")
                        .permitAll()

                        // The console's own routes, forwarded to index.html by
                        // SpaForwardingConfiguration. They have to be permitted or the filter
                        // chain answers 401 before the forward happens -- which is exactly
                        // what it used to do to /sign-in. Read from the one list both this
                        // file and the forwarding rules share, so they cannot drift again.
                        // What these return is the static bundle every visitor downloads
                        // anyway; the data behind it is authorised per endpoint below.
                        .requestMatchers(HttpMethod.GET, ConsoleRoutes.shellPathArray())
                        .permitAll();
    }

    /**
     * FR-10, the role matrix. Written here as one block rather than scattered across
     * controllers, so the whole policy can be reviewed in one place -- and tested as a matrix.
     */
    private static void authoriseByRole(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>
                    .AuthorizationManagerRequestMatcherRegistry auth) {
        auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/institutions").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/institutions/*/keys")
                        .hasRole("ADMIN")
                        // Registrars only. An administrator admits the institution; what it
                        // awards is the institution's own business to declare.
                        .requestMatchers(HttpMethod.POST, "/api/v1/qualifications")
                        .hasRole("REGISTRAR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/credentials/import")
                        .hasRole("REGISTRAR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/credentials/*/revoke")
                        .hasRole("REGISTRAR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/credentials/*/share")
                        .hasRole("REGISTRAR")
                        .requestMatchers(HttpMethod.POST, "/api/v1/credentials").hasRole("REGISTRAR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/credentials/**")
                        .hasAnyRole("REGISTRAR", "AUDITOR")
                        .requestMatchers("/api/v1/audit/**").hasRole("AUDITOR")

                        // Accounts. Every power in the system is handed out here, so it is the
                        // narrowest rule in the matrix.
                        .requestMatchers("/api/v1/users/**").hasRole("ADMIN");
    }

    /** Response headers that apply to every route, console and API alike. */
    private static void securityHeaders(
            org.springframework.security.config.annotation.web.configurers
                    .HeadersConfigurer<HttpSecurity> headers) {
        headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                "default-src 'self'; "
                                        + "script-src 'self'; "
                                        + "style-src 'self' 'unsafe-inline'; "
                                        + "img-src 'self' data:; "
                                        + "connect-src 'self'; "
                                        + "frame-ancestors 'none'; "
                                        + "base-uri 'self'; "
                                        + "form-action 'self'"))
                        .frameOptions(frame -> frame.deny())
                        .httpStrictTransportSecurity(hsts -> hsts
                                .includeSubDomains(true)
                                .maxAgeInSeconds(31_536_000));
    }

    /**
     * Validates access tokens.
     *
     * <p>HS256 with a shared secret: appropriate because exactly one service both issues and
     * validates these tokens. The moment a second service needs to validate them the right
     * answer becomes an asymmetric key and a JWKS endpoint, because sharing a signing secret
     * with a validator makes every validator able to mint tokens. That threshold is worth
     * naming in the report rather than discovering later.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(
                        jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"))
                .build();
    }

    /**
     * Maps the token's {@code role} claim onto a Spring Security authority.
     *
     * <p>The default converter reads {@code scope} or {@code scp}; this system issues a single
     * {@code role} claim, and the authority prefix has to match what the matchers above expect.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("role");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
