package com.opx.demon.engine;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.StatFs;
import android.util.Log;

import com.opx.demon.R;
import com.opx.demon.utils.Core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Asks the device what it can actually run, so the engine switcher can
 * RECOMMEND an engine instead of leaving the user to guess.
 *
 * Ported from strykerapp 6.5. The problem it solves is specific to this app:
 * two in-process VM engines (QEMU rootless and UML rootless) with very
 * different requirements, plus a chroot engine that needs root. A user picking
 * blind lands on the engine their phone cannot run, and the failure only shows
 * up minutes into a boot attempt.
 *
 * So the setup flow runs this once, and it:
 *   1. checks the cheap host facts (ABI, memory, storage, network),
 *   2. checks root, and skips the VM engines entirely when root works,
 *   3. actually LAUNCHES the UML kernel for 12s via {@link UmlProbe} to find
 *      out whether it starts here — the only trustworthy answer, because a
 *      16 KB-page phone refuses a 4 KB-layout binary with no useful message,
 *   4. turns those into an ordered {@link EngineType} plan and a recommended
 *      engine, persisted so the UI can show it later.
 */
public final class DeviceCapabilities {

    private static final String TAG = "DeviceCapabilities";

    public static final String K_DONE = "setup_probe_done";
    public static final String K_RECOMMENDED = "setup_recommended_engine";
    public static final String K_ROOT_OK = "setup_root_granted";
    public static final String K_SUMMARY = "setup_probe_summary";
    public static final String K_SKIPPED = "setup_probe_skipped";
    public static final String K_PLAN = "setup_engine_plan";
    public static final String K_UML_NOTES = "setup_uml_probe_notes";

    /** A hung `su` must not hang the probe; treat no answer as denied. */
    private static final long ROOT_TIMEOUT_MS = 30_000;

    private static final long MIN_FREE_BYTES = 2L * VmSpecs.GB;
    private static final long COMFORTABLE_FREE_BYTES = 6L * VmSpecs.GB;
    private static final long MIN_RAM_BYTES = 1536L * 1024L * 1024L;
    private static final long COMFORTABLE_RAM_BYTES = 3L * VmSpecs.GB;

    private DeviceCapabilities() {
    }

    public enum Outcome {
        OK,
        WARN,
        FAIL,
        SKIPPED
    }

    public enum Check {
        ARCH(R.string.caps_check_arch),
        ROOT(R.string.caps_check_root),
        MEMORY(R.string.caps_check_memory),
        STORAGE(R.string.caps_check_storage),
        NETWORK(R.string.caps_check_network),
        UML(R.string.caps_check_uml),
        QEMU(R.string.caps_check_vm);

        public final int labelRes;

