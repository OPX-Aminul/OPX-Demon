package com.opx.demon.wordlists;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class WordlistCatalog {

    private WordlistCatalog() {}

    public static class Entry {
        public final String id;
        public final String title;
        public final String description;
        public final WordlistCategory category;
        public final String url;
        public final String fileName;
        public final long approxBytes;
        public final long approxLines;

        Entry(String id, String title, String description, WordlistCategory category,
              String url, String fileName, long approxBytes, long approxLines) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.category = category;
            this.url = url;
            this.fileName = fileName;
            this.approxBytes = approxBytes;
            this.approxLines = approxLines;
        }

        public String describeSize() {
            return Wordlist.humanBytes(approxBytes);
        }
    }

    private static final String SECLISTS =
            "https://raw.githubusercontent.com/danielmiessler/SecLists/master/";

    public static List<Entry> entries() {
        return Arrays.asList(
                new Entry("top1k", "Top 1 000 passwords",
                        "The first thing to try after the built-in lists. Runs in seconds.",
                        WordlistCategory.PASSWORD,
                        SECLISTS + "Passwords/Common-Credentials/10-million-password-list-top-1000.txt",
                        "top-1000-passwords.txt", 8_000, 1_000),

                new Entry("top10k", "Top 10 000 passwords",
                        "The usual default for a first real pass.",
                        WordlistCategory.PASSWORD,
                        SECLISTS + "Passwords/Common-Credentials/10-million-password-list-top-10000.txt",
                        "top-10000-passwords.txt", 82_000, 10_000),

                new Entry("top100k", "Top 100 000 passwords",
                        "Ranked by frequency, so a run cut short has still tried the likely ones.",
                        WordlistCategory.PASSWORD,
                        SECLISTS + "Passwords/Common-Credentials/10-million-password-list-top-100000.txt",
                        "top-100000-passwords.txt", 850_000, 100_000),

                new Entry("top1m", "Top 1 000 000 passwords",
                        "Large. Worth having on the device before you need it, not during.",
                        WordlistCategory.PASSWORD,
                        SECLISTS + "Passwords/Common-Credentials/10-million-password-list-top-1000000.txt",
                        "top-1000000-passwords.txt", 8_500_000, 1_000_000),

                new Entry("wpa4800", "Probable WPA passphrases",
                        "Already filtered to the 8-character WPA minimum — nothing here is wasted.",
                        WordlistCategory.WIFI,
                        SECLISTS + "Passwords/WiFi-WPA/probable-v2-wpa-top4800.txt",
                        "probable-wpa-top4800.txt", 48_000, 4_800),

                new Entry("wpa62k", "Probable WPA passphrases (large)",
                        "The extended WPA set. Still length-filtered.",
                        WordlistCategory.WIFI,
                        SECLISTS + "Passwords/WiFi-WPA/probable-v2-wpa-top62.txt",
                        "probable-wpa-top62k.txt", 620_000, 62_000),

                new Entry("users-short", "Common usernames",
                        "Short login-name list for services that need one.",
                        WordlistCategory.USERNAME,
                        SECLISTS + "Usernames/top-usernames-shortlist.txt",
                        "top-usernames.txt", 1_000, 17),

                new Entry("users-long", "Usernames (extended)",
                        "Several thousand names, for when the short list misses.",
                        WordlistCategory.USERNAME,
                        SECLISTS + "Usernames/xato-net-10-million-usernames-dup.txt",
                        "usernames-extended.txt", 8_000_000, 1_000_000),

                new Entry("web-common", "Web content: common paths",
                        "The standard directory list for a first web sweep.",
                        WordlistCategory.WEB,
                        SECLISTS + "Discovery/Web-Content/common.txt",
                        "web-common.txt", 40_000, 4_700),

                new Entry("web-big", "Web content: big list",
                        "Larger directory and file list.",
                        WordlistCategory.WEB,
                        SECLISTS + "Discovery/Web-Content/big.txt",
                        "web-big.txt", 180_000, 20_500),

                new Entry("router-creds", "Default router credentials",
                        "Vendor defaults, as user:password pairs.",
                        WordlistCategory.PASSWORD,
                        SECLISTS + "Passwords/Default-Credentials/default-passwords.csv",
                        "default-router-credentials.csv", 90_000, 1_900)
        );
    }

    public static List<Entry> byCategory(WordlistCategory category) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries()) if (e.category == category) out.add(e);
        return out;
    }

    public static Entry byId(String id) {
        for (Entry e : entries()) if (e.id.equals(id)) return e;
        return null;
    }
}
