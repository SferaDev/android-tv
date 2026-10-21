package com.gathin.tvhome;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import static org.junit.Assert.*;

public class GameStreamCryptoTest {
    private static byte[] seq(int start, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) bytes[i] = (byte) (start + i);
        return bytes;
    }

    @Test public void hexRoundTrips() {
        assertEquals("00FF10", GameStreamCrypto.hex(new byte[]{0, (byte) 0xff, 0x10}));
        assertArrayEquals(new byte[]{0, (byte) 0xff, 0x10}, GameStreamCrypto.unhex("00ff10"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void oddHexIsRejected() {
        GameStreamCrypto.unhex("abc");
    }

    @Test public void aesKeyIsTheFirstSixteenHashBytes() {
        assertEquals("BAD0B4F7CAE08EB7C1B5ACC763A8ED25", GameStreamCrypto.hex(GameStreamCrypto.aesKey(true, seq(0, 16), "1234")));
        assertEquals("0CD2F00B23437ED6ADC97CD8F7DBF3ED", GameStreamCrypto.hex(GameStreamCrypto.aesKey(false, seq(0, 16), "1234")));
    }

    @Test public void aesMatchesTheFipsVector() {
        byte[] key = seq(0, 16), plain = unhexSeq("00112233445566778899aabbccddeeff");
        byte[] encrypted = GameStreamCrypto.aes(true, plain, key);
        assertEquals("69C4E0D86A7B0430D8CDB78070B4C55A", GameStreamCrypto.hex(encrypted));
        assertArrayEquals(plain, GameStreamCrypto.aes(false, encrypted, key));
    }

    @Test public void aesZeroPadsToWholeBlocks() {
        byte[] key = seq(0, 16);
        byte[] encrypted = GameStreamCrypto.aes(true, new byte[]{1, 2, 3}, key);
        assertEquals(16, encrypted.length);
        assertArrayEquals(Arrays.copyOf(new byte[]{1, 2, 3}, 16), GameStreamCrypto.aes(false, encrypted, key));
    }

    @Test public void challengeHashConcatenatesChallengeSignatureAndSecret() {
        byte[] signature = new byte[256];
        for (int i = 0; i < signature.length; i++) signature[i] = (byte) (i % 251);
        byte[] hash = GameStreamCrypto.hash(true, GameStreamCrypto.concat(seq(0xa0, 16), signature, new byte[]{
                0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55}));
        assertEquals("E09540E8D5E18D4DA2DE31DA2BBA118BC54824293EA7DEA073927E0506A7488C", GameStreamCrypto.hex(hash));
    }

    @Test public void pinHasFourDigits() {
        for (int i = 0; i < 50; i++) assertTrue(GameStreamCrypto.generatePin().matches("\\d{4}"));
    }

    @Test public void certificateIsAValidSelfSignedX509() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        long now = System.currentTimeMillis();
        byte[] der = GameStreamCrypto.selfSignedCertificate(keys, "Test Client", now - 1000, now + 86_400_000L,
                BigInteger.valueOf(0x7fff_1234_5678L));
        X509Certificate cert = GameStreamIdentity.parse(der);
        assertEquals("CN=Test Client", cert.getSubjectX500Principal().getName());
        assertEquals(cert.getSubjectX500Principal(), cert.getIssuerX500Principal());
        assertEquals(BigInteger.valueOf(0x7fff_1234_5678L), cert.getSerialNumber());
        assertEquals(3, cert.getVersion());
        assertEquals("SHA256withRSA", cert.getSigAlgName());
        assertEquals(256, cert.getSignature().length);
        cert.verify(keys.getPublic());
        cert.checkValidity();
    }

    @Test public void pemWrapsTheDerInSixtyFourColumnLines() throws Exception {
        String pem = new String(GameStreamCrypto.pem(new byte[100]), StandardCharsets.US_ASCII);
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"));
        assertTrue(pem.endsWith("\n-----END CERTIFICATE-----\n"));
        for (String line : pem.split("\n")) assertTrue(line.length() <= 64 || line.startsWith("-----"));
    }

    private static byte[] unhexSeq(String hex) {
        return GameStreamCrypto.unhex(hex);
    }
}
