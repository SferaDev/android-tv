package com.gathin.tvhome;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.io.ByteArrayInputStream;

/** This client's key, certificate and unique id, plus the server certificate once paired; kept in one directory. */
final class GameStreamIdentity {
    private static final String CLIENT_NAME = "NVIDIA GameStream Client";
    private static final long YEAR_MS = 365L * 24 * 3600 * 1000;

    final PrivateKey key;
    final X509Certificate certificate;
    final byte[] certificateDer;
    final String uniqueId;
    private final File dir;
    private volatile X509Certificate serverCertificate;

    private GameStreamIdentity(File dir, PrivateKey key, byte[] der, String uniqueId, X509Certificate server)
            throws GeneralSecurityException {
        this.dir = dir;
        this.key = key;
        this.certificateDer = der;
        this.certificate = parse(der);
        this.uniqueId = uniqueId;
        this.serverCertificate = server;
    }

    /** Loads the identity from the directory, creating the key, certificate and id on first use. */
    static GameStreamIdentity load(File dir) throws IOException, GeneralSecurityException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        File keyFile = new File(dir, "client.key"), certFile = new File(dir, "client.crt");
        File idFile = new File(dir, "uniqueid"), serverFile = new File(dir, "server.crt");
        if (!keyFile.isFile() || !certFile.isFile()) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair keys = generator.generateKeyPair();
            long now = System.currentTimeMillis();
            byte[] der = GameStreamCrypto.selfSignedCertificate(keys, CLIENT_NAME, now - 24 * 3600 * 1000L,
                    now + 20 * YEAR_MS, new BigInteger(63, new SecureRandom()).add(BigInteger.ONE));
            Files.write(keyFile.toPath(), keys.getPrivate().getEncoded());
            Files.write(certFile.toPath(), der);
            serverFile.delete();
        }
        if (!idFile.isFile()) newUniqueId(dir);
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile.toPath())));
        X509Certificate server = serverFile.isFile() ? parse(Files.readAllBytes(serverFile.toPath())) : null;
        return new GameStreamIdentity(dir, key, Files.readAllBytes(certFile.toPath()),
                new String(Files.readAllBytes(idFile.toPath()), StandardCharsets.US_ASCII).trim(), server);
    }

    /** A new client id, which the host takes for another client; the key and certificate stay. */
    static void newUniqueId(File dir) throws IOException {
        Files.write(new File(dir, "uniqueid").toPath(), GameStreamCrypto.hex(GameStreamCrypto.random(8)).toLowerCase()
                .getBytes(StandardCharsets.US_ASCII));
    }

    /** The server certificate pinned for TLS, or null before pairing. */
    X509Certificate serverCertificate() {
        return serverCertificate;
    }

    boolean paired() {
        return serverCertificate != null;
    }

    /** Pins the certificate in memory only, for the last pairing step that already runs over TLS. */
    void pinServerCertificate(X509Certificate cert) {
        serverCertificate = cert;
    }

    void savePaired() throws IOException, GeneralSecurityException {
        Files.write(new File(dir, "server.crt").toPath(), serverCertificate.getEncoded());
    }

    void clearPaired() {
        serverCertificate = null;
        new File(dir, "server.crt").delete();
    }

    static X509Certificate parse(byte[] der) throws GeneralSecurityException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(der));
    }
}
