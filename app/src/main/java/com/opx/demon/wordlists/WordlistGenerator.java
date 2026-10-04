package com.opx.demon.wordlists;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public class WordlistGenerator {

    public interface Progress {
        void onProgress(long produced, long estimatedTotal);
        boolean isCancelled();
    }

    public static final long DEFAULT_LIMIT = 5_000_000L;

    public static class Preset {
        public final String id;
        public final String title;
        public final String description;
        public final WordlistCategory category;
        public final String fileName;
        public final long approxLines;

        Preset(String id, String title, String description, WordlistCategory category,
               String fileName, long approxLines) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.category = category;
            this.fileName = fileName;
            this.approxLines = approxLines;
        }
    }

    public static List<Preset> presets() {
        return Arrays.asList(
                new Preset("top10", "Top 10 passwords",
                        "The ten most common passwords in every published dump. Seconds to run.",
                        WordlistCategory.PASSWORD, "top-10-passwords.txt", 10),
                new Preset("top250", "Top 250 passwords",
                        "Ranked most-common first. The sensible first attempt before anything larger.",
                        WordlistCategory.PASSWORD, "top-250-passwords.txt", 250),
                new Preset("pins20", "Top 20 PINs",
                        "The ranked head of the PIN distribution — 1234, 1111, 0000 and the rest.",
                        WordlistCategory.PIN, "top-20-pins.txt", 20),
                new Preset("pins250", "Top 250 PINs",
                        "Ranked head plus dates, runs and repeated pairs.",
                        WordlistCategory.PIN, "top-250-pins.txt", 250),
                new Preset("pins4all", "All 4-digit PINs",
                        "All 10 000, ordered by how often each actually turns up.",
                        WordlistCategory.PIN, "all-4-digit-pins.txt", 10000),
                new Preset("wifi", "Wi-Fi defaults",
                        "Router and hotspot defaults that satisfy the 8-character WPA minimum.",
                        WordlistCategory.WIFI, "wifi-defaults.txt", 35),
                new Preset("users", "Common usernames",
                        "Service and login names, ranked.",
                        WordlistCategory.USERNAME, "common-usernames.txt", 35),
                new Preset("walks", "Keyboard walks",
                        "Patterns a charset rule never produces and everyone types anyway.",
                        WordlistCategory.PASSWORD, "keyboard-walks.txt", 30)
        );
    }

    public static Preset presetById(String id) {
        for (Preset p : presets()) if (p.id.equals(id)) return p;
        return null;
    }

    public static Wordlist writePreset(WordlistStore store, Preset preset) {
        List<String> lines;
        switch (preset.id) {
            case "top10":   lines = Arrays.asList(BuiltinLists.TOP_10_PASSWORDS); break;
            case "top250":  lines = Arrays.asList(BuiltinLists.TOP_250_PASSWORDS); break;
            case "pins20":  lines = Arrays.asList(BuiltinLists.TOP_20_PINS); break;
            case "pins250": lines = BuiltinLists.top250Pins(); break;
            case "pins4all":lines = BuiltinLists.allFourDigitPins(); break;
            case "wifi":    lines = Arrays.asList(BuiltinLists.WIFI_DEFAULTS); break;
            case "users":   lines = Arrays.asList(BuiltinLists.COMMON_USERNAMES); break;
            case "walks":   lines = BuiltinLists.keyboardWalks(); break;
            default: return null;
        }
        Wordlist wl = store.create(preset.fileName, preset.category, Wordlist.Origin.GENERATED);
        wl.note = preset.title;
        long n = 0;
        try (BufferedWriter w = store.writer(wl, false)) {
            for (String line : lines) {
                w.write(line);
                w.write('\n');
                n++;
            }
        } catch (IOException e) {
            wl.file.delete();
            return null;
        }
        store.finish(wl, n);
        return wl;
    }

    public enum Mode { CHARSET, MASK, NUMERIC, MUTATE }

    public static final String DIGITS = "0123456789";
    public static final String LOWER = "abcdefghijklmnopqrstuvwxyz";
    public static final String UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final String SPECIAL = "!@#$%^&*()-_=+.,?";

    public static class Spec {
        public Mode mode = Mode.CHARSET;
        public String name = "generated.txt";
        public WordlistCategory category = WordlistCategory.PASSWORD;
        public long limit = DEFAULT_LIMIT;

        public String charset = DIGITS;
        public int minLength = 4;
        public int maxLength = 4;

        public String mask = "?d?d?d?d";

        public long from = 0;
        public long to = 9999;
        public int pad = 4;

        public Wordlist source;
        public boolean mutCapitalize = true;
        public boolean mutUpper = false;
        public boolean mutLeet = false;
        public boolean mutReverse = false;
        public boolean mutAppendDigits = false;
        public boolean mutAppendYears = false;
        public String prefix = "";
        public String suffix = "";

        public boolean wpaLengthOnly = false;
    }

    public static long estimate(Spec spec) {
        switch (spec.mode) {
            case CHARSET: {
                if (spec.charset == null || spec.charset.isEmpty()) return 0;
                BigInteger base = BigInteger.valueOf(spec.charset.length());
                BigInteger total = BigInteger.ZERO;
                for (int len = spec.minLength; len <= spec.maxLength; len++) {
                    total = total.add(base.pow(len));
                    if (total.bitLength() > 62) return spec.limit;
                }
                long t = total.longValue();
                return Math.min(t, spec.limit);
            }
            case MASK: {
                List<String> slots = parseMask(spec.mask);
                BigInteger total = BigInteger.ONE;
                for (String slot : slots) {
                    total = total.multiply(BigInteger.valueOf(slot.length()));
                    if (total.bitLength() > 62) return spec.limit;
                }
                return Math.min(total.longValue(), spec.limit);
            }
            case NUMERIC:
                return Math.min(Math.max(0, spec.to - spec.from + 1), spec.limit);
            default:
                return -1;
        }
    }

    public static Wordlist generate(WordlistStore store, Spec spec, Progress progress) {
        Wordlist wl = store.create(spec.name, spec.category, Wordlist.Origin.GENERATED);
        long estimated = estimate(spec);
        long written = 0;
        try (BufferedWriter w = store.writer(wl, false)) {
            switch (spec.mode) {
                case CHARSET: written = charset(w, spec, progress, estimated); break;
                case MASK:    written = mask(w, spec, progress, estimated); break;
                case NUMERIC: written = numeric(w, spec, progress, estimated); break;
                case MUTATE:  written = mutate(w, spec, progress); break;
            }
        } catch (IOException e) {
            wl.file.delete();
            return null;
        }
        if (progress != null && progress.isCancelled() && written == 0) {
            wl.file.delete();
            return null;
        }
        store.finish(wl, written);
        return wl;
    }

    private static long charset(BufferedWriter w, Spec spec, Progress p, long estimated)
            throws IOException {
        char[] alphabet = spec.charset.toCharArray();
        long written = 0;
        for (int len = spec.minLength; len <= spec.maxLength; len++) {
            int[] idx = new int[len];
            char[] buf = new char[len];
            while (true) {
                if (cancelled(p) || written >= spec.limit) return written;
                for (int i = 0; i < len; i++) buf[i] = alphabet[idx[i]];
                String candidate = new String(buf);
                if (accept(candidate, spec)) {
                    w.write(candidate);
                    w.write('\n');
                    written++;
                    report(p, written, estimated);
                }
                int pos = len - 1;
                while (pos >= 0 && ++idx[pos] == alphabet.length) {
                    idx[pos] = 0;
                    pos--;
                }
                if (pos < 0) break;
            }
        }
        return written;
    }

    private static long mask(BufferedWriter w, Spec spec, Progress p, long estimated)
            throws IOException {
        List<String> slots = parseMask(spec.mask);
        if (slots.isEmpty()) return 0;
        int len = slots.size();
        int[] idx = new int[len];
        char[] buf = new char[len];
        long written = 0;
        while (true) {
            if (cancelled(p) || written >= spec.limit) return written;
            for (int i = 0; i < len; i++) buf[i] = slots.get(i).charAt(idx[i]);
            String candidate = new String(buf);
            if (accept(candidate, spec)) {
                w.write(candidate);
                w.write('\n');
                written++;
                report(p, written, estimated);
            }
            int pos = len - 1;
            while (pos >= 0 && ++idx[pos] == slots.get(pos).length()) {
                idx[pos] = 0;
                pos--;
            }
            if (pos < 0) return written;
        }
    }

    private static long numeric(BufferedWriter w, Spec spec, Progress p, long estimated)
            throws IOException {
        long written = 0;
        String format = spec.pad > 0 ? "%0" + spec.pad + "d" : "%d";
        for (long v = spec.from; v <= spec.to; v++) {
            if (cancelled(p) || written >= spec.limit) return written;
            String candidate = String.format(Locale.US, format, v);
            if (accept(candidate, spec)) {
                w.write(candidate);
                w.write('\n');
                written++;
                report(p, written, estimated);
            }
        }
        return written;
    }

    private static long mutate(BufferedWriter w, Spec spec, Progress p) throws IOException {
        if (spec.source == null) return 0;
        long written = 0;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(spec.source.file), "UTF-8"), 1 << 16)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (cancelled(p) || written >= spec.limit) return written;
                String base = line.trim();
                if (base.isEmpty()) continue;
                for (String variant : variantsOf(base, spec)) {
                    if (written >= spec.limit) return written;
                    String candidate = spec.prefix + variant + spec.suffix;
                    if (accept(candidate, spec)) {
                        w.write(candidate);
                        w.write('\n');
                        written++;
                        report(p, written, -1);
                    }
                }
            }
        }
        return written;
    }

    private static List<String> variantsOf(String base, Spec spec) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(base);
        if (spec.mutCapitalize && base.length() > 0) {
            out.add(Character.toUpperCase(base.charAt(0)) + base.substring(1));
        }
        if (spec.mutUpper) out.add(base.toUpperCase(Locale.US));
        if (spec.mutLeet) out.add(leet(base));
        if (spec.mutReverse) out.add(new StringBuilder(base).reverse().toString());
        if (spec.mutAppendDigits) {
            for (int i = 0; i <= 9; i++) out.add(base + i);
            out.add(base + "123");
            out.add(base + "1234");
        }
        if (spec.mutAppendYears) {
            for (int y = 2026; y >= 1990; y--) {
                out.add(base + y);
                out.add(base + String.format(Locale.US, "%02d", y % 100));
            }
        }
        return new ArrayList<>(out);
    }

    private static String leet(String s) {
        return s.replace('a', '4').replace('A', '4')
                .replace('e', '3').replace('E', '3')
                .replace('i', '1').replace('I', '1')
                .replace('o', '0').replace('O', '0')
                .replace('s', '5').replace('S', '5')
                .replace('t', '7').replace('T', '7');
    }

    static List<String> parseMask(String mask) {
        List<String> slots = new ArrayList<>();
        if (mask == null) return slots;
        for (int i = 0; i < mask.length(); i++) {
            char c = mask.charAt(i);
            if (c == '?' && i + 1 < mask.length()) {
                char kind = mask.charAt(++i);
                switch (kind) {
                    case 'd': slots.add(DIGITS); break;
                    case 'l': slots.add(LOWER); break;
                    case 'u': slots.add(UPPER); break;
                    case 's': slots.add(SPECIAL); break;
                    case 'a': slots.add(LOWER + UPPER + DIGITS + SPECIAL); break;
                    case '?': slots.add("?"); break;
                    default:  slots.add(String.valueOf(kind)); break;
                }
            } else {
                slots.add(String.valueOf(c));
            }
        }
        return slots;
    }

    private static boolean accept(String candidate, Spec spec) {
        if (!spec.wpaLengthOnly) return true;
        int len = candidate.length();
        return len >= 8 && len <= 63;
    }

    private static boolean cancelled(Progress p) {
        return p != null && p.isCancelled();
    }

    private static void report(Progress p, long written, long estimated) {
        if (p != null && (written & 0x3FF) == 0) p.onProgress(written, estimated);
    }
}
