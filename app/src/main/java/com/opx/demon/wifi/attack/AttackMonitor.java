package com.opx.demon.wifi.attack;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.opx.demon.R;
import com.opx.demon.utils.Core;
import com.opx.demon.utils.SparklineView;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AttackMonitor {

    private static final int CONSOLE_MEMORY = 10000;

    private static final int CONSOLE_VISIBLE = 200;

    private static final long PUMP_MS = 150L;

    private static final int MAX_CLIENTS = 512;

    private static final Pattern MAC = Pattern.compile("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}");
    private static final Pattern WPS_MESSAGE = Pattern.compile("WPS Message M(\\d)(D?)");
    private static final Pattern DIGITS = Pattern.compile("\\d{4,8}");
    private static final Pattern ACK_PAIR = Pattern.compile("\\[\\s*(\\d+)\\s*\\|\\s*(\\d+)\\s+ACKs?\\s*]");
    private static final Pattern MDK4_SENT = Pattern.compile("Packets sent:\\s*(\\d+)");
    private static final Pattern MDK4_SPEED = Pattern.compile("Speed:\\s*(\\d+)\\s*packets/sec");

    private final Activity activity;
    private final Core core;
    private final AttackKind kind;
    private final Dialog dialog;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int accent;
    private final int greenColor;
    private final int redColor;
    private final int yellowColor;
    private final int greyColor;

    private final Object lock = new Object();

    private final EnumMap<AttackStage, StageRow> stageRows = new EnumMap<>(AttackStage.class);
    private final EnumMap<AttackMetric, MetricChip> metricChips = new EnumMap<>(AttackMetric.class);

    private final ArrayDeque<String> console = new ArrayDeque<>();
    private final Set<String> clients = new HashSet<>();

    private final TextView titleView;
    private final TextView targetView;
    private final TextView metaView;
    private final TextView pillView;
    private final TextView consoleView;
    private final TextView consoleCount;
    private final ScrollView consoleScroll;
    private final ImageView consoleCopy;
    private final LinearLayout tabBar;
    private final TextView tabProgress;
    private final TextView tabTerminal;
    private final TextView resultView;
    private final MaterialButton stopButton;
    private final MaterialButton primaryButton;
    private final MaterialButton secondaryButton;
    private final SparklineView rateView;
    private final ScrollView bodyScroll;

    private final long startedAt = SystemClock.elapsedRealtime();

    private Runnable onStop;
    private boolean consoleOpen;
    private volatile boolean dirty;
    private volatile boolean consoleDirty;
    private volatile boolean finished;
    private volatile boolean dead;
    private long lastElapsedAt;

    private int associations;
    private int pinsTried;
    private int bursts;
    private float rateCeiling = 1f;
    private float pendingRate = -1f;
    private long rateWindowStart;
    private int rateWindowCount;

    private AttackMonitor(Activity activity, Core core, AttackKind kind) {
        this.activity = activity;
        this.core = core;
        this.kind = kind;
        this.accent = ContextCompat.getColor(activity, kind.accentRes);
        this.greenColor = ContextCompat.getColor(activity, R.color.green);
        this.redColor = ContextCompat.getColor(activity, R.color.red);
        this.yellowColor = ContextCompat.getColor(activity, R.color.yellow);
        this.greyColor = ContextCompat.getColor(activity, R.color.grey);

        dialog = new Dialog(activity);
        dialog.setContentView(R.layout.wifi_attack_monitor);
        dialog.setCancelable(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }

        titleView = dialog.findViewById(R.id.attack_title);
        targetView = dialog.findViewById(R.id.attack_target);
        metaView = dialog.findViewById(R.id.attack_meta);
        pillView = dialog.findViewById(R.id.attack_pill);
        consoleView = dialog.findViewById(R.id.attack_console);
        consoleCount = dialog.findViewById(R.id.attack_console_count);
        consoleScroll = dialog.findViewById(R.id.attack_console_scroll);
        consoleCopy = dialog.findViewById(R.id.attack_console_copy);
        tabBar = dialog.findViewById(R.id.attack_tabbar);
        tabProgress = dialog.findViewById(R.id.attack_tab_progress);
        tabTerminal = dialog.findViewById(R.id.attack_tab_terminal);
        resultView = dialog.findViewById(R.id.attack_result);
        stopButton = dialog.findViewById(R.id.attack_stop);
        primaryButton = dialog.findViewById(R.id.attack_primary);
        secondaryButton = dialog.findViewById(R.id.attack_secondary);
        rateView = dialog.findViewById(R.id.attack_rate);
        bodyScroll = dialog.findViewById(R.id.attack_body_scroll);

        titleView.setText(kind.title);
        ImageView icon = dialog.findViewById(R.id.attack_icon);
        icon.setImageResource(kind.icon);
        icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
        tintPill(accent, "RUNNING");

        buildStages();
        buildMetrics();

        if (kind.rateLabel != null) {
            dialog.findViewById(R.id.attack_rate_block).setVisibility(View.VISIBLE);
            ((TextView) dialog.findViewById(R.id.attack_rate_label)).setText(kind.rateLabel);
            rateView.setAccent(accent);
        }

        sizeBody();
        buildTabs();
        consoleCopy.setOnClickListener(v -> {
            core.copyToClipBoard(consoleDump());
            core.toaster("Console copied");
        });
        consoleScroll.setOnTouchListener((v, e) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });

        stopButton.setOnClickListener(v -> {
            if (finished) {
                dismiss();
                return;
            }
            if (onStop != null) onStop.run();
        });

        pump();
    }

    public static AttackMonitor open(Activity activity, Core core, AttackKind kind,
                                     String target, String meta) {
        AttackMonitor monitor = new AttackMonitor(activity, core, kind);
        monitor.target(target, meta);
        monitor.dialog.show();
        return monitor;
    }

    public AttackKind kind() {
        return kind;
    }

    public boolean alive() {
        return !dead && !activity.isFinishing();
    }

    public AttackMonitor onStop(Runnable r) {
        this.onStop = r;
        return this;
    }

    public void target(String target, String meta) {
        ui(() -> {
            targetView.setText(target == null ? "" : target);
            if (meta == null || meta.isEmpty()) {
                metaView.setVisibility(View.GONE);
            } else {
                metaView.setVisibility(View.VISIBLE);
                metaView.setText(meta);
            }
        });
    }

    public void stage(AttackStage stage, AttackStage.State state) {
        stage(stage, state, null);
    }

    public void stage(AttackStage stage, AttackStage.State state, String detail) {
        StageRow row = stageRows.get(stage);
        if (row == null) return;
        synchronized (lock) {
            if (state == AttackStage.State.ACTIVE || state == AttackStage.State.DONE) {
                for (AttackStage earlier : kind.stages) {
                    if (earlier == stage) break;
                    StageRow before = stageRows.get(earlier);
                    if (before != null && before.want != AttackStage.State.FAILED
                            && before.want != AttackStage.State.DONE) {
                        before.want = AttackStage.State.DONE;
                    }
                }
            }
            row.want = state;
            if (detail != null) row.wantDetail = detail;
        }
        dirty = true;
    }

    public void metric(AttackMetric metric, String value) {
        MetricChip chip = metricChips.get(metric);
        if (chip == null || value == null) return;
        synchronized (lock) {
            chip.want = value;
            chip.wantColor = chip.defaultColor;
        }
        dirty = true;
    }

    public void metric(AttackMetric metric, int value) {
        metric(metric, String.valueOf(value));
    }

    public void metricColor(AttackMetric metric, int color) {
        MetricChip chip = metricChips.get(metric);
        if (chip == null) return;
        synchronized (lock) {
            chip.wantColor = color;
        }
        dirty = true;
    }

    public void rate(float value) {
        if (kind.rateLabel == null) return;
        synchronized (lock) {
            pendingRate = value;
        }
        dirty = true;
    }

    public void console(String line) {
        if (line == null) return;
        String text = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
        synchronized (console) {
            console.addLast(text);
            while (console.size() > CONSOLE_MEMORY) console.removeFirst();
        }
        consoleDirty = true;
        dirty = true;
    }

    public void note(String text) {
        console("[stryker] " + text);
    }

    private String consoleDump() {
        StringBuilder sb = new StringBuilder();
        synchronized (console) {
            for (String line : console) sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public void finish(boolean ok, String result) {
        if (finished) return;
        finished = true;
        ui(() -> {
            drain();
            dialog.setCancelable(true);
            tintPill(ok ? greenColor : greyColor, ok ? "DONE" : "STOPPED");
            if (result != null && !result.isEmpty()) {
                resultView.setVisibility(View.VISIBLE);
                resultView.setText(result);
                resultView.setTextColor(ok ? greenColor : greyColor);
            }
            synchronized (lock) {
                for (StageRow row : stageRows.values()) {
                    if (row.state == AttackStage.State.ACTIVE) {
                        row.want = ok ? AttackStage.State.DONE : AttackStage.State.PENDING;
                        apply(row);
                    }
                }
            }
            stopButton.setText(android.R.string.ok);
            stopButton.setIconResource(R.drawable.done);
        });
    }

    public void failStage(AttackStage stage, String detail, String result) {
        stage(stage, AttackStage.State.FAILED, detail);
        finish(false, result);
    }

    public void primary(String label, Runnable action) {
        ui(() -> {
            primaryButton.setVisibility(View.VISIBLE);
            primaryButton.setText(label);
            primaryButton.setOnClickListener(v -> action.run());
        });
    }

    public void primaryLabel(String label) {
        ui(() -> primaryButton.setText(label));
    }

    public void secondary(String label, Runnable action) {
        ui(() -> {
            secondaryButton.setVisibility(View.VISIBLE);
            secondaryButton.setText(label);
            secondaryButton.setOnClickListener(v -> action.run());
        });
    }

    public void hideSecondary() {
        ui(() -> secondaryButton.setVisibility(View.GONE));
    }

    public void dismiss() {
        dead = true;
        handler.removeCallbacksAndMessages(null);
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
        }
    }

    public void wps(String line) {
        console(line);
        if (line == null) return;
        String t = line.trim();

        if (t.contains("Unable to up interface") || t.contains("No such device")) {
            stage(AttackStage.RADIO, AttackStage.State.FAILED, "Interface unavailable");
            return;
        }
        if (t.contains("Running wpa_supplicant")) {
            stage(AttackStage.RADIO, AttackStage.State.DONE);
            stage(AttackStage.ASSOC, AttackStage.State.ACTIVE);
            metric(AttackMetric.STATE, "wpa_supplicant up");
            return;
        }
        if (t.contains("Trying PIN")) {
            Matcher m = DIGITS.matcher(t);
            if (m.find()) metric(AttackMetric.PIN, m.group());
            pinsTried++;
            metric(AttackMetric.TRIED, pinsTried);
            stage(AttackStage.WPS, AttackStage.State.ACTIVE);
            metric(AttackMetric.STATE, "Sending pin");
            return;
        }
        if (t.contains("Associating with AP")) {
            associations++;
            metric(AttackMetric.ATTEMPTS, associations);
            metric(AttackMetric.STATE, "Associating");
            stage(AttackStage.ASSOC, AttackStage.State.ACTIVE);
            return;
        }
        if (t.contains("Associated with")) {
            stage(AttackStage.ASSOC, AttackStage.State.DONE);
            stage(AttackStage.WPS, AttackStage.State.ACTIVE);
            metric(AttackMetric.STATE, "Associated");
            return;
        }
        if (t.contains("Authenticated")) {
            metric(AttackMetric.STATE, "Authenticated");
            return;
        }
        if (t.contains("Authenticating")) {
            metric(AttackMetric.STATE, "Authenticating");
            stage(AttackStage.ASSOC, AttackStage.State.ACTIVE);
            return;
        }
        if (t.contains("Scanning")) {
            metric(AttackMetric.STATE, "Scanning");
            return;
        }
        if (t.contains("Identity Request")) {
            metric(AttackMetric.STATE, "Identity request");
            stage(AttackStage.WPS, AttackStage.State.ACTIVE);
            return;
        }
        Matcher msg = WPS_MESSAGE.matcher(t);
        if (msg.find()) {
            String number = msg.group(1);
            boolean m2d = "D".equals(msg.group(2));
            boolean sent = t.contains("Sending");
            if (m2d) {
                metric(AttackMetric.STATE, "M2D — no credentials");
                metricColor(AttackMetric.STATE, yellowColor);
            } else {
                metric(AttackMetric.STATE, "M" + number + (sent ? " sent" : " received"));
                stage(AttackStage.WPS, AttackStage.State.ACTIVE);
            }
            return;
        }
        if (t.contains("Running Pixiewps")) {
            stage(AttackStage.WPS, AttackStage.State.DONE);
            stage(AttackStage.PIXIE, AttackStage.State.ACTIVE);
            metric(AttackMetric.STATE, "Computing the pin");
            return;
        }
        if (t.contains("WPS PIN:")) {
            metric(AttackMetric.PIN, quoted(t));
            metricColor(AttackMetric.PIN, greenColor);
            stage(AttackStage.PIXIE, AttackStage.State.DONE, "Pin recovered");
            stage(AttackStage.WPS, AttackStage.State.DONE);
            return;
        }
        if (t.contains("WPA PSK:")) {
            metric(AttackMetric.STATE, "Passphrase recovered");
            metricColor(AttackMetric.STATE, greenColor);
            return;
        }
        if (t.contains("not vulnerable") || t.contains("pin not found")) {
            stage(AttackStage.PIXIE, AttackStage.State.FAILED, "No pin from the exchange");
            return;
        }
        if (t.contains("wps_locked") || t.contains("locked")) {
            metric(AttackMetric.LOCK, "Locked");
            metricColor(AttackMetric.LOCK, redColor);
            return;
        }
        if (t.contains("NACK")) {
            metric(AttackMetric.STATE, "WSC NACK");
            metricColor(AttackMetric.STATE, yellowColor);
        }
    }

    public void aireplay(String line) {
        console(line);
        if (line == null) return;
        String t = line.trim();

        if (t.contains("Waiting for beacon frame")) {
            metric(AttackMetric.STATE, "Waiting for a beacon");
            stage(AttackStage.INJECT, AttackStage.State.ACTIVE);
            stage(AttackStage.DEAUTH, AttackStage.State.ACTIVE);
            return;
        }
        if (t.contains("No such BSSID available") || t.contains("is not available")) {
            metric(AttackMetric.STATE, "Target not on this channel");
            metricColor(AttackMetric.STATE, redColor);
            return;
        }
        if (t.contains("Sending") && t.contains("DeAuth")) {
            bursts++;
            metric(AttackMetric.FRAMES, bursts);
            metric(AttackMetric.STATE, "Injecting");
            stage(AttackStage.INJECT, AttackStage.State.ACTIVE);
            stage(AttackStage.DEAUTH, AttackStage.State.ACTIVE);
            countRate();
        }
    }

    public void mdk4(String line) {
        console(line);
        if (line == null) return;
        Matcher sent = MDK4_SENT.matcher(line);
        if (sent.find()) {
            metric(AttackMetric.FRAMES, sent.group(1));
            stage(AttackStage.INJECT, AttackStage.State.ACTIVE);
            metric(AttackMetric.STATE, "Flooding");
        }
        Matcher speed = MDK4_SPEED.matcher(line);
        if (speed.find()) {
            int pps = Integer.parseInt(speed.group(1));
            metric(AttackMetric.RATE, pps + "/s");
            rate(pps);
        }
    }

    public void airodump(String line, String bssid) {
        if (line == null) return;
        String t = line.trim();

        if (t.contains("WPA handshake")) {
            stage(AttackStage.EAPOL, AttackStage.State.DONE, "4-way handshake");
            metric(AttackMetric.STATE, "Handshake captured");
            metricColor(AttackMetric.STATE, greenColor);
            return;
        }
        if (t.contains("PMKID")) {
            stage(AttackStage.EAPOL, AttackStage.State.DONE, "PMKID");
            metric(AttackMetric.STATE, "PMKID captured");
            metricColor(AttackMetric.STATE, greenColor);
            return;
        }
        if (bssid == null) return;
        int seen = -1;
        Matcher m = MAC.matcher(t);
        while (m.find()) {
            String mac = m.group();
            if (mac.equalsIgnoreCase(bssid)) continue;
            boolean fresh;
            synchronized (clients) {
                fresh = clients.size() < MAX_CLIENTS && clients.add(mac.toUpperCase(Locale.ROOT));
                if (fresh) seen = clients.size();
            }
            if (fresh) note("Client " + (core.getBoolean("hide") ? Core.HIDDEN_MAC : mac));
        }
        if (seen >= 0) metric(AttackMetric.CLIENTS, seen);
    }

    public java.util.List<String> clientList() {
        synchronized (clients) {
            return new java.util.ArrayList<>(clients);
        }
    }

    public int clientCount() {
        synchronized (clients) {
            return clients.size();
        }
    }

    private void countRate() {
        long now = SystemClock.elapsedRealtime();
        if (rateWindowStart == 0L) rateWindowStart = now;
        rateWindowCount++;
        if (now - rateWindowStart >= 1000L) {
            float perSecond = rateWindowCount * 1000f / (now - rateWindowStart);
            rate(perSecond);
            metric(AttackMetric.RATE, String.format(Locale.US, "%.0f/s", perSecond));
            rateWindowStart = now;
            rateWindowCount = 0;
        }
    }

    private void buildStages() {
        LinearLayout container = dialog.findViewById(R.id.attack_stages);
        LayoutInflater inflater = LayoutInflater.from(activity);
        for (int i = 0; i < kind.stages.length; i++) {
            AttackStage stage = kind.stages[i];
            View row = inflater.inflate(R.layout.wifi_attack_stage, container, false);
            StageRow handles = new StageRow(
                    row.findViewById(R.id.stage_title),
                    row.findViewById(R.id.stage_detail),
                    row.findViewById(R.id.stage_icon),
                    row.findViewById(R.id.stage_spinner),
                    row.findViewById(R.id.stage_indicator),
                    row.findViewById(R.id.stage_rail));
            handles.title.setText(stage.label);
            if (i == kind.stages.length - 1) handles.rail.setVisibility(View.INVISIBLE);
            apply(handles);
            stageRows.put(stage, handles);
            container.addView(row);
        }
    }

    private void buildMetrics() {
        LinearLayout container = dialog.findViewById(R.id.attack_metrics);
        LayoutInflater inflater = LayoutInflater.from(activity);
        LinearLayout row = null;
        for (int i = 0; i < kind.metrics.length; i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                row.setLayoutParams(lp);
                container.addView(row);
            }
            AttackMetric metric = kind.metrics[i];
            View chip = inflater.inflate(R.layout.wifi_attack_metric, row, false);
            if (i % 2 == 1) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) chip.getLayoutParams();
                lp.setMarginStart(dp(6));
                chip.setLayoutParams(lp);
            }
            ImageView icon = chip.findViewById(R.id.metric_icon);
            icon.setImageResource(metric.icon);
            icon.setColorFilter(accent, PorterDuff.Mode.SRC_IN);
            ((TextView) chip.findViewById(R.id.metric_label)).setText(metric.label);
            TextView value = chip.findViewById(R.id.metric_value);
            value.setText("—");
            metricChips.put(metric, new MetricChip(value));
            row.addView(chip);
        }
        if (kind.metrics.length % 2 == 1 && row != null) {
            View spacer = new View(activity);
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            row.addView(spacer);
        }
    }

    private void sizeBody() {
        View body = dialog.findViewById(R.id.attack_body);
        if (body == null) return;
        int screen = activity.getResources().getDisplayMetrics().heightPixels;
        int height = Math.max(dp(300), Math.min(Math.round(screen * 0.60f), dp(620)));
        ViewGroup.LayoutParams lp = body.getLayoutParams();
        if (lp == null) return;
        lp.height = height;
        body.setLayoutParams(lp);
    }

    private void buildTabs() {
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(dp(13));
        track.setColor((accent & 0x00FFFFFF) | 0x16000000);
        tabBar.setBackground(track);
        tabProgress.setOnClickListener(v -> showConsole(false));
        tabTerminal.setOnClickListener(v -> showConsole(true));
        showConsole(false);
    }

    private void paintTab(TextView tab, boolean selected) {
        if (selected) {
            GradientDrawable pill = new GradientDrawable();
            pill.setCornerRadius(dp(10));
            pill.setColor(ContextCompat.getColor(activity, R.color.light_contrast));
            pill.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x33000000);
            tab.setBackground(pill);
            tab.setTextColor(accent);
        } else {
            tab.setBackground(null);
            tab.setTextColor(greyColor);
        }
    }

    private void showConsole(boolean show) {
        consoleOpen = show;
        paintTab(tabProgress, !show);
        paintTab(tabTerminal, show);
        consoleScroll.setVisibility(show ? View.VISIBLE : View.GONE);
        bodyScroll.setVisibility(show ? View.GONE : View.VISIBLE);
        consoleCopy.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            consoleDirty = true;
            drain();
        }
    }

    private void pump() {
        if (dead) return;
        handler.postDelayed(this::pump, PUMP_MS);

        long now = SystemClock.elapsedRealtime();
        if (!finished && now - lastElapsedAt >= 1000L) {
            lastElapsedAt = now;
            long seconds = (now - startedAt) / 1000L;
            metric(AttackMetric.ELAPSED,
                    String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60));
        }
        if (!dirty) return;
        drain();
    }

    private void drain() {
        dirty = false;
        synchronized (lock) {
            for (StageRow row : stageRows.values()) {
                boolean detailChanged = row.wantDetail != null
                        && !row.wantDetail.equals(row.shownDetail);
                if (row.want != row.state || detailChanged) apply(row);
            }
            for (MetricChip chip : metricChips.values()) {
                if (chip.want != null && !chip.want.equals(chip.shown)) {
                    chip.shown = chip.want;
                    chip.value.setText(chip.want);
                }
                if (chip.wantColor != chip.shownColor) {
                    chip.shownColor = chip.wantColor;
                    chip.value.setTextColor(chip.wantColor);
                }
            }
            if (pendingRate >= 0f) {
                if (pendingRate > rateCeiling) rateCeiling = pendingRate;
                rateView.push(rateCeiling <= 0f ? 0f : pendingRate / rateCeiling);
                pendingRate = -1f;
            }
        }
        if (consoleDirty) {
            consoleDirty = false;
            int size;
            synchronized (console) {
                size = console.size();
            }
            consoleCount.setText(size + (size == 1 ? " line" : " lines"));
            if (consoleOpen) {
                consoleView.setText(visibleConsole());
                consoleScroll.post(() -> consoleScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }

    private String visibleConsole() {
        StringBuilder sb = new StringBuilder();
        synchronized (console) {
            int skip = Math.max(0, console.size() - CONSOLE_VISIBLE);
            int i = 0;
            for (String line : console) {
                if (i++ < skip) continue;
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private void apply(StageRow row) {
        row.state = row.want;
        row.shownDetail = row.wantDetail;
        int color;
        switch (row.state) {
            case ACTIVE:
                color = accent;
                row.spinner.setVisibility(View.VISIBLE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, Typeface.BOLD);
                break;
            case DONE:
                color = greenColor;
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.done);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, Typeface.NORMAL);
                break;
            case FAILED:
                color = redColor;
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.VISIBLE);
                row.icon.setImageResource(R.drawable.error);
                row.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
                row.title.setTypeface(null, Typeface.BOLD);
                break;
            case PENDING:
            default:
                color = greyColor;
                row.spinner.setVisibility(View.GONE);
                row.icon.setVisibility(View.GONE);
                row.title.setTypeface(null, Typeface.NORMAL);
                break;
        }
        row.title.setTextColor(color);
        if (row.indicator.getBackground() != null) {
            row.indicator.getBackground().mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            row.indicator.getBackground().setAlpha(60);
        }
        row.rail.setBackgroundColor(row.state == AttackStage.State.DONE
                ? (color & 0x00FFFFFF) | 0x66000000
                : 0x22808080);
        if (row.shownDetail != null && !row.shownDetail.isEmpty()) {
            row.detail.setVisibility(View.VISIBLE);
            row.detail.setText(row.shownDetail);
        } else {
            row.detail.setVisibility(View.GONE);
        }
    }

    private void tintPill(int color, String text) {
        pillView.setText(text);
        pillView.setTextColor(color);
        if (pillView.getBackground() instanceof GradientDrawable) {
            GradientDrawable bg = (GradientDrawable) pillView.getBackground().mutate();
            bg.setColor((color & 0x00FFFFFF) | 0x22000000);
            bg.setStroke(dp(1), (color & 0x00FFFFFF) | 0x44000000);
            pillView.setBackground(bg);
        }
    }

    private static String quoted(String line) {
        int colon = line.indexOf(':');
        String tail = colon >= 0 ? line.substring(colon + 1) : line;
        return tail.replace("'", "").trim();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void ui(Runnable r) {
        if (dead) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            handler.post(() -> {
                if (!dead) r.run();
            });
        }
    }

    private static final class StageRow {
        final TextView title;
        final TextView detail;
        final ImageView icon;
        final ProgressBar spinner;
        final FrameLayout indicator;
        final View rail;

        AttackStage.State state = AttackStage.State.PENDING;
        AttackStage.State want = AttackStage.State.PENDING;
        String shownDetail;
        String wantDetail;

        StageRow(TextView title, TextView detail, ImageView icon, ProgressBar spinner,
                 FrameLayout indicator, View rail) {
            this.title = title;
            this.detail = detail;
            this.icon = icon;
            this.spinner = spinner;
            this.indicator = indicator;
            this.rail = rail;
        }
    }

    private static final class MetricChip {
        final TextView value;
        final int defaultColor;
        String shown;
        String want;
        int shownColor;
        int wantColor;

        MetricChip(TextView value) {
            this.value = value;
            this.defaultColor = value.getCurrentTextColor();
            this.shownColor = defaultColor;
            this.wantColor = defaultColor;
        }
    }
}
