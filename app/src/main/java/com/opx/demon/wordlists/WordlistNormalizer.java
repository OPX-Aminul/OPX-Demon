package com.opx.demon.wordlists;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;

public class WordlistNormalizer {

    private static final int CHUNK_LINES = 200_000;

    private static final char BOM = 0xFEFF;

    public static final int UNSORTED_DEDUPE_LIMIT = 3_000_000;

    public enum Sort { NONE, ALPHABETICAL, BY_LENGTH }

    public interface Progress {
        void onProgress(long linesRead, long linesKept);
        boolean isCancelled();
    }

    public static class Options {
        public boolean trim = true;
        public boolean dropEmpty = true;
        public boolean dropComments = true;
        public boolean stripControl = true;
        public boolean toLowercase = false;
        public boolean asciiOnly = false;
        public boolean dedupe = true;
        public Sort sort = Sort.NONE;

        public int minLength = 0;
        public int maxLength = 0;

        public boolean wpaOnly = false;

        public boolean inPlace = false;

        public String outputName = null;
    }

    public static class Result {
        public long read;
        public long kept;
        public long removedEmpty;
        public long removedComment;
        public long removedLength;
        public long removedDuplicate;
        public long removedNonAscii;
        public boolean dedupeTruncated;
        public boolean cancelled;
        public Wordlist output;

        public long removed() {
            return read - kept;
        }

        public String summary() {
            return String.format(Locale.US, "%,d in · %,d out · %,d removed", read, kept, removed());
        }
    }

    private final WordlistStore store;

    public WordlistNormalizer(WordlistStore store) {
        this.store = store;
    }

    public Result run(Wordlist source, Options opts, Progress progress) {
        Result result = new Result();
        List<File> chunks = new ArrayList<>();
        File staging = null;

        try {
            staging = File.createTempFile("wl-norm", ".tmp", store.dir());

            if (opts.sort == Sort.NONE) {
                filterStreaming(source, staging, opts, progress, result);
            } else {
                chunks = filterIntoSortedChunks(source, opts, progress, result);
                mergeChunks(chunks, staging, opts, result);
            }

            if (progress != null && progress.isCancelled()) {
                result.cancelled = true;
                staging.delete();
                return result;
            }

            result.output = publish(source, staging, opts);
            staging = null;
        } catch (IOException e) {
            result.cancelled = true;
        } finally {
            if (staging != null)
                staging.delete();
            for (File f : chunks)
                f.delete();
        }
        return result;
    }

    private void filterStreaming(Wordlist source, File target, Options opts,
                                 Progress progress, Result result) throws IOException {
        HashSet<String> seen = opts.dedupe ? new HashSet<>() : null;
        try (BufferedReader r = reader(source.file);
             BufferedWriter w = writer(target)) {
            String raw;
            boolean first = true;
            while ((raw = r.readLine()) != null) {
                if (progress != null && progress.isCancelled()) return;
                result.read++;
                String line = clean(raw, first, opts, result);
                first = false;
                if (line == null) continue;
                if (seen != null) {
                    if (seen.size() < UNSORTED_DEDUPE_LIMIT) {
                        if (!seen.add(line)) {
                            result.removedDuplicate++;
                            continue;
                        }
                    } else {
                        result.dedupeTruncated = true;
                    }
                }
                w.write(line);
                w.write('\n');
                result.kept++;
                if ((result.read & 0x3FFF) == 0 && progress != null) {
                    progress.onProgress(result.read, result.kept);
                }
            }
        }
    }

    private List<File> filterIntoSortedChunks(Wordlist source, Options opts,
                                              Progress progress, Result result) throws IOException {
        List<File> chunks = new ArrayList<>();
        List<String> buffer = new ArrayList<>(CHUNK_LINES);
        try (BufferedReader r = reader(source.file)) {
            String raw;
            boolean first = true;
            while ((raw = r.readLine()) != null) {
                if (progress != null && progress.isCancelled()) return chunks;
                result.read++;
                String line = clean(raw, first, opts, result);
                first = false;
                if (line == null) continue;
                buffer.add(line);
                if (buffer.size() >= CHUNK_LINES) {
                    chunks.add(flushChunk(buffer, opts));
                    buffer.clear();
                }
                if ((result.read & 0x3FFF) == 0 && progress != null) {
                    progress.onProgress(result.read, result.kept);
                }
            }
        }
        if (!buffer.isEmpty()) chunks.add(flushChunk(buffer, opts));
        return chunks;
    }

