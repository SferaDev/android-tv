package com.gathin.tvhome;

import android.util.Log;

import java.io.File;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pairs this launcher with the host as a GameStream client; the user types {@link #pin()} into the host's web UI. One
 * PIN stays up, asked for again as each wait runs out, until the pairing succeeds, fails or {@link #stop} abandons it.
 */
final class GameStreamPairing {
    private static final String TAG = "TvHomeGames";
    private static final int PIN_WAIT_MS = 5 * 60_000;
    /** A first step answered "not paired" sooner than this never reached the PIN prompt on the host. */
    static final long INSTANT_MS = 5_000;
    private static final AtomicBoolean RUNNING = new AtomicBoolean();
    private static volatile String pin;
    private static volatile GameStreamClient.PairResult last;
    private static volatile GameStreamClient active;
    private static volatile boolean cancelled;

    /** What to do after one attempt. */
    enum Next { DONE, ASK_AGAIN, NEW_ID, GIVE_UP }

    private GameStreamPairing() { }

    /** The PIN to enter on the host while a pairing attempt waits for it, else null. */
    static String pin() {
        return pin;
    }

    static boolean running() {
        return RUNNING.get();
    }

    /** Changes whenever what a pairing screen shows does, for polling. */
    static String state(boolean paired) {
        return running() + "/" + pin + "/" + paired;
    }

    /** How the last attempt ended; null while none finished or when it stopped with an error. */
    static GameStreamClient.PairResult lastResult() {
        return last;
    }

    /**
     * An instant refusal is the host still holding an abandoned attempt of this client id: one more try under a new
     * id, but not twice in a row. A null result is a wait that ran out with nobody typing the PIN.
     */
    static Next next(GameStreamClient.PairResult result, long elapsedMs, boolean afterNewId) {
        if (result == null) return Next.ASK_AGAIN;
        if (result == GameStreamClient.PairResult.PAIRED) return Next.DONE;
        boolean refused = result == GameStreamClient.PairResult.FAILED
                || result == GameStreamClient.PairResult.ALREADY_IN_PROGRESS;
        return refused && elapsedMs < INSTANT_MS && !afterNewId ? Next.NEW_ID : Next.GIVE_UP;
    }

    /** Starts pairing in the background unless it already runs. */
    static void start(File dir, String host) {
        if (!RUNNING.compareAndSet(false, true)) return;
        last = null;
        cancelled = false;
        new Thread(() -> {
            try {
                run(dir, host);
            } finally {
                pin = null;
                active = null;
                RUNNING.set(false);
            }
        }, "gamestream-pairing").start();
    }

    /** Abandons the attempt that waits for the PIN, so the host is not left holding it for nobody. */
    static void stop() {
        if (!RUNNING.get()) return;
        cancelled = true;
        GameStreamClient client = active;
        if (client != null) client.cancel();
    }

    private static void run(File dir, String host) {
        pin = GameStreamCrypto.generatePin();
        Log.i(TAG, "Pairing with " + host + ": enter PIN " + pin + " in the host's web UI (PIN tab)");
        boolean afterNewId = false;
        while (!cancelled) {
            GameStreamClient.PairResult result;
            long started = System.currentTimeMillis();
            GameStreamIdentity identity = null;
            try {
                identity = GameStreamIdentity.load(dir);
                GameStreamClient client = new GameStreamClient(host, identity);
                active = client;
                if (cancelled) break;
                result = client.pair(pin, PIN_WAIT_MS);
            } catch (SocketTimeoutException e) {
                result = null;
            } catch (Exception e) {
                if (!cancelled) Log.i(TAG, "Pairing did not finish: " + e);
                return;
            }
            if (cancelled) break;
            Next next = next(result, System.currentTimeMillis() - started, afterNewId);
            Log.i(TAG, "Pairing attempt: " + result + ", then " + next);
            afterNewId = false;
            switch (next) {
                case DONE:
                    try {
                        identity.savePaired();
                        last = result;
                    } catch (Exception e) {
                        Log.w(TAG, "Could not save the pairing: " + e);
                    }
                    return;
                case NEW_ID:
                    try {
                        GameStreamIdentity.newUniqueId(dir);
                        afterNewId = true;
                    } catch (java.io.IOException e) {
                        Log.w(TAG, "Could not change the client id: " + e);
                        last = result;
                        return;
                    }
                    break;
                case GIVE_UP:
                    last = result;
                    return;
                default:
                    break;
            }
        }
        Log.i(TAG, "Pairing abandoned");
    }
}
