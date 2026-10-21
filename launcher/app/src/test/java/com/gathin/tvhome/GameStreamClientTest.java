package com.gathin.tvhome;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class GameStreamClientTest {
    private static final String APPLIST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root status_code=\"200\">"
            + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>881448767</ID></App>"
            + "<App><IsHdrSupported>1</IsHdrSupported><AppTitle>ELDEN RING &amp; Co</AppTitle><ID>1245620</ID></App>"
            + "<App><AppTitle>No id</AppTitle></App>"
            + "<App><IsHdrSupported>0</IsHdrSupported><AppTitle>  </AppTitle><ID>7</ID></App>"
            + "</root>";

    @Test public void parsesAppList() throws Exception {
        List<GameStreamClient.App> apps = GameStreamClient.parseApps(APPLIST);
        assertEquals(2, apps.size());
        assertEquals(881448767, apps.get(0).id);
        assertEquals("Desktop", apps.get(0).title);
        assertFalse(apps.get(0).hdr);
        assertEquals("ELDEN RING & Co", apps.get(1).title);
        assertTrue(apps.get(1).hdr);
    }

    @Test public void hostTextIsUtf8EvenWhenThePrologSaysOtherwise() throws Exception {
        String title = "Ñandú Saga™: Café Édition";
        byte[] applist = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><root status_code=\"200\">"
                + "<App><AppTitle>" + title + "</AppTitle><ID>42</ID></App></root>").getBytes(StandardCharsets.UTF_8);
        assertEquals(title, GameStreamClient.parseApps(GameStreamClient.text(applist)).get(0).title);
        byte[] serverinfo = ("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><root status_code=\"200\">"
                + "<hostname>Estación ñ</hostname><uniqueid>u</uniqueid></root>").getBytes(StandardCharsets.UTF_8);
        assertEquals("Estación ñ", GameStreamClient.parseServerInfo(GameStreamClient.text(serverinfo)).hostname);
    }

    @Test(expected = GameStreamClient.AuthException.class)
    public void unauthorizedAppListIsAnAuthError() throws Exception {
        GameStreamClient.parseApps("<root status_code=\"401\" status_message=\"not paired\"></root>");
    }

    @Test(expected = IOException.class)
    public void truncatedXmlThrows() throws Exception {
        GameStreamClient.parseApps("<root status_code=\"200\"><App>");
    }

    @Test public void parsesServerInfo() throws Exception {
        GameStreamClient.ServerInfo info = GameStreamClient.parseServerInfo("<root status_code=\"200\">"
                + "<hostname>testhost</hostname><appversion>7.1.431.-1</appversion>"
                + "<uniqueid>00000000-0000-4000-8000-000000000001</uniqueid><HttpsPort>47984</HttpsPort>"
                + "<PairStatus>0</PairStatus></root>");
        assertEquals("testhost", info.hostname);
        assertEquals("00000000-0000-4000-8000-000000000001", info.uuid);
        assertEquals(47984, info.httpsPort);
        assertEquals(7, info.majorVersion);
    }

    @Test public void currentGameCountsOnlyWhileBusy() throws Exception {
        String busy = "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\"><hostname>testhost</hostname>"
                + "<uniqueid>u</uniqueid><PairStatus>0</PairStatus><currentgame>1234567</currentgame>"
                + "<state>SUNSHINE_SERVER_BUSY</state></root>";
        assertEquals(1234567, GameStreamClient.parseServerInfo(busy).currentGame);
        String free = "<root status_code=\"200\"><uniqueid>u</uniqueid><currentgame>0</currentgame>"
                + "<state>SUNSHINE_SERVER_FREE</state></root>";
        assertEquals(0, GameStreamClient.parseServerInfo(free).currentGame);
        String lastPlayed = "<root status_code=\"200\"><uniqueid>u</uniqueid><currentgame>42</currentgame>"
                + "<state>MJOLNIR_STATE_SERVER_AVAILABLE</state></root>";
        assertEquals(0, GameStreamClient.parseServerInfo(lastPlayed).currentGame);
        assertEquals(0, GameStreamClient.parseServerInfo("<root status_code=\"200\"><uniqueid>u</uniqueid></root>").currentGame);
    }

    @Test(expected = IOException.class)
    public void garbledCurrentGameThrows() throws Exception {
        GameStreamClient.parseServerInfo("<root status_code=\"200\"><uniqueid>u</uniqueid><currentgame>x</currentgame>"
                + "<state>SUNSHINE_SERVER_BUSY</state></root>");
    }

    @Test public void parsesCancelReplies() throws Exception {
        assertTrue(GameStreamClient.parseCancel(
                "<?xml version=\"1.0\" encoding=\"utf-8\"?><root status_code=\"200\"><cancel>1</cancel></root>"));
        assertFalse(GameStreamClient.parseCancel("<root status_code=\"200\"><cancel>0</cancel></root>"));
    }

    @Test(expected = GameStreamClient.AuthException.class)
    public void unauthorizedCancelIsAnAuthError() throws Exception {
        GameStreamClient.parseCancel("<root status_code=\"401\" status_message=\"The client is not authorized\"></root>");
    }

    @Test(expected = IOException.class)
    public void cancelReplyWithoutCancelThrows() throws Exception {
        GameStreamClient.parseCancel("<root status_code=\"200\"></root>");
    }

    @Test public void pairedFlagNeedsStatus200AndOne() {
        assertTrue(GameStreamClient.isPaired("<root status_code=\"200\"><paired>1</paired></root>"));
        assertFalse(GameStreamClient.isPaired("<root status_code=\"200\"><paired>0</paired></root>"));
        assertFalse(GameStreamClient.isPaired("<root status_code=\"400\"><paired>1</paired></root>"));
        assertFalse(GameStreamClient.isPaired("garbage"));
    }

    @Test public void pairsAgainstAHostThatFollowsSunshinesSteps() throws Exception {
        GameStreamIdentity id = identity();
        FakeHost host = new FakeHost("1234");
        assertEquals(GameStreamClient.PairResult.PAIRED, GameStreamClient.pair(host, id, 7, "1234", 1000));
        assertTrue(id.paired());
        assertEquals(host.serverCert, id.serverCertificate());
        assertEquals("pairchallenge", host.steps.get(host.steps.size() - 1));
    }

    @Test public void wrongPinIsReportedAndUnpairs() throws Exception {
        GameStreamIdentity id = identity();
        FakeHost host = new FakeHost("9999");
        assertEquals(GameStreamClient.PairResult.PIN_WRONG, GameStreamClient.pair(host, id, 7, "1234", 1000));
        assertEquals("unpair", host.steps.get(host.steps.size() - 1));
    }

    @Test public void pairsWithAnOlderSha1Host() throws Exception {
        GameStreamIdentity id = identity();
        FakeHost host = new FakeHost("4321");
        host.sha256 = false;
        assertEquals(GameStreamClient.PairResult.PAIRED, GameStreamClient.pair(host, id, 3, "4321", 1000));
    }

    private static GameStreamIdentity identity() throws Exception {
        File dir = Files.createTempDirectory("gs-identity").toFile();
        dir.deleteOnExit();
        return GameStreamIdentity.load(dir);
    }

    @Test public void identityIsStableAcrossLoadsAndKeepsThePairing() throws Exception {
        File dir = Files.createTempDirectory("gs-identity").toFile();
        GameStreamIdentity first = GameStreamIdentity.load(dir);
        assertFalse(first.paired());
        assertTrue(first.uniqueId.matches("[0-9a-f]{16}"));
        first.pinServerCertificate(first.certificate);
        first.savePaired();
        GameStreamIdentity second = GameStreamIdentity.load(dir);
        assertEquals(first.uniqueId, second.uniqueId);
        assertEquals(first.certificate, second.certificate);
        assertTrue(second.paired());
        second.clearPaired();
        assertFalse(GameStreamIdentity.load(dir).paired());
    }

    /** The server half of the handshake, written from Sunshine's nvhttp.cpp. */
    private static final class FakeHost implements GameStreamClient.Transport {
        final String pin;
        boolean sha256 = true;
        final List<String> steps = new ArrayList<>();
        final KeyPair serverKeys;
        final X509Certificate serverCert;
        byte[] aesKey, serverSecret, serverChallenge, clientHash;
        X509Certificate clientCert;

        FakeHost(String pin) throws Exception {
            this.pin = pin;
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            serverKeys = generator.generateKeyPair();
            long now = System.currentTimeMillis();
            serverCert = GameStreamIdentity.parse(GameStreamCrypto.selfSignedCertificate(serverKeys, "Test Host",
                    now - 1000, now + 86_400_000L, BigInteger.TEN));
        }

        @Override public byte[] get(boolean https, String path, String query, int readTimeoutMs) throws IOException {
            try {
                return reply(https, path, args(query));
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
        }

        private byte[] reply(boolean https, String path, Map<String, String> args)
                throws java.security.GeneralSecurityException {
            if (path.equals("unpair")) {
                steps.add("unpair");
                return xml("");
            }
            if (args.containsKey("phrase") && args.get("phrase").equals("getservercert")) {
                steps.add("getservercert");
                aesKey = GameStreamCrypto.aesKey(sha256, GameStreamCrypto.unhex(args.get("salt")), pin);
                clientCert = GameStreamIdentity.parse(GameStreamCrypto.unhex(args.get("clientcert")));
                return xml("<paired>1</paired><plaincert>" + GameStreamCrypto.hex(serverCert.getEncoded()) + "</plaincert>");
            }
            if (args.containsKey("clientchallenge")) {
                steps.add("clientchallenge");
                byte[] challenge = GameStreamCrypto.aes(false, GameStreamCrypto.unhex(args.get("clientchallenge")), aesKey);
                serverSecret = GameStreamCrypto.random(16);
                serverChallenge = GameStreamCrypto.random(16);
                byte[] hash = GameStreamCrypto.hash(sha256,
                        GameStreamCrypto.concat(challenge, serverCert.getSignature(), serverSecret));
                byte[] plain = GameStreamCrypto.concat(hash, serverChallenge);
                return xml("<paired>1</paired><challengeresponse>"
                        + GameStreamCrypto.hex(GameStreamCrypto.aes(true, plain, aesKey)) + "</challengeresponse>");
            }
            if (args.containsKey("serverchallengeresp")) {
                steps.add("serverchallengeresp");
                clientHash = GameStreamCrypto.aes(false, GameStreamCrypto.unhex(args.get("serverchallengeresp")), aesKey);
                byte[] secret = GameStreamCrypto.concat(serverSecret, GameStreamCrypto.sign(serverKeys.getPrivate(), serverSecret));
                return xml("<paired>1</paired><pairingsecret>" + GameStreamCrypto.hex(secret) + "</pairingsecret>");
            }
            if (args.containsKey("clientpairingsecret")) {
                steps.add("clientpairingsecret");
                byte[] all = GameStreamCrypto.unhex(args.get("clientpairingsecret"));
                byte[] secret = Arrays.copyOfRange(all, 0, 16), sign = Arrays.copyOfRange(all, 16, all.length);
                byte[] hash = GameStreamCrypto.hash(sha256,
                        GameStreamCrypto.concat(serverChallenge, clientCert.getSignature(), secret));
                boolean ok = Arrays.equals(hash, Arrays.copyOf(clientHash, hash.length))
                        && GameStreamCrypto.verify(clientCert.getPublicKey(), secret, sign);
                return xml("<paired>" + (ok ? 1 : 0) + "</paired>");
            }
            if (https && "pairchallenge".equals(args.get("phrase"))) {
                steps.add("pairchallenge");
                return xml("<paired>1</paired>");
            }
            throw new IllegalStateException("unexpected " + path + " " + args);
        }

        private static byte[] xml(String body) {
            return ("<root status_code=\"200\">" + body + "</root>").getBytes(StandardCharsets.UTF_8);
        }

        private static Map<String, String> args(String query) {
            Map<String, String> map = new HashMap<>();
            if (query == null) return map;
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) map.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            return map;
        }
    }
}
