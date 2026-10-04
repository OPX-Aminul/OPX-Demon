package com.opx.demon.engine;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answers one question before a full boot is attempted: does the UML kernel
 * binary start at all on this phone?
 *
 * Ported from strykerapp 6.5's UmlProbe. The full boot has a dozen ways to fail
 * (rootfs not mounted, agent never answers, network down) and they all end up
 * as the same unhelpful sentence. This probe launches the kernel with a 64 MB
 * memory image and NO rootfs, so the only thing that can stop it is the host:
 * it prints "Linux version ..." and then fails to find a root device. Reaching
 * the banner proves the binary loads, the exec is allowed, and seccomp works —
 * which rules out the whole class of "the kernel never started" failures, and
 * classifies the rest into a specific reason instead of "see the boot log".
 *
 * The probe is bounded (12 s) and never touches the guest, so it is safe to run
 * before a real boot.
 */
public final class UmlProbe {

    private static final String TAG = "UmlProbe";

    public static final long DEFAULT_TIMEOUT_MS = 12_000;

    /** Just enough memory to print the banner; the guest never mounts anything. */
    private static final int PROBE_MEM_MB = 64;

    private UmlProbe() {
    }

    public enum Verdict {
        /** The kernel printed its banner: this device can run UML. */
        KERNEL_STARTS,
        /** No verdict either way — the kernel was still silent when time ran out. */
        UNPROVEN,
        /** The kernel was refused or died before the banner. */
        BLOCKED
    }

    public static final class Result {
        public final Verdict verdict;
        public final String detail;
        public final List<String> notes;

        Result(Verdict verdict, String detail, List<String> notes) {
            this.verdict = verdict;
            this.detail = detail;
            this.notes = notes == null ? Collections.<String>emptyList() : notes;
        }

        /** True when the failure is on the host side, so retrying the boot cannot help. */
        public boolean ruledOut() {
            return verdict == Verdict.BLOCKED;
        }
    }

    public static Result run(Context app) {
        return run(app, DEFAULT_TIMEOUT_MS);
    }

    public static Result run(Context app, long timeoutMs) {
        return withPageSize(probe(app, timeoutMs));
    }

    /**
     * 16 KB-page phones refuse a binary laid out for 4 KB pages, and the kernel
     * says nothing useful when that happens, so the page size is part of the
     * diagnosis rather than something the user has to go look up.
     */
    private static Result withPageSize(Result r) {
        if (r == null || r.verdict == Verdict.KERNEL_STARTS) return r;
        long page = hostPageSize();
        if (page == 4096L || page <= 0L) return r;
        return new Result(r.verdict,
                r.detail + " · this phone's kernel uses " + (page / 1024) + " KB memory pages",
                r.notes);
    }

    private static long hostPageSize() {
        try {
            return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable t) {
            return -1L;
        }
    }

