package com.opx.demon.wordlists;

import com.opx.demon.R;

import java.util.Locale;

public enum WordlistCategory {

    PASSWORD("password", "Passwords",  R.drawable.password,  0xFF1565C0),
    WIFI    ("wifi",     "Wi-Fi PSK",  R.drawable.wifi,      0xFF00897B),
    PIN     ("pin",      "PIN codes",  R.drawable.fiber_pin, 0xFFEF6C00),
    USERNAME("username", "Usernames",  R.drawable.name,      0xFFAB47BC),
    WEB     ("web",      "Web paths",  R.drawable.web,       0xFF3949AB),
    OTHER   ("other",    "Other",      R.drawable.file,      0xFF757575);

    public final String id;
    public final String title;
    public final int iconRes;
    public final int color;

    WordlistCategory(String id, String title, int iconRes, int color) {
        this.id = id;
        this.title = title;
        this.iconRes = iconRes;
        this.color = color;
    }

    public static WordlistCategory byId(String id) {
        if (id != null) {
            for (WordlistCategory c : values()) {
                if (c.id.equalsIgnoreCase(id)) return c;
            }
        }
        return OTHER;
    }

    public static WordlistCategory detectFromName(String name) {
        if (name == null) return OTHER;
        String n = name.toLowerCase(Locale.US);
        if (n.contains("pin")) return PIN;
        if (n.contains("wpa") || n.contains("wifi") || n.contains("wi-fi")
                || n.contains("psk") || n.contains("router")) return WIFI;
        if (n.contains("user") || n.contains("login") || n.contains("names")) return USERNAME;
        if (n.contains("dir") || n.contains("path") || n.contains("subdomain")
                || n.contains("vhost") || n.contains("fuzz") || n.contains("endpoint")) return WEB;
        if (n.contains("pass") || n.contains("rockyou") || n.contains("wordlist")
                || n.contains("credential")) return PASSWORD;
        return OTHER;
    }

    public static WordlistCategory detectFromSample(java.util.List<String> sample) {
        if (sample == null || sample.isEmpty()) return OTHER;
        int numeric = 0, shortLines = 0, withSlash = 0, total = 0;
        for (String line : sample) {
            String s = line.trim();
            if (s.isEmpty()) continue;
            total++;
            if (s.matches("\\d{4,8}")) numeric++;
            if (s.length() < 8) shortLines++;
            if (s.startsWith("/") || s.contains("/")) withSlash++;
        }
        if (total == 0) return OTHER;
        if (numeric == total) return PIN;
        if (withSlash * 2 > total) return WEB;
        if (shortLines * 2 > total) return USERNAME;
        return PASSWORD;
    }
}
