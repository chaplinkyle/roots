package ${package};

import com.chaplin.roots.Roots;
import com.chaplin.roots.RootsConfig;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ApplicationTest {
    @Test
    void rendersTheHomePage() throws Exception {
        var config = RootsConfig.forApplication(Application.class).port(0).development(false).build();
        try (var application = Roots.start(config)) {
            var response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(application.uri()).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("Java is the full stack."));
            assertTrue(response.body().contains("name=\"theme-color\" data-roots-head=\"theme-color\" content=\"#17324d\""));
            assertTrue(response.body().contains("property=\"og:site_name\" data-roots-head=\"og:site_name\" content=\"${artifactId}\""));

            var about = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(application.uri().resolve("/about")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(200, about.statusCode());
            assertTrue(about.body().contains("data-roots-static"));
            assertTrue(about.body().contains("About"));
            assertTrue(about.body().contains("property=\"og:site_name\" data-roots-head=\"og:site_name\" content=\"${artifactId}\""));
            assertTrue(about.headers().firstValue("X-Roots-Prerender").isPresent());
            assertTrue(about.headers().firstValue("Set-Cookie").isEmpty());
            assertFalse(about.body().contains("/_roots/client.js"));

            var missing = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(application.uri().resolve("/missing"))
                            .header("Accept", "text/html")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertEquals(404, missing.statusCode());
            assertTrue(missing.body().contains("That page does not exist."));
            assertTrue(missing.body().contains("data-roots-view"));

            try (var manifest = Application.class.getResourceAsStream(
                    "/META-INF/roots/routes/${package}.Application.routes")) {
                assertTrue(manifest != null);
                var routes = new String(manifest.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                assertTrue(routes.startsWith("ROOTS_ROUTE_MANIFEST\t3\t"));
                assertTrue(routes.contains("NOT_FOUND\t/\t${package}.pages.NotFound"));
                assertTrue(routes.contains("ERROR_PAGE\t/\t${package}.pages.ErrorPage"));
            }
        }
    }
}
