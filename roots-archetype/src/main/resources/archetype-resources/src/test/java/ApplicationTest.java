package ${package};

import dev.roots.Roots;
import dev.roots.RootsConfig;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        }
    }
}
