package com.gathin.tvhome;

import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Small text files in filesDir, replaced whole through AtomicFile so an interrupted write keeps the last copy. */
final class StoredText {
    private StoredText() { }

    /** Null when the file does not exist yet; an IOException when it is larger than maxBytes. */
    static String read(File file, int maxBytes) throws IOException {
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
        try (FileInputStream in = new AtomicFile(file).openRead()) {
            return new String(readAll(in, maxBytes), StandardCharsets.UTF_8);
        }
    }

    static void write(File file, String text) throws IOException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
        } catch (IOException | RuntimeException e) {
            atomic.failWrite(out);
            throw e;
        }
    }

    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        for (int count; (count = in.read(buffer)) != -1; ) {
            if (out.size() + count > maxBytes) throw new IOException("over " + maxBytes + " bytes");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }
}
