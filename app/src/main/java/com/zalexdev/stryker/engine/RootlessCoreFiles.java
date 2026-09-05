package com.zalexdev.stryker.engine;

import android.content.Context;

import com.zalexdev.stryker.ota.QemuDownloader;
import com.zalexdev.stryker.ota.RemoteManifest;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RootlessCoreFiles {

    private static final long ROOTFS_MIN_BYTES = 50L * 1024 * 1024;

    public enum Kind {
        QEMU("qemu-system-aarch64", "QEMU", QemuInstaller.Stage.EXTRACTING_QEMU),
        KERNEL("Image", "kernel", QemuInstaller.Stage.EXTRACTING_KERNEL),
        INITRD("initrd.img", "initrd", QemuInstaller.Stage.EXTRACTING_KERNEL),
        LIBSLIRP("libslirp.so", "libslirp.so", QemuInstaller.Stage.EXTRACTING_LIBS),
        LIBSLIRP_SONAME("libslirp.so.0", "libslirp.so.0", QemuInstaller.Stage.EXTRACTING_LIBS),
        ROOTFS("rootfs.img", "rootfs.img", QemuInstaller.Stage.DECOMPRESSING_ROOTFS);

        public final String fileName;
        public final String label;
        public final QemuInstaller.Stage stage;

        Kind(String fileName, String label, QemuInstaller.Stage stage) {
            this.fileName = fileName;
            this.label = label;
            this.stage = stage;
        }
    }

    public static final class Gap {
        public final Kind kind;
        public final File dest;
        public final RemoteManifest.Asset asset;
        public final String reason;
        public final boolean download;
        public final boolean compressedRootfs;

        Gap(Kind kind, File dest, RemoteManifest.Asset asset, String reason,
            boolean download, boolean compressedRootfs) {
            this.kind = kind;
            this.dest = dest;
            this.asset = asset;
            this.reason = reason;
            this.download = download;
            this.compressedRootfs = compressedRootfs;
        }
    }

    private RootlessCoreFiles() {}

    public static boolean anyPresent(Context c) {
        return ready(RootlessPaths.qemuBin(c), 1)
                || ready(RootlessPaths.kernel(c), 1)
                || ready(RootlessPaths.initrd(c), 1)
                || ready(RootlessPaths.libslirp(c), 1)
                || ready(RootlessPaths.rootfs(c), ROOTFS_MIN_BYTES);
    }

    public static List<Gap> missing(Context c) {
        return missing(c, null);
    }

    private static List<Gap> missing(Context c, QemuDownloader.Bundle b) {
        List<Gap> out = new ArrayList<>();
        addIfNeeded(out, Kind.QEMU, RootlessPaths.qemuBin(c), asset(b, Kind.QEMU), false);
        addIfNeeded(out, Kind.KERNEL, RootlessPaths.kernel(c), asset(b, Kind.KERNEL), false);
        addIfNeeded(out, Kind.INITRD, RootlessPaths.initrd(c), asset(b, Kind.INITRD), false);
        addIfNeeded(out, Kind.LIBSLIRP, RootlessPaths.libslirp(c), asset(b, Kind.LIBSLIRP), false);
        if (ready(RootlessPaths.libslirp(c), 1)
                && !RootlessPaths.libslirpSoname(c).exists()) {
            out.add(new Gap(Kind.LIBSLIRP_SONAME, RootlessPaths.libslirpSoname(c),
                    asset(b, Kind.LIBSLIRP),
                    "QEMU loads libslirp.so.0 (SONAME), not libslirp.so", false, false));
        }
        addIfNeeded(out, Kind.ROOTFS, RootlessPaths.rootfs(c), asset(b, Kind.ROOTFS), true);
        return out;
    }

    private static RemoteManifest.Asset asset(QemuDownloader.Bundle b, Kind kind) {
        if (b == null) return null;
        switch (kind) {
            case QEMU: return b.qemu;
            case KERNEL: return b.kernel;
            case INITRD: return b.initrd;
            case LIBSLIRP:
            case LIBSLIRP_SONAME: return b.libslirp;
            case ROOTFS: return b.rootfs;
            default: return null;
        }
    }

    public static String summarize(List<Gap> gaps) {
        if (gaps == null || gaps.isEmpty()) return "";
        StringBuilder names = new StringBuilder();
        StringBuilder why = new StringBuilder();
        StringBuilder dl = new StringBuilder();
        long bytes = 0;
        for (Gap g : gaps) {
            if (names.length() > 0) names.append(", ");
            names.append(g.dest.getName());
            if (why.length() > 0) why.append('\n');
            why.append(g.dest.getName()).append(" — ").append(g.reason);
            if (g.download) {
                if (dl.length() > 0) dl.append(", ");
                dl.append(g.kind.fileName);
                if (g.asset != null && g.asset.size > 0) {
                    dl.append(" (").append(mb(g.asset.size)).append(")");
                    bytes += g.asset.size;
                }
            }
        }
        StringBuilder body = new StringBuilder();
        body.append("Missing: ").append(names).append('\n').append(why);
        if (dl.length() > 0) {
            body.append("\nWill download: ").append(dl);
            if (bytes > 0) body.append("\nTotal: ").append(mb(bytes));
        } else {
            body.append("\nNo re-download — linking or repairing local files only.");
        }
        return body.toString();
    }

    public static long downloadBytes(List<Gap> gaps) {
        long n = 0;
        if (gaps == null) return 0;
        for (Gap g : gaps) {
            if (g.download && g.asset != null && g.asset.size > 0) n += g.asset.size;
        }
        return n;
    }

    public static boolean repair(Context context, QemuInstaller.Progress p) {
        File base = RootlessPaths.base(context);
        if (!base.exists() && !base.mkdirs()) {
            QemuInstaller.emit(p, 3, "Cannot create " + base.getAbsolutePath());
            return false;
        }
        List<Gap> gaps = missing(context, QemuDownloader.resolve(context));
        if (gaps.isEmpty()) {
            RootlessPaths.ensureSlirpSoname(context);
            QemuInstaller.emitStage(p, QemuInstaller.Stage.DONE);
            return RootlessEngine.get(context).isInstalled();
        }
        QemuInstaller.emit(p, 1, "Repairing " + gaps.size()
                + " missing core file(s) — leaving intact files alone");
        QemuInstaller.emitStage(p, QemuInstaller.Stage.PREPARING);
        for (Gap g : gaps) {
            QemuInstaller.emitStage(p, g.kind.stage);
            if (g.kind == Kind.LIBSLIRP_SONAME) {
                if (!RootlessPaths.ensureSlirpSoname(context)) {
                    QemuInstaller.emit(p, 3, "Could not create libslirp.so.0");
                    return false;
                }
                QemuInstaller.emit(p, 2, "Linked libslirp.so -> libslirp.so.0");
                continue;
            }
            if (!g.download) continue;
            if (g.kind == Kind.ROOTFS && g.compressedRootfs) {
                File archive = new File(base, "rootfs.download");
                if (!QemuInstaller.fetchAsset(g.asset, archive, "rootfs", p)) return false;
                QemuInstaller.emit(p, 1, "Decompressing rootfs (this can take a minute)");
                if (!QemuInstaller.gunzipFetched(archive, RootlessPaths.rootfs(context), p)) {
                    //noinspection ResultOfMethodCallIgnored
                    archive.delete();
                    return false;
                }
                //noinspection ResultOfMethodCallIgnored
                archive.delete();
                continue;
            }
            if (!QemuInstaller.fetchAsset(g.asset, g.dest, g.kind.label, p)) return false;
            if (g.kind == Kind.QEMU) {
                //noinspection ResultOfMethodCallIgnored
                g.dest.setExecutable(true, false);
            }
            if (g.kind == Kind.LIBSLIRP && !RootlessPaths.ensureSlirpSoname(context)) {
                QemuInstaller.emit(p, 3, "libslirp.so downloaded but libslirp.so.0 could not be created");
                return false;
            }
        }
        QemuInstaller.emitStage(p, QemuInstaller.Stage.FINALIZING);
        QemuInstaller.growDisk(context, p);
        RootlessPaths.ensureSlirpSoname(context);
        boolean ok = RootlessEngine.get(context).isInstalled();
        if (ok) {
            QemuInstaller.emitStage(p, QemuInstaller.Stage.DONE);
            QemuInstaller.emit(p, 2, "Missing core files repaired");
        } else {
            QemuInstaller.emit(p, 3, "Repair finished but the engine is still incomplete");
        }
        return ok;
    }

    private static void addIfNeeded(List<Gap> out, Kind kind, File dest,
                                    RemoteManifest.Asset asset, boolean rootfs) {
        long min = rootfs ? ROOTFS_MIN_BYTES : 1;
        long expected = (!rootfs && asset != null && asset.size > 0) ? asset.size : 0;
        if (dest.isFile() && dest.length() >= min) {
            if (expected > 0 && dest.length() != expected) {
                out.add(new Gap(kind, dest, asset,
                        "Size mismatch (" + dest.length() + " vs " + expected + " bytes)",
                        true, compressed(asset)));
            }
            return;
        }
        String reason;
        if (!dest.exists()) {
            reason = "Not on disk — required to boot the rootless VM";
        } else if (dest.length() == 0) {
            reason = "Empty file — previous download did not finish";
        } else {
            reason = "Too small (" + dest.length() + " bytes) — not a usable image";
        }
        out.add(new Gap(kind, dest, asset, reason, true, compressed(asset)));
    }

    private static boolean compressed(RemoteManifest.Asset asset) {
        return asset != null && asset.url != null
                && (asset.url.endsWith(".imgz") || asset.url.endsWith(".gz"));
    }

    private static boolean ready(File f, long min) {
        return f != null && f.isFile() && f.length() >= min;
    }

    static String mb(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0);
    }
}
