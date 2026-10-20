package com.gathin.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;

/**
 * A minimal client for the TV's own adbd over TCP, so the launcher can run the few shell commands an app is not
 * allowed to (enabling or disabling another package). Needs USB debugging on; the first connection shows the TV's
 * "Allow USB debugging?" prompt for this app's key, which is kept in the app's files.
 */
final class SelfAdb implements Closeable {
    private static final String TAG = "TvHome.SelfAdb";
    private static final int PORT = 5555;
    private static final int PROMPT_TIMEOUT_MS = 60_000;
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int KEY_BITS = 2048;
    private static final int IO_TIMEOUT_MS = 5000;
    private static final int SHELL_TIMEOUT_MS = 20000;

    /** PKCS#1 v1.5 type 1 padding and the SHA-1 DigestInfo prefix: adbd verifies the token as a SHA-1 digest. */
    private static final byte[] SIGNATURE_PREFIX;
    static {
        byte[] digestInfo = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};
        SIGNATURE_PREFIX = new byte[KEY_BITS / 8 - 20];
        Arrays.fill(SIGNATURE_PREFIX, (byte) 0xff);
        SIGNATURE_PREFIX[0] = 0x00;
        SIGNATURE_PREFIX[1] = 0x01;
        SIGNATURE_PREFIX[SIGNATURE_PREFIX.length - digestInfo.length - 1] = 0x00;
        System.arraycopy(digestInfo, 0, SIGNATURE_PREFIX, SIGNATURE_PREFIX.length - digestInfo.length, digestInfo.length);
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private SelfAdb(Socket socket) throws IOException {
        this.socket = socket;
        in = new DataInputStream(socket.getInputStream());
        out = socket.getOutputStream();
    }

    interface Task {
        void run(SelfAdb adb) throws IOException;
    }

    /**
     * Connects to adbd on this device with the app's key. {@code onPrompt} runs (on this thread) when the TV is about
     * to ask whether to allow this key, and the connection then waits up to {@code promptTimeoutMs} for the answer.
     */
    static SelfAdb connect(Context app, int promptTimeoutMs, Runnable onPrompt)
            throws IOException, GeneralSecurityException {
        KeyPair keys = loadOrCreateKey(new File(app.getFilesDir(), "adbkey"));
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", PORT), IO_TIMEOUT_MS);
        socket.setSoTimeout(IO_TIMEOUT_MS);
        SelfAdb adb = new SelfAdb(socket);
        try {
            adb.handshake(keys, promptTimeoutMs, onPrompt);
            return adb;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            adb.close();
            throw e;
        }
    }

