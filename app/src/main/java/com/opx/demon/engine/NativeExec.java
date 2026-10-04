package com.opx.demon.engine;

import java.io.File;

/**
 * Pre-flight checks for the engine binaries before we try to exec them.
 *
 * Ported from strykerapp 6.5, where it guards binaries unpacked from the APK's
 * jniLibs. Ours are downloaded into the app's own files directory instead, so
 * the wording differs: the failure this catches here is a half-finished
 * download (zero-length file) or an exec bit that did not stick across an app
 * update, not a missing native library.
 *
 * Without this, a 0-byte or non-executable binary surfaces as a raw
 * IOException from ProcessBuilder.start() ("Cannot run program ... error=13,
 * Permission denied") and the user is told "see the boot log" for a boot that
 * never produced one.
 */
public final class NativeExec {

    private NativeExec() {
    }

    /**
     * @return null when every binary is present, non-empty and executable, or a
     *         plain-language sentence explaining what is wrong.
     */
    public static String check(File... bins) {
        for (File bin : bins) {
            if (bin == null) continue;
            if (!bin.exists()) {
                return bin.getName() + " is missing from " + bin.getParent()
                        + ". Re-run the engine install from Settings to download it again.";
            }
            if (bin.length() == 0) {
                return bin.getName() + " is a 0-byte file — the download did not finish."
                        + " Re-run the engine install from Settings.";
            }
            if (!bin.canExecute()) {
                // Android keeps the execute bit off for files in the app data dir
                // across some updates, and canExecute() is the only honest check.
                try {
                    bin.setExecutable(true, false);
                } catch (Throwable ignored) {
                }
                if (!bin.canExecute()) {
                    return "Android will not execute " + bin.getName() + " at " + bin.getParent()
                            + ". Re-run the engine install from Settings to fix the permissions.";
                }
            }
        }
        return null;
    }

    /**
     * Turns an exec failure into something a user can act on. Android's errmsg
     * is terse ("error=13, Permission denied", "error=8, Exec format error")
     * and says nothing about which of the four distinct causes it was.
     */
    public static String explain(File bin, Throwable t) {
        String raw = t == null || t.getMessage() == null ? "" : t.getMessage();
        String name = bin == null ? "the engine" : bin.getName();

        if (raw.contains("Permission denied")) {
            return name + " could not be started: the system refused the exec (error=13)."
                    + " The file lost its execute bit — re-run the engine install from Settings.";
        }
        if (raw.contains("Exec format error")) {
            return name + " could not be started: this phone's kernel will not load it (error=8)."
                    + " That means either a binary built for the wrong CPU, or one laid out for"
                    + " 4 KB memory pages on a phone that uses 16 KB pages.";
        }
        if (raw.contains("No such file")) {
            return name + " could not be started: the file, or something it needs to load, is"
                    + " not there. Re-run the engine install from Settings.";
        }
        if (raw.contains("Out of memory") || raw.contains("Cannot allocate")) {
            return name + " could not be started: the system refused the memory it asked for."
                    + " Close some apps and try again, or give the guest less RAM in Settings.";
        }
        return name + " could not be started: " + (raw.isEmpty() ? String.valueOf(t) : raw);
    }
}