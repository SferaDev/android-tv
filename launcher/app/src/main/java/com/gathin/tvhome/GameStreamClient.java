package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** A GameStream (Sunshine) client: pairs with a host, lists its apps, fetches their box art and quits the running one. No Android classes. */
final class GameStreamClient {
    static final int HTTP_PORT = 47989, DEFAULT_HTTPS_PORT = 47984;
    private static final int CONNECT_TIMEOUT_MS = 4000, READ_TIMEOUT_MS = 10000;
    /** Box art is a full-size PNG that the host can be slow to send. */
    static final int ASSET_TIMEOUT_MS = 30_000;
    /** The host answers a quit once the app and its undo commands have finished. */
    private static final int QUIT_TIMEOUT_MS = 30_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final String DEVICE_NAME = "tvhome";

    /** One HTTP exchange with the host: the body of a GET. */
    interface Transport {
        byte[] get(boolean https, String path, String query, int readTimeoutMs) throws IOException;
    }

    /** The host rejected this client (not paired, or its certificate changed). */
    static final class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    enum PairResult { PAIRED, PIN_WRONG, FAILED, ALREADY_IN_PROGRESS }

    static final class ServerInfo {
        final String uuid, hostname;
        final int httpsPort, majorVersion;
        /** The id of the app the host is running, 0 for none. */
        final int currentGame;

        ServerInfo(String uuid, String hostname, int httpsPort, int majorVersion, int currentGame) {
            this.uuid = uuid;
            this.hostname = hostname;
            this.httpsPort = httpsPort;
            this.majorVersion = majorVersion;
            this.currentGame = currentGame;
        }
    }

    static final class App {
        final int id;
        final String title;
        final boolean hdr;

        App(int id, String title, boolean hdr) {
            this.id = id;
            this.title = title;
            this.hdr = hdr;
        }
    }

    private final String host;
    private final GameStreamIdentity identity;
    private int httpsPort = DEFAULT_HTTPS_PORT;
    private volatile HttpURLConnection inFlight;
    private volatile boolean cancelled;

    GameStreamClient(String host, GameStreamIdentity identity) {
        this.host = host;
        this.identity = identity;
    }

    /** Unauthenticated; also learns the HTTPS port. */
    ServerInfo serverInfo() throws IOException {
        return serverInfo(READ_TIMEOUT_MS);
    }

    ServerInfo serverInfo(int readTimeoutMs) throws IOException {
        ServerInfo info = parseServerInfo(text(transport().get(false, "serverinfo", null, readTimeoutMs)));
        httpsPort = info.httpsPort;
        return info;
    }

    /** Needs the pairing to be done: the host's apps in its own order. */
    List<App> appList() throws IOException {
        return parseApps(text(transport().get(true, "applist", null, READ_TIMEOUT_MS)));
    }

    /** The app's box art as the host serves it (PNG). */
    byte[] boxArt(int appId) throws IOException {
        return transport().get(true, "appasset", "appid=" + appId + "&AssetType=2&AssetIdx=0", ASSET_TIMEOUT_MS);
    }

    /** Quits the app the host is running, as Moonlight's NvHTTP.quitApp does; false when the host refused. */
    boolean quitApp() throws IOException {
        return parseCancel(text(transport().get(true, "cancel", null, QUIT_TIMEOUT_MS)));
    }

    /** Pairs with the host; the host answers the first step only once the PIN is typed into its web UI. */
    PairResult pair(String pin, int pinWaitMs) throws IOException {
        ServerInfo info = serverInfo();
        return pair(transport(), identity, info.majorVersion, pin, pinWaitMs);
    }

    static PairResult pair(Transport t, GameStreamIdentity id, int majorVersion, String pin, int pinWaitMs)
            throws IOException {
        try {
            return pairSteps(t, id, majorVersion >= 7, pin, pinWaitMs);
        } catch (GeneralSecurityException | RuntimeException e) {
            unpair(t);
            throw new IOException("pairing failed: " + e);
        } catch (IOException e) {
            unpair(t);
            throw e;
        }
    }