    /**
     * Runs task on its own thread, with a toast when the TV asks to allow the key and, unless quiet, when it fails;
     * then done, if any, on the main thread.
     */
    static void inBackground(Context ctx, String name, boolean quiet, Task task, Runnable done) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try (SelfAdb adb = connect(app, PROMPT_TIMEOUT_MS, () -> toast(app, R.string.home_toggle_allow))) {
                task.run(adb);
            } catch (Exception e) {
                Log.w(TAG, name + " failed: " + e);
                if (!quiet) toast(app, R.string.home_toggle_failed);
            } finally {
                if (done != null) new Handler(Looper.getMainLooper()).post(done);
            }
        }, name).start();
    }

    static void toast(Context app, int message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, message, Toast.LENGTH_LONG).show());
    }

    private void handshake(KeyPair keys, int promptTimeoutMs, Runnable onPrompt)
            throws IOException, GeneralSecurityException {
        send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false, offeredKey = false;
        while (true) {
            Message m = read();
            if (m.command == CNXN) {
                socket.setSoTimeout(SHELL_TIMEOUT_MS);
                return;
            }
            if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected adb message " + m.command);
            if (!signed) {
                send(AUTH, AUTH_SIGNATURE, 0, sign((RSAPrivateCrtKey) keys.getPrivate(), m.data));
                signed = true;
            } else if (!offeredKey) {
                // The key is not trusted yet: offer it, and adbd asks on screen.
                send(AUTH, AUTH_RSAPUBLICKEY, 0, publicKeyLine((RSAPublicKey) keys.getPublic()));
                offeredKey = true;
                if (onPrompt != null) onPrompt.run();
                socket.setSoTimeout(promptTimeoutMs);
            } else {
                throw new IOException("adb key was not accepted");
            }
        }
    }

    /** Runs one shell command and returns what it printed. */
    String shell(String command) throws IOException {
        int localId = nextLocalId++;
        send(OPEN, localId, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int remoteId = 0;
        while (true) {
            Message m = read();
            if (m.arg1 != localId) continue;
            if (m.command == OKAY) {
                remoteId = m.arg0;
            } else if (m.command == WRTE) {
                output.write(m.data);
                send(OKAY, localId, m.arg0, new byte[0]);
            } else if (m.command == CLSE) {
                if (remoteId != 0) send(CLSE, localId, remoteId, new byte[0]);
                return output.toString("UTF-8");
            }
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    static final class Message {
        final int command, arg0, arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    /** The 24-byte header and payload of one adb message. */
    static byte[] encode(int command, int arg0, int arg1, byte[] data) {
        int checksum = 0;
        for (byte b : data) checksum += b & 0xff;
        ByteBuffer buf = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(checksum).putInt(~command);
        buf.put(data);
        return buf.array();
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(encode(command, arg0, arg1, data));
        out.flush();
    }

    private Message read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt(), arg0 = buf.getInt(), arg1 = buf.getInt(), length = buf.getInt();
        buf.getInt();   // checksum, not checked from protocol version 0x01000001 on
        if (buf.getInt() != ~command) throw new IOException("bad adb message header");
        if (length < 0 || length > 1024 * 1024) throw new IOException("bad adb message length");
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    /** The token signed the way adbd checks it: RSA over the padded SHA-1 DigestInfo with the token as the digest. */
    static byte[] sign(RSAPrivateCrtKey key, byte[] token) throws GeneralSecurityException {
        Cipher rsa = Cipher.getInstance("RSA/ECB/NoPadding");
        rsa.init(Cipher.ENCRYPT_MODE, key);
        byte[] padded = Arrays.copyOf(SIGNATURE_PREFIX, SIGNATURE_PREFIX.length + token.length);
        System.arraycopy(token, 0, padded, SIGNATURE_PREFIX.length, token.length);
        return rsa.doFinal(padded);
    }

    /** The public key in adbd's format (Android's mincrypt RSAPublicKey, base64) plus a name, NUL-terminated. */
    static byte[] publicKeyLine(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate();
        int words = KEY_BITS / 32;
        ByteBuffer buf = ByteBuffer.allocate(4 * (3 + 2 * words)).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) buf.putInt(n.shiftRight(32 * i).intValue());
        for (int i = 0; i < words; i++) buf.putInt(rr.shiftRight(32 * i).intValue());
        buf.putInt(key.getPublicExponent().intValue());
        String line = Base64.getEncoder().encodeToString(buf.array()) + " tvhome@launcher\0";
        return line.getBytes(StandardCharsets.UTF_8);
    }

    static KeyPair loadOrCreateKey(File file) throws IOException, GeneralSecurityException {
        KeyFactory factory = KeyFactory.getInstance("RSA");
        if (file.exists()) {
            RSAPrivateCrtKey priv = (RSAPrivateCrtKey) factory.generatePrivate(
                    new PKCS8EncodedKeySpec(Files.readAllBytes(file.toPath())));
            return new KeyPair(factory.generatePublic(
                    new RSAPublicKeySpec(priv.getModulus(), priv.getPublicExponent())), priv);
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_BITS);
        KeyPair keys = gen.generateKeyPair();
        Files.write(file.toPath(), keys.getPrivate().getEncoded());
        return keys;
    }
}
