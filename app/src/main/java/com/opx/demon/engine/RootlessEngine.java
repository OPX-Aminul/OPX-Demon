package com.opx.demon.engine;

import android.content.Context;
import android.util.Log;

import com.opx.demon.utils.Core;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RootlessEngine {

    private static final String TAG = "RootlessEngine";
    private static final int BOOT_TIMEOUT_MS = 900_000;
    /** Minimum spacing between console-bootstrap attempts inside one boot. */
    private static final long CONSOLE_RETRY_MS = 30_000;
    private volatile long lastConsoleBootstrapMs;
    /** Set when the last boot timed out with the guest alive but the agent never answering. */
    private volatile boolean lastBootAgentTimeout;
    private static final String PROMPT_MARK = "__OPX_DEMON_ID__";

    private static volatile RootlessEngine instance;

    private final Context app;
    private final ExecutorService qemuExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "opxdemon-qemu");
        t.setDaemon(true);
        return t;
    });

    private volatile Process qemuProcess;
    private volatile Process dyingProcess;
    private volatile boolean booted;
    private volatile long lastGuestOk;
    private static final long GUEST_FRESH_MS = 15_000;
    private volatile File shareInUse;
    private volatile boolean shareActive;
    private volatile boolean usbDriverOk;
    private final Object bootMarkLock = new Object();
    private volatile String lastError = "";
    private volatile String guestPrompt = "";
    private volatile boolean lastBootUsedFallback;
    private volatile boolean autoFallback = true;
    private volatile boolean stopRequested;
    private volatile QmpClient qmp;

    private static final String NETDEV_ID = "net0";
    /** QEMU engine: usb-host devices over QMP. UML engine: USB/IP over TCP 3240. */
    private volatile UsbBridge usb;
    /** UML-mode USB/IP server; null on the QEMU engine. */
    private volatile UmlUsbServer umlUsbServer;

    /** True while the running VM process is the UML kernel (not QEMU). */
    private volatile boolean umlProcess;
    /** Rootless UML network gateway (uml-netd) serving the guest's vec0. */
    private volatile Process umlNetdProcess;

    public interface BootListener {
        void onBootLine(String line);
        void onBooted();
        void onFailed(String reason);
    }

    public enum State { STOPPED, BOOTING, READY }

    private RootlessEngine(Context context) {
        this.app = context.getApplicationContext();
    }

    public static RootlessEngine get(Context context) {
        if (instance == null) {
            synchronized (RootlessEngine.class) {
                if (instance == null) instance = new RootlessEngine(context);
            }
        }
        return instance;
    }


    public boolean isInstalled() {
        if (EngineType.isUml(prefs())) {
            // UML engine: linux-uml + stub_exe + the shared rootfs. No QEMU chain.
            return RootlessPaths.umlKernel(app).isFile()
                    && RootlessPaths.umlStub(app).isFile()
                    && RootlessPaths.rootfs(app).isFile();
        }
        RootlessPaths.ensureSlirpSoname(app);
        return RootlessPaths.qemuBin(app).isFile()
                && RootlessPaths.kernel(app).isFile()
                && RootlessPaths.initrd(app).isFile()
                && RootlessPaths.libslirp(app).isFile()
                && RootlessPaths.libslirpSoname(app).exists()
                && RootlessPaths.rootfs(app).isFile();
    }

    public boolean isRunning() {
        Process p = qemuProcess;
        return p != null && isAlive(p);
    }

    public boolean isReady() {
        if (!isRunning() || !booted) return false;
        if (!GuestExec.ping(1000)) return false;
        lastGuestOk = System.currentTimeMillis();
        return true;
    }


    private volatile int consecutiveBootFailures = 0;
    private static final int MAX_CONSECUTIVE_BOOT_FAILURES = 3;

    public synchronized boolean startBlocking(BootListener listener) {
        if (isReady()) { if (listener != null) listener.onBooted(); return true; }
        if (isRunning() && booted) {
            for (int i = 0; i < 5; i++) {
                if (GuestExec.ping(2000)) {
                    if (listener != null) listener.onBooted();
                    return true;
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
            }
            lastError = "VM is running but the guest command server stopped responding";
            GuestExec.logToStore(lastError + " — not rebooting; restart the VM from the dashboard if it persists");
            if (listener != null) listener.onFailed(lastError);
            return false;
        }
        if (!isInstalled()) {
            lastError = "Rootless artifacts not installed";
            if (listener != null) listener.onFailed(lastError);
            return false;
        }
        lastBootUsedFallback = false;
        umlSeccompOff = false;
        umlSeccompRetryUsed = false;
        // Tell GuestExec where to mirror its diagnostics, and mark where this
        // attempt starts. NOT a delete: the engine-file check runs before this
        // point and its verdict lives in the same file, and wiping it here is
        // what made "the update never ran" indistinguishable from "the update
        // ran and found nothing". The file is size-capped at the writer.
        try {
            File diag = RootlessPaths.engineLog(app);
            GuestExec.setDiagnosticsFile(diag);
            GuestExec.logToStore("--- VM start requested (attempt "
                    + (consecutiveBootFailures + 1) + ") ---");
        } catch (Exception ignored) {}
        stopRequested = false;
        lastBootAgentTimeout = false;
        String reason = attemptBoot(listener);
        if (reason == null) {
            consecutiveBootFailures = 0;
            lastError = "";
            return true;
        }

        // The safe profile only changes QEMU I/O options (aio, cache, share, rng, USB).
        // It cannot bring a guest agent up, so a boot that failed with the guest alive but
        // opxdemon-agentd silent would burn another 600s on the same outcome while killing
        // the VM the user could still recover. Leave it running and report the real cause.
        if (lastBootAgentTimeout) {
            lastError = reason;
            GuestExec.logToStore("VM stays running — the guest is up, only opxdemon-agentd is "
                    + "missing. Restart the VM from the dashboard to retry the bootstrap.");
            if (listener != null) listener.onFailed(reason);
            return false;
        }

        Core prefs = prefs();
        if (autoFallback && prefs != null && !VmSpecs.safeBoot(prefs)) {
            lastError = reason;
            note(listener, "Boot failed (" + reason + ") — retrying with a safe profile");
            GuestExec.logToStore("VM boot failed (" + reason + "), falling back to the safe profile "
                    + "(aio=threads, cache=writeback, no 9p share, no virtio-rng, no USB HC)");
            dumpConsoleTail();
            VmSpecs.setSafeBoot(prefs, true);
            lastBootUsedFallback = true;
            killAndAwait(12_000);
            String second = attemptBoot(listener);
            if (second == null) {
                lastError = "";
                VmSpecs.setSafeBoot(prefs, false);
                consecutiveBootFailures = 0;
                GuestExec.logToStore("VM booted with the safe profile. The 9p capture share is off for "
                        + "this session — restart the VM to retry the normal profile.");
                return true;
            }
            VmSpecs.setSafeBoot(prefs, false);
            lastBootUsedFallback = false;
            lastError = second;
            if (listener != null) listener.onFailed(second);
            return false;
        }

        // Retry with backoff after consecutive failures
        consecutiveBootFailures++;
        if (consecutiveBootFailures >= MAX_CONSECUTIVE_BOOT_FAILURES) {
            Log.w(TAG, "VM has failed to boot " + consecutiveBootFailures + " times in a row");
            GuestExec.logToStore("VM failed to boot " + consecutiveBootFailures
                    + " times consecutively — resetting state and clearing stale sockets");
            consecutiveBootFailures = 0;
            clearStaleSockets();
            try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        }

        lastError = reason;
        if (listener != null) listener.onFailed(reason);
        return false;
    }

    private String attemptBoot(BootListener listener) {
        try {
            killAndAwait(12_000);
            clearStaleSockets();
            ensureExecutable();
            autoGrowDisk();
            if (EngineType.isUml(prefs())) {
                String uml = attemptBootUml(listener);
                // exit 159 = 128 + SIGSYS: Android's app filter killed the engine
                // during boot. Retry once with seccomp=off (ptrace userspace) so
                // the second attempt's failure message is the diagnosis the user
                // gets instead of a bare exit code.
                if (uml != null && !umlSeccompRetryUsed && safeExit(qemuProcess) == 159) {
                    umlSeccompRetryUsed = true;
                    GuestExec.logToStore("UML died with SIGSYS under seccomp=auto (" + uml
                            + ") — retrying once with seccomp=off (ptrace userspace)");
                    umlSeccompOff = true;
                    uml = attemptBootUml(listener);
                }
                return uml;
            }
            RootlessPaths.ensureLibslirpNames(app);
            VmProbe.ensureCpuProfileVerified(app, prefs());
            List<String> cmd = buildCommand();
            Log.i(TAG, "QEMU: " + join(cmd));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(RootlessPaths.base(app));
            pb.environment().put("LD_LIBRARY_PATH",
                    RootlessPaths.base(app).getAbsolutePath() + ":/system/lib64:/vendor/lib64");
            pb.redirectErrorStream(true);

            final Process proc = pb.start();
            qemuProcess = proc;
            umlProcess = false;
            booted = false;
            // Engine switch insurance: a pty announced by a previous UML boot
            // belongs to a dead kernel and must never be consulted here, and the
            // QEMU serial socket is a completely different console.
            GuestConsole.setUmlPtsDevice(null);
            GuestConsole.resetUmlChannel();

            new Thread(() -> pumpBootLog(proc, listener), "opxdemon-qemu-log").start();

            long deadline = System.currentTimeMillis() + BOOT_TIMEOUT_MS;
            // The guest can be fully up with no agent listening — Debian boots, the console
            // sits at a login prompt, and port 1050 stays silent because nothing serves it.
            // Waiting longer never fixes that, so once the console shows the guest is alive,
            // bootstrap the agent through it instead of running out the clock. The bootstrap
            // is retried periodically (not once): the first attempt can land while systemd is
            // still coming up, before the serial getty — and therefore the console login —
            // even exists, and giving up after it wastes the rest of the 600 s budget on a
            // failure one more try would have fixed.
            int bootstraps = 0;
            while (System.currentTimeMillis() < deadline) {
                if (stopRequested) return "stopped";
                if (!isAlive(proc)) {
                    return "QEMU exited during boot (code " + safeExit(proc) + "): " + lastLogProblem();
                }
                if (GuestExec.ping(1500) && guestShellReady()) {
                    markBooted();
                    if (listener != null) listener.onBooted();
                    return null;
                }
                // Same reasoning as the UML path, and the same bug removed: this
                // used to require VmBootStage.detect() to reach AGENT, which only
                // happens when the words "login:" or "root@" appear in the log
                // tail. A rootfs that autologins prints neither, so the gate
                // never opened and the bootstrap never ran. Gate on the console
                // being usable instead — that is the actual precondition.
                if (consoleShellUsable()
                        && (bootstraps == 0 || lastConsoleBootstrapMs + CONSOLE_RETRY_MS
                                <= System.currentTimeMillis())) {
                    bootstraps++;
                    lastConsoleBootstrapMs = System.currentTimeMillis();
                    note(listener, bootstraps == 1
                            ? "Guest is up but the agent is not answering — starting it"
                            : "Agent still not answering — retrying the console bootstrap ("
                              + bootstraps + ")");
                    bootstrapAgentOverConsole();
                }
                sleep(1000);
            }
            // Remember WHY this boot timed out: startBlocking uses it to skip the safe-profile
            // retry, which cannot fix a missing agent and would only kill a recoverable VM.
            lastBootAgentTimeout = bootstraps > 0;
            return "Boot timed out after " + (BOOT_TIMEOUT_MS / 1000) + "s"
                    + (bootstraps > 0 ? " — the guest booted but opxdemon-agentd never came up"
                                      : " — console bootstrap never had a guest to talk to");
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    public String lastError() {
        return lastError == null ? "" : lastError;
    }

    public String guestPrompt() {
        return guestPrompt == null ? "" : guestPrompt;
    }

    private boolean guestShellReady() {
        try {
            ArrayList<String> out = GuestExec.run(
                    "printf '" + PROMPT_MARK + "%s@%s\\n' \"$(id -un 2>/dev/null)\" \"$(hostname 2>/dev/null)\"");
            for (String l : out) {
                if (l == null) continue;
                int at = l.indexOf(PROMPT_MARK);
                if (at < 0) continue;
                String id = l.substring(at + PROMPT_MARK.length()).trim();
                if (id.length() > 5 && id.startsWith("root@")) {
                    guestPrompt = id;
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    public boolean usedSafeFallback() {
        return lastBootUsedFallback;
    }

    public void setAutoFallback(boolean enabled) {
        autoFallback = enabled;
    }

    private Core prefs() {
        try {
            return new Core(app);
        } catch (Throwable t) {
            return null;
        }
    }

    private void note(BootListener listener, String message) {
        if (listener != null) listener.onBootLine(message);
    }

    private void autoGrowDisk() {
        try {
            File img = RootlessPaths.rootfs(app);
            if (!img.exists()) return;
            if (!VmSpecs.shouldAutoGrow(app)) return;
            long target = VmSpecs.autoDiskTargetBytes(app);
            long before = img.length();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(img, "rw")) {
                raf.setLength(target);
                raf.getFD().sync();
            }
            if (img.length() != target) return;
            new Core(app).putBoolean(VmSpecs.K_RESIZE_PENDING, true);
            GuestExec.logToStore("VM disk grew " + (before / VmSpecs.GB) + " GB -> "
                    + (target / VmSpecs.GB) + " GB to match free storage");
        } catch (Throwable t) {
            Log.w(TAG, "autoGrowDisk: " + t.getMessage());
        }
    }

    private void reclaimFreedSpace() {
        try {
            GuestExec.run("command -v fstrim >/dev/null 2>&1 && fstrim / 2>&1 || true");
        } catch (Throwable t) {
            Log.w(TAG, "fstrim: " + t.getMessage());
        }
    }

    private void clearStaleSockets() {
        deleteQuietly(RootlessPaths.qmpSock(app));
        deleteQuietly(RootlessPaths.serialSock(app));
        deleteQuietly(RootlessPaths.termSock(app));
    }

    private static void deleteQuietly(File f) {
        try {
            if (f != null && f.exists()) //noinspection ResultOfMethodCallIgnored
                f.delete();
        } catch (Throwable ignored) {
        }
    }

    private void killAndAwait(long timeoutMs) {
        Process live = qemuProcess;
        Process dying = dyingProcess;
        if (live == null && (dying == null || !isAlive(dying))) {
            dyingProcess = null;
            return;
        }
        if (live != null) teardownRunning();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Process p = dyingProcess;
            if (p == null || !isAlive(p)) {
                dyingProcess = null;
                return;
            }
            sleep(200);
        }
        Process p = dyingProcess;
        if (p != null && isAlive(p)) {
            destroyForcibly(p);
            sleep(600);
        }
        if (p == null || !isAlive(p)) dyingProcess = null;
    }

    private String lastLogProblem() {
        // 159 = 128 + SIGSYS(31). A UML engine dies this way when Android's
        // per-app seccomp filter rejects a raw host syscall — in ptrace mode
        // (probe fell back, guest syscalls run raw) or while the SECCOMP probe
        // itself touches a syscall the host's syscall table lacks. Android
        // reports nothing itself, so this translation is the whole diagnosis.
        if ("ptrace".equals(umlUserspaceMode)) {
            return "guest fell back to ptrace userspace and Android's app seccomp "
                    + "filter killed a raw syscall with SIGSYS (exit 159) — the "
                    + "SECCOMP probe failed on this kernel and the ptrace fallback "
                    + "cannot survive the app filter";
        }
        // The stub died or was never exec'd: on Android the usual cause is
        // the untrusted_app SELinux domain refusing to exec the stub from an
        // anonymous memfd — which buildUmlCommand() avoids by passing
        // stub_exe=<path>. Seeing this text on a current kernel means the
        // stub file itself is unusable (wrong build, truncated download).
        try {
            java.util.List<String> tail = tailLog(40);
            if (tail.stream().anyMatch(l -> l != null
                    && l.contains("wait_stub_done_seccomp : the stub is gone"))) {
                return "the UML stub died — on Android this means the stub file is "
                        + "unusable (build or download problem); reinstall the engine";
            }
            for (int i = tail.size() - 1; i >= 0; i--) {
                String l = tail.get(i);
                if (l == null) continue;
                String lower = l.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("qemu-system") || lower.contains("error")
                        || lower.contains("failed") || lower.contains("not supported")
                        || lower.contains("invalid")
                        // UML is a Linux kernel, so its fatal messages are kernel
                        // messages: without these the user only ever sees
                        // "see the boot log" for a panic or a dead root mount.
                        || lower.contains("panic") || lower.contains("not syncing")
                        || lower.contains("vfs:") || lower.contains("root device")
                        || lower.contains("rootfs") || lower.contains("ubda")
                        || lower.contains("attempted to kill init")
                        || lower.contains("segmentation fault")
                        || lower.contains("attempted to kill")) {
                    return l.length() > 160 ? l.substring(0, 160) : l;
                }
            }
            // Nothing matched: quote the last lines anyway — a silent exit with no
            // keyword is exactly the case where "see the boot log" helps nobody.
            for (int i = tail.size() - 1; i >= 0; i--) {
                String l = tail.get(i);
                if (l != null && l.trim().length() > 8) {
                    return l.trim().length() > 160 ? l.trim().substring(0, 160) : l.trim();
                }
            }
        } catch (Throwable ignored) {
        }
        return "see the boot log";
    }

    public void startAsync() {
        qemuExecutor.submit(() -> startBlocking(null));
    }

    public void stop() {
        stopRequested = true;
        teardownRunning();
    }

    /**
     * Tears down whatever VM is currently running, without claiming the user asked to stop.
     *
     * stopRequested is the "abandon the boot we are waiting on" signal that attemptBoot() polls.
     * Clearing out a previous instance before starting a new one must not set it: doing so marks
     * the boot that has not even launched yet as stopped, and attemptBoot() bails on its first
     * loop with reason "stopped". Because each aborted attempt still leaves its own QEMU alive,
     * the next start() finds a live process, tears it down, sets the flag again, and the engine
     * never gets past this point.
     */
    private void teardownRunning() {
        booted = false;
        guestPrompt = "";
        final UsbBridge oldUsb = usb;
        final UmlUsbServer oldUmlUsb = umlUsbServer;
        final QmpClient oldQmp = qmp;
        usb = null;
        qmp = null;
        umlUsbServer = null;
        umlProcess = false;
        final Process oldNetd = umlNetdProcess;
        umlNetdProcess = null;
        // Stop the console tap so a dead VM's console cannot keep appending to serial.log.
        GuestConsole.setObserver(null);
        // The kernel's stdin/stdout belong to the process that is about to die.
        // Leaving them attached would let the next boot's console reads land in
        // this one's dead pipe.
        UmlStdio.detach();
        final Process p = qemuProcess;
        qemuProcess = null;
        if (p != null) dyingProcess = p;
        new Thread(() -> {
            try { if (oldUmlUsb != null) oldUmlUsb.stop(); } catch (Throwable ignored) {}
            try { if (oldUsb != null) oldUsb.detachAll(); } catch (Throwable ignored) {}
            try { if (oldQmp != null) oldQmp.powerdown(); } catch (Throwable ignored) {}
            try { if (oldQmp != null) oldQmp.close(); } catch (Throwable ignored) {}
            try { if (oldNetd != null) oldNetd.destroy(); } catch (Throwable ignored) {}
            if (p == null) return;
            sleep(2500);
            if (isAlive(p)) p.destroy();
            sleep(1500);
            if (isAlive(p)) destroyForcibly(p);
        }, "opxdemon-qemu-stop").start();
    }

    public boolean stopAndWait(long timeoutMs) {
        stop();
        killAndAwait(timeoutMs);
        clearStaleSockets();
        Process p = dyingProcess;
        return p == null || !isAlive(p);
    }

    public boolean hardRestart(BootListener listener) {
        stopAndWait(20_000);
        return startBlocking(listener);
    }


    public enum ResizeResult { OK, ALREADY_THAT_SIZE, SHRINK_UNSUPPORTED, VM_STILL_RUNNING, IMAGE_MISSING, IO_ERROR }

    public synchronized ResizeResult resizeDisk(long targetBytes) {
        File img = RootlessPaths.rootfs(app);
        if (!img.exists()) return ResizeResult.IMAGE_MISSING;
        long current = img.length();
        if (targetBytes == current) return ResizeResult.ALREADY_THAT_SIZE;
        if (targetBytes < current) return ResizeResult.SHRINK_UNSUPPORTED;

        if (!stopAndWait(20_000)) return ResizeResult.VM_STILL_RUNNING;

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(img, "rw")) {
            raf.setLength(targetBytes);
            raf.getFD().sync();
        } catch (Exception e) {
            Log.e(TAG, "resizeDisk failed", e);
            return ResizeResult.IO_ERROR;
        }
        if (img.length() != targetBytes) {
            Log.e(TAG, "resizeDisk: image is " + img.length() + " after asking for " + targetBytes);
            return ResizeResult.IO_ERROR;
        }
        try {
            Core prefs = new Core(app);
            prefs.putInt(VmSpecs.K_DISK_GB, (int) Math.round(targetBytes / (double) VmSpecs.GB));
            prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, true);
        } catch (Throwable ignored) {}
        return ResizeResult.OK;
    }

    private void maybeResizeFilesystem() {
        try {
            // Wait briefly for guest agent to stabilize after boot before issuing disk operations.
            long waitDeadline = System.currentTimeMillis() + 30_000;
            while (!GuestExec.ping(1500) && System.currentTimeMillis() < waitDeadline) {
                Thread.sleep(1000);
            }
            if (!GuestExec.ping(1500)) {
                Log.w(TAG, "skipping resize2fs — guest agent not ready after boot");
                return;
            }
            Core prefs = new Core(app);
            if (!prefs.getBoolean(VmSpecs.K_RESIZE_PENDING)) return;
            long imageBytes = VmSpecs.currentDiskBytes(app);
            long fits = VmSpecs.fittingDiskBytes(app);
            if (imageBytes > fits + VmSpecs.DISK_GROW_STEP_BYTES) {
                prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
                GuestExec.logToStore("VM image claims " + (imageBytes / VmSpecs.GB)
                        + " GB but only " + (fits / VmSpecs.GB)
                        + " GB fits on this device — skipping the filesystem expansion. "
                        + "Pick a size that fits under Settings if you need a bigger disk.");
                return;
            }
            GuestExec.logToStore("expanding VM disk filesystem (resize2fs /dev/vda)...");
            // Use a job to avoid blocking the session if resize2fs takes too long.
            String jobId = Long.toHexString(System.nanoTime()) + "-resize";
            boolean done = false;
            try {
                ArrayList<String> out = GuestExec.run(
                        "command -v resize2fs >/dev/null 2>&1 && echo __HAS_RESIZE2FS__ || echo __NO_RESIZE2FS__; "
                        + "echo __BEFORE__; df -k / | tail -n 1; "
                        + "resize2fs /dev/vda 2>&1 || resize2fs -f /dev/vda 2>&1; "
                        + "echo __AFTER__; df -k / | tail -n 1; echo __RESIZE_DONE__");
                boolean hasTool = false;
                boolean resized = false;
                boolean nothingToDo = false;
                long before = -1L;
                long after = -1L;
                int marker = 0;
                for (String l : out) {
                    if (l == null) continue;
                    if (l.contains("__HAS_RESIZE2FS__")) hasTool = true;
                    if (l.contains("__NO_RESIZE2FS__")) hasTool = false;
                    if (l.contains("__RESIZE_DONE__")) done = true;
                    if (l.toLowerCase(java.util.Locale.ROOT).contains("nothing to do")) nothingToDo = true;
                    if (l.contains("__BEFORE__")) { marker = 1; continue; }
                    if (l.contains("__AFTER__")) { marker = 2; continue; }
                    long blocks = dfBlocks(l);
                    if (blocks <= 0) continue;
                    if (marker == 1 && before < 0) before = blocks;
                    else if (marker == 2 && after < 0) after = blocks;
                }

                boolean grew = before > 0 && after > before;
                if (grew || nothingToDo) {
                    prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
                    GuestExec.logToStore(grew
                            ? "VM disk filesystem expanded to " + (after / 1024L) + " MB"
                            : "VM disk filesystem already fills the image");
                    return;
                }
                if (!hasTool) {
                    GuestExec.logToStore("disk grown, but resize2fs is missing in the guest — "
                            + "run 'apt-get install -y e2fsprogs' in the terminal, the grow retries on the next boot");
                    return;
                }
                if (!done) {
                    GuestExec.logToStore("resize2fs may have timed out — marking complete and retrying on next boot");
                    prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
                    return;
                }
                prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
                GuestExec.logToStore("resize2fs completed (no expansion needed or already applied)");
            } catch (Exception e) {
                GuestExec.logToStore("resize2fs encountered an error: " + e.getMessage());
                prefs.putBoolean(VmSpecs.K_RESIZE_PENDING, false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "maybeResizeFilesystem: " + t.getMessage());
        }
    }

    private static final String ATH9K_HTC_FW = "/lib/firmware/ath9k_htc/htc_9271-1.4.0.fw";
    private static final String RTL8188FU_FW = "/lib/firmware/rtlwifi/rtl8188fu.fw";

    private void ensureWifiFirmware() {
        try {
            ArrayList<String> have = GuestExec.run(
                    "[ -f " + ATH9K_HTC_FW + " ] && echo __ATH_OK__ || echo __ATH_MISSING__; "
                    + "[ -f " + RTL8188FU_FW + " ] && echo __RTL_OK__ || echo __RTL_MISSING__");
            boolean ath = false, rtl = false;
            for (String l : have) {
                if (l == null) continue;
                if (l.contains("__ATH_OK__")) ath = true;
                if (l.contains("__RTL_OK__")) rtl = true;
            }
            if (ath && rtl) return;
            GuestExec.logToStore("guest is missing WiFi firmware (ath9k_htc=" + !ath
                    + ", rtl8188fu=" + !rtl + ") — seeding from the filesystem");
            ArrayList<String> res = GuestExec.run(
                    "for s in /lib/firmware /usr/lib/firmware; do [ -d \"$s\" ] || continue; "
                    + "if [ ! -f " + ATH9K_HTC_FW + " ] && [ -f \"$s/ath9k_htc/htc_9271-1.4.0.fw\" ]; then "
                    + "mkdir -p /lib/firmware/ath9k_htc; cp \"$s/ath9k_htc/htc_9271-1.4.0.fw\" /lib/firmware/ath9k_htc/; fi; "
                    + "if [ ! -f " + RTL8188FU_FW + " ] && [ -f \"$s/rtlwifi/rtl8188fu.fw\" ]; then "
                    + "mkdir -p /lib/firmware/rtlwifi; cp \"$s/rtlwifi/rtl8188fu.fw\" /lib/firmware/rtlwifi/; fi; "
                    + "if [ ! -f /lib/firmware/rtlwifi/rtl8188fufw.bin ] && [ -f \"$s/rtlwifi/rtl8188fufw.bin\" ]; then "
                    + "mkdir -p /lib/firmware/rtlwifi; cp \"$s/rtlwifi/rtl8188fufw.bin\" /lib/firmware/rtlwifi/; fi; "
                    + "done; "
                    + "[ -f " + ATH9K_HTC_FW + " ] && echo __ATH_SEEDED__; "
                    + "[ -f " + RTL8188FU_FW + " ] || [ -f /lib/firmware/rtlwifi/rtl8188fufw.bin ] && echo __RTL_SEEDED__; true");
            boolean athOk = false, rtlOk = false;
            for (String l : res) {
                if (l == null) continue;
                if (l.contains("__ATH_SEEDED__")) athOk = true;
                if (l.contains("__RTL_SEEDED__")) rtlOk = true;
            }
            if (athOk || rtlOk) {
                GuestExec.logToStore("WiFi firmware seeded — replug the dongle to retry");
                return;
            }
            GuestExec.logToStore("WiFi firmware not on the filesystem — installing firmware packages");
            GuestExec.run("export DEBIAN_FRONTEND=noninteractive; "
                    + "apt-get install -y --no-install-recommends firmware-ath9k-htc firmware-realtek wireless-regdb "
                    + ">/dev/null 2>&1; true");
        } catch (Throwable ignored) {
        }
    }

    private void ensureKernelModules() {
        ensureWifiFirmware();
        try {
            GuestExec.run("mkdir -p /etc/modules-load.d; "
                    + "{ echo loop; echo squashfs; echo overlay; } > /etc/modules-load.d/opxdemon.conf 2>/dev/null; true");
            if (guestHasModules()) {
                GuestExec.run("modprobe loop >/dev/null 2>&1; modprobe squashfs >/dev/null 2>&1; "
                        + "modprobe overlay >/dev/null 2>&1; true");
                return;
            }
            File initrd = RootlessPaths.initrd(app);
            File share = resolveShareDir();
            if (!initrd.exists() || share == null) return;
            File staged = new File(share, ".initrd.img");
            GuestExec.logToStore("guest has no /lib/modules — unpacking kernel modules from the initrd");
            try (InputStream in = new java.io.FileInputStream(initrd);
                 java.io.OutputStream out = new java.io.FileOutputStream(staged)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                out.flush();
            }
            GuestExec.run("command -v cpio >/dev/null 2>&1 || "
                    + "(export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends cpio >/dev/null 2>&1); "
                    + "rm -rf /tmp/opxdemon-ird; mkdir -p /tmp/opxdemon-ird; cd /tmp/opxdemon-ird; "
                    + "(cpio -idm < /sdcard/OPX-Demon/.initrd.img || busybox cpio -idm < /sdcard/OPX-Demon/.initrd.img) >/dev/null 2>&1; "
                    + "if [ -d /tmp/opxdemon-ird/lib/modules ]; then mkdir -p /lib/modules; "
                    + "cp -a /tmp/opxdemon-ird/lib/modules/. /lib/modules/; depmod -a >/dev/null 2>&1; "
                    + "echo __MODULES_DEPLOYED__; fi; rm -rf /tmp/opxdemon-ird");
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            ArrayList<String> res = GuestExec.run(
                    "modprobe loop >/dev/null 2>&1; modprobe squashfs >/dev/null 2>&1; "
                    + "losetup -f >/dev/null 2>&1 && echo __LOOP_OK__ || echo __LOOP_NO__");
            for (String l : res) {
                if (l != null && l.contains("__LOOP_OK__")) {
                    GuestExec.logToStore("loop devices are available in the guest");
                    return;
                }
            }
            GuestExec.logToStore("loop still unavailable after loading modules");
        } catch (Throwable t) {
            Log.w(TAG, "ensureKernelModules: " + t.getMessage());
        }
    }

    private boolean guestHasModules() {
        ArrayList<String> out = GuestExec.run(
                "[ -d \"/lib/modules/$(uname -r)/kernel\" ] && echo __HAS_MODULES__ || echo __NO_MODULES__");
        for (String l : out) {
            if (l != null && l.trim().equals("__HAS_MODULES__")) return true;
        }
        return false;
    }

    private static long dfBlocks(String line) {
        try {
            String[] parts = line.trim().split("\\s+");
            if (parts.length < 2) return -1L;
            for (int i = 1; i < parts.length; i++) {
                try {
                    return Long.parseLong(parts[i]);
                } catch (NumberFormatException ignored) {
                    return -1L;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1L;
    }


    public ArrayList<String> exec(String command) {
        if (!isReady() && !startBlocking(null)) {
            GuestExec.logToStore("VM is not running — start it from the dashboard, then retry");
            return new ArrayList<>();
        }
        return GuestExec.run(command);
    }

    public GuestExec.Session openStream(String command) throws java.io.IOException {
        if (!isReady()) startBlocking(null);
        return GuestExec.openJob(command);
    }


    private static final String CORE_MARKER = "/CORE/PixieWps/pixie.py";
    private static final String CORE_ASSET = "rootless/opxdemon-guest-core.tar";
    /** Name the payload carries inside the 9p share, i.e. /sdcard/OPX-Demon/<this> in the guest. */
    private static final String STAGED_CORE = ".opxdemon-guest-core.tar";

    public synchronized boolean ensureGuestCore() {
        if (!isReady() && !startBlocking(null)) return false;
        ArrayList<String> chk = GuestExec.run("[ -f " + CORE_MARKER + " ] && "
                + "cat " + GuestCore.VERSION_FILE + " 2>/dev/null || echo __NO__");
        for (String l : chk) {
            if (l != null && GuestCore.VERSION.equals(l.trim())) return true;
        }
        return deployGuestCore();
    }

    /**
     * Shell that unpacks the staged payload and reports whether the AGENT specifically survived.
     *
     * The witness matters. Checking only {@link #CORE_MARKER} — a file from the /CORE part of the
     * archive — lets a deploy that produced a zero-byte /usr/local/sbin/opxdemon-agentd report
     * success. systemd then fails that unit with 203/EXEC forever, and because every repair path
     * runs through the agent, the VM never recovers: this is how an app update leaves a working
     * guest permanently stuck at "waiting for the guest agent". Test the file the guest actually
     * has to execute, and test it for content (-s), not just presence.
     */
    private static String unpackAndVerify(String tarPath) {
        return "tar xf " + tarPath + " -C / 2>&1; "
                + "chmod 0755 /usr/local/sbin/opxdemon-ptyd /usr/local/sbin/opxdemon-agentd 2>/dev/null; "
                + "echo __AGENT_BYTES__$(wc -c < /usr/local/sbin/opxdemon-agentd 2>/dev/null || echo 0); "
                + "if [ -s /usr/local/sbin/opxdemon-agentd ] && [ -x /usr/local/sbin/opxdemon-agentd ] "
                + "&& [ -f " + CORE_MARKER + " ]; then echo __DEPLOYED__; else echo __FAIL__; fi";
    }

    /** Copies the payload into the share and makes sure it is on disk before the guest reads it. */
    private java.io.File stageGuestCore(java.io.File shareDir) throws java.io.IOException {
        java.io.File staged = new java.io.File(shareDir, STAGED_CORE);
        try (java.io.InputStream in = app.getAssets().open(CORE_ASSET);
             java.io.FileOutputStream out = new java.io.FileOutputStream(staged)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
            // flush() only empties the Java buffer. The guest reads this file through 9p, so it
            // has to be on disk before tar runs there — otherwise tar sees a short archive and
            // creates the entries it never got the contents for, which is exactly the zero-byte
            // agent that breaks the VM for good.
            out.getFD().sync();
        }
        return staged;
    }

    public boolean deployGuestCore() {
        try {
            java.io.File shareDir = resolveShareDir();
            if (shareDir == null) return false;
            java.io.File staged = stageGuestCore(shareDir);
            ArrayList<String> res = GuestExec.run(
                    unpackAndVerify("/sdcard/OPX-Demon/" + STAGED_CORE));
            for (String l : res) {
                if (l != null && l.trim().startsWith("__AGENT_BYTES__")) {
                    GuestExec.logToStore("guest core deployed, agent is "
                            + l.trim().substring("__AGENT_BYTES__".length()) + " bytes");
                }
            }
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            boolean ok = false;
            for (String l : res) if (l != null && l.trim().equals("__DEPLOYED__")) ok = true;
            if (ok) restartGuestAgent();
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "deployGuestCore failed: " + e.getMessage());
            return false;
        }
    }


    /**
     * Brings the agent up over the serial console, for when it is not up to be talked to.
     *
     * Everything the app does inside the guest goes through opxdemon-agentd on port 1050 —
     * including deployGuestCore(), which installs the agent. That circularity means a guest
     * whose agent never started cannot be fixed through the normal path: the VM boots, the
     * console shows a root prompt, and the app waits for an agent that nothing is going to
     * start. The console is already there and already root, so use it.
     *
     * Handles every way the agent ends up unusable: never unpacked, unpacked as a zero-byte file
     * by a deploy that was verified against the wrong witness (see unpackAndVerify), socat absent
     * so it exits on startup, or simply not running.
     */
    public boolean bootstrapAgentOverConsole() {
        boolean uml = EngineType.isUml(prefs());
        String sock = uml ? null : RootlessPaths.serialSock(app).getAbsolutePath();
        // UML has no QEMU serial unix socket: its console is the kernel process's
        // own stdin/stdout (tty0), with the announced tty1 pty and a TCP
        // 127.0.0.1:1050 forward behind it as fallbacks. GuestConsole.run(null, …)
        // picks the first that answers.
        if (!uml && !new File(sock).exists()) return false;

        GuestExec.logToStore("guest agent unreachable — bootstrapping it over the guest console ("
                + consoleState() + ")");
        // The vector device is vec0 in UML, eth0 under QEMU/slirp.
        String netIf = uml ? "vec0" : "eth0";
        // Fix the address first: the guest-side __OPX_NET__FAIL gate below turns
        // a failed address attempt into a visible diagnosis instead of a silent
        // timeout, and everything after it needs 10.0.2.15 to be reachable.
        configureGuestNetIf(netIf, sock);
        // deployGuestCore() removes the payload after itself, so put a fresh copy in the share
        // before asking the console to unpack it.
        File staged = null;
        if (shareActive && shareInUse != null) {
            try {
                staged = stageGuestCore(shareInUse);
            } catch (Exception e) {
                Log.w(TAG, "staging guest core for console bootstrap failed: " + e.getMessage());
                staged = null;
            }
        }

        StringBuilder cmd = new StringBuilder();
        if (staged != null) {
            // Re-extract unconditionally: the file on disk may exist, be executable, and still be
            // empty, which is the state that produces 203/EXEC.
            cmd.append(unpackAndVerify("/sdcard/OPX-Demon/" + STAGED_CORE)).append("; ");
        }
        cmd.append("command -v socat >/dev/null 2>&1 || (export DEBIAN_FRONTEND=noninteractive; ")
           .append("apt-get install -y --no-install-recommends socat >/dev/null 2>&1); ")
           // An agent listening on 0.0.0.0 with no IP address is unreachable through the slirp
           // hostfwd — the 'listening' check below would then lie about being up. Self-heal the
           // network first: static slirp addresses on eth0, and persist them so the next boot
           // does not start from the same broken state (pre-rootfs.imgz guests only).
           .append("if ! ip -4 addr show dev ").append(netIf).append(" 2>/dev/null | grep -q '10.0.2.15'; then ")
           .append("ip link set ").append(netIf).append(" up 2>/dev/null; ")
           .append("ip addr flush dev ").append(netIf).append(" 2>/dev/null; ")
           .append("ip addr add 10.0.2.15/24 dev ").append(netIf).append(" 2>/dev/null; ")
           .append("ip route add default via 10.0.2.2 dev ").append(netIf).append(" 2>/dev/null; ")
           .append("mkdir -p /etc/network /run/opxdemon; ")
           .append("[ -f /etc/network/interfaces ] || printf 'auto lo\\niface lo inet loopback\\n\\nauto ")
           .append(netIf).append("\\niface ").append(netIf)
           .append(" inet static\\n    address 10.0.2.15\\n    netmask 255.255.255.0\\n    gateway 10.0.2.2\\n' > /etc/network/interfaces; ")
           .append("touch /run/opxdemon/net-ok; ")
           .append("fi; ")
           .append("ip -4 addr show dev ").append(netIf)
           .append(" 2>/dev/null | grep -q '10.0.2.15' || { echo __OPX_NET__FAIL; exit 0; }; ")
           .append("(systemctl restart opxdemon-agent.service >/dev/null 2>&1 ")
           .append("|| (pkill -f opxdemon-agentd >/dev/null 2>&1; ")
           .append("setsid /usr/local/sbin/opxdemon-agentd >/dev/null 2>&1 &)); ")
           .append("sleep 3; ")
           // A guest whose agent answers 1050 is the only proof of 'up': it needs the agent
           // AND the IP both to be true, which is what the app's own ping then exercises.
           .append("ip -4 addr show dev ").append(netIf).append(" 2>/dev/null | grep -q '10.0.2.15' ")
           .append("&& ss -ltn 2>/dev/null | grep -q ':1050' && echo __AGENT_UP__ || echo __AGENT_DOWN__");

        boolean up = false;
        boolean netFailed = false;
        // One attempt is often not enough: DHCP-less first-boot races (address applied before
        // slirp finishes wiring the NIC) and slow apt fallbacks both need a second try.
        for (int attempt = 0; attempt < 2 && !up; attempt++) {
            for (String l : GuestConsole.run(cmd.toString(), sock, 180_000)) {
                if (l == null) continue;
                if (l.contains("__AGENT_UP__")) up = true;
                if (l.contains("__OPX_NET__FAIL")) netFailed = true;
            }
        }
        if (netFailed) {
            GuestExec.logToStore("console bootstrap: the guest could not bring up"
                    + " 10.0.2.15 on " + netIf + " — vec0 addressing failed");
        }
        if (staged != null) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
        if (up) {
            GuestExec.logToStore("guest agent started from the console ("
                    + consoleState() + ") — vec0 now carries 10.0.2.15 and the"
                    + " guest listens on 1050");
            return true;
        }
        // Nothing to lose by naming what is missing: the same console can tell us.
        for (String l : GuestConsole.run(
                "printf 'agentd_bytes=%s socat=%s\\n' "
                + "\"$(wc -c < /usr/local/sbin/opxdemon-agentd 2>/dev/null || echo missing)\" "
                + "\"$(command -v socat >/dev/null 2>&1 && echo yes || echo NO)\"", sock, 20_000)) {
            if (l != null && l.startsWith("agentd_bytes=")) {
                GuestExec.logToStore("console bootstrap failed — guest reports " + l.trim());
            }
        }
        return false;
    }

    /**
     * Applies the static 10.0.2.15/24 address and default route to the guest's
     * network interface over the console. Idempotent, and the safety net for
     * kernels built before CONFIG_IP_PNP: the "ip=" boot parameter is ignored
     * there, so vec0 would otherwise come up address-less and USB/IP could
     * never reach 10.0.2.2.
     */
    private void configureGuestNetIf(String netIf, String consoleSock) {
        StringBuilder c = new StringBuilder();
        c.append("ip link set ").append(netIf).append(" up 2>/dev/null; ");
        c.append("ip -4 addr show dev ").append(netIf).append(" 2>/dev/null | grep -q '10.0.2.15/24' || { ")
         .append("ip addr flush dev ").append(netIf).append(" 2>/dev/null; ")
         .append("ip addr add 10.0.2.15/24 dev ").append(netIf).append(" 2>/dev/null; }; ");
        c.append("ip route show default dev ").append(netIf).append(" 2>/dev/null | grep -q 'via 10.0.2.2' || ")
         .append("ip route add default via 10.0.2.2 dev ").append(netIf).append(" 2>/dev/null; ");
        c.append("echo __NET_CFG_DONE__");
        boolean done = false;
        for (String l : GuestConsole.run(c.toString(), consoleSock, 30_000)) {
            if (l != null && l.contains("__NET_CFG_DONE__")) done = true;
        }
        if (done) {
            GuestExec.logToStore("guest network: " + netIf + " = 10.0.2.15/24 via 10.0.2.2");
        } else {
            // Silently dropping this was a real diagnostic hole: a console that
            // cannot be reached produces exactly this, and the boot then failed
            // for fifteen minutes with nothing on record about the network.
            GuestExec.logToStore("guest network: could not set 10.0.2.15 on " + netIf
                    + " over the console — the guest has no route to the app, so"
                    + " port 1050 will stay unreachable");
            Log.w(TAG, "could not configure " + netIf + " in the guest");
        }
    }

    private void restartGuestAgent() {
        GuestExec.run("(systemctl restart opxdemon-agent.service >/dev/null 2>&1 "
                + "|| (pkill -f opxdemon-agentd >/dev/null 2>&1; "
                + "setsid /usr/local/sbin/opxdemon-agentd >/dev/null 2>&1 &)) &");
        for (int i = 0; i < 20; i++) {
            for (String l : GuestExec.run(
                    "ss -ltn 2>/dev/null | grep -q ':1052' && echo __UP__ || echo __NO__")) {
                if (l != null && l.trim().equals("__UP__")) return;
            }
            try { Thread.sleep(500); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        Log.w(TAG, "guest agent restarted but port 1052 never came up");
    }

    public synchronized boolean ensureUsbWifiAttached() {
        if (!isReady() && !startBlocking(null)) return false;
        if (usb == null) return false;
        int candidates = usb.pickWifiDevices().size();
        int count = usb.attachAllWifiDongles(20_000);
        if (count <= 0) {
            usbDriverOk = false;
            GuestExec.logToStore("USB adapter: no adapter could be passed into the VM");
            return false;
        }
        if (candidates > 1) {
            GuestExec.logToStore("USB adapters: " + count + " of " + candidates + " passed into the VM");
        }
        return awaitGuestWlan(10_000, count);
    }

    public java.util.List<String> guestWifiInterfaces() {
        return guestWlanInterfaces();
    }

    public boolean usbDriverOk() {
        return usbDriverOk;
    }

    private static java.util.List<String> guestWlanInterfaces() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String l : GuestExec.run(
                "iw dev 2>/dev/null | awk '$1==\"Interface\"{print $2}'")) {
            if (l != null && !l.trim().isEmpty()) out.add(l.trim());
        }
        return out;
    }

    private boolean awaitGuestWlan(long timeoutMs, int expected) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        java.util.List<String> ifs = java.util.Collections.emptyList();
        while (true) {
            ifs = guestWlanInterfaces();
            if (ifs.size() >= Math.max(expected, 1)) break;
            if (System.currentTimeMillis() >= deadline) break;
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        if (ifs.isEmpty()) {
            usbDriverOk = false;
            GuestExec.logToStore("USB adapter: DRIVER MISSING — the dongle is attached to the VM but "
                    + "'iw dev' shows no interface after " + (timeoutMs / 1000) + "s. Install the driver "
                    + "or firmware for this chipset from the Terminal, then retry.");
            return false;
        }
        usbDriverOk = true;
        if (ifs.size() < expected) {
            GuestExec.logToStore("USB adapters: only " + ifs.size() + " of " + expected
                    + " bound a driver — guest exposes " + ifs
                    + ". The missing one needs its driver/firmware installed from the Terminal.");
        } else {
            GuestExec.logToStore("USB adapter: driver OK — guest exposes " + ifs);
        }
        return true;
    }

    public UsbBridge usb() { return usb; }
    public QmpClient qmp() { return qmp; }

    public boolean forwardPort(int hostPort, int guestPort) {
        QmpClient c = qmp;
        if (c == null || !c.isConnected()) return false;
        unforwardPort(hostPort);
        return c.hostfwdAdd(NETDEV_ID + " tcp:" + RootlessPaths.HOST_LOOPBACK + ":"
                + hostPort + "-:" + guestPort);
    }

    public boolean unforwardPort(int hostPort) {
        QmpClient c = qmp;
        if (c == null || !c.isConnected()) return false;
        return c.hostfwdRemove(NETDEV_ID + " tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + hostPort);
    }


    public State status() {
        if (!isRunning()) return State.STOPPED;
        if (!booted) return State.BOOTING;
        return System.currentTimeMillis() - lastGuestOk < GUEST_FRESH_MS
                ? State.READY : State.BOOTING;
    }

    public State statusBlocking() {
        if (isRunning()) {
            if (GuestExec.ping(1500)) {
                lastGuestOk = System.currentTimeMillis();
                markBooted();
                return State.READY;
            }
            return State.BOOTING;
        }
        if (GuestExec.ping(1500)) {
            lastGuestOk = System.currentTimeMillis();
            return State.READY;
        }
        return State.STOPPED;
    }

    private void markBooted() {
        synchronized (bootMarkLock) {
            lastGuestOk = System.currentTimeMillis();
            if (booted) return;
            booted = true;
        }
        connectControl();
        qemuExecutor.submit(() -> {
            try {
                maybeResizeFilesystem();
                awaitAgentAfterResize();
                reclaimFreedSpace();
                ensureKernelModules();
            } catch (Throwable t) {
                Log.w(TAG, "post-boot maintenance failed: " + t.getMessage());
            }
        });
    }

    private void awaitAgentAfterResize() {
        if (GuestExec.ping(2000)) return;
        GuestExec.logToStore("resize cycle interrupted the guest agent — waiting for port :1050 to come back");
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            if (GuestExec.ping(2000)) {
                GuestExec.logToStore("guest agent is back after the resize");
                return;
            }
            try { Thread.sleep(2000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        GuestExec.logToStore("guest agent did not return after the resize — restart the VM if tools stay offline");
    }

    public boolean usbAttached() {
        return usb != null && usb.hasAttached();
    }

    public java.util.List<String> tailLog(int maxLines) {
        java.util.List<String> out = new java.util.ArrayList<>();

        // The engine's own diagnostics come first and are NOT subject to the
        // guest-output budget. They are the only lines that say why the boot is
        // where it is, and a plain concatenation into one capped ring threw them
        // away first — which is exactly why the Console pane went blank while
        // the engine had plenty to report.
        java.util.ArrayDeque<String> diag = new java.util.ArrayDeque<>();
        File engineLog = RootlessPaths.engineLog(app);
        if (engineLog.exists() && engineLog.length() > 0) {
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(engineLog)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    diag.addLast(line);
                    // Bounded: a long session appends continuously, and the
                    // newest lines are the ones that explain the current stall.
                    while (diag.size() > 200) diag.removeFirst();
                }
            } catch (Exception ignored) {}
        }

        // The process stdout log (boot.log) holds everything the engine printed
        // before its console was usable; serial.log holds the console tap, which
        // is everything after that — including the VFS/panic line that explains a
        // failed UML boot. Reading only one of them is what made every failure
        // look identical.
        java.util.ArrayDeque<String> ring = new java.util.ArrayDeque<>();
        for (File log : new File[]{RootlessPaths.bootLog(app), RootlessPaths.serialLog(app)}) {
            if (!log.exists() || log.length() == 0) continue;
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(new java.io.FileInputStream(log)))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.trim().isEmpty()) continue;
                    ring.addLast(line);
                    while (ring.size() > maxLines) ring.removeFirst();
                }
            } catch (Exception ignored) {}
        }
        out.addAll(diag);
        out.addAll(ring);
        return out;
    }

    // "Userspace mode: SECCOMP/ptrace" is printed by os_early_checks() before
    // any guest runs; knowing which mode a failing boot used turns the exit-159
    // SIGSYS death from a mystery into a diagnosis (ptrace mode = Android's
    // app filter killed a raw guest syscall).
    private volatile String umlUserspaceMode;

    // One-shot SECCOMP retry state. seccomp=auto probes SECCOMP and falls back
    // to ptrace; when even the ptrace fallback dies with SIGSYS (exit 159),
    // the retry pass boots once with seccomp=off so the failure text is the
    // ptrace diagnosis instead of a bare exit code. Both reset at startBlocking().
    private boolean umlSeccompOff;
    private boolean umlSeccompRetryUsed;

    /** Console tap target: append whatever the engine's console carried to serial.log. */
    private void appendConsoleText(String text) {
        try {
            File log = RootlessPaths.serialLog(app);
            // The tap runs for the whole session and the console echoes every command,
            // so cap the file instead of letting it grow without bound.
            if (log.length() > 512L * 1024L) {
                //noinspection ResultOfMethodCallIgnored
                log.delete();
            }
            noteUserspaceMode(text);
            try (FileWriter fw = new FileWriter(log, true)) {
                fw.write(text);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Remembers which userspace mode the kernel reported ("Userspace mode:
     * SECCOMP|ptrace"). lastLogProblem() turns it into a real diagnosis for the
     * exit-159 SIGSYS death, so it has to be picked up from EVERY path the
     * kernel's output can take: the console tap and the boot log. Reading it
     * from the tap alone stopped working once the primary console became the
     * kernel's own stdout, which is where that line is printed.
     */
    private void noteUserspaceMode(String text) {
        if (text == null || text.indexOf("Userspace mode:") < 0) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("Userspace mode: (SECCOMP|ptrace)").matcher(text);
        if (m.find()) umlUserspaceMode = m.group(1);
    }

    private void connectControl() {
        try {
            if (EngineType.isUml(prefs())) {
                // UML has no QMP and no usb-host: USB passthrough is the USB/IP
                // server, which the guest binds to via usbip attach.
                UmlUsbServer s = new UmlUsbServer(app);
                umlUsbServer = s;
                usb = s;
                s.start();
                return;
            }
            qmp = new QmpClient(RootlessPaths.qmpSock(app).getAbsolutePath());
            if (qmp.connect()) {
                usb = new UsbPassthroughManager(app, qmp);
            } else {
                Log.w(TAG, "QMP connect failed — USB passthrough unavailable");
            }
        } catch (Exception e) {
            Log.w(TAG, "control connect failed: " + e.getMessage());
        }
    }


    /**
     * Boots the UML engine: the linux-uml userspace ELF IS the kernel.
     *
     * Process-shaped command line (no QEMU devices): mem/ubd root + the shared
     * Debian Trixie rootfs + a vector-net tap transport for guest networking +
     * one port: channel for the serial console that backs GuestExec. The stub
     * binary the kernel needs is looked up relative to the kernel executable,
     * which is why it is installed beside linux-uml as stub_exe.
     */
    private String attemptBootUml(BootListener listener) {
        try {
            File kern = RootlessPaths.umlKernel(app);
            File stub = RootlessPaths.umlStub(app);
            if (!kern.isFile()) return "linux-uml is missing";
            if (!stub.isFile()) return "stub_exe is missing";
            File rootfs = RootlessPaths.rootfs(app);
            if (!rootfs.isFile()) return "rootfs.img is missing";
            kern.setExecutable(true, false);
            stub.setExecutable(true, false);

            // Start the rootless network gateway before the kernel connects.
            // Without it the guest has no 10.0.2.2 and `usbip attach` (and any
            // other guest->host traffic) cannot reach the app. Boot continues
            // without it — only USB passthrough needs the network hop.
            boolean netd = startUmlNetd();
            if (!netd) {
                GuestExec.logToStore("uml-netd unavailable — the UML guest will boot "
                        + "without networking; USB passthrough needs uml-netd");
            }

            List<String> a = buildUmlCommand(kern, stub, rootfs, netd);
            Log.i(TAG, "UML: " + join(a));
            ProcessBuilder pb = new ProcessBuilder(a);
            pb.directory(RootlessPaths.base(app));
            // Android has no /dev/shm and no /tmp, and UML's early boot hard-exits
            // with code 1 when it cannot create its memory file: os_early_checks()
            // -> check_tmpexec() -> create_tmp_file() (arch/um/os-Linux/mem.c) takes
            // the tempdir from $TMPDIR — accepting a non-tmpfs directory, it only
            // warns — then open(dir, O_TMPFILE). With no $TMPDIR it falls back to
            // /tmp, which does not exist, open() fails with ENOENT, and ENOENT is
            // not in the "kernel does not support O_TMPFILE, retry with mkstemp"
            // list, so create_tmp_file() calls exit(1) right after the
            // "Warning: tempdir /tmp is not on tmpfs" line — long before the
            // kernel prints "Linux version" or registers its console.
            File tmpDir = umlTempDir();
            pb.environment().put("TMPDIR", tmpDir.getAbsolutePath());
            Log.i(TAG, "UML TMPDIR: " + tmpDir.getAbsolutePath());
            pb.redirectErrorStream(true);
            final Process proc = pb.start();
            qemuProcess = proc;
            umlProcess = true;
            booted = false;
            // Fresh per attempt: a stale "ptrace" captured on a previous boot
            // would mislabel this attempt's exit-159 diagnosis.
            umlUserspaceMode = null;
            // Same for the console channels: this attempt's kernel announces its
            // own /dev/pts node on stdout, and a leftover node or a cached
            // "which channel works" answer from a killed engine must never be
            // trusted for this boot.
            GuestConsole.setUmlPtsDevice(null);
            GuestConsole.resetUmlChannel();

            new Thread(() -> pumpBootLog(proc, listener), "opxdemon-uml-log").start();
            // Everything the kernel prints after it registers its console arrives here, not
            // on stdout — without the tap a failed UML boot has no error text at all.
            GuestConsole.setObserver(this::appendConsoleText);

            long bootStartMs = System.currentTimeMillis();
            long deadline = bootStartMs + BOOT_TIMEOUT_MS;
            int bootstraps = 0;
            long lastProbeNoteMs = 0;
            long lastConsoleNoteMs = 0;
            GuestExec.logToStore("UML kernel started (pid " + safePid(proc)
                    + ") — the guest console is the kernel process's own"
                    + " stdin/stdout (tty0); the app reads and writes it directly");
            while (System.currentTimeMillis() < deadline) {
                if (stopRequested) return "stopped";
                if (!isAlive(proc)) {
                    return "UML exited during boot (code " + safeExit(proc) + "): " + lastLogProblem();
                }
                if (GuestExec.ping(1500) && guestShellReady()) {
                    markBooted();
                    // vec0 addressing: set by ip= on kernels with CONFIG_IP_PNP,
                    // re-applied here for the already-released kernels that ignore it.
                    if (netd) configureGuestNetIf("vec0", null);
                    GuestExec.logToStore("guest agent is answering — the VM is READY");
                    if (listener != null) listener.onBooted();
                    return null;
                }
                long nowMs = System.currentTimeMillis();

                // Progress note, throttled. Without it a stalled boot is silent,
                // which is the single biggest reason these rounds were blind:
                // the pane showed the same last line for fifteen minutes.
                if (nowMs - lastConsoleNoteMs >= 20_000) {
                    lastConsoleNoteMs = nowMs;
                    GuestExec.logToStore("boot +" + ((nowMs - bootStartMs) / 1000)
                            + "s — stage " + bootStageName(VmBootStage.detect(tailLog(120)))
                            + ", console " + consoleState()
                            + ", agent " + (GuestExec.ping(1200) ? "answering" : "silent"));
                }

                // Agent bootstrap over the guest console.
                //
                // NOT gated on VmBootStage any more. That gate required the text
                // "login:" or "root@" to appear in the tail of the boot log — but
                // under UML the console is the kernel's own stdout, the rootfs
                // autologins root with no prompt at all, and the agent's own
                // systemd unit never logs the words the detector looks for. So
                // the stage never reached AGENT, the bootstrap never ran, and
                // the boot burned its whole budget doing nothing. What actually
                // matters is only "a shell exists and the agent is not answering",
                // and the console can report that directly.
                if ((bootstraps == 0 || lastConsoleBootstrapMs + CONSOLE_RETRY_MS <= nowMs)
                        && consoleShellUsable()) {
                    bootstraps++;
                    lastConsoleBootstrapMs = nowMs;
                    note(listener, bootstraps == 1
                            ? "Guest is up but the agent is not answering — starting it"
                            : "Agent still not answering — retrying the console bootstrap ("
                              + bootstraps + ")");
                    GuestExec.logToStore("bootstrapping the agent over the guest console ("
                            + consoleState() + ") — bypassing vec0 and uml-netd");
                    bootstrapAgentOverConsole();
                }

                // Which side of 127.0.0.1:1050 the handshake is stuck on:
                // listener down = uml-netd/forward problem; listener up but
                // silent = guest-side agent or vec0 addressing problem.
                if (nowMs - lastProbeNoteMs >= 15_000) {
                    lastProbeNoteMs = nowMs;
                    boolean listenerUp = netd && tcpListenerUp(RootlessPaths.HOST_EXEC_PORT);
                    GuestExec.logToStore("boot wait: agent probe — 127.0.0.1:"
                            + RootlessPaths.HOST_EXEC_PORT + " listener "
                            + (listenerUp ? "UP" : "DOWN")
                            + (listenerUp ? "" : " (uml-netd forward missing)")
                            + ", guest agent " + (GuestExec.ping(1200) ? "answering" : "silent"));
                }
                sleep(1000);
            }
            lastBootAgentTimeout = bootstraps > 0;
            return "Boot timed out after " + (BOOT_TIMEOUT_MS / 1000) + "s"
                    + (bootstraps > 0 ? " — the guest booted but opxdemon-agentd never came up"
                                      : " — the UML console never produced a guest shell");
        } catch (Exception e) {
            Log.e(TAG, "UML start failed", e);
            return e.getMessage() == null ? e.toString() : e.getMessage();
        }
    }

    /**
     * $TMPDIR for the UML kernel: the directory it builds the guest's whole memory
     * image in (an unlinked temp file, so nothing is left behind on a clean exit).
     * Only the app's own directories qualify on Android — there is no /dev/shm and
     * no /tmp, and an app uid cannot mount a tmpfs of its own. UML prefers tmpfs
     * only so guest memory is not subject to the host's vm.dirty_ratio; a plain
     * directory is accepted with a warning.
     */
    private File umlTempDir() {
        File[] candidates = {
                new File(RootlessPaths.base(app), "tmp"),
                new File(app.getCacheDir(), "uml"),
                app.getFilesDir()
        };
        for (File dir : candidates) {
            if (umlTempUsable(dir)) return dir;
        }
        return candidates[0];
    }

    private boolean umlTempUsable(File dir) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        try {
            File probe = new File(dir, ".uml-tmp-probe");
            try (FileOutputStream out = new FileOutputStream(probe)) {
                out.write(0);
            }
            //noinspection ResultOfMethodCallIgnored
            probe.delete();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private List<String> buildUmlCommand(File kern, File stub, File rootfs, boolean netd) {
        int cpus = VmSpecs.DEFAULT_CPUS;
        int ramMb = VmSpecs.DEFAULT_RAM_MB;
        boolean safe = false;
        try {
            Core prefs = prefs();
            if (prefs != null) {
                cpus = VmSpecs.effectiveCpus(app, prefs);
                ramMb = VmSpecs.effectiveRamMb(app, prefs);
                safe = VmSpecs.safeBoot(prefs);
            }
        } catch (Throwable ignored) {}
        if (safe) {
            // UML is an ordinary process inside the app, so the guest's RAM is the
            // phone's RAM: a profile sized for a desktop-hosted VM can map memory the
            // Android per-app budget does not have, and the kernel dies before the
            // rootfs is ever mounted. Networking is kept, because a booted guest is
            // only useful with vec0/uml-netd.
            ramMb = Math.min(ramMb, 1024);
            cpus = Math.min(cpus, 2);
        }
        String base = RootlessPaths.base(app).getAbsolutePath();

        List<String> a = new ArrayList<>();
        a.add(kern.getAbsolutePath());
        // ubd0 = the shared Debian Trixie rootfs, rw (matches the QEMU -append root=/dev/vda)
        a.add("ubd0=" + rootfs.getAbsolutePath());
        a.add("root=/dev/ubda");
        // The shared rootfs is built with mkfs.ext4, and the UML kernel config
        // carries CONFIG_EXT4_FS=y but not EXT2_FS. Without an explicit
        // rootfstype the kernel guesses, and a wrong guess is a dead mount.
        a.add("rootfstype=ext4");
        a.add("rw");
        // Same console contract as QEMU's ttyAMA0: getty/autologin root, app drives commands.
        a.add("console=tty0");
        a.add("mem=" + ramMb + "M");
        // SMP: the released kernel is CONFIG_SMP=y (NR_CPUS up to 64).
        a.add(String.valueOf(cpus));
        // One port channel: the UML serial console is served over TCP 127.0.0.1:1050 —
        // the exact port GuestExec/GuestConsole already talk to.
        a.add("port=1050");
        if (netd) {
            // vec0 = vector-net device. The BESS transport makes the KERNEL a
            // client of our AF_UNIX SOCK_SEQPACKET socket (uml-netd listens);
            // one seqpacket = one raw Ethernet frame, no header. uml-netd then
            // plays the 10.0.2.2 gateway (ARP/ICMP + TCP relay to 127.0.0.1),
            // which carries `usbip attach -r 10.0.2.2` to the USB/IP server.
            // No CAP_NET_ADMIN, no /dev/net/tun, no VpnService — both endpoints
            // are ordinary app processes.
            a.add("vec0:transport=bess,dst=" + RootlessPaths.umlNetdSock(app).getAbsolutePath());
            // Give vec0 the QEMU/slirp-equivalent addressing. Parsed when CONFIG_IP_PNP
            // is on (do_ipauto in drivers/net/ipv4/devinet.c); on kernels without it
            // the parameter is ignored by the kernel and vec0 is configured from the
            // console instead — either way, keeping it costs nothing and fixes the
            // no-address case the moment the kernel supports it.
            a.add("ip=10.0.2.15::10.0.2.2:255.255.255.0:opxdemon:vec0:off");
            // guestfwd-equivalent: nothing else needed — uml-netd relays
            // guest -> 10.0.2.2:<port> to 127.0.0.1:<same port>.
        } else {
            // Legacy/fallback: tap transport needs /dev/net/tun + CAP_NET_ADMIN
            // which an app uid never has; kept only as a diagnostic path.
            a.add("eth0=tap,,,10.0.2.15");
        }
        a.add("umid=opxdemon-uml");
        // Android refuses to exec the stub from an anonymous memfd (SELinux
        // untrusted_app: "the stub is gone... the host refused to exec it
        // from a memfd" right after /sbin/init starts). The tree carries the
        // stub_exe= boot parameter for exactly this: open the installed
        // stub_exe file instead of memfd_create — see init_stub_exe_fd() in
        // arch/um/os-Linux/skas/process.c. The file is the binary CI pins as
        // uml_stub, installed next to the kernel and marked executable.
        a.add("stub_exe=" + stub.getAbsolutePath());
        // SECCOMP userspace mode is a hard requirement on Android. The zygote
        // installs an unremovable seccomp filter in every app process; in
        // ptrace mode guest syscalls execute as raw host syscalls and the
        // filter KILLs the engine with SIGSYS (exit 159) the moment guest
        // init makes its first disallowed call. In SECCOMP mode the stub
        // traps every guest syscall for the UML kernel to emulate, and the
        // stub's own host syscalls sit inside Android's app allowlist.
        // uml_seccomp_config() parses STRINGS only (strcmp off/auto/on in
        // start_up.c) — a numeric value is rejected before the kernel even
        // boots ("Invalid seccomp option '2'").
        //
        // "auto" (=1) probes SECCOMP and falls back to ptrace when the probe
        // fails: the right default for a consumer app. The kernel-side patch
        // build-tools/uml-android-seccomp.py keeps close_range out of the
        // probe entirely (Android kills that syscall number with SIGSYS
        // instead of answering -ENOSYS — the exit 159 of v1.2.5), and when
        // even so a host kills the boot with SIGSYS, startBlocking() retries
        // once with seccomp=off so the failure text is a real diagnosis.
        a.add(umlSeccompOff ? "seccomp=off" : "seccomp=auto");
        return a;
    }

    /**
     * Spawns uml-netd listening on the BESS socket the kernel will connect to.
     * Idempotent: a stale socket file from a previous killed boot is unlinked
     * by the daemon itself. Returns false when the binary is missing or the
     * process died immediately — boot continues either way.
     */
    private boolean startUmlNetd() {
        try {
            File netd = RootlessPaths.umlNetd(app);
            if (!netd.isFile()) {
                // Engines installed before the BESS gateway existed look complete but
                // have no uml-netd, which silently costs USB passthrough. Pull the
                // one 2.9 MB file instead of sending the user back to the installer.
                QemuInstaller.ensureUmlNetd(app);
            }
            if (!netd.isFile()) return false;
            netd.setExecutable(true, false);
            File sock = RootlessPaths.umlNetdSock(app);
            // Best-effort cleanup of a dead daemon's socket
            try { sock.delete(); } catch (Throwable ignored) {}
            // A daemon from a previous app session can still be alive (Android
            // keeps same-uid children after the app process dies) and still
            // holds the forward listener — the fresh daemon's bind would fail
            // silently and every agent dial would land on a dead guest. The
            // daemon writes its pid next to the socket; retire the old one.
            try {
                File pidFile = new File(sock.getAbsolutePath() + ".pid");
                if (pidFile.isFile()) {
                    // Plain java.io read: java.nio.file.Files is API 26+ and
                    // minSdk is 24. A pidfile is one short line of digits.
                    String txt = "";
                    try (java.io.FileReader fr = new java.io.FileReader(pidFile)) {
                        char[] buf = new char[32];
                        int n = fr.read(buf);
                        if (n > 0) txt = new String(buf, 0, n).trim();
                    }
                    long pid = Long.parseLong(txt);
                    if (pid > 1) {
                        // Same-uid only: Android enforces this, and the signal
                        // is a no-op if the pid was recycled by another process.
                        android.os.Process.sendSignal((int) pid,
                                android.os.Process.SIGNAL_KILL);
                        // Give the kernel a beat to release the listener port.
                        for (int i = 0; i < 20 && tcpListenerUp(RootlessPaths.HOST_EXEC_PORT); i++) {
                            Thread.sleep(100);
                        }
                        GuestExec.logToStore("retired stale uml-netd (pid " + pid + ")");
                    }
                    //noinspection ResultOfMethodCallIgnored
                    pidFile.delete();
                }
            } catch (Throwable ignored) {
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(netd.getAbsolutePath());
            cmd.add("--socket");
            cmd.add(sock.getAbsolutePath());
            // Guest internet: 10.0.2.2 and 127.0.0.0/8 always stay on the device
            // (that is the usbip path); anything else leaves through the
            // daemon's own sockets, which carry the app's INTERNET permission.
            // A SOCKS5 proxy can be forced from SharedPreferences
            // ("opx_demon" / "uml_socks5" = "host:port") for networks that
            // block direct egress.
            String socks = null;
            try {
                socks = app.getSharedPreferences("opx_demon", Context.MODE_PRIVATE)
                        .getString("uml_socks5", null);
            } catch (Throwable ignored) {
            }
            if (socks != null && socks.trim().contains(":")) {
                cmd.add("--socks");
                cmd.add(socks.trim());
            } else {
                cmd.add("--egress");
                cmd.add("direct");
            }
            // Verbose: kernel-connect / ARP / forward-establish lines are the
            // only visibility into the UML agent channel — without them a
            // silent 1050 is undiagnosable from the exported log.
            cmd.add("--verbose");
            // Agent channel inbound forward: QEMU gets 127.0.0.1:1050 ->
            // guest:1050 from slirp hostfwd; UML has no slirp, so uml-netd
            // holds the listener itself and bridges to 10.0.2.15:1050 over
            // the BESS wire. This is what GuestExec/GuestConsole dial on
            // every command.
            cmd.add("--forward");
            cmd.add(RootlessPaths.HOST_EXEC_PORT + ":" + RootlessPaths.GUEST_EXEC_PORT);
            Log.i(TAG, "uml-netd: " + join(cmd));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(RootlessPaths.base(app));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            umlNetdProcess = proc;
            new Thread(() -> {
                try (java.io.BufferedReader br = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        Log.d(TAG, "uml-netd: " + line);
                        // The agent channel's fate is decided inside this daemon:
                        // kernel connect, forward bind, SYN-ACK waits and guest
                        // refusals all print here and nowhere else. Surface them
                        // in the exported session log.
                        if (line.contains("forward") || line.contains("kernel")
                                || line.contains("REFUSED") || line.contains("SYN-ACK")
                                || line.contains("failed") || line.contains("established")
                                || line.contains("watchdog")) {
                            GuestExec.logToStore("uml-netd: "
                                    + line.replaceFirst("^uml-netd: ", ""));
                        }
                    }
                } catch (Throwable ignored) {
                }
            }, "opxdemon-uml-netd-log").start();
            // Give the listener a beat; a daemon that dies on startup (bad ABI,
            // old device) must not leave a half-alive process behind.
            Thread.sleep(150);
            if (!proc.isAlive()) {
                GuestExec.logToStore("uml-netd exited immediately (code "
                        + safeExit(proc) + ") — booting without networking");
                umlNetdProcess = null;
                return false;
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "uml-netd start failed: " + t.getMessage());
            umlNetdProcess = null;
            return false;
        }
    }

    private List<String> buildCommand() {
        int cpus = VmSpecs.DEFAULT_CPUS, ramMb = VmSpecs.DEFAULT_RAM_MB;
        boolean usbEnabled = true, shareEnabled = true, rngEnabled = true, mttcg = true;
        boolean ioThread = true, fastBoot = true;
        String cacheMode = "writeback", aioMode = "threads";
        String cpuModel = "max,sve=off,pmu=off,pauth=off";
        int tbSize = 512;
        Core prefs = null;
        try {
            prefs = new Core(app);
            cpus = VmSpecs.effectiveCpus(app, prefs);
            ramMb = VmSpecs.effectiveRamMb(app, prefs);
            usbEnabled = VmSpecs.usbEnabled(prefs);
            shareEnabled = VmSpecs.shareEnabled(prefs);
            rngEnabled = VmSpecs.rngEnabled(prefs);
            mttcg = VmSpecs.mttcg(prefs);
            cacheMode = VmSpecs.cacheMode(prefs);
            aioMode = VmSpecs.aioMode(prefs);
            tbSize = VmSpecs.tbSizeMb(app, prefs, ramMb);
            cpuModel = VmSpecs.cpuModel(prefs);
            ioThread = VmSpecs.ioThread(prefs);
            fastBoot = VmSpecs.fastBoot(prefs);
        } catch (Throwable ignored) {}

        String base = RootlessPaths.base(app).getAbsolutePath();
        List<String> a = new ArrayList<>();
        a.add(RootlessPaths.qemuBin(app).getAbsolutePath());

        a.add("-nodefaults");
        a.add("-M"); a.add("virt,gic-version=3");

        File kvm = new File("/dev/kvm");
        if (kvm.exists() && kvm.canWrite()) {
            a.add("-cpu"); a.add("host");
            a.add("-accel"); a.add("kvm");
        } else {
            a.add("-cpu"); a.add(cpuModel);
            a.add("-accel"); a.add("tcg,thread=" + (mttcg ? "multi" : "single") + ",tb-size=" + tbSize);
        }
        a.add("-smp"); a.add(cpus + ",sockets=1,cores=" + cpus + ",threads=1");
        a.add("-m");   a.add(String.valueOf(ramMb));

        a.add("-kernel"); a.add(RootlessPaths.kernel(app).getAbsolutePath());
        a.add("-initrd"); a.add(RootlessPaths.initrd(app).getAbsolutePath());
        a.add("-append"); a.add(kernelCmdline(fastBoot));

        a.add("-drive"); a.add("file=" + RootlessPaths.rootfs(app).getAbsolutePath()
                + ",if=none,id=drive0,format=raw,cache=" + cacheMode + ",aio=" + aioMode
                + ",discard=unmap,detect-zeroes=unmap");
        if (ioThread) {
            a.add("-object"); a.add("iothread,id=io0");
            a.add("-device"); a.add("virtio-blk-pci,drive=drive0,iothread=io0");
        } else {
            a.add("-device"); a.add("virtio-blk-pci,drive=drive0");
        }

        a.add("-netdev"); a.add("user,id=net0,ipv6=off"
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_EXEC_PORT
                + "-:" + RootlessPaths.GUEST_EXEC_PORT
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_TERM_PORT
                + "-:" + RootlessPaths.GUEST_TERM_PORT
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_PTY_PORT
                + "-:" + RootlessPaths.GUEST_PTY_PORT
                + ",hostfwd=tcp:" + RootlessPaths.HOST_LOOPBACK + ":" + RootlessPaths.HOST_SSH_PORT
                + "-:" + RootlessPaths.GUEST_SSH_PORT);
        a.add("-device"); a.add("virtio-net-pci,netdev=net0,romfile=");

        if (usbEnabled) {
            a.add("-device"); a.add("qemu-xhci,id=usbhc0,p2=8,p3=8");
        }

        if (rngEnabled) { a.add("-device"); a.add("virtio-rng-pci"); }

        shareInUse = null;
        shareActive = false;
        if (shareEnabled) {
            File share = pickShareDir();
            if (share != null) {
                shareInUse = share;
                shareActive = true;
                a.add("-fsdev"); a.add("local,id=fsdev0,security_model=none,path=" + share.getAbsolutePath());
                a.add("-device"); a.add("virtio-9p-pci,fsdev=fsdev0,mount_tag=opxdemonshare");
            } else {
                Log.w(TAG, "9p share dir unavailable — booting without /sdcard share");
            }
        }
        if (!shareActive) {
            GuestExec.logToStore("VM is booting WITHOUT the /sdcard capture share — handshakes and "
                    + "reports written inside the guest will not be visible to the app");
        }

        a.add("-chardev"); a.add("socket,id=serial0,path=" + RootlessPaths.serialSock(app).getAbsolutePath()
                + ",server=on,wait=off,logfile=" + RootlessPaths.serialLog(app).getAbsolutePath());
        a.add("-serial"); a.add("chardev:serial0");
        a.add("-device"); a.add("virtio-serial-pci");
        a.add("-chardev"); a.add("socket,id=term0,path=" + RootlessPaths.termSock(app).getAbsolutePath()
                + ",server=on,wait=off");
        a.add("-device"); a.add("virtconsole,chardev=term0,name=org.opxdemon.term");

        a.add("-display"); a.add("none");
        a.add("-qmp"); a.add("unix:" + RootlessPaths.qmpSock(app).getAbsolutePath() + ",server,nowait");
        return a;
    }

    private static String kernelCmdline(boolean fastBoot) {
        StringBuilder sb = new StringBuilder("root=/dev/vda rw rootwait rootflags=noatime "
                + "console=ttyAMA0 loglevel=4 net.ifnames=0 mitigations=off opxdemon.rootless=1");
        if (fastBoot) {
            sb.append(" init_on_alloc=0 init_on_free=0 audit=0 nokaslr")
              .append(" rcupdate.rcu_expedited=1 rcupdate.rcu_normal_after_boot=1")
              .append(" cryptomgr.notests random.trust_bootloader=on");
        }
        return sb.toString();
    }


    private void ensureExecutable() {
        try { RootlessPaths.qemuBin(app).setExecutable(true, false); } catch (Exception ignored) {}
        try { RootlessPaths.umlKernel(app).setExecutable(true, false); } catch (Exception ignored) {}
        RootlessPaths.ensureSlirpSoname(app);
    }

    public File resolveShareDir() {
        if (isRunning()) return shareActive ? shareInUse : null;
        return pickShareDir();
    }

    public boolean shareActive() {
        return !isRunning() || shareActive;
    }

    private File pickShareDir() {
        if (hasStorageAccess()) {
            File pub = new File(android.os.Environment.getExternalStorageDirectory(), "OpxDemon");
            if ((pub.isDirectory() || pub.mkdirs()) && pub.canWrite()) {
                return withSubdirs(pub);
            }
        }
        File ext = app.getExternalFilesDir(null);
        if (ext != null) {
            File s = new File(ext, "OpxDemon");
            if (s.isDirectory() || s.mkdirs()) return withSubdirs(s);
        }
        return null;
    }

    private boolean hasStorageAccess() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try { return android.os.Environment.isExternalStorageManager(); }
            catch (Throwable t) { return false; }
        }
        try {
            return app.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) { return false; }
    }

    private static File withSubdirs(File base) {
        //noinspection ResultOfMethodCallIgnored
        new File(base, "hs").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(base, "captured").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(base, "reports").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(base, "wordlists").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(base, "exploits").mkdirs();
        return base;
    }

    /**
     * Copies the tail of the console into the session log. The exported log is
     * the only thing a user can send after a failed boot, and "see the boot log"
     * on its own says nothing — the kernel's actual complaint (missing root
     * device, bad geometry, out of memory) is worth shipping.
     */
    private void dumpConsoleTail() {
        try {
            java.util.List<String> tail = tailLog(30);
            if (tail == null || tail.isEmpty()) return;
            StringBuilder sb = new StringBuilder("---- console tail ----");
            for (String l : tail) {
                if (l != null && !l.trim().isEmpty()) sb.append('\n').append(l);
            }
            GuestExec.logToStore(sb.toString());
        } catch (Throwable ignored) {
        }
    }

    private void pumpBootLog(Process proc, BootListener listener) {
        File log = RootlessPaths.bootLog(app);
        // Fresh per attempt, whichever engine: an append onto the previous
        // attempt's log makes every diagnosis point at stale lines.
        try { //noinspection ResultOfMethodCallIgnored
            log.delete(); } catch (Exception ignored) {}
        if (umlProcess) {
            // The UML kernel process's stdout IS the guest's tty0 console
            // (CONFIG_CON_ZERO_CHAN="fd:0,fd:1"), and it is also the only way to
            // write a command back into the guest. UmlStdio therefore owns the
            // single reader on that stream and hands each line to the boot log —
            // a second reader here would split the stream and lose lines.
            UmlStdio.attach(proc, line -> appendBootLine(line, listener));
            return;
        }
        try (InputStream in = proc.getInputStream();
             BufferedReader br = new BufferedReader(new InputStreamReader(in));
             FileWriter fw = new FileWriter(log, true)) {
            String line;
            while ((line = br.readLine()) != null) {
                fw.write(line); fw.write("\n"); fw.flush();
                if (listener != null) listener.onBootLine(line);
            }
        } catch (Exception ignored) {}
    }

    /** Appends one guest console line to boot.log and hands it to the listener. */
    private void appendBootLine(String line, BootListener listener) {
        if (line == null) return;
        noteUserspaceMode(line);
        try (FileWriter fw = new FileWriter(RootlessPaths.bootLog(app), true)) {
            fw.write(line);
            fw.write("\n");
        } catch (Exception ignored) {}
        if (listener != null) listener.onBootLine(line);
        // UML announces its second console's host-side pty on stdout. That pty is
        // a /dev/pts node created inside this process, so the app (same uid) can
        // read and write it directly. It is tty1, NOT the console systemd writes
        // to (that one is the kernel's own stdin/stdout) — kept only as a
        // fallback channel.
        if (line.contains("assigned device '/dev/pts/")) {
            try {
                int a = line.indexOf("'/dev/pts/");
                int e2 = line.indexOf('\'', a + 1);
                String dev = line.substring(a + 1, e2);
                GuestConsole.setUmlPtsDevice(dev);
                GuestExec.logToStore("uml fallback pty (tty1) announced: " + dev
                        + " — the primary console is the kernel's own stdin/stdout");
            } catch (Exception ignored) {}
        }
    }

    /** True when something on this host accepts TCP on the port (no data exchanged). */
    private static boolean tcpListenerUp(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(RootlessPaths.HOST_LOOPBACK, port), 800);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isAlive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }

    /** Plain-text boot stage name for the engine diagnostics file. */
    private static String bootStageName(int stage) {
        switch (stage) {
            case VmBootStage.KERNEL: return "kernel";
            case VmBootStage.ROOTFS: return "rootfs";
            case VmBootStage.SERVICES: return "services";
            case VmBootStage.AGENT: return "agent";
            case VmBootStage.READY: return "ready";
            default: return "start";
        }
    }

    /**
     * One-line description of the guest console, for the diagnostics the user
     * reads while the VM boots. Names the channel the app would use and whether
     * a shell is answering on it, which is the difference between "waiting for
     * the guest" and "waiting for a console that will never answer".
     */
    private String consoleState() {
        UmlStdio stdio = UmlStdio.current();
        if (stdio != null) {
            return "kernel stdio (tty0)" + (stdio.shellSeen() ? " with a shell" : ", no shell yet");
        }
        String pts = GuestConsole.umlPts();
        if (pts != null) return "fallback pty " + pts + " (tty1)";
        return "not available";
    }

    /**
     * True when there is a guest console worth typing into. A console that has
     * printed nothing at all yet is not a failure — the guest may still be
     * mounting its rootfs — so this only says yes once the console is attached
     * and has produced output, prompt or not: agetty waiting for a username is
     * precisely a case the bootstrap knows how to log into.
     */
    private boolean consoleShellUsable() {
        UmlStdio stdio = UmlStdio.current();
        if (stdio != null) {
            if (stdio.shellSeen()) return true;
            // The kernel prints within a second of start, so "the console said
            // something" is true far too early to act on — the first attempt
            // would be spent on a console with no userspace behind it yet. Wait
            // for the guest's own userspace instead. This is deliberately NOT
            // VmBootStage: it looks for the guest actually being alive, not for
            // the specific wording its agent unit happens to use.
            String tail = stdio.recent(6000).toLowerCase(java.util.Locale.ROOT);
            return tail.contains("systemd") || tail.contains("reached target")
                    || tail.contains("starting ") || tail.contains("login:")
                    || tail.contains("root@") || tail.contains("welcome to");
        }
        if (umlProcess) return GuestConsole.umlPts() != null;
        // QEMU: the serial unix socket exists as soon as QEMU starts, but it only
        // carries a shell once the guest's agetty is on it, so the socket's
        // existence proves nothing. Probing is the only honest test — and it
        // blocks for seconds, hence the throttle: the boot loop asks once a
        // second and must not pay for a probe every time.
        long now = System.currentTimeMillis();
        if (now - lastSerialProbeMs < 15_000) return lastSerialProbeOk;
        lastSerialProbeMs = now;
        lastSerialProbeOk = GuestConsole.probeSerialSocket(
                RootlessPaths.serialSock(app).getAbsolutePath());
        return lastSerialProbeOk;
    }

    /** Throttle for the blocking QEMU serial-socket probe. */
    private long lastSerialProbeMs;
    private boolean lastSerialProbeOk;

    private static String safePid(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            Object v = f.get(p);
            if (v instanceof Number) return String.valueOf(((Number) v).longValue());
        } catch (Throwable ignored) {}
        return "?";
    }

    private static int safeExit(Process p) {
        try { return p.exitValue(); } catch (Exception e) { return -1; }
    }

    private static void destroyForcibly(Process p) {
        try { p.getClass().getMethod("destroyForcibly").invoke(p); }
        catch (Throwable t) { p.destroy(); }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p).append(' ');
        return sb.toString().trim();
    }
}