    private static PairResult pairSteps(Transport t, GameStreamIdentity id, boolean sha256, String pin,
                                        int pinWaitMs) throws IOException, GeneralSecurityException {
        int hashLength = sha256 ? 32 : 20;
        byte[] salt = GameStreamCrypto.random(16);
        byte[] aesKey = GameStreamCrypto.aesKey(sha256, salt, pin);

        String reply = text(t.get(false, "pair", pairQuery("phrase=getservercert&salt=" + GameStreamCrypto.hex(salt)
                + "&clientcert=" + GameStreamCrypto.hex(GameStreamCrypto.pem(id.certificateDer))), pinWaitMs));
        if (!isPaired(reply)) return PairResult.FAILED;
        String certText = xmlValue(reply, "plaincert");
        if (certText == null || certText.isEmpty()) return fail(t, PairResult.ALREADY_IN_PROGRESS);
        X509Certificate serverCert = GameStreamIdentity.parse(GameStreamCrypto.unhex(certText));
        id.pinServerCertificate(serverCert);

        byte[] challenge = GameStreamCrypto.random(16);
        reply = text(t.get(false, "pair", pairQuery("clientchallenge="
                + GameStreamCrypto.hex(GameStreamCrypto.aes(true, challenge, aesKey))), READ_TIMEOUT_MS));
        if (!isPaired(reply)) return fail(t, PairResult.FAILED);
        byte[] decrypted = GameStreamCrypto.aes(false,
                GameStreamCrypto.unhex(xmlValue(reply, "challengeresponse")), aesKey);
        byte[] serverResponse = Arrays.copyOfRange(decrypted, 0, hashLength);
        byte[] serverChallenge = Arrays.copyOfRange(decrypted, hashLength, hashLength + 16);

        byte[] clientSecret = GameStreamCrypto.random(16);
        byte[] answer = GameStreamCrypto.hash(sha256,
                GameStreamCrypto.concat(serverChallenge, id.certificate.getSignature(), clientSecret));
        reply = text(t.get(false, "pair", pairQuery("serverchallengeresp="
                + GameStreamCrypto.hex(GameStreamCrypto.aes(true, answer, aesKey))), READ_TIMEOUT_MS));
        if (!isPaired(reply)) return fail(t, PairResult.FAILED);

        byte[] pairingSecret = GameStreamCrypto.unhex(xmlValue(reply, "pairingsecret"));
        byte[] serverSecret = Arrays.copyOfRange(pairingSecret, 0, 16);
        byte[] serverSignature = Arrays.copyOfRange(pairingSecret, 16, pairingSecret.length);
        if (!GameStreamCrypto.verify(serverCert.getPublicKey(), serverSecret, serverSignature)) {
            return fail(t, PairResult.FAILED);
        }
        byte[] expected = GameStreamCrypto.hash(sha256,
                GameStreamCrypto.concat(challenge, serverCert.getSignature(), serverSecret));
        if (!Arrays.equals(expected, serverResponse)) return fail(t, PairResult.PIN_WRONG);

        byte[] clientPairingSecret = GameStreamCrypto.concat(clientSecret, GameStreamCrypto.sign(id.key, clientSecret));
        reply = text(t.get(false, "pair", pairQuery("clientpairingsecret="
                + GameStreamCrypto.hex(clientPairingSecret)), READ_TIMEOUT_MS));
        if (!isPaired(reply)) return fail(t, PairResult.FAILED);

        reply = text(t.get(true, "pair", pairQuery("phrase=pairchallenge"), READ_TIMEOUT_MS));
        return isPaired(reply) ? PairResult.PAIRED : fail(t, PairResult.FAILED);
    }

    private static String pairQuery(String arguments) {
        return "devicename=" + DEVICE_NAME + "&updateState=1&" + arguments;
    }

    private static PairResult fail(Transport t, PairResult result) {
        unpair(t);
        return result;
    }

    private static void unpair(Transport t) {
        try {
            t.get(false, "unpair", null, READ_TIMEOUT_MS);
        } catch (IOException ignored) {
            // Best effort: the host forgets a half-finished pairing on its own too.
        }
    }

    /** Drops the request in flight, from another thread; this client makes no more. */
    void cancel() {
        cancelled = true;
        HttpURLConnection connection = inFlight;
        if (connection != null) connection.disconnect();
    }

    static boolean isPaired(String xml) {
        try {
            return rootStatus(xml) == 200 && "1".equals(xmlValue(xml, "paired"));
        } catch (IOException e) {
            return false;
        }
    }

    static ServerInfo parseServerInfo(String xml) throws IOException {
        checkStatus(xml);
        String uuid = xmlValue(xml, "uniqueid"), hostname = xmlValue(xml, "hostname");
        if (uuid == null || uuid.isEmpty()) throw new IOException("serverinfo has no uniqueid");
        int https = DEFAULT_HTTPS_PORT, major = 0;
        try {
            String port = xmlValue(xml, "HttpsPort");
            if (port != null) https = Integer.parseInt(port.trim());
            String version = xmlValue(xml, "appversion");
            if (version != null) major = Integer.parseInt(version.trim().split("\\.")[0]);
        } catch (NumberFormatException e) {
            throw new IOException("bad serverinfo: " + e.getMessage());
        }
        return new ServerInfo(uuid, hostname == null ? "" : hostname, https, major, currentGame(xml));
    }

    /** Like Moonlight: currentgame counts only while the host says it is busy, as GFE keeps the last game there. */
    static int currentGame(String xml) throws IOException {
        String state = xmlValue(xml, "state"), game = xmlValue(xml, "currentgame");
        if (state == null || !state.trim().endsWith("_SERVER_BUSY") || game == null) return 0;
        try {
            return Integer.parseInt(game.trim());
        } catch (NumberFormatException e) {
            throw new IOException("bad currentgame: " + game);
        }
    }

