package com.zalexdev.stryker.ota;

public final class StrykerEndpoints {

    public static final String GITHUB_REPO = "https://github.com/OPX-Aminul/strykerapp";

    public static final String MANIFEST_URL =
            "https://raw.githubusercontent.com/OPX-Aminul/strykerapp/main/stryker_manifest.json";

    public static final String FALLBACK_CHROOT_64 =
            "https://github.com/zalexdev/strykerapp/releases/download/chroot-main/chroot64-debian.tar.gz";

    private static final String ROOTLESS_BASE =
            "https://github.com/OPX-Aminul/strykerapp/releases/download/all-core-file/";
    public static final String FALLBACK_ROOTLESS_QEMU     = ROOTLESS_BASE + "qemu-system-aarch64";
    public static final String FALLBACK_ROOTLESS_KERNEL   = ROOTLESS_BASE + "Image";
    public static final String FALLBACK_ROOTLESS_LIBSLIRP = ROOTLESS_BASE + "libslirp.so";
    public static final String FALLBACK_ROOTLESS_INITRD   = ROOTLESS_BASE + "initrd.img";
    public static final String FALLBACK_ROOTLESS_ROOTFS   = ROOTLESS_BASE + "rootfs.imgz";

    // IMPORTANT: these must match the qemu-system-aarch64 currently uploaded to the
    // all-core-file release. The Dockerfile pipeline patches the QEMU binary (links
    // libslirp.so), so a core rebuild changes its size/hash — build.yml re-pins
    // stryker_manifest.json after every upload, keep these in sync with it. A stale
    // pin makes every install download the file, fail verification at 100%, and then
    // re-download the same binary forever.
    public static final String FALLBACK_ROOTLESS_QEMU_SHA256 =
            "108ef92bb5bc3ff861c3fbc255d6c1465f0f439b725efa1a017a663ee00b24ba";
    public static final long FALLBACK_ROOTLESS_QEMU_SIZE = 128470352L;

    public static final String FALLBACK_ROOTLESS_KERNEL_SHA256 =
            "cbe59a02e7ea979a150661032440c94e2c4db0b735af2416e11ae5cac15a58e4";
    public static final long FALLBACK_ROOTLESS_KERNEL_SIZE = 37605312L;

    public static final String FALLBACK_ROOTLESS_INITRD_SHA256 =
            "77223e4ad3d4d107f7cd7da41065c8e4fbdfcf662d3923b57d69c879de50bb87";
    public static final long FALLBACK_ROOTLESS_INITRD_SIZE = 38228336L;

    public static final String FALLBACK_ROOTLESS_LIBSLIRP_SHA256 =
            "0ffd8937e252d50a5ded386059856523d083769b7e49160bab41f32fb66376e7";
    public static final long FALLBACK_ROOTLESS_LIBSLIRP_SIZE = 3371272L;

    public static final String FALLBACK_ROOTLESS_ROOTFS_SHA256 =
            "d3ead6368d679e5acc1b55756b773c04b29b225d93d0d0a07ed09a39ca51d255";
    public static final long FALLBACK_ROOTLESS_ROOTFS_SIZE = 379126383L;

    public static final String PREFS = "stryker_ota";

    private StrykerEndpoints() {
    }
}
