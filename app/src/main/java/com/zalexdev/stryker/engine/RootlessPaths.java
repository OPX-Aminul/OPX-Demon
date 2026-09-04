package com.zalexdev.stryker.engine;

import android.content.Context;

import java.io.File;

public final class RootlessPaths {

    private RootlessPaths() {}

    public static File base(Context c) {
        return new File(c.getFilesDir(), "rootless");
    }

    public static File qemuBin(Context c)   { return new File(base(c), "qemu-system-aarch64"); }
    public static File libslirp(Context c)  { return new File(base(c), "libslirp.so"); }
    public static File libslirpSo0(Context c) { return new File(base(c), "libslirp.so.0"); }

    /**
     * The all-core-file QEMU binary was linked with a DT_NEEDED of "libslirp.so.0",
     * while the release ships the library as "libslirp.so" (its soname was patched to
     * match the shipped file name). Android's linker only matches the exact needed
     * name when scanning LD_LIBRARY_PATH, so boot/probe attempts die with
     * "CANNOT LINK EXECUTABLE ... library \"libslirp.so.0\" not found".
     *
     * Mirror the verified library under the .so.0 name as well so QEMU can load it.
     * Idempotent and cheap — call it before any QEMU launch.
     */
    public static void ensureLibslirpNames(Context c) {
        try {
            File so = libslirp(c);
            if (!so.exists()) return;
            File so0 = libslirpSo0(c);
            if (so0.exists() && so0.length() == so.length()) return;
            try (java.io.InputStream in = new java.io.FileInputStream(so);
                 java.io.OutputStream out = new java.io.FileOutputStream(so0)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
                out.getFD().sync();
            }
        } catch (Throwable ignored) {
        }
    }

    public static File kernel(Context c)    { return new File(base(c), "Image"); }
    public static File initrd(Context c)    { return new File(base(c), "initrd.img"); }
    public static File rootfs(Context c)    { return new File(base(c), "rootfs.img"); }
    public static File rootfsGz(Context c)  { return new File(base(c), "rootfs.img.gz"); }

    public static File qmpSock(Context c)   { return new File(base(c), "qmp.sock"); }
    public static File serialSock(Context c){ return new File(base(c), "serial.sock"); }
    public static File serialLog(Context c){ return new File(base(c), "serial.log"); }
    public static File termSock(Context c)  { return new File(base(c), "term.sock"); }
    public static File bootLog(Context c)   { return new File(base(c), "boot.log"); }

    public static final int GUEST_EXEC_PORT = 1050;
    public static final int HOST_EXEC_PORT  = 1050;
    public static final String HOST_LOOPBACK = "127.0.0.1";

    public static final int GUEST_TERM_PORT = 1051;
    public static final int HOST_TERM_PORT  = 1051;

    public static final int GUEST_PTY_PORT = 1052;
    public static final int HOST_PTY_PORT  = 1052;

    public static final int GUEST_SSH_PORT = 22;
    public static final int HOST_SSH_PORT  = 2222;

    public static File activeFlag(Context c) {
        return new File(base(c), ".active");
    }
    public static final String ACTIVE_FLAG_PATH =
            "/data/data/com.zalexdev.stryker/files/rootless/.active";
}
