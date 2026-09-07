package com.chaplin.roots.examples.workflow;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowSecurityTest {
    static final String PASSWORD = "test-only-password-with-24-chars";
    @TempDir Path temp;
    static ConfigurableApplicationContext start(String url) {
        return new SpringApplicationBuilder(Application.class).run(
                "--server.port=0", "--WORKFLOW_AUTH=local", "--WORKFLOW_SECURE_COOKIES=false",
                "--WORKFLOW_JDBC_URL=" + url, "--WORKFLOW_EDITOR_PASSWORD=" + PASSWORD,
                "--WORKFLOW_VIEWER_PASSWORD=" + PASSWORD, "--spring.main.banner-mode=off",
                "--logging.level.root=WARN", "--roots.shutdown-timeout=1s",
                "--spring.lifecycle.timeout-per-shutdown-phase=2s");
    }
    static String base(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }
    @Test void realServletLoginRolesCsrfAndReadiness() throws Exception {
        var url = "jdbc:h2:file:" + temp.resolve("security").toString().replace('\\', '/');
        try (var ignored = CustomerRepositoryTest.pool(url, true)) { }
        try (var context = start(url); var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var base = base(context);
            assertEquals(200, get(client, base + "/actuator/health/readiness").statusCode());
            assertEquals(302, get(client, base + "/app/customers").statusCode());
            assertEquals(403, post(client, base + "/login", "username=editor&password=" + encode(PASSWORD)).statusCode());
            login(client, base, "viewer");
            var page = get(client, base + "/app/customers");
            assertEquals(200, page.statusCode(), page.body()); assertFalse(page.body().contains("New customer"));
            assertEquals(403, get(client, base + "/app/drafts").statusCode());
            assertEquals(400, post(client, base + "/app/_roots/action", "_view=missing&_action=create").statusCode());
            assertEquals(403, post(client, base + "/logout", "").statusCode());
            assertEquals(200, get(client, base + "/logout").statusCode());
            try (var editor = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
                login(editor, base, "editor");
                var html = get(editor, base + "/app/customers").body();
                assertTrue(html.contains("New customer"));
                assertEquals(200, get(editor, base + "/app/workflow.css").statusCode());
                var action = "_view=" + encode(match("data-roots-view=\"([^\"]+)\"", html))
                        + "&_csrf=" + encode(match("data-roots-csrf=\"([^\"]+)\"", html))
                        + "&_action=" + encode(match("data-roots-on-click=\"([^\"]+)\"", html))
                        + "&_event=click&_protocol=" + encode(com.chaplin.roots.Roots.PROTOCOL_VERSION);
                assertEquals(409, post(editor, base + "/app/_roots/action",
                        action.replaceFirst("&_csrf=[^&]+", "&_csrf=invalid")).statusCode());
                assertTrue(context.getBean(CustomerRepository.class).drafts(CustomerRepositoryTest.EDITOR).isEmpty());
                var response = post(editor, base + "/app/_roots/action", action);
                assertEquals(200, response.statusCode(), response.body());
                assertTrue(response.body().contains("/drafts/"), response.body());
            }
        }
    }
    @Test void oidcRolesFailClosedAndSubjectNamesIncludeIssuer() {
        assertTrue(Security.roles(null).isEmpty());
        assertTrue(Security.roles(List.of("admin", "ROLE_EDITOR", "editor")).isEmpty());
        assertEquals(2, Security.roles(List.of("roots-editor", "roots-viewer")).size());
        assertNotEquals(Security.subject("https://one.example", "123"), Security.subject("https://two.example", "123"));
        assertEquals(Security.subject("https://one.example", "123"), Security.subject("https://one.example", "123"));
    }
    static void login(HttpClient client, String base, String username) throws Exception {
        var login = get(client, base + "/login");
        assertEquals(200, login.statusCode(), login.body());
        var csrf = match("name=\"_csrf\"[^>]*value=\"([^\"]+)\"", login.body());
        var result = post(client, base + "/login", "username=" + username + "&password=" + encode(PASSWORD) + "&_csrf=" + encode(csrf));
        assertEquals(302, result.statusCode(), result.body());
        assertTrue(result.headers().firstValue("Location").orElse("").endsWith("/app/customers"), result.headers().toString());
    }
    static HttpResponse<String> get(HttpClient client, String uri) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    static HttpResponse<String> post(HttpClient client, String uri, String form) throws Exception {
        var origin = URI.create(uri);
        return client.send(HttpRequest.newBuilder(origin).header("Content-Type", "application/x-www-form-urlencoded")
                .header("Origin", origin.getScheme() + "://" + origin.getAuthority())
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    }
    static String match(String regex, String value) {
        var matcher = Pattern.compile(regex).matcher(value); assertTrue(matcher.find(), value); return matcher.group(1);
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
