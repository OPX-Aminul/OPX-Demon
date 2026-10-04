package com.opx.demon.hashcrack;

import com.opx.demon.wordlists.Wordlist;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class HashCracker {

    private static final int BATCH = 2048;
    private static final int QUEUE_DEPTH = 8;

    public interface Listener {
        void onProgress(long tried, long total, long hashesPerSecond);
        void onFound(String hash, String password);
        void onFinished(Result result);
    }

    public static class Result {
        public final Map<String, String> cracked = new LinkedHashMap<>();
        public long tried;
        public long elapsedMillis;
        public boolean cancelled;
        public boolean exhausted;
        public String error;

        public long rate() {
            return elapsedMillis <= 0 ? 0 : tried * 1000 / elapsedMillis;
        }
    }

    public enum SaltMode { NONE, PREFIX, SUFFIX }

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicLong tried = new AtomicLong();
    private volatile Thread coordinator;

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void start(final List<String> targets, final HashType type, final Wordlist wordlist,
                      final String salt, final SaltMode saltMode, final long total,
                      final Listener listener) {

        coordinator = new Thread(() -> {
            Result result = new Result();
            long started = System.currentTimeMillis();

            final Set<String> remaining = Collections.newSetFromMap(new ConcurrentHashMap<>());
            for (String t : targets) {
                String n = HashType.normalize(t);
                if (!n.isEmpty()) remaining.add(n);
            }
            if (remaining.isEmpty()) {
                result.error = "No hashes to test";
                listener.onFinished(result);
                return;
            }

            final Map<String, String> found = new ConcurrentHashMap<>();
            final BlockingQueue<List<String>> queue = new ArrayBlockingQueue<>(QUEUE_DEPTH);
            final List<String> poison = new ArrayList<>(0);

            int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            List<Thread> workers = new ArrayList<>(workerCount);
            final AtomicBoolean failed = new AtomicBoolean(false);

            for (int i = 0; i < workerCount; i++) {
                Thread worker = new Thread(() -> {
                    HashType.Digester digester;
                    try {
                        digester = type.digester();
                    } catch (Exception e) {
                        failed.set(true);
                        cancelled.set(true);
                        return;
                    }
                    while (true) {
                        List<String> batch;
                        try {
                            batch = queue.take();
                        } catch (InterruptedException e) {
                            return;
                        }
                        if (batch == poison) return;
                        for (String candidate : batch) {
                            if (cancelled.get() || remaining.isEmpty()) break;
                            String digest = digester.hash(salted(candidate, salt, saltMode));
                            if (remaining.contains(digest)) {
                                remaining.remove(digest);
                                found.put(digest, candidate);
                                listener.onFound(digest, candidate);
                            }
                        }
                        tried.addAndGet(batch.size());
                    }
                }, "hashcrack-worker-" + i);
                worker.setPriority(Thread.NORM_PRIORITY - 1);
                workers.add(worker);
                worker.start();
            }

            long lastReport = started;
            long lastTried = 0;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(wordlist.file), "UTF-8"), 1 << 16)) {
                List<String> batch = new ArrayList<>(BATCH);
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelled.get() || remaining.isEmpty()) break;
                    batch.add(line);
                    if (batch.size() >= BATCH) {
                        queue.put(batch);
                        batch = new ArrayList<>(BATCH);

                        long now = System.currentTimeMillis();
                        if (now - lastReport >= 250) {
                            long done = tried.get();
                            long rate = (done - lastTried) * 1000 / Math.max(1, now - lastReport);
                            listener.onProgress(done, total, rate);
                            lastReport = now;
                            lastTried = done;
                        }
                    }
                }
                if (!batch.isEmpty() && !cancelled.get()) queue.put(batch);
                result.exhausted = !cancelled.get() && !remaining.isEmpty();
            } catch (IOException e) {
                result.error = e.getMessage();
            } catch (InterruptedException e) {
                cancelled.set(true);
            }

            try {
                for (int i = 0; i < workerCount; i++) queue.put(poison);
            } catch (InterruptedException ignored) {
            }
            for (Thread worker : workers) {
                try {
                    worker.join(2000);
                } catch (InterruptedException ignored) {
                }
            }

            if (failed.get() && result.error == null) {
                result.error = "This device has no provider for " + type.title;
            }
            result.cracked.putAll(found);
            result.tried = tried.get();
            result.elapsedMillis = System.currentTimeMillis() - started;
            result.cancelled = cancelled.get() && !failed.get();
            listener.onFinished(result);
        }, "hashcrack-coordinator");

        coordinator.start();
    }

    private static String salted(String candidate, String salt, SaltMode mode) {
        if (salt == null || salt.isEmpty() || mode == SaltMode.NONE) return candidate;
        return mode == SaltMode.PREFIX ? salt + candidate : candidate + salt;
    }

    public static List<String> parseHashes(String input) {
        List<String> out = new ArrayList<>();
        if (input == null) return out;
        Set<String> seen = new HashSet<>();
        for (String token : input.split("[\\s,;]+")) {
            String t = HashType.normalize(token);
            if (t.contains(":")) {
                String[] parts = t.split(":");
                t = HashType.normalize(parts[parts.length - 1]);
            }
            if (t.isEmpty() || !HashType.isHex(t)) continue;
            if (seen.add(t)) out.add(t);
        }
        return out;
    }

    public static String describeRate(long perSecond) {
        if (perSecond >= 1_000_000) {
            return String.format(Locale.US, "%.1f M h/s", perSecond / 1_000_000.0);
        }
        if (perSecond >= 1_000) {
            return String.format(Locale.US, "%.1f k h/s", perSecond / 1_000.0);
        }
        return perSecond + " h/s";
    }
}
