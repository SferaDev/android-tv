package com.gathin.tvhome;

import android.view.KeyEvent;

/**
 * The "Press the button on your remote" step in Settings. While a listener is set, the key services and the
 * Settings screen hand every remote key here first, so buttons the system routes to a service are caught too.
 * Keys the TV needs (arrows, OK, Back) pass through, so the screen stays usable and Back cancels.
 */
final class KeyCapture {
    interface Listener {
        void onCaptured(int keyCode);
    }

    private static Listener listener;
    private static int down;

    private KeyCapture() { }

    static synchronized void start(Listener l) {
        listener = l;
        down = 0;
    }

    static synchronized void stop(Listener l) {
        if (listener == l) listener = null;
    }

    /** True when the event was taken; the listener hears the key once it is released. Main thread. */
    static boolean offer(KeyEvent event) {
        Listener heard;
        int code = event.getKeyCode();
        synchronized (KeyCapture.class) {
            if (listener == null || !KeyMappings.capturable(code)) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                down = code;
                return true;
            }
            if (event.getAction() != KeyEvent.ACTION_UP || code != down) return true;
            heard = listener;
            listener = null;
        }
        heard.onCaptured(code);
        return true;
    }
}