        Check(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    public static final class Finding {
        public final Check check;
        public final Outcome outcome;
        public final String detail;

        public Finding(Check check, Outcome outcome, String detail) {
            this.check = check;
            this.outcome = outcome;
            this.detail = detail == null ? "" : detail;
        }
    }

    /** Progress sink, so the setup slide can show each check as it lands. */
    public interface Listener {
        void onCheckStarted(Check check);

        void onCheckFinished(Finding finding);
    }

    public static final class Report {
        public final List<EngineType> plan;
        public final List<Finding> findings;
        public final boolean rootGranted;
        public final String summary;
        public final List<String> umlNotes;

        Report(List<EngineType> plan, List<Finding> findings, boolean rootGranted,
               String summary, List<String> umlNotes) {
            this.plan = plan;
            this.findings = findings;
            this.rootGranted = rootGranted;
            this.summary = summary == null ? "" : summary;
            this.umlNotes = umlNotes == null ? new ArrayList<String>() : umlNotes;
        }

        /** First engine in the plan that actually passed, or null if none did. */
        public EngineType recommended() {
            return plan.isEmpty() ? null : plan.get(0);
        }
    }

    public static Report probe(final Context app, final Core core, final Listener listener) {
        List<Finding> findings = new ArrayList<>();
        Finding arch = step(listener, findings, Check.ARCH, () -> probeArch());
        Finding memory = step(listener, findings, Check.MEMORY, () -> probeMemory(app));
        Finding storage = step(listener, findings, Check.STORAGE, () -> probeStorage(app));
        Finding network = step(listener, findings, Check.NETWORK, () -> probeNetwork(app));
        Finding root = step(listener, findings, Check.ROOT, () -> probeRoot(core));

        boolean rootGranted = root.outcome == Outcome.OK;
        boolean arm64 = arch.outcome != Outcome.FAIL;

        // Root makes both VM engines pointless — they exist to avoid root.
        if (rootGranted) {
            String why = "not needed · root is available";
            skip(listener, findings, Check.UML, why);
            skip(listener, findings, Check.QEMU, why);
            List<EngineType> plan = new ArrayList<>();
            plan.add(EngineType.CHROOT);
            return new Report(plan, findings, true,
                    summarise(app, plan, true, memory, storage, network), null);
        }

        // The real question: does the UML kernel start on this phone?
        final UmlProbe.Result[] umlProbe = new UmlProbe.Result[1];
        Finding uml = step(listener, findings, Check.UML, () -> {
            if (!arm64) return new Finding(Check.UML, Outcome.FAIL, "needs 64-bit ARM");
            umlProbe[0] = UmlProbe.run(app);
            return readUml(umlProbe[0]);
        });
        step(listener, findings, Check.QEMU, () -> probeQemu(arm64, storage.outcome));

        List<EngineType> plan = planFor(arm64, uml.outcome, storage.outcome);
        return new Report(plan, findings, false,
                summarise(app, plan, false, memory, storage, network),
                umlProbe[0] == null ? null : umlProbe[0].notes);
    }

    /**
     * UML first when its probe did not FAIL — it is the engine that starts
     * fastest and needs no 500 MB download chain. QEMU is kept as the fallback
     * whenever the probe merely failed to prove itself.
     */
    private static List<EngineType> planFor(boolean arm64, Outcome uml, Outcome storage) {
        List<EngineType> plan = new ArrayList<>();
        if (!arm64) return plan;
        if (uml != Outcome.FAIL) plan.add(EngineType.ROOTLESS_UML);
        if (storage != Outcome.FAIL) plan.add(EngineType.ROOTLESS);
        return plan;
    }

    public static void persist(Core core, Report report) {
        if (core == null || report == null) return;
        core.putBoolean(K_DONE, true);
        core.putBoolean(K_SKIPPED, false);
        core.putBoolean(K_ROOT_OK, report.rootGranted);
        EngineType first = report.recommended();
        core.putString(K_RECOMMENDED, first == null ? "" : first.name());
        core.putString(K_PLAN, join(report.plan));
        core.putString(K_SUMMARY, report.summary);
        core.putString(K_UML_NOTES, android.text.TextUtils.join("\n", report.umlNotes));
    }

    /** Keeps the probe's raw notes so the UI can show why a verdict came out. */
    public static void rememberUmlNotes(Core core, UmlProbe.Result probe) {
        if (core == null || probe == null) return;
        core.putString(K_UML_NOTES, android.text.TextUtils.join("\n", probe.notes));
    }

    public static EngineType recommended(Core core) {
        if (core == null) return null;
        return parse(core.getString(K_RECOMMENDED));
    }

    public static List<EngineType> plan(Core core) {
        List<EngineType> out = new ArrayList<>();
        if (core == null) return out;
        String raw = core.getString(K_PLAN);
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split(",")) {
            EngineType t = parse(part.trim());
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    public static List<String> umlNotes(Core core) {
        List<String> out = new ArrayList<>();
        if (core == null) return out;
        String raw = core.getString(K_UML_NOTES);
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (!line.trim().isEmpty()) out.add(line.trim());
        }
        return out;
    }

    public static boolean probed(Core core) {
        return core != null && core.getBoolean(K_DONE);
    }

    public static boolean skipped(Core core) {
        return core != null && core.getBoolean(K_SKIPPED);
    }

    public static String summary(Core core) {
        return core == null ? "" : core.getString(K_SUMMARY);
    }

    private static String join(List<EngineType> plan) {
        StringBuilder sb = new StringBuilder();
        for (EngineType t : plan) {
            if (sb.length() > 0) sb.append(',');
            sb.append(t.name());
        }
        return sb.toString();
    }

    private static EngineType parse(String name) {
        if (name == null || name.isEmpty()) return null;
        for (EngineType t : EngineType.values()) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    private static long hostPageSize() {
        try {
            return android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * ABI alone is not enough to promise a VM: a binary laid out for 4 KB
     * memory pages cannot load on a 16 KB-page device, and the kernel's answer
     * is a bare ENOEXEC. So report the page size here too — it is the first
     * thing to check when UML will not start.
     */
    private static Finding probeArch() {
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null) {
            for (String abi : abis) {
                if ("arm64-v8a".equals(abi)) {
                    long page = hostPageSize();
                    String pages = page > 0 && page != 4096L
                            ? " · " + (page / 1024) + " KB pages" : "";
                    return new Finding(Check.ARCH, Outcome.OK,
                            "arm64-v8a · " + Build.MODEL + pages);
                }
            }
        }
        return new Finding(Check.ARCH, Outcome.FAIL,
                "needs 64-bit ARM · reports "
                        + (abis == null || abis.length == 0 ? "nothing" : abis[0]));
    }

    private static Finding probeMemory(Context app) {
        try {
            ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return new Finding(Check.MEMORY, Outcome.WARN, "could not be read");
            ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(info);
            long total = info.totalMem;
            String text = gb(total) + " total · " + gb(info.availMem) + " free";
            if (total < MIN_RAM_BYTES) {
                return new Finding(Check.MEMORY, Outcome.FAIL, text + " · too little");
            }
            if (total < COMFORTABLE_RAM_BYTES) {
                return new Finding(Check.MEMORY, Outcome.WARN, text + " · the guest will be slow");
            }
            return new Finding(Check.MEMORY, Outcome.OK, text);
        } catch (Throwable t) {
            return new Finding(Check.MEMORY, Outcome.WARN, "could not be read");
        }
    }

    private static Finding probeStorage(Context app) {
        try {
            File dir = app.getFilesDir();
            if (dir == null) return new Finding(Check.STORAGE, Outcome.WARN, "could not be read");
            StatFs fs = new StatFs(dir.getAbsolutePath());
            long free = fs.getAvailableBytes();
            String text = gb(free) + " free";
            if (free < MIN_FREE_BYTES) {
                return new Finding(Check.STORAGE, Outcome.FAIL,
                        text + " · at least " + gb(MIN_FREE_BYTES) + " needed");
            }
            if (free < COMFORTABLE_FREE_BYTES) {
                return new Finding(Check.STORAGE, Outcome.WARN,
                        text + " · the disk cannot grow much");
            }
            return new Finding(Check.STORAGE, Outcome.OK, text);
        } catch (Throwable t) {
            return new Finding(Check.STORAGE, Outcome.WARN, "could not be read");
        }
    }

    private static Finding probeNetwork(Context app) {
        if (QemuInstaller.assetsPresent(app)) {
            return new Finding(Check.NETWORK, Outcome.OK, "not needed · already on disk");
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return new Finding(Check.NETWORK, Outcome.WARN, "could not be read");
            android.net.Network net = cm.getActiveNetwork();
            NetworkCapabilities caps = net == null ? null : cm.getNetworkCapabilities(net);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                return new Finding(Check.NETWORK, Outcome.FAIL, "offline · nothing to download");
            }
            boolean wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
            if (wifi || ethernet) {
                return new Finding(Check.NETWORK, Outcome.OK, wifi ? "Wi-Fi" : "Ethernet");
            }
            return new Finding(Check.NETWORK, Outcome.WARN, "mobile data · large download");
        } catch (Throwable t) {
            return new Finding(Check.NETWORK, Outcome.WARN, "could not be read");
        }
    }

    private static Finding probeRoot(Core core) {
        if (core == null) return new Finding(Check.ROOT, Outcome.WARN, "could not be checked");
        Boolean granted = withDeadline(ROOT_TIMEOUT_MS, () -> {
            java.util.ArrayList<String> out = core.customCommand("id", ROOT_TIMEOUT_MS);
            return Core.contains(out, "uid=0");
        });

        if (granted == null) {
            return new Finding(Check.ROOT, Outcome.WARN,
                    "no answer in " + (ROOT_TIMEOUT_MS / 1000) + "s · treated as denied");
        }
        if (granted) {
            return new Finding(Check.ROOT, Outcome.OK, "granted");
        }
        if (core.suSpawnFailed()) {
            return new Finding(Check.ROOT, Outcome.WARN, "not rooted");
        }
        return new Finding(Check.ROOT, Outcome.WARN, "denied");
    }

    private static Finding readUml(UmlProbe.Result probe) {
        if (probe == null) {
            return new Finding(Check.UML, Outcome.WARN, "could not be tested");
        }
        switch (probe.verdict) {
            case KERNEL_STARTS:
                return new Finding(Check.UML, Outcome.OK, probe.detail);
            case BLOCKED:
                return new Finding(Check.UML, Outcome.FAIL, probe.detail);
            case UNPROVEN:
            default:
                // Not proven is not proven bad: keep UML on the plan, first.
                return new Finding(Check.UML, Outcome.WARN, probe.detail + " · unproven here");
        }
    }

    private static Finding probeQemu(boolean arm64, Outcome storage) {
        if (!arm64) {
            return new Finding(Check.QEMU, Outcome.FAIL, "needs 64-bit ARM");
        }
        if (storage == Outcome.FAIL) {
            return new Finding(Check.QEMU, Outcome.FAIL, "not enough free storage");
        }
        return new Finding(Check.QEMU, Outcome.OK, "available · downloads on demand");
    }

    private interface Probe {
        Finding run();
    }

    private static void skip(Listener listener, List<Finding> into, Check check, String why) {
        Finding finding = new Finding(check, Outcome.SKIPPED, why);
        into.add(finding);
        if (listener != null) listener.onCheckFinished(finding);
    }

    private static Finding step(Listener listener, List<Finding> into, Check check, Probe probe) {
        if (listener != null) listener.onCheckStarted(check);
        Finding finding;
        try {
            finding = probe.run();
        } catch (Throwable t) {
            Log.w(TAG, "probe " + check + " threw", t);
            finding = new Finding(check, Outcome.FAIL, "check failed");
        }
        into.add(finding);
        if (listener != null) listener.onCheckFinished(finding);
        return finding;
    }

    private static <T> T withDeadline(long timeoutMs, Callable<T> work) {
        ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "capability-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<T> future = exec.submit(work);
            try {
                return future.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (Throwable t) {
                future.cancel(true);
                return null;
            }
        } finally {
            exec.shutdownNow();
        }
    }

    private static String summarise(Context app, List<EngineType> plan, boolean rootGranted,
                                    Finding memory, Finding storage, Finding network) {
        if (plan.isEmpty()) return app.getString(R.string.caps_sum_none);

        StringBuilder sb = new StringBuilder();
        if (rootGranted) {
            sb.append(app.getString(R.string.caps_sum_root));
        } else if (plan.get(0) == EngineType.ROOTLESS_UML) {
            sb.append(app.getString(plan.size() > 1
                    ? R.string.caps_sum_uml_fallback : R.string.caps_sum_uml));
        } else {
            sb.append(app.getString(R.string.caps_sum_vm));
        }
        if (network.outcome == Outcome.WARN) sb.append(' ').append(app.getString(R.string.caps_warn_data));
        if (storage.outcome == Outcome.WARN) sb.append(' ').append(app.getString(R.string.caps_warn_storage));
        if (memory.outcome == Outcome.WARN) sb.append(' ').append(app.getString(R.string.caps_warn_memory));
        return sb.toString();
    }

    private static String gb(long bytes) {
        if (bytes <= 0) return "0 GB";
        double g = bytes / 1024.0 / 1024.0 / 1024.0;
        if (g < 1.0) {
            return String.format(Locale.US, "%.0f MB", bytes / 1024.0 / 1024.0);
        }
        return String.format(Locale.US, "%.1f GB", g);
    }
}