package com.opx.demon.engine;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * USB/IP server for the UML engine — the UML side of {@link UsbBridge}.
 *
 * The guest kernel carries CONFIG_USBIP_VHCI_HCD=y (8-port VHCI) and the
 * Debian Trixie rootfs ships usbip-utils 2.0 (/usr/sbin/usbip). Attaching a
 * device therefore needs nothing but a correct USB/IP TCP server on port
 * 3240: the guest runs `usbip attach -r 10.0.2.2 -b <busid>` and the kernel's
 * vhci_hcd streams URBs over the resulting connection.
 *
 * Wire format (all big-endian, from usbip-utils 2.0 / kernel usbip_common.h):
 *
 *   op_common (8 B):  u16 version (0x0111), u16 code, u32 status
 *     OP_REQ_DEVLIST  = 0x8005   OP_REP_DEVLIST = 0x0005
 *     OP_REQ_IMPORT   = 0x8003   OP_REP_IMPORT  = 0x0003
 *
 *   op_devlist_reply: op_common + u32 ndev, then per device:
 *     usbip_usb_device (312 B packed):
 *       char path[256]; char busid[32];
 *       u32 busnum, devnum, speed;
 *       u16 idVendor, idProduct, bcdDevice;
 *       u8 bDeviceClass, bDeviceSubClass, bDeviceProtocol,
 *          bConfigurationValue, bNumConfigurations, bNumInterfaces;
 *     then bNumInterfaces × usbip_usb_interface (4 B):
 *       u8 bInterfaceClass, bInterfaceSubClass, bInterfaceProtocol, pad
 *
 *   op_import_reply: op_common + the same 312-byte device struct.
 *
 *   After OP_REP_IMPORT the connection becomes a URB stream. Every PDU:
 *     usbip_header (48 B packed):
 *       basic  (20 B): u32 command, seqnum, devid, direction, ep
 *       submit (28 B): u32 transfer_flags, s32 transfer_buffer_length,
 *                      s32 start_frame, s32 number_of_packets, s32 interval,
 *                      u8 setup[8]
 *     USBIP_CMD_SUBMIT = 0x0001, USBIP_RET_SUBMIT = 0x0003,
 *     USBIP_CMD_UNLINK = 0x0002, USBIP_RET_UNLINK = 0x0004
 *     direction: USBIP_DIR_OUT = 0, USBIP_DIR_IN = 1
 *     For IN submits the payload buffer is NOT sent; for OUT submits the
 *     payload bytes follow the header. RET_SUBMIT echoes the 20-byte basic
 *     header (command → RET_SUBMIT, direction/ep echoed verbatim) + u32
 *     status, s32 actual_length, s32 start_frame(-1), s32
 *     number_of_packets(0), s32 error_count(0) + payload for IN transfers.
 *
 * Xiaomi/MIUI speed quirk: some Xiaomi host stacks report full-speed devices
 * as low-speed. The QEMU engine patches the guest hub.c; here the UML guest
 * carries the same hub.c quirk (build-tools/uml-xiaomi-hub.patch) AND this
 * server applies the spec-level invariant at export time: never report
 * USB_SPEED_LOW for a device whose bMaxPacketSize0 exceeds 8 (read from the
 * real cached device descriptor via UsbDeviceConnection.getRawDescriptors(),
 * offset 7 — not guessed from getDeviceProtocol()).
 */
public final class UmlUsbServer implements UsbBridge {

    private static final String TAG = "UmlUsbServer";

    static final int LISTEN_PORT = 3240; // IANA-assigned usbip port

    /**
     * Host address the guest dials. UML's vector-net over slirp gives the
     * guest the classic 10.0.2.15/24 with 10.0.2.2 as the host — the same
     * address the QEMU engine's slirp uses, so {@code usbip attach -r
     * 10.0.2.2} works on both engines without per-engine config.
     */
    public static final String GUEST_VISIBLE_HOST = "10.0.2.2";

