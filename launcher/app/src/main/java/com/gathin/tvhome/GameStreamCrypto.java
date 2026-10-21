package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** The byte math of GameStream pairing, and the client's self-signed certificate; no Android classes. */
final class GameStreamCrypto {
    private static final byte[] SHA256_WITH_RSA = {0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 1, 1, 0x0b};
    private static final byte[] COMMON_NAME = {0x55, 0x04, 0x03};

    private GameStreamCrypto() { }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        return sb.toString();
    }

    static byte[] unhex(String text) {
        if (text.length() % 2 != 0) throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(text.charAt(2 * i), 16), lo = Character.digit(text.charAt(2 * i + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("not hex");
            out[i] = (byte) (hi << 4 | lo);
        }
        return out;
    }

    static byte[] random(int length) {
        byte[] bytes = new byte[length];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    static String generatePin() {
        SecureRandom random = new SecureRandom();
        return String.format(Locale.ROOT, "%d%d%d%d", random.nextInt(10), random.nextInt(10),
                random.nextInt(10), random.nextInt(10));
    }

    /** SHA-256 from server generation 7, SHA-1 before. */
    static byte[] hash(boolean sha256, byte[] data) {
        try {
            return MessageDigest.getInstance(sha256 ? "SHA-256" : "SHA-1").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.write(part, 0, part.length);
        return out.toByteArray();
    }

    /** The first 16 bytes of the hash of salt followed by the PIN text. */
    static byte[] aesKey(boolean sha256, byte[] salt, String pin) {
        return Arrays.copyOf(hash(sha256, concat(salt, pin.getBytes(StandardCharsets.UTF_8))), 16);
    }

    /** AES-128 in ECB mode over the data zero-padded to whole blocks, as the protocol does. */
    static byte[] aes(boolean encrypt, byte[] data, byte[] key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(Arrays.copyOf(data, (data.length + 15) & ~15));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sign(PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }

    static boolean verify(PublicKey key, byte[] data, byte[] signatureBytes) throws GeneralSecurityException {
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(key);
        signature.update(data);
        return signature.verify(signatureBytes);
    }

    /** The certificate in PEM text, which is the form the server wants in getservercert. */
    static byte[] pem(byte[] der) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return ("-----BEGIN CERTIFICATE-----\n" + body + "\n-----END CERTIFICATE-----\n")
                .getBytes(StandardCharsets.US_ASCII);
    }

    /** A DER X.509 v3 certificate for the key pair, signed with its own key (SHA256withRSA). */
    static byte[] selfSignedCertificate(KeyPair keys, String commonName, long notBefore, long notAfter,
                                        BigInteger serial) throws GeneralSecurityException {
        byte[] algorithm = seq(tlv(0x06, SHA256_WITH_RSA), tlv(0x05, new byte[0]));
        byte[] name = seq(tlv(0x31, seq(tlv(0x06, COMMON_NAME),
                tlv(0x0c, commonName.getBytes(StandardCharsets.UTF_8)))));
        byte[] tbs = seq(tlv(0xa0, tlv(0x02, new byte[]{2})), tlv(0x02, serial.toByteArray()), algorithm, name,
                seq(utcTime(notBefore), utcTime(notAfter)), name, keys.getPublic().getEncoded());
        byte[] signature = sign(keys.getPrivate(), tbs);
        return seq(tbs, algorithm, tlv(0x03, concat(new byte[]{0}, signature)));
    }

    private static byte[] utcTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, format.format(new Date(millis)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    private static byte[] tlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else {
            int bytes = length > 0xffff ? 3 : length > 0xff ? 2 : 1;
            out.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) out.write(length >> (8 * i));
        }
        out.write(content, 0, length);
        return out.toByteArray();
    }
}
