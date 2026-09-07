package com.chaplin.roots.spring.boot;

import com.chaplin.roots.AuthorizationPolicy;
import com.chaplin.roots.Response;
import com.chaplin.roots.Roots;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RootsSpringSecurityIntegrationTest {
    private static final Pattern VIEW = Pattern.compile("data-roots-view=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("data-roots-csrf=\"([^\"]+)\"");
    private static final Pattern ACTION = Pattern.compile("data-roots-on-click=\"([^\"]+)\"");

    @Test
    void propagatesSpringAuthenticationAndInvalidatesViewsWhenIdentityChanges() throws Exception {
        try (var context = new SpringApplicationBuilder(SecurityApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "server.port=0",
                        "server.servlet.context-path=/secure-app",
                        "roots.transport=servlet",
                        "roots.development=false",
                        "spring.main.banner-mode=off",
                        "logging.level.root=OFF"
                )
                .run()) {
            var web = (ServletWebServerApplicationContext) context;
            var base = URI.create("http://127.0.0.1:" + web.getWebServer().getPort() + "/secure-app/");
            try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
                var anonymousIdentity = get(client, base.resolve("api/identity"), null);
                var userIdentity = get(client, base.resolve("api/identity"), "user");
                var adminIdentity = get(client, base.resolve("api/identity"), "admin");
                assertEquals(200, anonymousIdentity.statusCode(), anonymousIdentity.body());
                assertTrue(anonymousIdentity.body().contains("anonymous"), anonymousIdentity.body());
                assertTrue(userIdentity.body().contains("\"name\":\"user\""), userIdentity.body());
                assertTrue(userIdentity.body().contains("ROLE_USER"), userIdentity.body());
                assertTrue(adminIdentity.body().contains("ROLE_ADMIN"), adminIdentity.body());

                assertEquals(403, get(client, base.resolve("admin"), null).statusCode());
                assertEquals(403, get(client, base.resolve("admin"), "user").statusCode());
                var adminPage = get(client, base.resolve("admin"), "admin");
                assertEquals(200, adminPage.statusCode(), adminPage.body());
                assertTrue(adminPage.body().contains("Page identity: admin"), adminPage.body());

                var credentials = credentials(adminPage.body());
                var adminAction = action(client, base.resolve("_roots/action"), "admin", credentials);
                assertEquals(200, adminAction.statusCode(), adminAction.body());
                assertTrue(adminAction.body().contains("Action identity: admin"), adminAction.body());

                var changedIdentity = action(client, base.resolve("_roots/action"), "user", credentials);
                assertEquals(409, changedIdentity.statusCode(), changedIdentity.body());
                assertTrue(changedIdentity.body().contains("expired"), changedIdentity.body());
                assertEquals(0, context.getBean(RootsRuntime.class).runtimeSnapshot().liveViews());

                var alreadyClosed = action(client, base.resolve("_roots/action"), "admin", credentials);
                assertEquals(409, alreadyClosed.statusCode(), alreadyClosed.body());

                var streamOwnerPage = get(client, base.resolve("admin"), "admin");
                var streamOwner = credentials(streamOwnerPage.body());
                var changedStreamIdentity = get(
                        client,
                        base.resolve("_roots/stream?view=" + encode(streamOwner.view())
                                + "&csrf=" + encode(streamOwner.csrf())
                                + "&protocol=" + encode(Roots.PROTOCOL_VERSION)),
                        "user"
                );
                assertEquals(409, changedStreamIdentity.statusCode(), changedStreamIdentity.body());
                assertTrue(changedStreamIdentity.body().contains("expired"), changedStreamIdentity.body());
                assertEquals(0, context.getBean(RootsRuntime.class).runtimeSnapshot().liveViews());
            }
        }
    }

    private static HttpResponse<String> get(HttpClient client, URI uri, String username) throws Exception {
        var request = HttpRequest.newBuilder(uri).GET();
        authorize(request, username);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> action(
            HttpClient client,
            URI uri,
            String username,
            Credentials credentials
    ) throws Exception {
        var form = "_view=" + encode(credentials.view())
                + "&_csrf=" + encode(credentials.csrf())
                + "&_protocol=" + encode(Roots.PROTOCOL_VERSION)
                + "&_action=" + encode(credentials.action())
                + "&_event=click";
        var request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        authorize(request, username);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void authorize(HttpRequest.Builder request, String username) {
        if (username != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":password").getBytes(StandardCharsets.UTF_8)
            ));
        }
    }

    private static Credentials credentials(String html) {
        return new Credentials(attribute(VIEW, html), attribute(CSRF, html), attribute(ACTION, html));
    }

    private static String attribute(Pattern pattern, String html) {
        var matcher = pattern.matcher(html);
        assertTrue(matcher.find(), () -> "Missing " + pattern + " in " + html);
        return matcher.group(1);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record Credentials(String view, String csrf, String action) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableRoots(com.chaplin.roots.spring.boot.securityapp.Application.class)
    static class SecurityApplication {
        @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
            return http
                    .authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll())
                    .httpBasic(Customizer.withDefaults())
                    .csrf(csrf -> csrf.disable())
                    .build();
        }

        @Bean
        UserDetailsService users() {
            return new InMemoryUserDetailsManager(
                    User.withUsername("user").password("{noop}password").roles("USER").build(),
                    User.withUsername("admin").password("{noop}password").roles("ADMIN").build()
            );
        }

        @Bean
        RootsConfigCustomizer securityPolicies() {
            var rejection = Response.json(403, "{\"error\":\"forbidden\"}");
            return builder -> builder.authorize("admin", AuthorizationPolicy.role("ADMIN", rejection));
        }
    }
}
