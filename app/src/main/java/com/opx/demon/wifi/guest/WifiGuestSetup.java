package com.opx.demon.wifi.guest;

import android.content.Context;
import android.hardware.usb.UsbDevice;

import com.opx.demon.R;
import com.opx.demon.engine.EngineType;
import com.opx.demon.engine.Engines;
import com.opx.demon.engine.GuestEngine;
import com.opx.demon.engine.QemuInstaller;
import com.opx.demon.engine.RootlessService;
import com.opx.demon.engine.UmlProbe;
import com.opx.demon.engine.VmSpecs;
import com.opx.demon.engine.WifiDriverProbe;
import com.opx.demon.engine.WifiEngine;
import com.opx.demon.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WifiGuestSetup {

    public enum Step {
        ADAPTER,
        DEVICE,
        ENGINE,
        FILES,
        BOOT,
        PASSTHROUGH,
        INTERFACE
    }

    public enum State { WAITING, RUNNING, OK, FAILED }

    public interface Listener {
        void onStep(Step step, State state, String detail);

        void onLog(String line);

        void onFinished(boolean ok, String summary, String iface, boolean retry);
    }

    private static final long BOOT_DEADLINE_MS = 240_000;

    private static final long IFACE_WAIT_MS = 40_000;

    private static final long NEEDED_FREE_BYTES = 3L * VmSpecs.GB;

    private final Context app;
    private final Listener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public WifiGuestSetup(Context context, Listener listener) {
        this.app = context.getApplicationContext();
        this.listener = listener;
    }

    public void cancel() {
        cancelled.set(true);
    }

    public void run() {
        Core core = new Core(app);
        try {
            runOrThrow(core);
        } catch (Throwable t) {
            log("setup failed: " + t);
            fail(Step.INTERFACE, s(R.string.wg_unexpected),
                    s(R.string.wg_unexpected_body, String.valueOf(t)), true);
        }
    }

    private void runOrThrow(Core core) {
        step(Step.ADAPTER, s(R.string.wg_looking));
        UsbDevice adapter = WifiDriverProbe.firstAdapter(app);
        if (adapter == null) {
            fail(Step.ADAPTER, s(R.string.wg_no_adapter), s(R.string.wg_no_adapter_body), true);
            return;
        }
        String vidPid = WifiDriverProbe.vidPid(adapter);
        String adapterLabel = WifiDriverProbe.describe(adapter);
        ok(Step.ADAPTER, adapterLabel);
        log("adapter: " + adapterLabel);
        if (cancelled()) return;

        step(Step.DEVICE, s(R.string.wg_checking));
        if (!EngineType.rootlessSupported(app)) {
            fail(Step.DEVICE, s(R.string.wg_not_arm64), s(R.string.wg_not_arm64_body), false);
            return;
        }
        EngineType installed = alreadyInstalled();
        long free = freeBytes();
        if (installed == null && free < NEEDED_FREE_BYTES) {
            fail(Step.DEVICE, s(R.string.wg_low_space, VmSpecs.humanBytes(free)),
                    s(R.string.wg_low_space_body, VmSpecs.humanBytes(NEEDED_FREE_BYTES)), true);
            return;
        }
        ok(Step.DEVICE, installed == null
                ? s(R.string.wg_device_ok, VmSpecs.humanBytes(free))
                : s(R.string.wg_device_ok_short));
        if (cancelled()) return;

        step(Step.ENGINE, s(R.string.wg_choosing));
        EngineType engineType = installed;
        if (engineType != null) {
            log("reusing the engine already installed: " + engineType);
            ok(Step.ENGINE, s(R.string.wg_engine_installed, displayName(engineType)));
        } else {
            step(Step.ENGINE, s(R.string.wg_probing));
            UmlProbe.Result probe = UmlProbe.run(app);
            for (String note : probe.notes) log("  " + note);
            engineType = probe.ruledOut() ? EngineType.ROOTLESS : EngineType.UML;
            log("kernel probe: " + probe.verdict + " — " + probe.detail);
            ok(Step.ENGINE, displayName(engineType));
        }
        if (cancelled()) return;

        WifiEngine.choose(core, engineType);
        final GuestEngine engine = Engines.active(app, engineType);

        step(Step.FILES, s(engine.isInstalled() ? R.string.wg_checking : R.string.wg_downloading));
        if (!engine.isInstalled()) {
            QemuInstaller.Outcome outcome = QemuInstaller.install(app, installProgress(), engineType);
            if (outcome == QemuInstaller.Outcome.OFFLINE) {
                WifiEngine.disarm(core);
                fail(Step.FILES, s(R.string.wg_offline), s(R.string.wg_offline_body), true);
                return;
            }
            if (outcome != QemuInstaller.Outcome.OK) {
                WifiEngine.disarm(core);
                fail(Step.FILES, s(R.string.wg_install_failed), s(R.string.wg_check_log), true);
                return;
            }
        }
        if (!engine.isInstalled()) {
            WifiEngine.disarm(core);
            fail(Step.FILES, s(R.string.wg_missing, join(engine.missing())),
                    s(R.string.wg_missing_body), true);
            return;
        }
        ok(Step.FILES, s(R.string.wg_files_ready));
        if (cancelled()) return;

        step(Step.BOOT, s(R.string.wg_booting));
        if (!bootWithDeadline(engine)) {
            WifiEngine.disarm(core);
            fail(Step.BOOT, s(R.string.wg_boot_failed), shortError(engine), true);
            return;
        }
        ok(Step.BOOT, s(R.string.wg_boot_ok, displayName(engineType)));
        RootlessService.start(app);
        if (cancelled()) return;

        step(Step.PASSTHROUGH, s(R.string.wg_usb_asking));
        if (!engine.ensureUsbWifiAttached()) {
            WifiEngine.disarm(core);
            fail(Step.PASSTHROUGH, s(R.string.wg_usb_failed), s(R.string.wg_usb_failed_body), true);
            return;
        }
        ok(Step.PASSTHROUGH, adapterLabel);
        if (cancelled()) return;

        step(Step.INTERFACE, s(R.string.wg_iface_waiting));
        String iface = waitForInterface(engineType);
        if (iface == null) {
            captureDiagnostics(engineType);
            WifiEngine.disarm(core);
            fail(Step.INTERFACE, s(R.string.wg_iface_failed), s(R.string.wg_iface_failed_body),
                    false);
            return;
        }
        ok(Step.INTERFACE, iface);

        WifiEngine.arm(core, engineType, vidPid, iface);
        core.putString("wlan_wifi", iface);
        core.putString("wlan_scan", iface);
        core.putString("wlan_deauth", iface);
        core.putString("wlan_wps", iface);
        log("armed: Wi-Fi runs in " + displayName(engineType) + " on " + iface);
        finish(true, s(R.string.wg_done_body, displayName(engineType)), iface, false);
    }

    private EngineType alreadyInstalled() {
        try {
            if (Engines.active(app, EngineType.UML).isInstalled()) return EngineType.UML;
            if (Engines.active(app, EngineType.ROOTLESS).isInstalled()) return EngineType.ROOTLESS;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private long freeBytes() {
        try {
            return app.getFilesDir().getUsableSpace();
        } catch (Throwable t) {
            return Long.MAX_VALUE;
        }
    }

    private boolean bootWithDeadline(GuestEngine engine) {
        if (engine.isReady()) return true;
        final AtomicBoolean booted = new AtomicBoolean(false);
        Thread t = new Thread(() -> {
            try {
                booted.set(engine.startBlocking(new GuestEngine.BootListener() {
                    @Override public void onBootLine(String line) { log(line); }
                    @Override public void onBooted() { }
                    @Override public void onFailed(String reason) { log("boot failed: " + reason); }
                }));
            } catch (Throwable e) {
                log("boot threw: " + e);
            }
        }, "wifi-guest-boot");
        t.setDaemon(true);
        t.start();
        try {
            t.join(BOOT_DEADLINE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (t.isAlive()) {
            log("boot did not finish within " + (BOOT_DEADLINE_MS / 1000) + "s");
            return false;
        }
        return booted.get() && engine.isReady();
    }

    private String waitForInterface(EngineType type) {
        Core guestCore = new Core(app).overrideEngine(type);
        long deadline = System.currentTimeMillis() + IFACE_WAIT_MS;
        while (System.currentTimeMillis() < deadline && !cancelled()) {
            for (String[] pair : guestCore.monitorManager.listInterfaces()) {
                String name = pair[0];
                if (name == null || name.isEmpty()) continue;
                if (name.startsWith("wlan") || name.startsWith("wlx") || name.endsWith("mon")) {
                    return name;
                }
            }
            sleep(1500);
        }
        return null;
    }

    private void captureDiagnostics(EngineType type) {
        Core guestCore = new Core(app).overrideEngine(type);
        log("--- guest diagnostics ---");
        try {
            for (String line : guestCore.customChrootCommand(
                    "echo '# lsusb'; lsusb 2>&1; "
                    + "echo '# ip link'; ip -br link 2>&1; "
                    + "echo '# iw dev'; iw dev 2>&1; "
                    + "echo '# dmesg'; dmesg 2>&1 | grep -iE "
                    + "'usb|wlan|firmware|cfg80211|ieee80211|rtl|ath|mt7|88x' | tail -40", true)) {
                log(line);
            }
        } catch (Throwable t) {
            log("diagnostics unavailable: " + t);
        }
    }

    private QemuInstaller.Progress installProgress() {
        return new QemuInstaller.Progress() {
            @Override public void onStage(QemuInstaller.Stage stage) {
                step(Step.FILES, stage.title);
            }
            @Override public void onBytes(String label, long done) {
                step(Step.FILES, label + " · " + VmSpecs.humanBytes(done));
            }
            @Override public void onLog(int level, String message) { log(message); }
        };
    }

    private String s(int res) {
        return app.getString(res);
    }

    private String s(int res, Object arg) {
        return app.getString(res, arg);
    }

    private String displayName(EngineType t) {
        return app.getString(t == EngineType.UML
                ? com.opx.demon.R.string.engine_uml_name
                : com.opx.demon.R.string.engine_vm_name);
    }

    private static String join(List<String> parts) {
        if (parts == null || parts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private String shortError(GuestEngine engine) {
        String why;
        try {
            why = engine.lastError();
        } catch (Throwable t) {
            why = "";
        }
        if (why == null || why.isEmpty()) return s(R.string.wg_check_log);
        return why.length() > 160 ? why.substring(0, 160) : why;
    }

    private boolean cancelled() {
        if (!cancelled.get()) return false;
        try {
            WifiEngine.disarm(new Core(app));
        } catch (Throwable ignored) {
        }
        finish(false, s(R.string.wg_cancelled), "", true);
        return true;
    }

    private void step(Step s, String detail) {
        if (listener != null) listener.onStep(s, State.RUNNING, detail);
    }

    private void ok(Step s, String detail) {
        if (listener != null) listener.onStep(s, State.OK, detail);
    }

    private void fail(Step s, String detail, String summary, boolean retry) {
        if (listener != null) {
            listener.onStep(s, State.FAILED, detail);
            listener.onFinished(false, summary, "", retry);
        }
    }

    private void finish(boolean ok, String summary, String iface, boolean retry) {
        if (listener != null) listener.onFinished(ok, summary, iface, retry);
    }

    private void log(String line) {
        if (listener != null && line != null) listener.onLog(line);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public static List<Step> steps() {
        List<Step> out = new ArrayList<>();
        out.add(Step.ADAPTER);
        out.add(Step.DEVICE);
        out.add(Step.ENGINE);
        out.add(Step.FILES);
        out.add(Step.BOOT);
        out.add(Step.PASSTHROUGH);
        out.add(Step.INTERFACE);
        return out;
    }
}
