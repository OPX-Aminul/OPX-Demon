package com.opx.demon.wifi.attack;

public enum AttackStage {

    RADIO("Freeing the radio"),
    ASSOC("Associating with the AP"),
    WPS("WPS exchange"),
    PIXIE("Pixie-dust computation"),
    PINS("Collecting pin candidates"),
    SWEEP("Working through the targets"),
    WORDLIST("Reading the wordlist"),
    TRY("Trying candidates"),
    MONITOR("Monitor mode"),
    CAPTURE("Capturing the channel"),
    TARGET("Target on the air"),
    DEAUTH("Deauthenticating clients"),
    EAPOL("Handshake / PMKID"),
    SAVE("Saving the capture"),
    INJECT("Injecting frames");

    public final String label;

    AttackStage(String label) {
        this.label = label;
    }

    public enum State {
        PENDING,
        ACTIVE,
        DONE,
        FAILED
    }
}
