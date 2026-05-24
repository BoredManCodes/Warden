package io.warden.analytics;

import com.maxmind.geoip2.DatabaseReader;
import com.maxmind.geoip2.exception.AddressNotFoundException;
import com.maxmind.geoip2.model.CountryResponse;
import io.warden.config.WardenConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Optional GeoIP lookup, used by the analytics Geography page. Operators
 * provide a MaxMind licence key via config (or the WARDEN_GEOIP_LICENSE_KEY env
 * var); Warden downloads the configured GeoLite2 edition into
 * {@code plugins/Warden/data/geoip/} and refreshes it on a configurable
 * cadence (default weekly).
 *
 * If GeoIP is disabled, the licence key is missing, or the download fails, the
 * service stays silent: {@link #lookupCountryIso(String)} returns
 * {@link Optional#empty()} and the Geography page shows a "not configured"
 * panel. We never bundle the .mmdb file in the plugin jar - that data is
 * licensed separately by MaxMind.
 */
public final class GeoIpService {

    private static final String DOWNLOAD_BASE = "https://download.maxmind.com/app/geoip_download";

    private final WardenConfig.GeoIp cfg;
    private final ExecutorService bgExecutor;
    private final Logger log;
    private final HttpClient http;
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile DatabaseReader reader;
    private volatile Path dbPath;
    private volatile Instant lastRefreshAt;
    private volatile String lastError;

    public GeoIpService(WardenConfig.GeoIp cfg, ExecutorService bgExecutor, Logger log) {
        this.cfg = cfg;
        this.bgExecutor = bgExecutor;
        this.log = log;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * Best-effort init. Loads any existing .mmdb on disk synchronously so the
     * Geography page works immediately on the first request, then kicks off an
     * async refresh in the background if the local copy is stale.
     */
    public void initOnEnable() {
        if (!cfg.enabled()) {
            log.fine("geoip: disabled in config");
            return;
        }
        Path candidate = expectedDbPath();
        try {
            Files.createDirectories(cfg.dbDir());
        } catch (IOException e) {
            this.lastError = "could not create geoip dir: " + e.getMessage();
            log.log(Level.WARNING, "geoip: " + lastError, e);
            return;
        }
        if (Files.exists(candidate)) {
            try {
                openReader(candidate);
                log.info("geoip: loaded " + cfg.edition() + " from " + candidate
                        + " (modified " + Files.getLastModifiedTime(candidate) + ")");
            } catch (IOException e) {
                this.lastError = "open failed: " + e.getMessage();
                log.log(Level.WARNING, "geoip: failed to open existing db at " + candidate, e);
            }
        } else if (!cfg.hasLicense()) {
            this.lastError = "no .mmdb on disk and no licence key configured";
            log.warning("geoip: " + lastError + " - drop a " + cfg.edition()
                    + ".mmdb in " + cfg.dbDir() + " or set analytics.geoip.license_key");
            return;
        }
        if (cfg.hasLicense() && needsRefresh(candidate)) {
            bgExecutor.submit(this::refreshFromMaxmind);
        }
    }

    /** Synchronous lookup. Returns ISO 3166-1 alpha-2, or empty for unknown / unconfigured. */
    public Optional<String> lookupCountryIso(String rawIp) {
        DatabaseReader r = reader;
        if (r == null || rawIp == null || rawIp.isBlank()) return Optional.empty();
        try {
            InetAddress addr = InetAddress.getByName(rawIp);
            CountryResponse resp = r.country(addr);
            if (resp == null || resp.getCountry() == null) return Optional.empty();
            String iso = resp.getCountry().getIsoCode();
            return (iso == null || iso.isBlank()) ? Optional.empty() : Optional.of(iso);
        } catch (AddressNotFoundException ignored) {
            return Optional.empty();
        } catch (Exception e) {
            log.log(Level.FINE, "geoip: lookup failed for " + redact(rawIp), e);
            return Optional.empty();
        }
    }

    public boolean isReady() {
        return reader != null;
    }

    public Status status() {
        Path p = dbPath;
        Long modified = null;
        if (p != null && Files.exists(p)) {
            try {
                modified = Files.getLastModifiedTime(p).toMillis();
            } catch (IOException ignored) {
                // leave null
            }
        }
        return new Status(
                cfg.enabled(),
                cfg.hasLicense(),
                isReady(),
                cfg.edition(),
                modified,
                lastRefreshAt == null ? null : lastRefreshAt.toEpochMilli(),
                lastError
        );
    }

    public void close() {
        DatabaseReader r = reader;
        reader = null;
        if (r != null) {
            try { r.close(); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    private Path expectedDbPath() {
        return cfg.dbDir().resolve(cfg.edition() + ".mmdb");
    }

    private boolean needsRefresh(Path candidate) {
        if (!Files.exists(candidate)) return true;
        try {
            Instant modified = Files.getLastModifiedTime(candidate).toInstant();
            return Instant.now().isAfter(modified.plus(Duration.ofDays(cfg.refreshDays())));
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Pull a fresh tar.gz from MaxMind, extract the .mmdb, atomically replace
     * the on-disk copy, and reopen the reader. Runs on the background executor.
     */
    void refreshFromMaxmind() {
        if (!cfg.hasLicense()) return;
        if (!refreshLock.tryLock()) {
            log.fine("geoip: refresh already in progress; skipping");
            return;
        }
        try {
            log.info("geoip: refreshing " + cfg.edition() + " from MaxMind...");
            String url = DOWNLOAD_BASE
                    + "?edition_id=" + URLEncoder.encode(cfg.edition(), StandardCharsets.UTF_8)
                    + "&license_key=" + URLEncoder.encode(cfg.licenseKey(), StandardCharsets.UTF_8)
                    + "&suffix=tar.gz";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "Warden-GeoIP/1 (https://github.com/warden)")
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) {
                String snippet;
                try (InputStream in = resp.body()) {
                    byte[] buf = in.readNBytes(512);
                    snippet = new String(buf, StandardCharsets.UTF_8).trim();
                }
                this.lastError = "download failed: HTTP " + resp.statusCode()
                        + (snippet.isEmpty() ? "" : " - " + snippet);
                log.warning("geoip: " + lastError);
                return;
            }
            Path target = expectedDbPath();
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), cfg.edition() + "-", ".mmdb.tmp");
            try (InputStream body = resp.body();
                 GZIPInputStream gz = new GZIPInputStream(body)) {
                if (!extractMmdb(gz, tmp)) {
                    this.lastError = "tar archive did not contain a .mmdb entry";
                    log.warning("geoip: " + lastError);
                    Files.deleteIfExists(tmp);
                    return;
                }
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            openReader(target);
            this.lastRefreshAt = Instant.now();
            this.lastError = null;
            log.info("geoip: refreshed " + cfg.edition() + " (" + Files.size(target) + " bytes)");
        } catch (Exception e) {
            this.lastError = "refresh failed: " + e.getMessage();
            log.log(Level.WARNING, "geoip: " + lastError, e);
        } finally {
            refreshLock.unlock();
        }
    }

    private synchronized void openReader(Path mmdb) throws IOException {
        DatabaseReader next = new DatabaseReader.Builder(mmdb.toFile()).build();
        DatabaseReader prev = this.reader;
        this.reader = next;
        this.dbPath = mmdb;
        if (prev != null) {
            try { prev.close(); } catch (Exception ignored) { /* best-effort */ }
        }
    }

    /**
     * Minimal POSIX/ustar tar reader. We only need to find the first regular
     * file whose name ends in {@code .mmdb} and stream it to {@code out};
     * everything else (LICENSE.txt, README, etc.) is skipped.
     */
    private static boolean extractMmdb(InputStream tarStream, Path out) throws IOException {
        byte[] header = new byte[512];
        while (true) {
            int read = readFully(tarStream, header, 0, 512);
            if (read < 512) return false;
            if (isZeroBlock(header)) return false; // end marker

            String name = readString(header, 0, 100);
            long size = readOctal(header, 124, 12);
            char typeFlag = (char) (header[156] & 0xFF);
            // Long-name "L" entries (GNU tar) put the real name in the next payload.
            // GeoLite2 archives don't use them in practice, but skip defensively.
            if (typeFlag == 'L') {
                long padded = ((size + 511) / 512) * 512;
                skipFully(tarStream, padded);
                continue;
            }
            boolean isRegular = (typeFlag == '0' || typeFlag == 0 || typeFlag == ' ');
            if (isRegular && name.toLowerCase().endsWith(".mmdb")) {
                try (var fos = Files.newOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    long remaining = size;
                    while (remaining > 0) {
                        int want = (int) Math.min(buf.length, remaining);
                        int got = tarStream.read(buf, 0, want);
                        if (got < 0) throw new IOException("unexpected EOF inside tar entry " + name);
                        fos.write(buf, 0, got);
                        remaining -= got;
                    }
                }
                long padding = (512 - (size % 512)) % 512;
                skipFully(tarStream, padding);
                return true;
            }
            long padded = ((size + 511) / 512) * 512;
            skipFully(tarStream, padded);
        }
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int got = in.read(buf, off + total, len - total);
            if (got < 0) return total;
            total += got;
        }
        return total;
    }

    private static void skipFully(InputStream in, long n) throws IOException {
        long remaining = n;
        byte[] scratch = new byte[4096];
        while (remaining > 0) {
            int want = (int) Math.min(scratch.length, remaining);
            int got = in.read(scratch, 0, want);
            if (got < 0) return;
            remaining -= got;
        }
    }

    private static boolean isZeroBlock(byte[] header) {
        for (byte b : header) if (b != 0) return false;
        return true;
    }

    private static String readString(byte[] header, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && header[end] != 0) end++;
        return new String(header, off, end - off, StandardCharsets.US_ASCII);
    }

    private static long readOctal(byte[] header, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (v == 0) continue;
                else break;
            }
            if (b < '0' || b > '7') break;
            v = (v << 3) | (b - '0');
        }
        return v;
    }

    private static String redact(String ip) {
        if (ip == null) return "(null)";
        int dot = ip.lastIndexOf('.');
        int colon = ip.lastIndexOf(':');
        int cut = Math.max(dot, colon);
        return cut > 0 ? ip.substring(0, cut + 1) + "x" : "x";
    }

    public record Status(
            boolean enabled,
            boolean hasLicense,
            boolean ready,
            String edition,
            Long dbModifiedAt,
            Long lastRefreshAt,
            String lastError
    ) {}
}