    private File flushChunk(List<String> buffer, Options opts) throws IOException {
        Collections.sort(buffer, comparator(opts));
        File chunk = File.createTempFile("wl-chunk", ".tmp", store.dir());
        try (BufferedWriter w = writer(chunk)) {
            for (String line : buffer) {
                w.write(line);
                w.write('\n');
            }
        }
        return chunk;
    }

    private void mergeChunks(List<File> chunks, File target, Options opts, Result result)
            throws IOException {
        List<BufferedReader> readers = new ArrayList<>();
        try {
            PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) ->
                    comparator(opts).compare(a.line, b.line));
            for (File chunk : chunks) {
                BufferedReader r = reader(chunk);
                readers.add(r);
                String line = r.readLine();
                if (line != null) queue.add(new Entry(line, r));
            }
            String previous = null;
            try (BufferedWriter w = writer(target)) {
                while (!queue.isEmpty()) {
                    Entry e = queue.poll();
                    if (opts.dedupe && e.line.equals(previous)) {
                        result.removedDuplicate++;
                    } else {
                        w.write(e.line);
                        w.write('\n');
                        result.kept++;
                        previous = e.line;
                    }
                    String next = e.reader.readLine();
                    if (next != null) queue.add(new Entry(next, e.reader));
                }
            }
        } finally {
            for (BufferedReader r : readers) {
                try { r.close(); } catch (IOException ignored) {}
            }
        }
    }

    private static class Entry {
        final String line;
        final BufferedReader reader;

        Entry(String line, BufferedReader reader) {
            this.line = line;
            this.reader = reader;
        }
    }

    private static java.util.Comparator<String> comparator(Options opts) {
        if (opts.sort == Sort.BY_LENGTH) {
            return (a, b) -> {
                int d = Integer.compare(a.length(), b.length());
                return d != 0 ? d : a.compareTo(b);
            };
        }
        return String::compareTo;
    }

    private String clean(String raw, boolean first, Options opts, Result result) {
        String line = raw;
        if (first && !line.isEmpty() && line.charAt(0) == BOM) line = line.substring(1);

        if (opts.stripControl) {
            line = line.replace("\r", "");
            StringBuilder sb = null;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c < 0x20 || c == 0x7F) {
                    if (sb == null) sb = new StringBuilder(line.substring(0, i));
                } else if (sb != null) {
                    sb.append(c);
                }
            }
            if (sb != null) line = sb.toString();
        }
        if (opts.trim) line = line.trim();

        if (opts.dropComments && (line.startsWith("#") || line.startsWith("//"))) {
            result.removedComment++;
            return null;
        }
        if (opts.dropEmpty && line.isEmpty()) {
            result.removedEmpty++;
            return null;
        }
        if (opts.toLowercase) line = line.toLowerCase(Locale.US);
        if (opts.asciiOnly) {
            String folded = Normalizer.normalize(line, Normalizer.Form.NFKD)
                    .replaceAll("\\p{M}+", "");
            if (!folded.matches("\\p{ASCII}*")) {
                result.removedNonAscii++;
                return null;
            }
            line = folded;
        }

        int min = opts.wpaOnly ? 8 : opts.minLength;
        int max = opts.wpaOnly ? 63 : opts.maxLength;
        if (min > 0 && line.length() < min) {
            result.removedLength++;
            return null;
        }
        if (max > 0 && line.length() > max) {
            result.removedLength++;
            return null;
        }
        return line;
    }

    private Wordlist publish(Wordlist source, File staging, Options opts) throws IOException {
        if (opts.inPlace) {
            File dest = source.file;
            dest.delete();
            if (!staging.renameTo(dest)) {
                copy(staging, dest);
                staging.delete();
            }
            Wordlist updated = new Wordlist(dest);
            updated.category = source.category;
            updated.origin = source.origin;
            updated.note = source.note;
            store.finish(updated, -1);
            return updated;
        }

        String name = opts.outputName != null && !opts.outputName.isEmpty()
                ? opts.outputName
                : source.getDisplayName() + "-clean.txt";
        Wordlist out = store.create(name, source.category, Wordlist.Origin.DERIVED);
        out.note = "Cleaned from " + source.getName();
        if (!staging.renameTo(out.file)) {
            copy(staging, out.file);
            staging.delete();
        }
        store.finish(out, -1);
        return out;
    }

    private static void copy(File from, File to) throws IOException {
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[1 << 16];
            int read;
            while ((read = in.read(buf)) > 0) out.write(buf, 0, read);
        }
    }

    private static BufferedReader reader(File f) throws IOException {
        return new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"), 1 << 16);
    }

    private static BufferedWriter writer(File f) throws IOException {
        return new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"), 1 << 16);
    }
}
