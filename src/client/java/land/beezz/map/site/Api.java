package land.beezz.map.site;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

/**
 * Talks to play.beezzland.com the way its own pages do. Reading the map is plain GET requests, the same files every
 * guest gets. Sending a marker needs the player's own site session (the "bz" cookie a login confirmed in the bot gives):
 * it lives only on the player's computer (Site.session()), the mod itself has no account, key or token.
 */
public final class Api {
    public static final String SITE = "https://play.beezzland.com";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL) // /head/<nick> answers with a redirect to the skin's head
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final String AGENT = "BeezzMap/" + land.beezz.map.BeezzMap.VERSION + " (Minecraft mod)";

    /** An answer: the code, the body and the session the site set (only after a confirmed login). */
    public record Reply(int code, String body, String cookie) {}

    private Api() {}

    /** The body of a GET, unpacked if it came gzipped (Pl3xMap's .gz files are sent with Content-Encoding: gzip); null on 404. */
    public static byte[] get(String pathOrUrl) throws IOException {
        return get(pathOrUrl, null);
    }

    public static byte[] get(String pathOrUrl, String session) throws IOException {
        String url = pathOrUrl.startsWith("http") ? pathOrUrl : SITE + pathOrUrl;
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", AGENT)
                .header("Accept-Encoding", "gzip")
                .header("Cache-Control", "no-cache")
                .GET();
        if (session != null && url.startsWith(SITE)) b.header("Cookie", "bz=" + session);
        HttpResponse<byte[]> r = send(b.build());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new IOException(url + ": HTTP " + r.statusCode());
        return unzip(r.body());
    }

    public static String text(String path) throws IOException {
        return text(path, null);
    }

    public static String text(String path, String session) throws IOException {
        byte[] b = get(path, session);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** A POST to the site: JSON (a String) or a picture (bytes). Any answer comes back, errors included. */
    public static Reply post(String path, Object body, String session) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(SITE + path))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", AGENT);
        if (body instanceof String s) {
            b.header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(s));
        } else {
            b.header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofByteArray((byte[]) body));
        }
        if (session != null) b.header("Cookie", "bz=" + session);
        HttpResponse<byte[]> r = send(b.build());
        return new Reply(r.statusCode(), new String(unzip(r.body()), java.nio.charset.StandardCharsets.UTF_8), cookie(r));
    }

    /** A GET whose session cookie matters (the login's status). */
    public static Reply ask(String path) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(SITE + path))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", AGENT)
                .header("Cache-Control", "no-cache")
                .GET().build();
        HttpResponse<byte[]> r = send(req);
        return new Reply(r.statusCode(), new String(unzip(r.body()), java.nio.charset.StandardCharsets.UTF_8), cookie(r));
    }

    private static String cookie(HttpResponse<?> r) {
        for (String h : r.headers().allValues("set-cookie")) {
            String first = h.split(";", 2)[0].trim();
            if (first.startsWith("bz=") && first.length() > 3) return first.substring(3);
        }
        return null;
    }

    private static HttpResponse<byte[]> send(HttpRequest req) throws IOException {
        try {
            return HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static byte[] unzip(byte[] body) throws IOException {
        if (body.length > 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                return in.readAllBytes();
            }
        }
        return body;
    }
}
