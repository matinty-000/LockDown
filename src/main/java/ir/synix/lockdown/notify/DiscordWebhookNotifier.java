package ir.synix.lockdown.notify;

import ir.synix.lockdown.config.Settings;
import ir.synix.lockdown.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Posts alerts and confirmation codes to a Discord channel webhook.
 *
 * <p>Uses the JDK's built-in {@link HttpClient}, so there is no extra runtime
 * dependency. All I/O is asynchronous and never blocks the game thread.
 */
public final class DiscordWebhookNotifier implements Notifier {

    private final Settings settings;
    private final Logger logger;
    private final HttpClient http;

    public DiscordWebhookNotifier(Settings settings, Logger logger) {
        this.settings = settings;
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public String name() {
        return "Discord";
    }

    @Override
    public boolean enabled() {
        return settings.discord.enabled();
    }

    @Override
    public void send(String subject, String body) {
        if (!enabled()) return;
        String content = (subject == null || subject.isBlank() ? "" : "**" + subject + "**\n") + body;
        if (content.length() > 2000) content = content.substring(0, 1997) + "...";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", content);
        payload.put("allowed_mentions", Map.of("parse", List.of()));
        if (settings.discord.username != null && !settings.discord.username.isBlank()) {
            payload.put("username", settings.discord.username);
        }
        if (settings.discord.avatarUrl != null && !settings.discord.avatarUrl.isBlank()) {
            payload.put("avatar_url", settings.discord.avatarUrl);
        }
        byte[] json = Json.write(payload).getBytes(StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(settings.discord.webhook.trim()))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("User-Agent", "LockDown")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json))
                .build();

        CompletableFuture<HttpResponse<Void>> fut = http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        fut.whenComplete((resp, err) -> {
            if (err != null) {
                logger.log(Level.WARNING, "[LockDown] Discord webhook failed: " + err.getMessage());
            } else if (resp.statusCode() >= 400) {
                logger.warning("[LockDown] Discord webhook returned HTTP " + resp.statusCode());
            }
        });
    }
}
