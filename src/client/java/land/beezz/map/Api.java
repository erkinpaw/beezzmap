package land.beezz.map;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * Reads the public map of play.beezzland.com: the same files every guest of the site gets. Nothing is ever sent but
 * plain GET requests, no account, no token.
 */
final class Api {
    static final String SITE = "https://play.beezzland.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // /head/<nick> answers with a redirect to the skin's head
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final String AGENT = "BeezzMap/" + BeezzMap.VERSION + " (Minecraft mod)";

    private Api() {}

    /** The body of a GET, unpacked if it came gzipped (Pl3xMap's .gz files are sent with Content-Encoding: gzip); null on 404. */
    static byte[] get(String pathOrUrl) throws IOException {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : SITE + pathOrUrl;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", AGENT)
                .header("Accept-Encoding", "gzip")
                .header("Cache-Control", "no-cache")
                .GET().build();
        HttpResponse<byte[]> r;
        try {
            r = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new IOException(url + ": HTTP " + r.statusCode());
        byte[] body = r.body();
        if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }

    static String text(String path) throws IOException {
        byte[] b = get(path);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }
}
