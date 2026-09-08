package com.opx.demon.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Requests every runtime permission the app can possibly ask for, across
 * Android 6 (API 23) through Android 16 (API 36):
 *
 *  - API 23+   runtime dangerous permissions (location, mic, storage-legacy…)
 *  - API 26+   battery-optimization exemption (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
 *  - API 29+   full external-storage handoff to the system dialog
 *  - API 30+   BLUETOOTH_SCAN / BLUETOOTH_CONNECT (replacing the pre-31 pairs)
 *  - API 31+   Bluetooth + Wi-Fi pairing special-access screens
 *  - API 33+   POST_NOTIFICATIONS runtime dialog + RECORD_AUDIO already covered
 *  - API 34+   FOREGROUND_SERVICE_DATA_SYNC is manifest-only; granted at install
 *  - API 36    no new runtime grants for the app's permission set
 *
 * "Special" grants that are Settings screens instead of dialogs (All-files-access,
 * battery, precise alarms…) are opened sequentially; the caller decides whether a
 * screen was skipped (user pressed back) and simply moves on — nothing blocks Next.
 */
public final class PermissionWizard {

    /** Result of a full pass: what the OS actually granted, per well-known key. */
    public static final class Report {
        public final Map<String, Boolean> runtime = new LinkedHashMap<>();
        public boolean allFilesAccess;
        public boolean batteryExempt;

        /** True when every runtime permission is granted — informational only. */
        public boolean allRuntimeGranted() {
            for (boolean v : runtime.values()) if (!v) return false;
            return true;
        }
    }

    private PermissionWizard() {
    }

    /** Dangerous runtime permissions, filtered per OS level. */
    public static List<String> runtimePermissions() {
        List<String> p = new ArrayList<>();
        p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        p.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) {
            p.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
        }
        if (Build.VERSION.SDK_INT <= 32) {
            // Pre-33 storage model; harmless no-op above 32 (maxSdkVersion in manifest).
            p.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            p.add(Manifest.permission.BLUETOOTH_SCAN);
            p.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            p.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        p.add(Manifest.permission.RECORD_AUDIO);
        return p;
    }

    /** Human-readable group name for a permission string, for the live status list. */
    public static String labelFor(Context ctx, String perm) {
        try {
            PackageManager pm = ctx.getPackageManager();
            PermissionInfo info = pm.getPermissionInfo(perm, 0);
            PermissionGroupInfo group = info.group == null ? null
                    : pm.getPermissionGroupInfo(info.group, 0);
            if (group != null && group.loadLabel(pm) != null) {
                String l = String.valueOf(group.loadLabel(pm));
                if (!l.isEmpty()) return l;
            }
        } catch (Exception ignored) {
        }
        int dot = perm.lastIndexOf('.');
        return dot >= 0 && dot < perm.length() - 1 ? perm.substring(dot + 1) : perm;
    }

    /** One dialog covering every dangerous permission this OS level understands. */
    public static void requestAllRuntime(Activity a) {
        if (a == null) return;
        List<String> missing = new ArrayList<>();
        for (String p : runtimePermissions()) {
            if (ContextCompat.checkSelfPermission(a, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        if (missing.isEmpty()) return;
        try {
            ActivityCompat.requestPermissions(a, missing.toArray(new String[0]), 4242);
        } catch (Exception ignored) {
        }
    }

    public static boolean hasAllFilesAccess(Context c) {
        if (Build.VERSION.SDK_INT < 29) {
            return ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        try {
            return Environment.isExternalStorageManager();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens the All-files-access Settings screen (API 29+) — falls back below 29. */
    public static void requestAllFilesAccess(Activity a) {
        if (a == null) return;
        if (Build.VERSION.SDK_INT < 29) {
            requestAllRuntime(a);
            return;
        }
        if (hasAllFilesAccess(a)) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            try {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isBatteryExempt(Context c) {
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Opens the battery-optimization exemption dialog (API 23+). */
    public static void requestBatteryExemption(Activity a) {
        if (a == null || isBatteryExempt(a)) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + a.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** All-files-access first (when needed), battery dialog right after. */
    public static void requestSpecialAccess(Activity a) {
        requestAllFilesAccess(a);
        requestBatteryExemption(a);
    }

    /** Bluetooth + Wi-Fi pairing special-access screens (API 31+); no-op below. */
    public static void requestCompanionAccess(Activity a) {
        if (a == null || Build.VERSION.SDK_INT < 31) return;
        try {
            a.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
        try {
            a.startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {
        }
    }

    /** Snapshot of what the OS has granted right now. */
    public static Report survey(Context c) {
        Report r = new Report();
        for (String p : runtimePermissions()) {
            r.runtime.put(p, ContextCompat.checkSelfPermission(c, p)
                    == PackageManager.PERMISSION_GRANTED);
        }
        r.allFilesAccess = hasAllFilesAccess(c);
        r.batteryExempt = isBatteryExempt(c);
        return r;
    }
}