    /** A /cancel reply: Moonlight takes anything but cancel 0 as done. */
    static boolean parseCancel(String xml) throws IOException {
        checkStatus(xml);
        String cancel = xmlValue(xml, "cancel");
        if (cancel == null) throw new IOException("cancel reply has no cancel");
        return !"0".equals(cancel.trim());
    }

    /** The apps of an applist response, skipping entries without a numeric id or a title. */
    static List<App> parseApps(String xml) throws IOException {
        checkStatus(xml);
        List<App> apps = new ArrayList<>();
        NodeList nodes = document(xml).getElementsByTagName("App");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element app = (Element) nodes.item(i);
            String id = child(app, "ID"), title = child(app, "AppTitle");
            if (id == null || title == null || title.trim().isEmpty()) continue;
            try {
                apps.add(new App(Integer.parseInt(id.trim()), title.trim(), "1".equals(child(app, "IsHdrSupported"))));
            } catch (NumberFormatException e) {
                // Not an id Moonlight could use either.
            }
        }
        return apps;
    }

    /** First element with the tag anywhere in the document, or null. */
    static String xmlValue(String xml, String tag) throws IOException {
        NodeList nodes = document(xml).getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
    }

    private static String child(Element parent, String tag) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && tag.equals(n.getNodeName())) return n.getTextContent();
        }
        return null;
    }

    private static int rootStatus(String xml) throws IOException {
        String code = document(xml).getDocumentElement().getAttribute("status_code");
        try {
            return Integer.parseInt(code.trim());
        } catch (NumberFormatException e) {
            throw new IOException("response has no status_code");
        }
    }

    private static void checkStatus(String xml) throws IOException {
        int status = rootStatus(xml);
        if (status == 401 || status == 403) throw new AuthException("host answered " + status);
        if (status != 200) throw new IOException("host answered " + status);
    }

    private static Document document(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            try {
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Not every parser knows the feature; none of them fetches external entities by default.
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            // Characters, not bytes: the body is already decoded, so an encoding in the prolog cannot re-decode it.
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new IOException("malformed response: " + e.getMessage());
        }
    }

    /** GameStream hosts send UTF-8 whatever their XML prolog says. */
    static String text(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    private Transport transport() {
        return (https, path, query, readTimeoutMs) -> {
            if (cancelled) throw new IOException("cancelled");
            String q = (query == null ? "" : query + "&") + "uniqueid=" + identity.uniqueId + "&uuid=" + UUID.randomUUID();
            URL url = new URL(https ? "https" : "http", host, https ? httpsPort : HTTP_PORT, "/" + path + "?" + q);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(readTimeoutMs);
            // The host serves one connection at a time, so a pooled idle socket would hold up the next request.
            connection.setRequestProperty("Connection", "close");
            if (https) tls((HttpsURLConnection) connection);
            inFlight = connection;
            try {
                int code = connection.getResponseCode();
                if (code == 401 || code == 403) throw new AuthException("HTTP " + code);
                if (code != 200) throw new IOException("HTTP " + code);
                try (InputStream in = connection.getInputStream()) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (bytes.size() + count > MAX_BYTES) throw new IOException("response too large");
                        bytes.write(buffer, 0, count);
                    }
                    return bytes.toByteArray();
                }
            } catch (SSLHandshakeException e) {
                throw new AuthException("TLS rejected: " + e.getMessage());
            } finally {
                inFlight = null;
                connection.disconnect();
            }
        };
    }

    /** Client certificate auth, and trust only in the certificate pinned at pairing. */
    private void tls(HttpsURLConnection connection) throws IOException {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[]{keyManager()}, new TrustManager[]{trustManager()}, new SecureRandom());
            connection.setSSLSocketFactory(context.getSocketFactory());
            connection.setHostnameVerifier((hostname, session) -> {
                try {
                    return pinned(session.getPeerCertificates());
                } catch (javax.net.ssl.SSLPeerUnverifiedException e) {
                    return false;
                }
            });
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        }
    }

    private boolean pinned(java.security.cert.Certificate[] chain) {
        X509Certificate server = identity.serverCertificate();
        return server != null && chain.length == 1 && chain[0].equals(server);
    }

    private X509TrustManager trustManager() {
        return new X509TrustManager() {
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }

            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {
                throw new IllegalStateException();
            }

            @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                    throws CertificateException {
                if (!pinned(chain)) throw new CertificateException("not the paired host's certificate");
            }
        };
    }

    private X509KeyManager keyManager() {
        PrivateKey key = identity.key;
        X509Certificate[] chain = {identity.certificate};
        return new X509KeyManager() {
            @Override public String chooseClientAlias(String[] keyTypes, Principal[] issuers, java.net.Socket s) {
                return "tvhome";
            }

            @Override public String chooseServerAlias(String keyType, Principal[] issuers, java.net.Socket s) {
                return null;
            }

            @Override public X509Certificate[] getCertificateChain(String alias) {
                return chain;
            }

            @Override public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{"tvhome"};
            }

            @Override public PrivateKey getPrivateKey(String alias) {
                return key;
            }

            @Override public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }
        };
    }
}