    /** Guest-side usbip binary shipped in the Debian Trixie rootfs. */
    private static final String GUEST_USBIP = "/usr/sbin/usbip";

    private static final String ACTION_USB_PERMISSION =
            "com.opx.demon.USB_PERMISSION";

    // op_common
    private static final int USBIP_VERSION = 0x0111;
    private static final int OP_REQ_DEVLIST = 0x8005;
    private static final int OP_REP_DEVLIST = 0x0005;
    private static final int OP_REQ_IMPORT = 0x8003;
    private static final int OP_REP_IMPORT = 0x0003;
    private static final int ST_OK = 0x0000;
    private static final int ST_NA = 0x0001;

    // URB commands
    private static final int USBIP_CMD_SUBMIT = 0x0001;
    private static final int USBIP_CMD_UNLINK = 0x0002;
    private static final int USBIP_RET_SUBMIT = 0x0003;
    private static final int USBIP_RET_UNLINK = 0x0004;
    private static final int USBIP_DIR_IN = 0x01;

    // Linux usb_device_speed enum values the VHCI expects
    private static final int USB_SPEED_LOW = 1;
    private static final int USB_SPEED_FULL = 2;
    private static final int USB_SPEED_HIGH = 3;
    private static final int USB_SPEED_SUPER = 4;
    private static final int USB_SPEED_UNKNOWN = 0;

    private static final int BUSID_LEN = 32;
    private static final int PATH_LEN = 256;
    private static final int DEV_STRUCT_LEN =
            PATH_LEN + BUSID_LEN + 4 + 4 + 4 + 2 + 2 + 2 + 1 + 1 + 1 + 1 + 1 + 1; // 312

    /** Android USB speed constants (UsbConstants) for reference. */
    private static final int ANDROID_SPEED_LOW = UsbConstants.USB_SPEED_LOW;
    private static final int ANDROID_SPEED_FULL = UsbConstants.USB_SPEED_FULL;
    private static final int ANDROID_SPEED_HIGH = UsbConstants.USB_SPEED_HIGH;
    private static final int ANDROID_SPEED_SUPER = UsbConstants.USB_SPEED_SUPER;

    private final Context context;
    private final UsbManager usbManager;
    private final Map<Integer, Exported> exported = new HashMap<>();
    private volatile ServerSocket server;
    private volatile boolean running;
    private Thread acceptThread;

    // permission plumbing — mirrors UsbPassthroughManager
    private final Map<Integer, UsbPassthroughManager.PermissionCallback> permissionCallbacks =
            new HashMap<>();
    private volatile CountDownLatch pendingPermission;
    private volatile int awaitingDeviceId = -1;
    private volatile boolean receiverRegistered;
    private android.os.HandlerThread receiverThread;
    private android.os.Handler receiverHandler;

    private static final class Exported {
        final UsbDevice device;
        final UsbDeviceConnection connection;
        final List<UsbInterface> claimed;
        final int devnum;
        final int speed;
        final String busid;
        Exported(UsbDevice d, UsbDeviceConnection c, List<UsbInterface> cl,
                 int devnum, int speed, String busid) {
            device = d; connection = c; claimed = cl;
            this.devnum = devnum; this.speed = speed; this.busid = busid;
        }
    }

    public UmlUsbServer(Context context) {
        this.context = context.getApplicationContext();
        this.usbManager = (UsbManager) this.context.getSystemService(Context.USB_SERVICE);
    }

    // ── UsbBridge ────────────────────────────────────────────────────────

