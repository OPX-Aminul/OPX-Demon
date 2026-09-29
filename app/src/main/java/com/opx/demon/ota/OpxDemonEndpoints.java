package com.opx.demon.ota;

public final class OpxDemonEndpoints {

    public static final String GITHUB_REPO = "https://github.com/OPX-Aminul/OPX-Demon";
    public static final String SITE_URL = "https://opaminulff.vercel.app/";
    public static final String GITHUB_RELEASES_URL =
            "https://api.github.com/repos/OPX-Aminul/OPX-Demon/releases?per_page=30";

    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/OPX-Aminul/OPX-Demon/main/opx_manifest.json";

    public static final String FALLBACK_CHROOT_64 =
            "https://github.com/OPX-Aminul/OPX-Demon/releases/download/chroot-main/chroot64-debian.tar.gz";

    private static final String ROOTLESS_BASE =
            "https://github.com/OPX-Aminul/OPX-Demon/releases/download/all-core-file/";
    public static final String FALLBACK_ROOTLESS_QEMU     = ROOTLESS_BASE + "qemu-system-aarch64";
    public static final String FALLBACK_ROOTLESS_KERNEL   = ROOTLESS_BASE + "Image";
    public static final String FALLBACK_ROOTLESS_LIBSLIRP = ROOTLESS_BASE + "libslirp.so";
    public static final String FALLBACK_ROOTLESS_INITRD   = ROOTLESS_BASE + "initrd.img";
    public static final String FALLBACK_ROOTLESS_ROOTFS   = ROOTLESS_BASE + "rootfs.imgz";

    /** UML engine core files live in their own release (uml-mode-all-file). */
    private static final String UML_BASE =
            "https://github.com/OPX-Aminul/OPX-Demon/releases/download/uml-mode-all-file/";
    public static final String FALLBACK_UML_KERNEL = UML_BASE + "linux-uml";
    public static final String FALLBACK_UML_STUB   = UML_BASE + "stub_exe";
    /** Rootless UML network gateway (BESS vector transport) — guest vec0 -> host TCP relay. */
    public static final String FALLBACK_UML_NETD   = UML_BASE + "uml-netd";

    // IMPORTANT: these must match the binaries currently uploaded to the
    // all-core-file release (kept in sync with opx_manifest.json, 2026-09-29). The Dockerfile pipeline patches the QEMU binary (links
    // libslirp.so), so a core rebuild changes its size/hash — build.yml re-pins
    // opx_manifest.json after every upload, keep these in sync with it. A stale
    // pin makes every install download the file, fail verification at 100%, and then
    // re-download the same binary forever.
    public static final String FALLBACK_ROOTLESS_QEMU_SHA256 =
            "74add153c4c8c3096618fc57d68b1b94a93b4f8b7421cb232b48315845a3349b";
    public static final long FALLBACK_ROOTLESS_QEMU_SIZE = 128470352L;

    public static final String FALLBACK_ROOTLESS_KERNEL_SHA256 =
            "0ffa0e1040ab1a0fc59ed6746848c175b67ac3531da4e3d0700360f5b8afb851";
    public static final long FALLBACK_ROOTLESS_KERNEL_SIZE = 48517632L;

    public static final String FALLBACK_ROOTLESS_INITRD_SHA256 =
            "d874296ac2df0569d1f9bda85364ade9c54f774f95162d31192596d9b7358df9";
    public static final long FALLBACK_ROOTLESS_INITRD_SIZE = 39101407L;

    public static final String FALLBACK_ROOTLESS_LIBSLIRP_SHA256 =
            "0ffd8937e252d50a5ded386059856523d083769b7e49160bab41f32fb66376e7";
    public static final long FALLBACK_ROOTLESS_LIBSLIRP_SIZE = 3371272L;

    public static final String FALLBACK_ROOTLESS_ROOTFS_SHA256 =
            "3ff3b0d7a8c44990ef741f9bdda561c3953c294650dd1b12cea95ffa771d9b90";
    public static final long FALLBACK_ROOTLESS_ROOTFS_SIZE = 366784674L;

    public static final String PREFS = "opxdemon_ota";

    private OpxDemonEndpoints() {
    }
}
