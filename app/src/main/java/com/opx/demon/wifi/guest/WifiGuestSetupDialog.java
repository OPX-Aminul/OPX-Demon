package com.opx.demon.wifi.guest;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.opx.demon.R;
import com.opx.demon.appintro.install.InstallLogDialog;
import com.opx.demon.appintro.install.LogAdapter;
import com.opx.demon.appintro.install.LogLevel;
import com.opx.demon.appintro.install.LogLine;

import java.util.EnumMap;
import java.util.Map;

public final class WifiGuestSetupDialog {

    public interface Done {
        void onClosed(boolean armed, String iface);
    }

    private final Activity activity;
    private final Dialog dialog;
    private final Map<WifiGuestSetup.Step, Row> rows = new EnumMap<>(WifiGuestSetup.Step.class);
    private final LogAdapter log;
    private final TextView subtitle;
    private final TextView summary;
    private final MaterialButton primary;
    private final MaterialButton cancel;
    private final Done done;

    private WifiGuestSetup setup;
    private InstallLogDialog logWindow;
    private boolean running;
    private boolean armed;
    private String iface = "";

    private static final class Row {
        final ImageView icon;
        final ProgressBar spinner;
        final TextView detail;

        Row(ImageView icon, ProgressBar spinner, TextView detail) {
            this.icon = icon;
            this.spinner = spinner;
            this.detail = detail;
        }
    }

    public static void show(Activity activity, Done done) {
        new WifiGuestSetupDialog(activity, done).open();
    }

    private WifiGuestSetupDialog(Activity activity, Done done) {
        this.activity = activity;
        this.done = done;
        this.log = new LogAdapter(activity);

        View root = LayoutInflater.from(activity).inflate(R.layout.dialog_wifi_guest, null);
        this.subtitle = root.findViewById(R.id.wifi_guest_subtitle);
        this.summary = root.findViewById(R.id.wifi_guest_summary);
        this.primary = root.findViewById(R.id.wifi_guest_primary);
        this.cancel = root.findViewById(R.id.wifi_guest_cancel);

        LinearLayout steps = root.findViewById(R.id.wifi_guest_steps);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (WifiGuestSetup.Step step : WifiGuestSetup.steps()) {
            View row = inflater.inflate(R.layout.item_wifi_guest_step, steps, false);
            ((TextView) row.findViewById(R.id.step_title)).setText(titleOf(step));
            rows.put(step, new Row(
                    row.findViewById(R.id.step_icon),
                    row.findViewById(R.id.step_spinner),
                    row.findViewById(R.id.step_detail)));
            steps.addView(row);
        }

        dialog = new Dialog(activity);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setCanceledOnTouchOutside(false);

        root.findViewById(R.id.wifi_guest_log).setOnClickListener(v -> openLog());
        cancel.setOnClickListener(v -> onCancel());
        primary.setOnClickListener(v -> onPrimary());
    }

    private void open() {
        dialog.setOnDismissListener(d -> {
            if (setup != null) setup.cancel();
            closeLog();
            if (done != null) done.onClosed(armed, iface);
        });
        dialog.show();
    }

    private void onPrimary() {
        if (running) return;
        if (armed) { dialog.dismiss(); return; }
        start();
    }

    private void onCancel() {
        if (running) {
            if (setup != null) setup.cancel();
            cancel.setEnabled(false);
            subtitle.setText(R.string.wg_cancelling);
            return;
        }
        dialog.dismiss();
    }

    private void start() {
        running = true;
        armed = false;
        summary.setVisibility(View.GONE);
        subtitle.setText(R.string.wifi_guest_running_warning);
        primary.setEnabled(false);
        cancel.setEnabled(true);
        cancel.setText(R.string.wifi_guest_cancel);
        for (WifiGuestSetup.Step step : rows.keySet()) reset(step);

        setup = new WifiGuestSetup(activity, new WifiGuestSetup.Listener() {
            @Override public void onStep(WifiGuestSetup.Step step, WifiGuestSetup.State state,
                                         String detail) {
                ui(() -> render(step, state, detail));
            }

            @Override public void onLog(String line) {
                ui(() -> log.append(new LogLine(LogLevel.INFO, line)));
            }

            @Override public void onFinished(boolean ok, String text, String guestIface,
                                             boolean retry) {
                ui(() -> finished(ok, text, guestIface, retry));
            }
        });
        Thread t = new Thread(setup::run, "wifi-guest-setup");
        t.setDaemon(true);
        t.start();
    }

    private void reset(WifiGuestSetup.Step step) {
        Row row = rows.get(step);
        if (row == null) return;
        row.spinner.setVisibility(View.GONE);
        row.icon.setVisibility(View.VISIBLE);
        row.icon.setImageResource(R.drawable.step_dot_pending);
        row.icon.clearColorFilter();
        row.detail.setVisibility(View.GONE);
    }

    private void render(WifiGuestSetup.Step step, WifiGuestSetup.State state, String detail) {
        Row row = rows.get(step);
        if (row == null) return;
        boolean busy = state == WifiGuestSetup.State.RUNNING;
        row.spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        row.icon.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        if (!busy) {
            boolean good = state == WifiGuestSetup.State.OK;
            row.icon.setImageResource(good ? R.drawable.done : R.drawable.error);
            row.icon.setColorFilter(ContextCompat.getColor(activity,
                    good ? R.color.green : R.color.red));
        }
        if (detail == null || detail.isEmpty()) {
            row.detail.setVisibility(View.GONE);
        } else {
            row.detail.setText(detail);
            row.detail.setVisibility(View.VISIBLE);
        }
    }

    private void finished(boolean ok, String text, String guestIface, boolean retry) {
        running = false;
        armed = ok;
        iface = guestIface == null ? "" : guestIface;
        summary.setText(text);
        summary.setVisibility(View.VISIBLE);
        subtitle.setText(R.string.wifi_guest_subtitle);
        primary.setEnabled(ok || retry);
        primary.setText(ok ? R.string.wifi_guest_done : R.string.wifi_guest_retry);
        cancel.setEnabled(true);
        cancel.setText(R.string.wifi_guest_close);
        if (!ok) openLog();
    }

    private void openLog() {
        if (logWindow != null) return;
        logWindow = InstallLogDialog.show(activity, log);
    }

    private void closeLog() {
        if (logWindow != null) {
            logWindow.dismiss();
            logWindow = null;
        }
    }

    private void ui(Runnable r) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        activity.runOnUiThread(() -> {
            if (dialog.isShowing()) r.run();
        });
    }

    private int titleOf(WifiGuestSetup.Step step) {
        switch (step) {
            case ADAPTER:     return R.string.wifi_guest_step_adapter;
            case DEVICE:      return R.string.wifi_guest_step_device;
            case ENGINE:      return R.string.wifi_guest_step_engine;
            case FILES:       return R.string.wifi_guest_step_files;
            case BOOT:        return R.string.wifi_guest_step_boot;
            case PASSTHROUGH: return R.string.wifi_guest_step_passthrough;
            default:          return R.string.wifi_guest_step_interface;
        }
    }
}
