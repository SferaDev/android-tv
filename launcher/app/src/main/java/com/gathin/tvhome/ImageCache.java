package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.concurrent.Executors;

/** Card pictures (watch artwork, game covers) decoded off the main thread, downsampled to the card, kept in a small cache. */
final class ImageCache {
    private static final int CACHE_BYTES = 12 * 1024 * 1024;
    private static final int TIMEOUT_MS = 5_000;
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private final Context ctx;
    /** Its own thread: a slow artwork URL must not hold up the rows. */
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    /** Sources that failed once; not retried until the process restarts. */
    private final Set<String> failed = Collections.synchronizedSet(new HashSet<>());

    ImageCache(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    /** Drops the decoded pictures; the disk cache refills them. */
    void trim() {
        cache.evictAll();
    }

    void close() {
        bg.shutdownNow();
        cache.evictAll();
    }

    /**
     * Shows {@code source} (a file path or a file, content, android.resource or http(s) URI) in {@code target} once
     * decoded, then runs {@code shown}. Nothing happens when it cannot be read, or when the view has been rebound to
     * another source meanwhile.
     */
    void load(String source, int widthPx, ImageView target, Runnable shown) {
        String key = key(source, widthPx);
        target.setTag(key);
        get(source, widthPx, bitmap -> {
            if (!key.equals(target.getTag())) return;
            target.setImageBitmap(bitmap);
            shown.run();
        });
    }

    /** Hands the decoded picture to {@code done} on the main thread: at once when cached, never when unreadable. */
    void get(String source, int widthPx, Consumer<Bitmap> done) {
        String key = key(source, widthPx);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            done.accept(cached);
            return;
        }
        if (failed.contains(key) || bg.isShutdown()) return;
        bg.execute(() -> {
            Bitmap bitmap = cache.get(key);
            if (bitmap == null) bitmap = decode(source, widthPx);
            if (bitmap == null) {
                failed.add(key);
                return;
            }
            cache.put(key, bitmap);
            Bitmap ready = bitmap;
            main.post(() -> done.accept(ready));
        });
    }

    private static String key(String source, int widthPx) {
        return source + '@' + widthPx;
    }

    /** Forgets the view's source, so a late decode for its previous card does not land on it. */
    static void clear(ImageView target) {
        target.setTag(null);
    }

    /** Runs a blocking job (a backdrop's decode and composition) on this loader's thread. */
    void run(Runnable job) {
        if (!bg.isShutdown()) bg.execute(job);
    }

    /** Blocking: full colour, at least {@code minWidthPx} wide when the source is (no cache; for the backdrop). */
    Bitmap decodeLarge(String source, int minWidthPx) {
        return decode(source, minWidthPx, Bitmap.Config.ARGB_8888);
    }

    private Bitmap decode(String source, int widthPx) {
        return decode(source, widthPx, Bitmap.Config.RGB_565);
    }

    private Bitmap decode(String source, int widthPx, Bitmap.Config config) {
        try {
            byte[] bytes = read(source);
            return bytes == null ? null : sampled(bytes, widthPx, config);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** The largest power-of-two reduction that keeps the picture at least {@code widthPx} wide; null if unreadable. */
    static Bitmap sampled(byte[] bytes, int widthPx, Bitmap.Config config) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Integer.highestOneBit(bounds.outWidth / Math.max(1, widthPx)));
        options.inPreferredConfig = config;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private byte[] read(String source) throws IOException {
        if (source.startsWith("http://") || source.startsWith("https://")) return fetchCached(source);
        Uri uri = source.startsWith("/") ? Uri.fromFile(new File(source)) : Uri.parse(source);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return in == null ? null : StoredText.readAll(in, MAX_BYTES);
        }
    }

    /** Remote pictures share {@link Artwork}'s disk cache, keyed by the URL's hash, trimmed oldest-used first. */
    private byte[] fetchCached(String source) throws IOException {
        File dir = Artwork.cacheDir(ctx);
        File file = new File(dir, Artwork.sha1(source));
        if (file.isFile() && file.length() > 0) {
            file.setLastModified(System.currentTimeMillis());
            try (InputStream in = new FileInputStream(file)) {
                return StoredText.readAll(in, MAX_BYTES);
            }
        }
        byte[] bytes = fetch(source);
        if (bytes != null && (dir.isDirectory() || dir.mkdirs())) {
            File tmp = new File(dir, file.getName() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(bytes);
            }
            if (!tmp.renameTo(file)) tmp.delete();
            Artwork.trim(dir, null);
        }
        return bytes;
    }

    private byte[] fetch(String source) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(source).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        try {
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                return StoredText.readAll(in, MAX_BYTES);
            }
        } finally {
            connection.disconnect();
        }
    }
}
