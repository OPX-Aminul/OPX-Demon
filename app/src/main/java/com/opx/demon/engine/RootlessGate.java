package com.opx.demon.engine;

import android.content.Context;

/**
 * Boot gate for the rootless engine.
 *
 * MainActivity fires RootlessService.start() the moment the app opens, and
 * the Dashboard's auto-repair scans/repairs stale engine files on its own
 * thread — the two race, and the VM would boot with the stale binaries while
 * the repair is still downloading the new ones (v1.2.8 → v1.2.9: a stale
 * uml-netd died on the new --forward flag exactly this way). The service now
 * waits here until the engine files actually match the release manifest, so
 * "boot" and "update" can never run at the same time; the Dashboard's repair
 * card keeps its own UX and boots the VM when the repair finishes.
 */
public final class RootlessGate {

    private RootlessGate() {}

    /**
     * Blocks until the installed engine files match the manifest (or the
     * repair path proves hopeless), then returns true when booting is safe.
     * Runs on the VM service's boot thread — the few seconds of hashing are
     * cheaper than one boot attempt with a stale engine.
     */
    public static boolean awaitEngineReady(Context context) {
        // The Dashboard's repair thread may already be running: let it win
        // (its completion path boots the VM itself), up to the repair cap.
        RootlessCoreFiles.awaitRepairIdle(600_000);
        if (RootlessCoreFiles.isRepairing()) return false;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (RootlessCoreFiles.missing(context).isEmpty()) return true;
            // Stale or missing engine files: repair them here (the exact same
            // pipeline the Dashboard button uses) before the VM can start.
            if (!QemuInstaller.repair(context, null)) return false;
        }
        return false;
    }


}
