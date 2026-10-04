package com.opx.demon.wordlists;

import com.opx.demon.ota.Net;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class WordlistDownloader {

    public interface Progress {
        void onProgress(long downloaded, long total);
        boolean isCancelled();
    }

    private final WordlistStore store;

    public WordlistDownloader(WordlistStore store) {
        this.store = store;
    }

    public Wordlist download(WordlistCatalog.Entry entry, Progress progress) throws IOException {
        return download(entry.url, entry.fileName, entry.category, progress);
    }

    public Wordlist download(String url, String name, WordlistCategory category, Progress progress)
            throws IOException {
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("Refusing non-HTTPS URL");
        }

        String fileName = name == null || name.trim().isEmpty() ? nameFromUrl(url) : name;
        boolean gzipped = url.endsWith(".gz");
        if (gzipped && fileName.endsWith(".gz")) {
            fileName = fileName.substring(0, fileName.length() - 3);
        }

        Wordlist wl = store.create(fileName, category, Wordlist.Origin.DOWNLOADED);
        File staging = new File(store.dir(), wl.getName() + ".part");

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", Net.userAgent())
                .header("Accept", "text/plain, */*")
                .build();

        long written = 0;
        try (Response response = Net.client().newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException("HTTP " + response.code());
            }
            long total = body.contentLength();

            InputStream in = body.byteStream();
            if (gzipped) in = new GZIPInputStream(in);

            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(staging), 1 << 16)) {
                byte[] buf = new byte[1 << 16];
                int read;
                while ((read = in.read(buf)) > 0) {
                    if (progress != null && progress.isCancelled()) {
                        staging.delete();
                        return null;
                    }
                    out.write(buf, 0, read);
                    written += read;
                    if (progress != null) progress.onProgress(written, gzipped ? -1 : total);
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            staging.delete();
            throw e;
        }

        if (written == 0) {
            staging.delete();
            throw new IOException("Empty response");
        }

        if (!staging.renameTo(wl.file)) {
            staging.delete();
            throw new IOException("Could not move the download into place");
        }

        wl.note = url;
        store.finish(wl, -1);
        return wl;
    }

    static String nameFromUrl(String url) {
        String s = url;
        int q = s.indexOf('?');
        if (q > 0) s = s.substring(0, q);
        int slash = s.lastIndexOf('/');
        if (slash >= 0 && slash < s.length() - 1) s = s.substring(slash + 1);
        if (s.isEmpty()) s = "download.txt";
        if (!s.contains(".")) s = s + ".txt";
        return s;
    }
}
