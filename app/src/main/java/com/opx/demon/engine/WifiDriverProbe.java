package com.opx.demon.engine;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import com.opx.demon.utils.Core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WifiDriverProbe {

    public enum Verdict {
        NO_ADAPTER,
        DRIVEN,
        NOT_DRIVEN,
        UNKNOWN
    }

    public static final class Result {
        public final Verdict verdict;
        public final String vidPid;
        public final String label;
        public final String iface;

        Result(Verdict verdict, String vidPid, String label, String iface) {
            this.verdict = verdict;
            this.vidPid = vidPid == null ? "" : vidPid;
            this.label = label == null ? "" : label;
            this.iface = iface == null ? "" : iface;
        }

        public boolean needsGuest() {
            return verdict == Verdict.NOT_DRIVEN;
        }
    }

    private WifiDriverProbe() {}

    public static Result probe(Context context, Core core) {
        List<UsbDevice> adapters = adapters(context);
        if (adapters.isEmpty()) {
            return new Result(Verdict.NO_ADAPTER, "", "", "");
        }

        Result fallback = null;
        for (UsbDevice adapter : adapters) {
            String vidPid = vidPid(adapter);
            String label = UsbHostAccess.describe(adapter);
            String iface = ifaceFromSysfs(core, adapter);
            if (iface == null) {
                if (fallback == null) fallback = new Result(Verdict.UNKNOWN, vidPid, label, "");
                continue;
            }
            if (iface.isEmpty()) {
                return new Result(Verdict.NOT_DRIVEN, vidPid, label, "");
            }
            if (fallback == null || fallback.verdict != Verdict.DRIVEN) {
                fallback = new Result(Verdict.DRIVEN, vidPid, label, iface);
            }
        }
        if (fallback != null && fallback.verdict == Verdict.DRIVEN) return fallback;

        UsbDevice first = adapters.get(0);
        String vidPid = vidPid(first);
        String label = UsbHostAccess.describe(first);
        List<String> external = externalInterfaces(core);
        if (!external.isEmpty()) {
            return new Result(Verdict.DRIVEN, vidPid, label, external.get(0));
        }
        return new Result(core.checkRoot() ? Verdict.NOT_DRIVEN : Verdict.UNKNOWN,
                vidPid, label, "");
    }

    public static List<UsbDevice> adapters(Context context) {
        List<UsbDevice> out = new ArrayList<>();
        try {
            UsbManager m = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (m == null) return out;
            List<UsbDevice> all = new ArrayList<>(m.getDeviceList().values());
            java.util.Collections.sort(all,
                    (a, b) -> Integer.compare(a.getDeviceId(), b.getDeviceId()));
            for (UsbDevice d : all) {
                if (UsbHostAccess.isWifiCandidate(d)) out.add(d);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    public static String vidPid(UsbDevice d) {
        return d == null ? "" : String.format(Locale.US, "%04x:%04x",
                d.getVendorId(), d.getProductId());
    }

    public static String describe(UsbDevice d) {
        return UsbHostAccess.describe(d);
    }

    public static UsbDevice firstAdapter(Context context) {
        List<UsbDevice> all = adapters(context);
        return all.isEmpty() ? null : all.get(0);
    }

    private static String ifaceFromSysfs(Core core, UsbDevice device) {
        String vid = String.format(Locale.US, "%04x", device.getVendorId());
        String pid = String.format(Locale.US, "%04x", device.getProductId());
        String script =
                "echo BEGIN; "
                + "for d in /sys/bus/usb/devices/*; do "
                + "[ -r \"$d/idVendor\" ] || continue; "
                + "v=$(cat \"$d/idVendor\" 2>/dev/null); "
                + "p=$(cat \"$d/idProduct\" 2>/dev/null); "
                + "[ \"$v\" = \"" + vid + "\" ] && [ \"$p\" = \"" + pid + "\" ] || continue; "
                + "echo \"DEV $d\"; "
                + "for n in \"$d\"/*/net/*; do [ -e \"$n\" ] && echo \"NET $(basename \"$n\")\"; done; "
                + "done; echo END";

        boolean sawBegin = false, sawEnd = false, sawDevice = false;
        String iface = "";
        for (String line : core.customCommand(script, true)) {
            if (line == null) continue;
            String l = line.trim();
            if (l.equals("BEGIN")) { sawBegin = true; continue; }
            if (l.equals("END")) { sawEnd = true; continue; }
            if (l.startsWith("DEV ")) { sawDevice = true; continue; }
            if (l.startsWith("NET ") && iface.isEmpty()) iface = l.substring(4).trim();
        }
        if (!sawBegin || !sawEnd) return null;
        if (!sawDevice) return null;
        return iface;
    }

    private static List<String> externalInterfaces(Core core) {
        List<String> out = new ArrayList<>();
        try {
            for (String[] pair : core.monitorManager.listInterfaces()) {
                String name = pair[0];
                if (name == null || name.isEmpty()) continue;
                if (com.opx.demon.utils.MonitorManager.isInternalRadio(name)) continue;
                out.add(name);
            }
        } catch (Throwable ignored) {
        }
        return out;
    }
}
