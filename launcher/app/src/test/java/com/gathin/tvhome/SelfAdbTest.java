package com.gathin.tvhome;

import java.io.File;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SelfAdbTest {
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
    private static KeyPair keys;

    @BeforeClass public static void generateKey() throws Exception {
        File file = File.createTempFile("adbkey", null);
        file.delete();
        keys = SelfAdb.loadOrCreateKey(file);
        file.delete();
    }

    @Test public void encodeWritesLittleEndianHeader() {
        byte[] data = {1, 2, (byte) 0xff};
        ByteBuffer buf = ByteBuffer.wrap(SelfAdb.encode(SelfAdb.OPEN, 7, 9, data)).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(27, buf.capacity());
        assertEquals(SelfAdb.OPEN, buf.getInt());
        assertEquals(7, buf.getInt());
        assertEquals(9, buf.getInt());
        assertEquals(3, buf.getInt());
        assertEquals(1 + 2 + 255, buf.getInt());
        assertEquals(~SelfAdb.OPEN, buf.getInt());
        assertEquals(1, buf.get());
        assertEquals(0x4e45504f, SelfAdb.OPEN);
        assertEquals(0x4f, SelfAdb.encode(SelfAdb.OPEN, 0, 0, new byte[0])[0]);
    }

    @Test public void publicKeyLineIsAdbdsMincryptFormat() {
        byte[] line = SelfAdb.publicKeyLine((RSAPublicKey) keys.getPublic());
        assertEquals(0, line[line.length - 1]);
        String text = new String(line, 0, line.length - 1, java.nio.charset.StandardCharsets.UTF_8);
        byte[] raw = Base64.getDecoder().decode(text.substring(0, text.indexOf(' ')));
        assertEquals(524, raw.length);
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(64, buf.getInt());
        long n0inv = buf.getInt() & 0xffffffffL;
        BigInteger n = ((RSAPublicKey) keys.getPublic()).getModulus();
        // words are little-endian
        BigInteger fromWords = BigInteger.ZERO;
        for (int i = 63; i >= 0; i--) {
            fromWords = fromWords.shiftLeft(32).or(BigInteger.valueOf(buf.getInt(8 + 4 * i) & 0xffffffffL));
        }
        assertEquals(n, fromWords);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv).multiply(n).mod(r32));
        assertEquals(65537, buf.getInt(raw.length - 4));
    }

    @Test public void signatureVerifiesAsSha1DigestOfTheToken() throws Exception {
        byte[] token = new byte[20];
        for (int i = 0; i < token.length; i++) token[i] = (byte) (i * 13 + 5);
        byte[] signature = SelfAdb.sign((RSAPrivateCrtKey) keys.getPrivate(), token);
        byte[] expected = Arrays.copyOf(SHA1_DIGEST_INFO, SHA1_DIGEST_INFO.length + token.length);
        System.arraycopy(token, 0, expected, SHA1_DIGEST_INFO.length, token.length);
        Signature verify = Signature.getInstance("NONEwithRSA");
        verify.initVerify(keys.getPublic());
        verify.update(expected);
        assertTrue(verify.verify(signature));
    }

    @Test public void keyRoundTripsThroughItsFile() throws Exception {
        File file = File.createTempFile("adbkey", null);
        file.delete();
        try {
            KeyPair created = SelfAdb.loadOrCreateKey(file);
            KeyPair loaded = SelfAdb.loadOrCreateKey(file);
            assertArrayEquals(created.getPrivate().getEncoded(), loaded.getPrivate().getEncoded());
            assertArrayEquals(created.getPublic().getEncoded(), loaded.getPublic().getEncoded());
        } finally {
            file.delete();
        }
    }
}
