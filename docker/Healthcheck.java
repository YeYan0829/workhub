import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** JRE-only container probe; no curl, shell HTTP client or application credentials. */
public final class Healthcheck {
    public static void main(String[] args) {
        HttpURLConnection connection = null;
        try {
            int port = Integer.parseInt(System.getenv().getOrDefault("APP_PORT", "8080"));
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + "/actuator/health")
                    .toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(4000);
            if (connection.getResponseCode() != 200) System.exit(1);
            String body = new String(connection.getInputStream().readNBytes(4096), StandardCharsets.UTF_8);
            if (!Pattern.compile("\"status\"\\s*:\\s*\"UP\"").matcher(body).find()) System.exit(1);
        } catch (Exception ignored) {
            System.exit(1);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
