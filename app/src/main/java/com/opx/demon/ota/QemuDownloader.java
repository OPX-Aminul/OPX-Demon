package com.opx.demon.ota;

import android.content.Context;

public final class QemuDownloader {

    private QemuDownloader() {}

    public static final class Bundle {
        public final RemoteManifest.Asset qemu;
        public final RemoteManifest.Asset kernel;
        public final RemoteManifest.Asset initrd;
        public final RemoteManifest.Asset libslirp;
        public final RemoteManifest.Asset rootfs;
        /** UML engine binaries; null when the manifest carries no rootless_v2 block. */
        public final RemoteManifest.Asset umlKernel;
        public final RemoteManifest.Asset umlStub;
        /** Optional BESS network gateway (uml-netd); null on manifests predating it. */
        public final RemoteManifest.Asset umlNetd;

        Bundle(RemoteManifest.Asset qemu, RemoteManifest.Asset kernel, RemoteManifest.Asset initrd,
               RemoteManifest.Asset libslirp, RemoteManifest.Asset rootfs) {
            this(qemu, kernel, initrd, libslirp, rootfs, null, null, null);
        }

        Bundle(RemoteManifest.Asset qemu, RemoteManifest.Asset kernel, RemoteManifest.Asset initrd,
               RemoteManifest.Asset libslirp, RemoteManifest.Asset rootfs,
               RemoteManifest.Asset umlKernel, RemoteManifest.Asset umlStub) {
            this(qemu, kernel, initrd, libslirp, rootfs, umlKernel, umlStub, null);
        }

        Bundle(RemoteManifest.Asset qemu, RemoteManifest.Asset kernel, RemoteManifest.Asset initrd,
               RemoteManifest.Asset libslirp, RemoteManifest.Asset rootfs,
               RemoteManifest.Asset umlKernel, RemoteManifest.Asset umlStub,
               RemoteManifest.Asset umlNetd) {
            this.qemu = qemu;
            this.kernel = kernel;
            this.initrd = initrd;
            this.libslirp = libslirp;
            this.rootfs = rootfs;
            this.umlKernel = umlKernel;
            this.umlStub = umlStub;
            this.umlNetd = umlNetd;
        }

        public boolean hasUml() {
            return umlKernel != null && umlKernel.isUsable()
                    && umlStub != null && umlStub.isUsable();
        }
    }

    public static Bundle resolve(Context context) {
        RemoteManifest manifest = ManifestService.fetch(context);
        if (manifest != null && manifest.rootless != null && manifest.rootless.isComplete()) {
            RemoteManifest.RootlessAssets r = manifest.rootless;
            return new Bundle(r.qemu, r.kernel, r.initrd, r.libslirp, r.rootfs,
                    r.umlKernel, r.umlStub, r.umlNetd);
        }
        Bundle fallback = new Bundle(
                new RemoteManifest.Asset(
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_QEMU,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_QEMU_SHA256,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_QEMU_SIZE),
                new RemoteManifest.Asset(
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_KERNEL,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_KERNEL_SHA256,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_KERNEL_SIZE),
                new RemoteManifest.Asset(
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_INITRD,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_INITRD_SHA256,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_INITRD_SIZE),
                new RemoteManifest.Asset(
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_LIBSLIRP,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_LIBSLIRP_SHA256,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_LIBSLIRP_SIZE),
                new RemoteManifest.Asset(
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_ROOTFS,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_ROOTFS_SHA256,
                        OpxDemonEndpoints.FALLBACK_ROOTLESS_ROOTFS_SIZE));
        // UML fallbacks: the pinned uml-mode-all-file release files. Sizes are unknown
        // (0) so the downloader streams without a length gate; hashes are empty so
        // downloads are accepted — the manifest pins them once a release lands.
        return new Bundle(fallback.qemu, fallback.kernel, fallback.initrd,
                fallback.libslirp, fallback.rootfs,
                new RemoteManifest.Asset(OpxDemonEndpoints.FALLBACK_UML_KERNEL, "", 0),
                new RemoteManifest.Asset(OpxDemonEndpoints.FALLBACK_UML_STUB, "", 0),
                new RemoteManifest.Asset(OpxDemonEndpoints.FALLBACK_UML_NETD, "", 0));
    }
}
