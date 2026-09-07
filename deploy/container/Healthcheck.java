import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Container-local readiness probe; no shell, credentials, or application sessions. */
public final class Healthcheck {
    private Healthcheck() { }
    public static void main(String[] args) {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var port = Integer.parseInt(System.getenv().getOrDefault("ROOTS_PORT", "8080"));
            var path = System.getenv().getOrDefault("ROOTS_HEALTH_PATH", "/_roots/health");
            if (!path.startsWith("/") || path.startsWith("//") || path.contains("#") || path.contains("?")) {
                System.exit(1);
            }
            var response = client.send(HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) System.exit(1);
        } catch (Exception unavailable) {
            System.exit(1);
        }
    }
}
