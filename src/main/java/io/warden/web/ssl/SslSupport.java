package io.warden.web.ssl;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.SecureRequestCustomizer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Loads PEM cert + private key files into an in-memory PKCS12 keystore and
 * produces a Jetty SSL ServerConnector for Javalin. Designed for the common
 * Let's Encrypt layout (fullchain.pem + privkey.pem) so admins can point
 * Warden directly at certbot's live directory without converting anything.
 */
public final class SslSupport {

    private SslSupport() {}

    /**
     * Build a Jetty {@link SslContextFactory.Server} from PEM files. The
     * keystore is in-memory with a random password; nothing is persisted.
     *
     * @throws IOException if a file is missing, malformed, or in a legacy PKCS1
     *                     format we don't auto-convert.
     */
    public static SslContextFactory.Server buildSslContextFactory(Path certFile, Path keyFile) throws Exception {
        if (certFile == null || !Files.exists(certFile)) {
            throw new IOException("SSL cert file not found: "
                    + (certFile == null ? "<null>" : certFile.toAbsolutePath().toString()));
        }
        if (keyFile == null || !Files.exists(keyFile)) {
            throw new IOException("SSL key file not found: "
                    + (keyFile == null ? "<null>" : keyFile.toAbsolutePath().toString()));
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> chain;
        try (var in = Files.newInputStream(certFile)) {
            Collection<? extends Certificate> col = cf.generateCertificates(in);
            chain = col.stream().map(c -> (Certificate) c).toList();
        }
        if (chain.isEmpty()) {
            throw new IOException("No X.509 certificates found in " + certFile.toAbsolutePath());
        }

        PrivateKey key = readPrivateKey(keyFile);
        char[] password = randomPassword();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        ks.setKeyEntry("warden", key, password, chain.toArray(new Certificate[0]));

        SslContextFactory.Server ssl = new SslContextFactory.Server();
        ssl.setKeyStore(ks);
        ssl.setKeyStorePassword(new String(password));
        ssl.setKeyManagerPassword(new String(password));
        return ssl;
    }

    /**
     * BiFunction suitable for {@code cfg.jetty.addConnector(...)}. Adds a second
     * Jetty ServerConnector that terminates TLS on {@code port}, alongside
     * whatever HTTP connector Javalin set up via {@code app.start(host, port)}.
     */
    public static BiFunction<Server, HttpConfiguration, Connector> connectorFactory(
            String host, int port, SslContextFactory.Server ssl) {
        return (server, baseHttpConfig) -> {
            HttpConfiguration httpsConfig = new HttpConfiguration(baseHttpConfig);
            httpsConfig.setSecureScheme("https");
            httpsConfig.setSecurePort(port);
            httpsConfig.addCustomizer(new SecureRequestCustomizer());
            ServerConnector connector = new ServerConnector(server,
                    new SslConnectionFactory(ssl, "http/1.1"),
                    new HttpConnectionFactory(httpsConfig));
            connector.setHost(host);
            connector.setPort(port);
            return connector;
        };
    }

    /**
     * Inspect a PEM cert file for the dashboard status panel. Returns empty if
     * the file is missing or unparseable - callers should surface "no cert
     * found" copy in that case.
     */
    public static Optional<CertSummary> inspect(Path certFile) {
        if (certFile == null || !Files.exists(certFile)) return Optional.empty();
        try (var in = Files.newInputStream(certFile)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> col = cf.generateCertificates(in);
            if (col.isEmpty()) return Optional.empty();
            X509Certificate leaf = (X509Certificate) col.iterator().next();
            return Optional.of(new CertSummary(
                    leaf.getSubjectX500Principal().getName(),
                    leaf.getIssuerX500Principal().getName(),
                    leaf.getNotBefore().toInstant(),
                    leaf.getNotAfter().toInstant(),
                    col.size(),
                    leaf.getSigAlgName()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static PrivateKey readPrivateKey(Path keyFile) throws Exception {
        String raw = Files.readString(keyFile);
        if (raw.contains("BEGIN RSA PRIVATE KEY") || raw.contains("BEGIN EC PRIVATE KEY")) {
            throw new IOException("Private key is in legacy PKCS1 format. Convert with: "
                    + "openssl pkcs8 -topk8 -nocrypt -in " + keyFile.getFileName()
                    + " -out " + keyFile.getFileName() + ".pkcs8");
        }
        String body = raw.replaceAll("-----BEGIN [^-]+-----", "")
                         .replaceAll("-----END [^-]+-----", "")
                         .replaceAll("\\s+", "");
        if (body.isEmpty()) {
            throw new IOException("Private key file is empty or has no PEM body: "
                    + keyFile.toAbsolutePath());
        }
        byte[] der = Base64.getDecoder().decode(body);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(spec);
        } catch (Exception rsaFail) {
            try {
                return KeyFactory.getInstance("EC").generatePrivate(spec);
            } catch (Exception ecFail) {
                throw new IOException("Private key in " + keyFile.toAbsolutePath()
                        + " is not a recognised RSA or EC PKCS8 key: " + ecFail.getMessage());
            }
        }
    }

    private static char[] randomPassword() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b).toCharArray();
    }

    /**
     * Snapshot of the leaf cert for the dashboard status panel. Subject/issuer
     * are the raw RFC 2253 DN strings; the handler can pretty-print them.
     */
    public record CertSummary(
            String subject,
            String issuer,
            Instant notBefore,
            Instant notAfter,
            int chainLength,
            String sigAlg
    ) {
        public boolean expired() {
            return Instant.now().isAfter(notAfter);
        }
        public long daysUntilExpiry() {
            return java.time.Duration.between(Instant.now(), notAfter).toDays();
        }
    }
}
