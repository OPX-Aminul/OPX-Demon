package com.opx.demon.engine;

import android.content.Context;

import com.opx.demon.BuildConfig;
import com.opx.demon.utils.Core;

import java.util.List;

/**
 * Release fingerprint of the rootless engine files this APK was built to pair
 * with: the app's own versionCode. When the installed app version changes,
 * every device MUST re-verify its engine files against the release manifest
 * before the VM boots — regardless of what the previous release left on disk.
 *
 * This is the rule the v1.2.x rounds kept tripping over: a device with
 * same-size stale binaries booted against them while "Updating please wait"
 * sat on screen doing nothing. The rule is now unconditional:
 *
 *   installed versionCode != ENGINE_RELEASE_FINGERPRINT
 *       → verify EVERY engine file's sha256 against the manifest
 *       → re-download what drifted (usually one or two small files)
 *       → only then boot the VM.
 *
 * Live progress goes through {@link UpdateProgress}; the Dashboard attaches
 * its core-repair card as the listener so the user sees exactly which file
 * is downloading and what percentage it is at.
 */
public final class RootlessGate {

    /** The engine-file release this APK pairs with (the installed versionCode). */
    public static final int ENGINE_RELEASE_FINGERPRINT = BuildConfig.VERSION_CODE;

    private static final String KEY_LAST_VERIFIED = "rootless_engine_verified_release";

    private RootlessGate() {}

    /** Progress sink for the update phase (wired to the Dashboard card). */
    public interface UpdateProgress {
        /** Active file, bytes done, bytes total (0 when unknown), percent 0–100. */
        void onFile(String file, long done, long total, int pct);
        /** One line of update log (stages, verification results). */
        void onMessage(String msg);
        /** Update finished; {@code ok} says whether the engine is verified. */
        void onDone(boolean ok);
    }

    private static volatile UpdateProgress sProgress;

    /** Attach the Dashboard's progress relay; call with null to detach. */
    public static void setProgressListener(UpdateProgress l) { sProgress = l; }

    private static void file(String label, long done, long total) {
        UpdateProgress l = sProgress;
        if (l != null) l.onFile(label, done, total,
                total > 0 ? (int) Math.min(100, done * 100 / total) : 0);
    }

    private static void msg(String m) {
        UpdateProgress l = sProgress;
        if (l != null) l.onMessage(m);
    }

    private static String mb(long b) {
        return String.format(java.util.Locale.US, "%.1f MB", b / 1024.0 / 1024.0);
    }

    /**
     * Blocks until the engine files are verified for THIS app release.
     * Runs on the VM service's boot thread.
     */
    public static boolean awaitEngineReady(Context context) {
        Core core = new Core(context);
        int verified = core.getInt(KEY_LAST_VERIFIED, 0);

        // A Dashboard repair may already be running: it owns the update, let
        // it finish (it boots the VM itself when done).
        if (RootlessCoreFiles.isRepairing()) {
            msg("An engine update is already running…");
            RootlessCoreFiles.awaitRepairIdle(600_000);
            if (RootlessCoreFiles.isRepairing()) return false;
            core.putInt(KEY_LAST_VERIFIED, ENGINE_RELEASE_FINGERPRINT);
            return true;
        }

        // THE RULE: new app release (or nothing verified yet) → re-verify
        // everything against the manifest before any boot. No shortcuts.
        if (verified == ENGINE_RELEASE_FINGERPRINT
                && RootlessCoreFiles.missing(context).isEmpty()) {
            return true;
        }
        msg("App updated — verifying engine files for this release…");

        final boolean[] ok = {false};
        boolean ran = RootlessCoreFiles.runExclusive(() ->
                ok[0] = repairWithProgress(context));
        if (!ran) return false; // another thread took the repair; it finishes it

        if (ok[0]) {
            core.putInt(KEY_LAST_VERIFIED, ENGINE_RELEASE_FINGERPRINT);
            msg("Engine verified for this release — starting the VM");
        } else {
            msg("Engine update failed — tap Download missing to retry");
        }
        return ok[0];
    }

    /**
     * The update itself: the same Gap pipeline the Dashboard button uses.
     * Per-file progress: each Gap's expected size is known up front, so the
     * downloader's byte callbacks are attributed to the active file with an
     * accurate percentage; files whose download never starts keep 0%.
     */
    private static boolean repairWithProgress(Context context) {
        final List<RootlessCoreFiles.Gap> gaps = RootlessCoreFiles.missing(context);
        for (RootlessCoreFiles.Gap g : gaps) {
            if (!g.download) continue;
            long size = g.asset != null ? g.asset.size : 0;
            msg("Needs update: " + g.dest.getName()
                    + (size > 0 ? " (" + mb(size) + ")" : ""));
        }

        // Active download state, filled in from the byte callbacks.
        final String[] activeFile = {null};
        final long[] activeDone = {0};

        QemuInstaller.Progress p = new QemuInstaller.Progress() {
            @Override public void onStage(QemuInstaller.Stage stage) {
                msg(stage.title + "…");
            }
            @Override public void onBytes(String label, long done) {
                // fetchAsset passes the Kind's human label ("UML kernel",
                // "UML netd", "rootfs.img"); match it against both label and
                // fileName so every engine file resolves to real totals.
                long total = 0;
                String display = label;
                for (RootlessCoreFiles.Gap g : gaps) {
                    boolean hit = g.kind.fileName.equals(label) || g.kind.label.equals(label);
                    if (hit && g.asset != null) {
                        total = g.asset.size;
                        display = g.dest.getName();
                        break;
                    }
                }
                if (!display.equals(activeFile[0])) {
                    activeFile[0] = display;
                    activeDone[0] = 0;
                }
                activeDone[0] = Math.max(activeDone[0], done);
                file(display, activeDone[0], total);
            }
            @Override public void onLog(int level, String message) {
                // "X ready (…)" closes that file's progress at 100%.
                for (RootlessCoreFiles.Gap g : gaps) {
                    String fn = g.kind.fileName, lb = g.kind.label;
                    if ((message.startsWith(fn) || message.startsWith(lb))
                            && message.contains("ready") && g.asset != null) {
                        long size = g.asset.size;
                        file(g.dest.getName(), size, size);
                    }
                }
                if (level >= 1) msg(message);
            }
        };
        boolean ok = RootlessCoreFiles.repair(context, p);
        UpdateProgress l = sProgress;
        if (l != null) l.onDone(ok);
        return ok;
    }
}
