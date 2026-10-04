package com.opx.demon.wordlists;

import java.io.File;
import java.util.Locale;

public class Wordlist {

    public enum Origin {
        IMPORTED("imported"),
        GENERATED("generated"),
        DOWNLOADED("downloaded"),
        DERIVED("derived");

        public final String id;

        Origin(String id) { this.id = id; }

        public static Origin byId(String id) {
            if (id != null) {
                for (Origin o : values()) if (o.id.equalsIgnoreCase(id)) return o;
            }
            return IMPORTED;
        }
    }

    public final File file;
    public WordlistCategory category;
    public Origin origin;
    public String note;

    public long lines = -1;
    public long sizeBytes;
    public long modified;

    public Wordlist(File file) {
        this.file = file;
        this.sizeBytes = file.length();
        this.modified = file.lastModified();
        this.category = WordlistCategory.OTHER;
        this.origin = Origin.IMPORTED;
        this.note = "";
    }

    public String getName() {
        return file.getName();
    }

    public String getDisplayName() {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    public boolean hasLineCount() {
        return lines >= 0;
    }

    public String describeSize() {
        return humanBytes(sizeBytes);
    }

    public String describe() {
        String size = humanBytes(sizeBytes);
        if (!hasLineCount()) return size;
        return String.format(Locale.US, "%,d lines · %s", lines, size);
    }

    public static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