    private static Result probe(Context app, long timeoutMs) {
        File kernel = RootlessPaths.umlKernel(app);
        File stub = RootlessPaths.umlStub(app);

        String problem = NativeExec.check(kernel, stub);
        if (problem != null) {
            return new Result(Verdict.BLOCKED, problem, null);
        }

        Process p = null;
        Scan scan = new Scan();
        try {
            File dir = RootlessPaths.base(app);
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            ProcessBuilder pb = new ProcessBuilder(command(kernel, stub));
            pb.directory(dir);
            pb.redirectErrorStream(true);
            String tmp = umlTempDir(app);
            if (tmp != null) pb.environment().put("TMPDIR", tmp);

            try {
                p = pb.start();
            } catch (java.io.IOException e) {
                return new Result(Verdict.BLOCKED,
                        NativeExec.explain(kernel, e), scan.notes());
            }
            pump(p, scan);

            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (scan.decided()) break;
                if (!isAlive(p)) {
                    sleep(250);
                    break;
                }
                sleep(100);
            }
            return scan.verdict(isAlive(p) ? null : exitCode(p));
        } catch (Throwable t) {
            Log.w(TAG, "uml probe failed to start", t);
            return new Result(Verdict.BLOCKED,
                    "the kernel would not start: " + reason(t), scan.notes());
        } finally {
            kill(p);
        }
    }

    /**
     * The probe command: the same con/con0/seccomp contract the real boot uses,
     * minus everything that needs a rootfs. The kernel is EXPECTED to end in a
     * root-device failure here — that is the "KERNEL_STARTS" signal, not a bug.
     */
    private static List<String> command(File kernel, File stub) {
        List<String> cmd = new ArrayList<>();
        cmd.add(kernel.getAbsolutePath());
        cmd.add("mem=" + PROBE_MEM_MB + "M");
        cmd.add("con=null");
        cmd.add("con0=fd:0,fd:1");
        cmd.add("stub_exe=" + stub.getAbsolutePath());
        cmd.add("seccomp=auto");
        return cmd;
    }

    private static void pump(Process p, final Scan scan) {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = in.readLine()) != null) scan.accept(line);
            } catch (Throwable ignored) {
            }
        }, "uml-probe-console");
        t.setDaemon(true);
        t.start();
    }

    private static final class Scan {
        private final List<String> notes =
                Collections.synchronizedList(new ArrayList<String>());
        private volatile boolean banner;
        private volatile boolean completed;
        private volatile String blocker;
        private volatile String mode = "";

        void accept(String raw) {
            if (raw == null) return;
            String line = raw.trim();
            if (line.isEmpty()) return;

            if (line.contains("Linux version")) banner = true;

            // Expected outcomes for a probe with no rootfs: reaching one of
            // these means the kernel got all the way into mounting, which is a
            // successful probe.
            if (line.contains("Unable to mount root fs")
                    || line.contains("VFS: Cannot open root device")
                    || line.contains("Kernel panic")) {
                banner = true;
                completed = true;
            }

            if (line.contains("Userspace mode:")) {
                mode = line;
                keep(line);
            }
            if (line.contains("tempdir") || line.contains("PROT_EXEC")
                    || line.startsWith("Checking")) {
                keep(line);
            }

            // Past the banner nothing is a failure any more.
            if (banner) return;
            if (line.contains("none found")) {
                blocked("no writable tempdir for the guest's memory");
            } else if (line.contains("Failed to") || line.contains("failed to")
                    || line.contains("Aborted") || line.contains("Illegal instruction")
                    || line.contains("Permission denied") || line.contains("not permitted")) {
                blocked(line);
                keep(line);
            }
        }

        private void keep(String line) {
            synchronized (notes) {
                if (notes.size() < 24) notes.add(line);
            }
        }

        private void blocked(String why) {
            if (blocker == null) blocker = why;
        }

        boolean decided() {
            return completed || (blocker != null && !banner);
        }

        List<String> notes() {
            synchronized (notes) {
                return new ArrayList<>(notes);
            }
        }

        Result verdict(Integer exit) {
            if (banner) {
                String detail = completed && !mode.isEmpty()
                        ? "the kernel starts here · " + shortMode()
                        : "the kernel starts here";
                return new Result(Verdict.KERNEL_STARTS, detail, notes());
            }
            if (blocker != null) {
                return new Result(Verdict.BLOCKED, blocker, notes());
            }
            if (exit != null) {
                return new Result(Verdict.BLOCKED,
                        "the kernel exited immediately (code " + exit + ")", notes());
            }
            return new Result(Verdict.UNPROVEN,
                    "the kernel did not answer in time", notes());
        }

        private String shortMode() {
            int at = mode.indexOf("Userspace mode:");
            String tail = at < 0 ? mode : mode.substring(at);
            return tail.length() > 48 ? tail.substring(0, 48) : tail;
        }
    }

    /**
     * The probe needs the same writable tempdir the real boot needs, because
     * UML builds its memory image in $TMPDIR before it prints anything.
     */
    private static String umlTempDir(Context app) {
        File[] candidates = {
                new File(RootlessPaths.base(app), "tmp"),
                new File(app.getCacheDir(), "uml"),
                app.getFilesDir()
        };
        for (File dir : candidates) {
            if (usable(dir)) return dir.getAbsolutePath();
        }
        return null;
    }

    private static boolean usable(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        try {
            File probe = new File(dir, ".uml-probe-tmp");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(probe)) {
                out.write(0);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void kill(Process p) {
        if (p == null) return;
        try {
            p.destroy();
            for (int i = 0; i < 10 && isAlive(p); i++) sleep(100);
            if (isAlive(p)) {
                // destroyForcibly() is API 26 and minSdk is 24, so go through
                // reflection and fall back to destroy().
                try {
                    p.getClass().getMethod("destroyForcibly").invoke(p);
                } catch (Throwable t) {
                    p.destroy();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** exitValue() instead of isAlive(): Process.isAlive() is API 26, minSdk is 24. */
    private static boolean isAlive(Process p) {
        try {
            p.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static Integer exitCode(Process p) {
        try {
            return p.exitValue();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String reason(Throwable t) {
        String m = t.getMessage();
        return m == null || m.isEmpty() ? t.getClass().getSimpleName() : m;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    }