    @Override
    public UsbDevice findByVidPid(String vidPid) {
        if (usbManager == null || vidPid == null) return null;
        String[] p = vidPid.split(":");
        if (p.length != 2) return null;
        int vid, pid;
        try {
            vid = Integer.parseInt(p[0].trim(), 16);
            pid = Integer.parseInt(p[1].trim(), 16);
        } catch (NumberFormatException e) {
            return null;
        }
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (d.getVendorId() == vid && d.getProductId() == pid) return d;
        }
        return null;
    }

    @Override
    public boolean hasPermission(UsbDevice device) {
        return usbManager != null && device != null && usbManager.hasPermission(device);
    }

    @Override
    public boolean isAttached(UsbDevice device) {
        if (device == null) return false;
        synchronized (this) { return exported.containsKey(device.getDeviceId()); }
    }

    @Override
    public int attachedCount() {
        synchronized (this) { return exported.size(); }
    }

    @Override
    public void requestUsbPermission(UsbDevice device,
                                     UsbPassthroughManager.PermissionCallback cb) {
        if (usbManager == null || device == null) {
            if (cb != null) cb.onResult(false, device);
            return;
        }
        synchronized (permissionCallbacks) { permissionCallbacks.put(device.getDeviceId(), cb); }
        registerReceiver();
        usbManager.requestPermission(device, permissionIntent(device));
    }

    private PendingIntent permissionIntent(UsbDevice device) {
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(context, device.getDeviceId(),
                new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName()), flags);
    }

    @Override
    public void attachAsync(UsbDevice device, UsbPassthroughManager.AttachCallback done) {
        if (device == null) { if (done != null) done.onResult(false, null); return; }
        if (isAttached(device)) { if (done != null) done.onResult(true, device); return; }
        if (hasPermission(device)) {
            boolean ok = attach(device);
            if (done != null) done.onResult(ok, device);
            return;
        }
        requestUsbPermission(device, (granted, d) -> {
            boolean ok = granted && attach(device);
            if (done != null) done.onResult(ok, device);
        });
    }

    /**
     * Exports one already-permitted device and attaches it inside the guest.
     * Claims every interface so Android host drivers release the pipes, then
     * runs {@code usbip attach} over the agent/console path so the VHCI binds
     * the port and starts streaming URBs. The guest round trip happens
     * outside the export lock so the USB/IP protocol threads never stall on
     * a slow guest console.
     */
    @Override
    public boolean attach(UsbDevice device) {
        String busid = exportDevice(device);
        if (busid == null) return false;
        if (!guestAttach(busid)) {
            Log.w(TAG, "guest usbip attach failed for " + busid
                    + " — device exported, will retry on demand");
        }
        return true;
    }

    private synchronized String exportDevice(UsbDevice device) {
        if (usbManager == null || device == null) return null;
        if (exported.containsKey(device.getDeviceId()))
            return exported.get(device.getDeviceId()).busid;
        UsbDeviceConnection conn;
        try {
            conn = usbManager.openDevice(device);
        } catch (Throwable t) {
            Log.w(TAG, "openDevice threw for " + device.getDeviceName(), t);
            return null;
        }
        if (conn == null) {
            Log.w(TAG, "openDevice failed for " + device.getDeviceName());
            return null;
        }
        List<UsbInterface> claimed = new ArrayList<>();
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            try {
                if (conn.claimInterface(iface, true)) claimed.add(iface);
            } catch (Throwable t) {
                Log.w(TAG, "claimInterface " + i + " failed: " + t.getMessage());
            }
        }
        int devnum = 2 + (Math.abs(device.getDeviceId()) % 100);
        String busid = "1-" + devnum;
        int maxP0 = readEp0MaxPacket(device, conn);
        int speed = linuxSpeed(device, maxP0);
        exported.put(device.getDeviceId(), new Exported(
                device, conn, claimed, devnum, speed, busid));
        Log.i(TAG, "Exported " + device.getDeviceName()
                + " (vid=" + Integer.toHexString(device.getVendorId())
                + " pid=" + Integer.toHexString(device.getProductId())
                + ") as busid " + busid + " speed " + speed
                + " ep0maxpacket=" + maxP0);
        return busid;
    }

    @Override
    public void detach(int deviceId) {
        Exported e;
        synchronized (this) {
            e = exported.remove(deviceId);
        }
        if (e == null) return;
        guestDetach(e.busid);
        for (UsbInterface iface : e.claimed) {
            try { e.connection.releaseInterface(iface); } catch (Throwable ignored) {}
        }
        try { e.connection.close(); } catch (Throwable ignored) {}
    }

    @Override
    public void detachAll() {
        List<Exported> all;
        synchronized (this) {
            all = new ArrayList<>(exported.values());
            exported.clear();
            unregisterReceiver();
        }
        for (Exported e : all) {
            guestDetach(e.busid);
            for (UsbInterface iface : e.claimed) {
                try { e.connection.releaseInterface(iface); } catch (Throwable ignored) {}
            }
            try { e.connection.close(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public synchronized boolean hasAttached() {
        return !exported.isEmpty();
    }

    /** Legacy alias kept for callers written against the older server API. */
    public synchronized boolean hasExported() { return !exported.isEmpty(); }

    @Override
    public boolean isWifiCandidate(UsbDevice d) {
        if (d == null || d.getDeviceClass() == UsbConstants.USB_CLASS_HUB) return false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC
                    || intf.getInterfaceClass() == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<UsbDevice> pickWifiDevices() {
        List<UsbDevice> out = new ArrayList<>();
        if (usbManager == null) return out;
        for (UsbDevice d : usbManager.getDeviceList().values()) {
            if (isWifiCandidate(d)) out.add(d);
        }
        java.util.Collections.sort(out,
                (a, b) -> Integer.compare(a.getDeviceId(), b.getDeviceId()));
        return out;
    }

    @Override
    public int attachAllWifiDongles(long waitMs) {
        List<UsbDevice> picks = pickWifiDevices();
        if (picks.isEmpty()) return 0;
        int ok = 0;
        for (UsbDevice d : picks) {
            if (isAttached(d)) { ok++; continue; }
            if (!usbManager.hasPermission(d)
                    && (!requestPermissionBlocking(d, waitMs) || !usbManager.hasPermission(d))) {
                Log.w(TAG, "USB permission not granted for " + d.getDeviceName());
                continue;
            }
            if (attach(d)) ok++;   // attach() includes the guest-side bind + retry
        }
        return ok;
    }

    @Override
    public UsbDevice pickWifiDevice() {
        List<UsbDevice> picks = pickWifiDevices();
        return picks.isEmpty() ? null : picks.get(0);
    }

    // ── lifecycle ────────────────────────────────────────────────────────

    public synchronized void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(this::acceptLoop, "usbip-accept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (server != null && !server.isClosed()) server.close(); } catch (Exception ignored) {}
        server = null;
        detachAll();
    }

    /**
     * Guest-side VHCI bind. Runs inside the guest through whatever transport
     * is alive: the agent on port 1050 when it is up, else the serial console
     * (the same two paths the engine uses for everything else). One retry —
     * the first attempt can land before the guest's slirp link is fully up.
     */
    private boolean guestAttach(String busid) {
        String cmd = GUEST_USBIP + " attach -r " + GUEST_VISIBLE_HOST + " -b " + busid
                + " >/dev/null 2>&1; echo __USBIP_$?__";
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String line : guestRun(cmd, 20_000)) {
                if (line != null && line.contains("__USBIP_0__")) return true;
            }
            // Exit != 0 can still mean bound: a stale port from a previous app
            // run keeps the busid listed. 'usbip port' naming it is success.
            if (guestPortHas(busid)) return true;
            if (attempt == 0) {
                // Bring the link up the same way bootstrapAgentOverConsole does.
                guestRun("ip link set eth0 up 2>/dev/null; "
                        + "ip addr add 10.0.2.15/24 dev eth0 2>/dev/null; "
                        + "ip route add default via 10.0.2.2 dev eth0 2>/dev/null; "
                        + "true", 10_000);
            }
        }
        return false;
    }

    private boolean guestPortHas(String busid) {
        for (String line : guestRun(GUEST_USBIP + " port 2>/dev/null; true", 10_000)) {
            if (line != null && line.contains(busid)) return true;
        }
        return false;
    }

    private void guestDetach(String busid) {
        String cmd = "port=$( " + GUEST_USBIP + " port 2>/dev/null | grep -B2 'usbip-vudc\\|" + busid
                + "' | grep -oE 'port [0-9]+' | grep -oE '[0-9]+' | head -1 ); "
                + "[ -n \"$port\" ] && " + GUEST_USBIP + " detach -p $port >/dev/null 2>&1; true";
        guestRun(cmd, 10_000);
    }

    /**
     * Runs a command inside the guest: through opxdemon-agentd (GuestExec) when
     * it answers, else over the serial console (UML's TCP 1050 port channel —
     * socketPath null routes GuestConsole to that backend).
     */
    private static List<String> guestRun(String command, int timeoutMs) {
        List<String> out = GuestExec.run(command);
        if (!out.isEmpty()) return out;
        return GuestConsole.run(command, null, timeoutMs);
    }

    private void acceptLoop() {
        try {
            // 0.0.0.0, not just loopback: the UML guest reaches the host through
            // the slirp gateway, and on some devices that traffic does not present
            // as 127.0.0.1 to the app sandbox. Android sandboxes the process
            // anyway; the port is IANA-assigned usbip and the app owns it.
            server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(LISTEN_PORT), 4);
            while (running) {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "usbip-conn");
                t.setDaemon(true);
                t.start();
            }
        } catch (Exception e) {
            if (running) Log.w(TAG, "accept loop ended: " + e.getMessage());
        }
    }

    private void serve(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            while (running) {
                int version = readU16(in);
                int code = readU16(in);
                int status = readS32(in);
                if (version != USBIP_VERSION) {
                    Log.w(TAG, "client version 0x" + Integer.toHexString(version));
                }
                switch (code) {
                    case OP_REQ_DEVLIST:
                        handleDevlist(out);
                        break;
                    case OP_REQ_IMPORT:
                        handleImport(in, out);
                        return; // connection now carries URBs only
                    default:
                        Log.w(TAG, "unknown op 0x" + Integer.toHexString(code));
                        return;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "connection dropped: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
    }

    private void handleDevlist(OutputStream out) throws Exception {
        List<Exported> list;
        synchronized (this) { list = new ArrayList<>(exported.values()); }
        writeOpCommon(out, OP_REP_DEVLIST, ST_OK);
        writeU32(out, list.size());
        for (Exported e : list) {
            writeDeviceStruct(out, e);
            writeU32(out, e.device.getInterfaceCount());
            for (int i = 0; i < e.device.getInterfaceCount(); i++) {
                UsbInterface iface = e.device.getInterface(i);
                out.write(iface.getInterfaceClass());
                out.write(iface.getInterfaceSubclass());
                out.write(iface.getInterfaceProtocol());
                out.write(0);
            }
        }
        out.flush();
    }

    private void handleImport(InputStream in, OutputStream out) throws Exception {
        byte[] busidBytes = readFully(in, BUSID_LEN);
        String busid = new String(busidBytes, StandardCharsets.US_ASCII).trim();

        Exported pick = null;
        synchronized (this) {
            for (Exported e : exported.values()) {
                if (e.busid.equals(busid)) pick = e;
            }
        }
        if (pick == null) {
            Log.w(TAG, "import request for unknown busid '" + busid + "'");
            writeOpCommon(out, OP_REP_IMPORT, ST_NA);
            out.flush();
            return;
        }
        writeOpCommon(out, OP_REP_IMPORT, ST_OK);
        writeDeviceStruct(out, pick);
        out.flush();
        Log.i(TAG, "imported " + busid + " — entering URB loop");
        urbLoop(in, out, pick);
    }

    // ── URB loop ─────────────────────────────────────────────────────────

    private void urbLoop(InputStream in, OutputStream out, Exported e) {
        try {
            while (running) {
                int command = readS32(in);
                int seqnum = readS32(in);
                readS32(in);               // devid
                int direction = readS32(in);
                int ep = readS32(in);

                if (command == USBIP_CMD_UNLINK) {
                    readS32(in);           // unlink seqnum
                    readFully(in, 24);     // reserved
                    writeRetHeader(out, USBIP_RET_UNLINK, seqnum, 0, 0, null,
                            direction, ep);
                    continue;
                }
                if (command != USBIP_CMD_SUBMIT) {
                    Log.w(TAG, "bad URB command " + command);
                    return;
                }

                int transferFlags = readS32(in);   // parsed for the 48-byte header layout
                int bufLen = readS32(in);
                readS32(in);               // start_frame
                readS32(in);               // number_of_packets
                int interval = readS32(in); // unused on non-iso paths
                byte[] setup = readFully(in, 8);

                boolean isInput = direction == USBIP_DIR_IN;
                boolean isControl = ep == 0;

                if (isControl) {
                    // Control transfer: the DATA stage length lives in
                    // setup wLength, not in transfer_buffer_length (which is
                    // just the URB buffer size). OUT payloads (host→device,
                    // wLength > 0) follow the header; IN payloads come back
                    // with the RET_SUBMIT.
                    int wLength = ((setup[6] & 0xff) | ((setup[7] & 0xff) << 8));
                    boolean hostRead = (setup[0] & 0x80) != 0;
                    byte[] payload = null;
                    if (!hostRead && wLength > 0) {
                        payload = readFully(in, wLength);
                    }
                    byte[] data = new byte[Math.max(0, hostRead ? wLength : 0)];
                    int n = e.connection.controlTransfer(
                            setup[0] & 0xff, setup[1] & 0xff,
                            (setup[2] & 0xff) | ((setup[3] & 0xff) << 8),
                            (setup[4] & 0xff) | ((setup[5] & 0xff) << 8),
                            hostRead ? data : payload,
                            hostRead ? data.length : (payload == null ? 0 : payload.length),
                            5000);
                    byte[] resp = (hostRead && n > 0)
                            ? trim(data, n) : new byte[0];
                    writeRetHeader(out, USBIP_RET_SUBMIT, seqnum,
                            n >= 0 ? 0 : -1, resp.length, resp, direction, ep);
                } else if (isInput) {
                    // IN bulk/interrupt: bufLen is the caller's buffer size.
                    UsbEndpoint endpoint = findEndpoint(e, ep, true);
                    byte[] buf = new byte[Math.max(1, Math.min(bufLen, 32768))];
                    int n = endpoint == null ? -1
                            : e.connection.bulkTransfer(endpoint, buf, buf.length, 5000);
                    byte[] resp = n > 0 ? trim(buf, n) : new byte[0];
                    writeRetHeader(out, USBIP_RET_SUBMIT, seqnum,
                            n >= 0 ? 0 : -1, resp.length, resp, direction, ep);
                } else {
                    // OUT bulk/interrupt: payload bytes follow the header.
                    byte[] payload = readFully(in, Math.max(0, bufLen));
                    UsbEndpoint endpoint = findEndpoint(e, ep, false);
                    int n = endpoint == null ? -1
                            : e.connection.bulkTransfer(endpoint, payload, payload.length, 5000);
                    writeRetHeader(out, USBIP_RET_SUBMIT, seqnum,
                            n >= 0 ? 0 : -1, 0, null, direction, ep);
                }
            }
        } catch (EOFException ignored) {
            // client detached
        } catch (Exception ex) {
            Log.w(TAG, "URB loop ended: " + ex.getMessage());
        }
    }

    private void writeRetHeader(OutputStream out, int command, int seqnum,
                                int status, int actualLength, byte[] payload,
                                int direction, int ep)
            throws Exception {
        writeU32(out, command);
        writeU32(out, seqnum);
        writeU32(out, 0);      // devid
        writeU32(out, direction); // echoed verbatim — the kernel matches on it
        writeU32(out, ep);        // echoed verbatim
        writeS32(out, status);
        writeS32(out, actualLength);
        writeS32(out, -1);     // start_frame
        writeS32(out, 0);      // number_of_packets
        writeS32(out, 0);      // error_count
        if (payload != null && payload.length > 0) out.write(payload);
        out.flush();
    }

    // ── device struct ────────────────────────────────────────────────────

    private void writeDeviceStruct(OutputStream out, Exported e) throws Exception {
        UsbDevice d = e.device;
        byte[] path = new byte[PATH_LEN];
        byte[] pathStr = ("/sys/devices/usbip/" + e.busid).getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(pathStr, 0, path, 0, Math.min(pathStr.length, PATH_LEN));
        out.write(path);

        byte[] busid = new byte[BUSID_LEN];
        byte[] busidStr = e.busid.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(busidStr, 0, busid, 0, Math.min(busidStr.length, BUSID_LEN));
        out.write(busid);

        writeU32(out, 1);                  // busnum
        writeU32(out, e.devnum);           // devnum
        writeU32(out, e.speed);            // speed (Linux enum)
        writeU16(out, d.getVendorId());
        writeU16(out, d.getProductId());
        writeU16(out, 0x0100);             // bcdDevice
        out.write(d.getDeviceClass());
        out.write(d.getDeviceSubclass());
        out.write(d.getDeviceProtocol());
        out.write(1);                      // bConfigurationValue
        out.write(Math.max(1, d.getConfigurationCount()));
        out.write(Math.max(1, d.getInterfaceCount()));
    }

    private void writeOpCommon(OutputStream out, int code, int status) throws Exception {
        writeU16(out, USBIP_VERSION);
        writeU16(out, code);
        writeS32(out, status);
    }

    // ── Xiaomi/MIUI speed correction ─────────────────────────────────────

    /**
     * Maps the Android-reported speed to the Linux usb_device_speed enum the
     * VHCI port must take, applying the Xiaomi/MIUI correction: Android hosts
     * that misreport full-speed devices as low-speed are overridden to
     * USB_SPEED_FULL because a real low-speed device can never have an ep0
     * maxpacket above 8 (read from the real cached device descriptor).
     */
    private static int linuxSpeed(UsbDevice d, int maxPacket0) {
        int speed;
        try {
            speed = d.getSpeed();
        } catch (Throwable t) {
            speed = UsbConstants.USB_SPEED_UNKNOWN;
        }
        int linux;
        switch (speed) {
            case ANDROID_SPEED_LOW: linux = USB_SPEED_LOW; break;
            case ANDROID_SPEED_FULL: linux = USB_SPEED_FULL; break;
            case ANDROID_SPEED_HIGH: linux = USB_SPEED_HIGH; break;
            case ANDROID_SPEED_SUPER: linux = USB_SPEED_SUPER; break;
            default: linux = USB_SPEED_UNKNOWN; break;
        }
        if (linux == USB_SPEED_LOW && maxPacket0 > 8) {
            // Xiaomi/MIUI misreport: spec-invariant correction. The guest
            // hub.c quirk (uml-xiaomi-hub.patch) re-checks this at
            // enumeration time, so both ends agree.
            linux = USB_SPEED_FULL;
        } else if (linux == USB_SPEED_UNKNOWN) {
            linux = USB_SPEED_FULL;
        }
        return linux;
    }

    /**
     * bMaxPacketSize0 sits at offset 7 of the cached device descriptor.
     * UsbDeviceConnection.getRawDescriptors() returns the 18-byte descriptor
     * the kernel itself enumerated with, so this is the ground truth — not a
     * guess from other UsbDevice fields.
     */
    private static int readEp0MaxPacket(UsbDevice d, UsbDeviceConnection conn) {
        try {
            byte[] desc = conn.getRawDescriptors();
            if (desc != null && desc.length > 7) {
                int mps = desc[7] & 0xff;
                if (mps > 0) return mps;
            }
        } catch (Throwable ignored) {}
        return 8; // conservative: never upgrades a device past full-speed
    }

    private static UsbEndpoint findEndpoint(Exported e, int address, boolean in) {
        for (int i = 0; i < e.device.getInterfaceCount(); i++) {
            UsbInterface iface = e.device.getInterface(i);
            for (int j = 0; j < iface.getEndpointCount(); j++) {
                UsbEndpoint ep = iface.getEndpoint(j);
                boolean epIn = ep.getDirection() == UsbConstants.USB_DIR_IN;
                if (ep.getAddress() == address && epIn == in) return ep;
            }
        }
        return null;
    }

    // ── permission receiver (mirrors UsbPassthroughManager) ─────────────

    private boolean requestPermissionBlocking(UsbDevice device, long waitMs) {
        pendingPermission = new CountDownLatch(1);
        awaitingDeviceId = device.getDeviceId();
        registerReceiver();
        usbManager.requestPermission(device, permissionIntent(device));
        try {
            return pendingPermission.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        } finally {
            awaitingDeviceId = -1;
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
                boolean granted =
                        intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (pendingPermission != null
                        && (d == null || d.getDeviceId() == awaitingDeviceId)) {
                    pendingPermission.countDown();
                }
                UsbPassthroughManager.PermissionCallback cb = null;
                synchronized (permissionCallbacks) {
                    if (d != null) {
                        cb = permissionCallbacks.remove(d.getDeviceId());
                    } else if (permissionCallbacks.size() == 1) {
                        Integer only = permissionCallbacks.keySet().iterator().next();
                        cb = permissionCallbacks.remove(only);
                    }
                }
                if (cb != null) cb.onResult(granted, d);
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && isWifiCandidate(d) && hasPermission(d) && !isAttached(d)) {
                    new Thread(() -> {
                        try { attach(d); } catch (Throwable t) {
                            Log.w(TAG, "attach on plug failed", t);
                        }
                    }, "usbip-attach-on-plug").start();
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null) {
                    try { detach(d.getDeviceId()); } catch (Throwable t) {
                        Log.w(TAG, "detach failed", t);
                    }
                }
            }
        }
    };

    private void registerReceiver() {
        if (receiverRegistered) return;
        if (receiverThread == null) {
            receiverThread = new android.os.HandlerThread("usbip-permission");
            receiverThread.start();
            receiverHandler = new android.os.Handler(receiverThread.getLooper());
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, null, receiverHandler,
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, null, receiverHandler);
        }
        receiverRegistered = true;
    }

    private void unregisterReceiver() {
        if (!receiverRegistered) return;
        try { context.unregisterReceiver(receiver); } catch (Exception ignored) {}
        receiverRegistered = false;
        if (receiverThread != null) {
            receiverThread.quitSafely();
            receiverThread = null;
            receiverHandler = null;
        }
        synchronized (permissionCallbacks) { permissionCallbacks.clear(); }
    }

    // ── wire helpers ─────────────────────────────────────────────────────

    private static int readU16(InputStream in) throws Exception {
        int b1 = in.read(), b2 = in.read();
        if ((b1 | b2) < 0) throw new EOFException();
        return (b1 << 8) | b2;
    }

    private static int readS32(InputStream in) throws Exception {
        byte[] b = readFully(in, 4);
        return ((b[0] & 0xff) << 24) | ((b[1] & 0xff) << 16)
                | ((b[2] & 0xff) << 8) | (b[3] & 0xff);
    }

    private static void writeU16(OutputStream out, int v) throws Exception {
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static void writeU32(OutputStream out, int v) throws Exception {
        out.write((v >>> 24) & 0xff);
        out.write((v >>> 16) & 0xff);
        out.write((v >>> 8) & 0xff);
        out.write(v & 0xff);
    }

    private static void writeS32(OutputStream out, int v) throws Exception {
        writeU32(out, v);
    }

    private static byte[] readFully(InputStream in, int n) throws Exception {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
        return buf;
    }

    private static byte[] trim(byte[] src, int n) {
        if (n <= 0) return new byte[0];
        byte[] out = new byte[n];
        System.arraycopy(src, 0, out, 0, n);
        return out;
    }
}
