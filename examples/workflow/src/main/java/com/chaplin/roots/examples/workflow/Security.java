package com.chaplin.roots.examples.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

@Configuration(proxyBeanMethods = false)
class Security {
    @Bean SecurityFilterChain filterChain(HttpSecurity http, Environment env) throws Exception {
        http.authorizeHttpRequests(rules -> rules
                .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR).permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .anyRequest().authenticated());
        // Roots validates its session-bound CSRF token and Origin on these exact endpoints.
        // Spring retains CSRF protection for login, logout, and all other POSTs.
        http.csrf(csrf -> csrf.ignoringRequestMatchers(request -> {
            var path = request.getRequestURI().substring(request.getContextPath().length());
            return request.getMethod().equals("POST") &&
                    (path.equals("/app/_roots/action") || path.equals("/app/_roots/dispose"));
        }));
        http.logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        switch (env.getProperty("workflow.auth", "oidc")) {
            case "local" -> {
                var encoder = new BCryptPasswordEncoder(12);
                var users = new InMemoryUserDetailsManager(List.of(
                        User.withUsername("editor").password("{bcrypt}" + encoder.encode(password(env, "EDITOR"))).roles("EDITOR").build(),
                        User.withUsername("viewer").password("{bcrypt}" + encoder.encode(password(env, "VIEWER"))).roles("VIEWER").build()));
                http.userDetailsService(users);
                http.formLogin(login -> login.defaultSuccessUrl("/app/customers", true));
            }
            case "oidc" -> {
                var registration = ClientRegistrations.fromIssuerLocation(Application.required(env, "WORKFLOW_OIDC_ISSUER"))
                        .registrationId("company").clientId(Application.required(env, "WORKFLOW_OIDC_CLIENT_ID"))
                        .clientSecret(Application.required(env, "WORKFLOW_OIDC_CLIENT_SECRET"))
                        .redirectUri(Application.required(env, "WORKFLOW_OIDC_REDIRECT_URI"))
                        .scope("openid", "profile").build();
                var delegate = new OidcUserService();
                http.oauth2Login(login -> login
                        .clientRegistrationRepository(new InMemoryClientRegistrationRepository(registration))
                        .defaultSuccessUrl("/app/customers", true)
                        .userInfoEndpoint(info -> info.oidcUserService(request -> {
                            var user = delegate.loadUser(request);
                            // Only groups in the verified ID token grant roles, never arbitrary request headers.
                            var roles = roles(user.getIdToken().getClaimAsStringList("groups"));
                            var name = subject(user.getIdToken().getIssuer().toString(), user.getSubject());
                            return new DefaultOidcUser(roles, user.getIdToken(), user.getUserInfo()) {
                                @Override public String getName() { return name; }
                            };
                        })));
            }
            default -> throw new IllegalStateException("WORKFLOW_AUTH must be local or oidc");
        }
        return http.build();
    }

    static Set<SimpleGrantedAuthority> roles(List<String> groups) {
        if (groups == null) return Set.of();
        var result = new java.util.HashSet<SimpleGrantedAuthority>();
        if (groups.contains("roots-viewer")) result.add(new SimpleGrantedAuthority("ROLE_VIEWER"));
        if (groups.contains("roots-editor")) result.add(new SimpleGrantedAuthority("ROLE_EDITOR"));
        return Set.copyOf(result);
    }

    static String subject(String issuer, String subject) {
        try {
            return "oidc:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((issuer.length() + ":" + issuer + subject).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String password(Environment env, String role) {
        var value = Application.required(env, "WORKFLOW_" + role + "_PASSWORD");
        if (value.length() < 16 || value.getBytes(StandardCharsets.UTF_8).length > 72)
            throw new IllegalStateException("Local passwords require at least 16 characters and at most 72 UTF-8 bytes");
        return value;
    }
}
