package com.opx.demon.engine;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.util.Log;

final class UsbHostAccess {

    private static final String TAG = "UsbHostAccess";

    private static final String ACTION_PERMISSION = "com.opx.demon.USB_PERMISSION";

    private final Context app;
    private final UsbManager manager;

    UsbHostAccess(Context context) {
        this.app = context.getApplicationContext();
        this.manager = (UsbManager) this.app.getSystemService(Context.USB_SERVICE);
    }

    boolean available() {
        return manager != null;
    }

    List<UsbDevice> devices() {
        List<UsbDevice> out = new ArrayList<>();
        if (manager == null) return out;
        out.addAll(manager.getDeviceList().values());
        Collections.sort(out, (a, b) -> Integer.compare(a.getDeviceId(), b.getDeviceId()));
        return out;
    }

    UsbDevice byName(String deviceName) {
        if (manager == null || deviceName == null) return null;
        return manager.getDeviceList().get(deviceName);
    }

    UsbDevice findByVidPid(String vidPid) {
        List<UsbDevice> all = matching(vidPid);
        return all.isEmpty() ? null : all.get(0);
    }

    List<UsbDevice> matching(String vidPid) {
        List<UsbDevice> out = new ArrayList<>();
        if (manager == null || vidPid == null) return out;
        String[] p = vidPid.split(":");
        if (p.length != 2) return out;
        int vid, pid;
        try {
            vid = Integer.parseInt(p[0].trim(), 16);
            pid = Integer.parseInt(p[1].trim(), 16);
        } catch (NumberFormatException e) {
            return out;
        }
        for (UsbDevice d : manager.getDeviceList().values()) {
            if (d.getVendorId() == vid && d.getProductId() == pid) out.add(d);
        }
        Collections.sort(out, (a, b) -> Integer.compare(a.getDeviceId(), b.getDeviceId()));
        return out;
    }

    boolean hasPermission(UsbDevice device) {
        return manager != null && device != null && manager.hasPermission(device);
    }

    static boolean isWifiCandidate(UsbDevice d) {
        if (d == null || d.getDeviceClass() == UsbConstants.USB_CLASS_HUB) return false;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            int cls = d.getInterface(i).getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_VENDOR_SPEC
                    || cls == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER) {
                return true;
            }
        }
        return false;
    }

    static String describe(UsbDevice d) {
        if (d == null) return "(none)";
        String label = d.getProductName();
        if (label == null || label.isEmpty()) label = d.getManufacturerName();
        if (label == null || label.isEmpty()) label = "USB device";
        return String.format("%04x:%04x %s", d.getVendorId(), d.getProductId(), label);
    }

    boolean requestPermissionBlocking(UsbDevice device, long waitMs) {
        if (manager == null || device == null) return false;
        synchronized (UsbPassthroughManager.PERMISSION_LOCK) {
            return askLocked(device, waitMs);
        }
    }

    private boolean askLocked(UsbDevice device, long waitMs) {
        if (manager.hasPermission(device)) return true;

        final CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_PERMISSION);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(receiver, filter);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot register the permission receiver", t);
            return false;
        }
        try {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(app, device.getDeviceId(),
                    new Intent(ACTION_PERMISSION).setPackage(app.getPackageName()), flags);
            manager.requestPermission(device, pi);
            latch.await(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        } finally {
            try { app.unregisterReceiver(receiver); } catch (Throwable ignored) {}
        }
        return manager.hasPermission(device);
    }

    Handle open(UsbDevice device) {
        if (manager == null || device == null) return null;
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            Log.w(TAG, "openDevice returned null for " + device.getDeviceName());
            return null;
        }
        int fd = conn.getFileDescriptor();
        if (fd < 0) {
            conn.close();
            Log.w(TAG, "no descriptor for " + device.getDeviceName());
            return null;
        }
        int claimed = 0;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface intf = device.getInterface(i);
            boolean ok;
            try {
                ok = conn.claimInterface(intf, true);
            } catch (Throwable t) {
                ok = false;
            }
            if (ok) claimed++;
            else Log.w(TAG, "the framework would not claim interface " + intf.getId()
                    + " of " + device.getDeviceName());
        }
        if (claimed == 0 && device.getInterfaceCount() > 0 && reconfigure(device, conn)) {
            claimed = 0;
            for (int i = 0; i < device.getInterfaceCount(); i++) {
                try {
                    if (conn.claimInterface(device.getInterface(i), true)) claimed++;
                } catch (Throwable ignored) {
                }
            }
            Log.i(TAG, "reconfigured " + describe(device)
                    + ", interfaces claimed " + claimed + "/" + device.getInterfaceCount());
        }
        Log.i(TAG, "opened " + describe(device) + " fd=" + fd
                + " interfaces claimed " + claimed + "/" + device.getInterfaceCount());
        return new Handle(device, conn, fd);
    }

    private static boolean reconfigure(UsbDevice device, UsbDeviceConnection conn) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
        try {
            if (device.getConfigurationCount() <= 0) return false;
            return conn.setConfiguration(device.getConfiguration(0));
        } catch (Throwable t) {
            Log.w(TAG, "could not set a configuration on " + describe(device) + ": " + t);
            return false;
        }
    }

    static final class Handle {
        final UsbDevice device;
        private final UsbDeviceConnection conn;
        final int fd;

        Handle(UsbDevice device, UsbDeviceConnection conn, int fd) {
            this.device = device;
            this.conn = conn;
            this.fd = fd;
        }

        void close() {
            try { conn.close(); } catch (Throwable ignored) {}
        }
    }

    static AutoCloseable serveFd(final String name, final int fd) throws java.io.IOException {
        final LocalServerSocket server = new LocalServerSocket(name);
        Thread t = new Thread(() -> {
            try {
                LocalSocket client = server.accept();
                ParcelFileDescriptor pfd = null;
                try {
                    pfd = ParcelFileDescriptor.fromFd(fd);
                    client.setFileDescriptorsForSend(
                            new java.io.FileDescriptor[]{pfd.getFileDescriptor()});
                    client.getOutputStream().write(0);
                    client.getOutputStream().flush();
                    Log.i(TAG, "descriptor sent over @" + name);
                } finally {
                    if (pfd != null) try { pfd.close(); } catch (Throwable ignored) {}
                    try { client.close(); } catch (Throwable ignored) {}
                }
            } catch (Throwable t2) {
                Log.w(TAG, "serving @" + name + ": " + t2.getMessage());
            } finally {
                try { server.close(); } catch (Throwable ignored) {}
            }
        }, "usb-serve-" + name);
        t.setDaemon(true);
        t.start();
        return () -> {
            try { server.close(); } catch (Throwable ignored) {}
        };
    }
}
