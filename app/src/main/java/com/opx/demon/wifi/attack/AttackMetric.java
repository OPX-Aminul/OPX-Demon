package com.opx.demon.wifi.attack;

import com.opx.demon.R;

public enum AttackMetric {

    ELAPSED("Elapsed", R.drawable.timer),
    STATE("State", R.drawable.alt_route),
    ATTEMPTS("Associations", R.drawable.repeat),
    LOCK("WPS registrar", R.drawable.lock_closed),
    PIN("Pin", R.drawable.fiber_pin),
    PSK("Passphrase", R.drawable.key),
    CANDIDATE("Trying", R.drawable.key),
    TRIED("Tried", R.drawable.repeat),
    PROGRESS("Progress", R.drawable.analytics),
    LEFT("Left", R.drawable.number),
    RATE("Rate", R.drawable.bolt),
    ETA("Time left", R.drawable.timer),
    CHANNEL("Channel", R.drawable.wifi_channel),
    CLIENTS("Clients", R.drawable.devices),
    FRAMES("Deauth bursts", R.drawable.deauth),
    NETWORKS("Networks", R.drawable.latest_wifi),
    CAPTURED("Handshakes", R.drawable.handshake_interface),
    TARGET("Target", R.drawable.router),
    CRACKED("Cracked", R.drawable.done),
    TIMEOUT("Timeout", R.drawable.timer),
    IFACE("Interface", R.drawable.lan),
    CAPTURE("Capture", R.drawable.file);

    public final String label;
    public final int icon;

    AttackMetric(String label, int icon) {
        this.label = label;
        this.icon = icon;
    }
}
