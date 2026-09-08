package com.opx.demon.appintro.slides;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.PorterDuff;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;
import com.opx.demon.R;
import com.opx.demon.appintro.AppIntroActivity;
import com.opx.demon.utils.Core;
import com.opx.demon.utils.PermissionWizard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Permissions slide — redesigned golden UI + a full Android 6→16 grant flow.
 *
 * The "Grant all permissions" button fires every dialog/screen the OS offers at
 * this API level (runtime group, all-files access, battery exemption). Status
 * rows update live in {@link #onResume()} as grants land. Nothing here blocks
 * Next: the button always completes the one-time app bootstrap and moves on,
 * whether or not the user granted anything.
 */
public class Slide2 extends Fragment {

    private Activity activity;
    private Context context;
    private Core core;
    private ViewPager2 mPager;

    private LinearLayout permList;
    private MaterialButton button;

    private static final class Row {
        ImageView icon;
        TextViewRef subtitle;
        ProgressBar spinner;
        ImageView status;
    }

    /** Tiny holder so rows can live in a map without a per-field class. */
    private static final class TextViewRef {
        android.widget.TextView v;
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    private Row storageRow;
    private Row batteryRow;
    private Row rootRow;

    private boolean rootChecked = false;
    private boolean rootGranted = false;

    @SuppressLint({"SdCardPath", "SetTextI18n"})
    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.new_slide2, container, false);
        activity = getActivity();
        if (activity == null) return view;
        context = getContext();
        core = new Core(context);
        mPager = activity.findViewById(R.id.view_pager);

        permList = view.findViewById(R.id.perm_list);
        button = view.findViewById(R.id.login);

        buildRows();
        refreshStatuses();

        button.setOnClickListener(view12 -> tryGrant());
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (context != null && core != null) refreshStatuses();
    }

    /** One golden row per runtime permission + storage + battery (+ root on rooted engines). */
    private void buildRows() {
        if (permList == null) return;
        boolean rootless = core.isRootless();

        if (!rootless) {
            rootRow = addRow(R.drawable.terminal, "Root (superuser)",
                    "Required to mount the chroot and run privileged tools");
        }

        for (String perm : PermissionWizard.runtimePermissions()) {
            if (rows.containsKey(perm)) continue;
            rows.put(perm, addRow(iconFor(perm), titleFor(perm), purposeFor(perm)));
        }

        storageRow = addRow(R.drawable.storage, "All-files access",
                "Reads/writes wordlists, scans and captured handshakes");
        batteryRow = addRow(R.drawable.bolt, "Battery optimization",
                "Keeps long scans alive in the background");
    }

    private Row addRow(int iconRes, String title, String subtitle) {
        if (getContext() == null) return null;
        View v = getLayoutInflater().inflate(R.layout.permission_row, permList, false);
        Row r = new Row();
        r.icon = v.findViewById(R.id.perm_icon);
        TextViewRef sub = new TextViewRef();
        sub.v = v.findViewById(R.id.perm_subtitle);
        r.subtitle = sub;
        r.spinner = v.findViewById(R.id.perm_spinner);
        r.status = v.findViewById(R.id.perm_status);
        if (r.icon != null) r.icon.setImageResource(iconRes);
        View titleTv = v.findViewById(R.id.perm_title);
        if (titleTv instanceof android.widget.TextView) {
            ((android.widget.TextView) titleTv).setText(title);
        }
        if (r.subtitle.v != null) r.subtitle.v.setText(subtitle);
        permList.addView(v);
        return r;
    }

    private static int iconFor(String perm) {
        switch (perm) {
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_COARSE_LOCATION":
            case "android.permission.ACCESS_BACKGROUND_LOCATION":
                return R.drawable.map;
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return R.drawable.storage;
            case "android.permission.BLUETOOTH_SCAN":
            case "android.permission.BLUETOOTH_CONNECT":
                return R.drawable.wpair;
            case "android.permission.POST_NOTIFICATIONS":
                return R.drawable.bell;
            case "android.permission.RECORD_AUDIO":
                return R.drawable.mic;
            default:
                return R.drawable.shield;
        }
    }

    private static String titleFor(String perm) {
        switch (perm) {
            case "android.permission.ACCESS_FINE_LOCATION":
                return "Location (precise)";
            case "android.permission.ACCESS_COARSE_LOCATION":
                return "Location (approximate)";
            case "android.permission.ACCESS_BACKGROUND_LOCATION":
                return "Background location";
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return "Storage (legacy)";
            case "android.permission.BLUETOOTH_SCAN":
                return "Bluetooth scan";
            case "android.permission.BLUETOOTH_CONNECT":
                return "Bluetooth connect";
            case "android.permission.POST_NOTIFICATIONS":
                return "Notifications";
            case "android.permission.RECORD_AUDIO":
                return "Microphone";
            default:
                return PermissionWizard.labelFor(null, perm);
        }
    }

    private static String purposeFor(String perm) {
        switch (perm) {
            case "android.permission.ACCESS_FINE_LOCATION":
            case "android.permission.ACCESS_COARSE_LOCATION":
                return "Required by Android to scan nearby Wi-Fi networks";
            case "android.permission.ACCESS_BACKGROUND_LOCATION":
                return "Continues network discovery when the app is hidden";
            case "android.permission.READ_EXTERNAL_STORAGE":
            case "android.permission.WRITE_EXTERNAL_STORAGE":
                return "Wordlists and captured files on older Android versions";
            case "android.permission.BLUETOOTH_SCAN":
                return "Discovers nearby BLE devices for WhisperPair";
            case "android.permission.BLUETOOTH_CONNECT":
                return "Talks to paired BLE devices";
            case "android.permission.POST_NOTIFICATIONS":
                return "Shows scan progress and the VM service notification";
            case "android.permission.RECORD_AUDIO":
                return "Audio tooling in the virtual machine";
            default:
                return "Used by individual tools when needed";
        }
    }

    /**
     * Fires the whole grant chain for this OS level, then always completes the
     * one-time bootstrap and advances — grant outcome only affects the badges.
     */
    private void tryGrant() {
        setWorking(true);

        // 1) One runtime dialog covering every dangerous permission this OS knows.
        PermissionWizard.requestAllRuntime(activity);
        // 2) Special screens: all-files access (API 29+) and battery exemption (API 23+).
        //    They stack above the runtime dialog; back out of any of them is fine.
        PermissionWizard.requestSpecialAccess(activity);

        new Thread(() -> {
            try {
                core.checkPermission(activity);
            } catch (Exception ignored) {
            }
            boolean rootless = core.isRootless();
            boolean rooted = !rootless && core.checkRoot();
            rootChecked = true;
            rootGranted = rooted;

            // Rooted devices can have legacy storage + battery whitelist granted via su.
            if (rooted) {
                core.customCommand("pm grant com.opx.demon android.permission.WRITE_EXTERNAL_STORAGE", true);
                core.customCommand("pm grant com.opx.demon android.permission.READ_EXTERNAL_STORAGE", true);
                core.customCommand("dumpsys deviceidle whitelist +com.opx.demon", true);
            }

            // One-time bootstrap — identical to the original flow, now unconditional
            // so a device that cannot show some dialogs still lands on the next step.
            try {
                core.putString("vnc_passwd", "opxdemon");
                if (rooted) {
                    ArrayList<String> interfaces = core.getInterfacesList();
                    if (interfaces.contains("swlan0")) {
                        core.putString("wlan_scan", "swlan0");
                        core.putString("wlan_wifi", "swlan0");
                        core.putString("wlan_deauth", "swlan0");
                        core.putString("wlan_wps", "swlan0");
                    } else {
                        core.putString("wlan_scan", "wlan0");
                        core.putString("wlan_deauth", "wlan0");
                        core.putString("wlan_wifi", "wlan0");
                        core.putString("wlan_wps", "wlan0");
                    }
                } else {
                    core.putString("wlan_scan", "wlan0");
                    core.putString("wlan_deauth", "wlan0");
                    core.putString("wlan_wifi", "wlan0");
                    core.putString("wlan_wps", "wlan0");
                }
                core.putInt("max_par", 3);
                core.remove("installed_modules");
                core.putBoolean("first_open", true);
                core.putBoolean("store_scan", true);
                core.putBoolean("auto_update", true);
                copyAssets();
                core.putBoolean("save_aps", true);
                core.putBoolean("autoScan", true);
                core.putBoolean("dash", true);
                core.putInt("night", 2);
                core.putInt("threads", 100);
                if (rooted) {
                    core.chmodFolder("/data/data/com.opx.demon/files");
                }
            } catch (Exception e) {
                Log.e("Slide2", "bootstrap failed", e);
            }

            uiSafe(() -> {
                setWorking(false);
                refreshStatuses();
                if (rooted) {
                    boolean alreadyInstalled = core.checkFolder("/data/local/opxdemon/release/sdcard/OPX-Demon")
                            && core.checkFile(Core.CHROOT_MARKER);
                    if (alreadyInstalled) {
                        ((AppIntroActivity) activity).jumpToLast();
                        return;
                    }
                }
                core.moveNext(mPager);
            });
        }).start();
    }

    private void setWorking(boolean working) {
        for (Row r : allRows()) setPip(r, working);
    }

    private List<Row> allRows() {
        List<Row> all = new ArrayList<>();
        if (rootRow != null) all.add(rootRow);
        all.addAll(rows.values());
        if (storageRow != null) all.add(storageRow);
        if (batteryRow != null) all.add(batteryRow);
        return all;
    }

    private void refreshStatuses() {
        if (context == null) return;
        boolean rootless = core.isRootless();

        if (rootRow != null) {
            applyStatus(rootRow, rootless || (rootChecked && rootGranted),
                    rootless ? "Not required — rootless VM engine"
                            : "Granted — superuser ready",
                    "Required to mount the chroot and run privileged tools");
        }
        for (Map.Entry<String, Row> e : rows.entrySet()) {
            boolean granted = ContextCompat.checkSelfPermission(context, e.getKey())
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String sub = granted ? "Granted" : "Not granted — optional, you can continue";
            applyStatus(e.getValue(), granted, sub, null);
        }
        if (storageRow != null) {
            boolean ok = PermissionWizard.hasAllFilesAccess(context);
            applyStatus(storageRow, ok,
                    ok ? "Granted — can read/write storage"
                            : "Not granted — needed for scan files",
                    "Reads/writes wordlists, scans and captured handshakes");
        }
        if (batteryRow != null) {
            boolean ok = PermissionWizard.isBatteryExempt(context);
            applyStatus(batteryRow, ok,
                    ok ? "Whitelisted — scans survive in background"
                            : "Not whitelisted — scans may pause",
                    "Keeps long scans alive in the background");
        }
    }

    private void applyStatus(Row r, boolean ok, String subOk, String subPending) {
        if (r == null) return;
        setPip(r, false);
        if (r.subtitle.v != null) {
            r.subtitle.v.setText(ok || subPending == null ? subOk : subPending);
        }
        if (r.status != null) {
            r.status.setImageResource(ok ? R.drawable.done : R.drawable.warning);
            r.status.setColorFilter(ContextCompat.getColor(context,
                    ok ? R.color.green : R.color.grey), PorterDuff.Mode.SRC_IN);
        }
    }

    private void setPip(Row r, boolean working) {
        if (r == null) return;
        if (r.spinner != null) r.spinner.setVisibility(working ? View.VISIBLE : View.GONE);
        if (r.status != null) r.status.setVisibility(working ? View.GONE : View.VISIBLE);
    }

    private void copyAssets() {
        AssetManager assetManager = activity.getAssets();
        String[] files = null;
        try {
            files = assetManager.list("");
        } catch (IOException e) {
            Log.e("Slide2", "Failed to get asset file list.", e);
        }
        if (files == null) return;
        for (String filename : files) {
            if (filename.equals(Core.BUSYBOX_ASSET)) continue;
            if (filename.equals("rootless")) continue;
            InputStream in = null;
            OutputStream out = null;
            try {
                in = assetManager.open(filename, AssetManager.ACCESS_STREAMING);
                @SuppressLint("SdCardPath") File outFile = new File("/data/data/com.opx.demon/files/", filename);
                out = new FileOutputStream(outFile);
                copyFile(in, out);
                out.flush();
            } catch (IOException ignored) {
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) {}
                if (out != null) try { out.close(); } catch (IOException ignored) {}
            }
        }
        Core.extractBusybox(activity);
        if (!core.isRootless()) {
            core.customCommand("dos2unix /data/data/com.opx.demon/files/*.sh", true);
            core.customCommand("dos2unix /data/data/com.opx.demon/files/*root*", true);
        }
    }

    private void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    private void uiSafe(Runnable r) {
        if (activity == null || !isAdded()) return;
        activity.runOnUiThread(() -> {
            if (isAdded()) r.run();
        });
    }
}
