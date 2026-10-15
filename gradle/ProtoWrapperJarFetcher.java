import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * PROTOTYPE (measurement only): extracts gradle-wrapper.jar from the nested
 * lib/plugins/gradle-wrapper-main-VERSION.jar inside gradle-VERSION-bin.zip using HTTP range
 * requests, then verifies it against the published gradle-VERSION-wrapper.jar.sha256.
 * Usage: java ProtoWrapperJarFetcher.java VERSION OUTPUT_DIR
 */
public class ProtoWrapperJarFetcher {
    private static final String BASE = "https://services.gradle.org/distributions";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).build();
    private static long fetched = 0;
    private static int requests = 0;
    private static String finalUrl;
    private static long totalSize;

    public static void main(String[] args) throws Exception {
        String version = args[0];
        Path out = Path.of(args[1]);
        Files.createDirectories(out);

        // 1. Resolve the redirect once and learn the total size from a one-byte range request.
        HttpResponse<byte[]> probe = get(BASE + "/gradle-" + version + "-bin.zip", 0, 0);
        finalUrl = probe.uri().toString();
        String contentRange = probe.headers().firstValue("Content-Range").orElseThrow();
        totalSize = Long.parseLong(contentRange.substring(contentRange.indexOf('/') + 1));

        // 2. End of central directory (non-zip64 is enough for these archives).
        long tailStart = Math.max(0, totalSize - 65557);
        byte[] tail = range(tailStart, totalSize - 1);
        int eocd = -1;
        for (int i = tail.length - 22; i >= 0; i--) {
            if (le(tail, i, 4) == 0x06054b50L) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            throw new IllegalStateException("end of central directory not found");
        }
        long cdSize = le(tail, eocd + 12, 4);
        long cdOffset = le(tail, eocd + 16, 4);

        // 3. Central directory, then find the wrapper-main jar entry.
        byte[] cd = range(cdOffset, cdOffset + cdSize - 1);
        String wanted = "gradle-" + version + "/lib/plugins/gradle-wrapper-main-" + version + ".jar";
        int pos = 0;
        long localOffset = -1;
        long compressedSize = 0;
        int method = 0;
        while (pos + 46 <= cd.length && le(cd, pos, 4) == 0x02014b50L) {
            int nameLen = (int) le(cd, pos + 28, 2);
            int extraLen = (int) le(cd, pos + 30, 2);
            int commentLen = (int) le(cd, pos + 32, 2);
            String name = new String(cd, pos + 46, nameLen, StandardCharsets.UTF_8);
            if (name.equals(wanted)) {
                method = (int) le(cd, pos + 10, 2);
                compressedSize = le(cd, pos + 20, 4);
                localOffset = le(cd, pos + 42, 4);
                break;
            }
            pos += 46 + nameLen + extraLen + commentLen;
        }
        if (localOffset < 0) {
            throw new IllegalStateException(wanted + " not found in the distribution");
        }

        // 4. Local header (to skip name and extra), then the entry data.
        byte[] lh = range(localOffset, localOffset + 29);
        long dataStart = localOffset + 30 + le(lh, 26, 2) + le(lh, 28, 2);
        byte[] data = range(dataStart, dataStart + compressedSize - 1);
        byte[] nestedJar = method == 0 ? data : inflate(data);

        // 5. Pull gradle-wrapper.jar out of the nested jar, in memory.
        byte[] wrapperJar = null;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(nestedJar))) {
            for (ZipEntry e = zin.getNextEntry(); e != null; e = zin.getNextEntry()) {
                if (e.getName().equals("gradle-wrapper.jar")) {
                    wrapperJar = zin.readAllBytes();
                    break;
                }
            }
        }
        if (wrapperJar == null) {
            throw new IllegalStateException("gradle-wrapper.jar not found inside the wrapper-main jar");
        }

        // 6. Verify against the published checksum, then write it out.
        String expected = new String(get(BASE + "/gradle-" + version + "-wrapper.jar.sha256").body(),
                StandardCharsets.UTF_8).trim();
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(wrapperJar));
        System.out.println("B: bytes fetched " + fetched + " in " + requests + " requests (of " + totalSize + ")");
        if (!expected.equals(actual)) {
            System.out.println("B: wrapper jar MISMATCH expected " + expected + " actual " + actual);
            System.exit(1);
        }
        Files.write(out.resolve("gradle-wrapper.jar"), wrapperJar);
        System.out.println("B: wrapper jar OK (" + actual + ")");
    }

    private static HttpResponse<byte[]> get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        count(resp.body().length);
        return resp;
    }

    private static HttpResponse<byte[]> get(String url, long from, long to) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Range", "bytes=" + from + "-" + to).GET().build();
        HttpResponse<byte[]> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofByteArray());
        count(resp.body().length);
        return resp;
    }

    private static byte[] range(long from, long to) throws Exception {
        return get(finalUrl, from, to).body();
    }

    private static void count(int bytes) {
        fetched += bytes;
        requests++;
    }

    private static long le(byte[] b, int off, int len) {
        long v = 0;
        for (int i = len - 1; i >= 0; i--) {
            v = (v << 8) | (b[off + i] & 0xffL);
        }
        return v;
    }

    private static byte[] inflate(byte[] deflated) throws Exception {
        Inflater inflater = new Inflater(true);
        inflater.setInput(deflated);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!inflater.finished()) {
            int n = inflater.inflate(buf);
            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                break;
            }
            bos.write(buf, 0, n);
        }
        inflater.end();
        return bos.toByteArray();
    }
}
