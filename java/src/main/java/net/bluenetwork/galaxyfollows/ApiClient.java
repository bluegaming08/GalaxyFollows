package net.bluenetwork.galaxyfollows;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP client for the BlueGalaxy plugin API (open-source GalaxyFollows
 * edition). Uses the same HMAC authentication as the BlueGalaxy plugin:
 *
 *   X-BG-Server:    server-id
 *   X-BG-Timestamp: unix seconds (±60s skew allowed)
 *   X-BG-Nonce:     unique per request (replay protection)
 *   X-BG-Key:       the api key (only ever over TLS)
 *   X-BG-Signature: hex(HMAC_SHA256(apiKey, "{server}|{ts}|{nonce}|{sha256(body)}"))
 *
 * All calls are async so the Minecraft main thread never blocks.
 */
public final class ApiClient {

    /** HTTP-layer result. body is non-null exactly when success is true. */
    public record Result(boolean success, int status, Map<String, Object> json, String raw, String error) {
        public String str(String key) { return json == null ? null : Json.str(json, key); }
        public java.util.List<Object> arr(String key) { return json == null ? null : Json.arr(json, key); }
    }

    /** BlueGalaxy endpoint. Public by design (open source). The plugin
     *  identifies itself ONLY with the username it rewards - there is no
     *  api key, no pairing code, nothing secret to configure or store. */
    public static final String API_URL =
            "https://tybyyeeapetvzjenzjid.supabase.co/functions/v1/plugin";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    public CompletableFuture<Result> post(String path, Object bodyObj) {
        String body = Json.write(bodyObj);
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(API_URL + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        return http.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(resp -> {
                    Map<String, Object> json = null;
                    String raw = resp.body();
                    if (raw != null && !raw.isBlank() && raw.trim().startsWith("{")) {
                        try { json = Json.parseObject(raw); } catch (RuntimeException ignored) {}
                    }
                    boolean ok = resp.statusCode() >= 200 && resp.statusCode() < 300;
                    if (ok) return new Result(true, resp.statusCode(), json, raw, null);
                    String err = json != null ? Json.str(json, "error") : null;
                    return new Result(false, resp.statusCode(), json, raw,
                            err != null ? err : ("HTTP " + resp.statusCode()));
                })
                .exceptionally(ex -> {
                    String msg = ex.getCause() != null ? ex.getCause().getMessage() : ex.getMessage();
                    return new Result(false, 0, null, null, msg == null ? "network error" : msg);
                });
    }
}
