package com.opx.demon.utils;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

public final class Exports {

    private static final int KEEP = 3;

    private Exports() {
    }

    public static String stamp() {
        return new SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(new Date());
    }

    public static File fresh(File dir, String prefix, String suffix) {
        if (dir != null && !dir.isDirectory()) {
            dir.mkdirs();
        }
        prune(dir, prefix, suffix);
        String base = prefix + "-" + stamp();
        File f = new File(dir, base + suffix);
        for (int n = 2; f.exists() && n < 100; n++) {
            f = new File(dir, base + "-" + n + suffix);
        }
        return f;
    }

    private static void prune(File dir, String prefix, String suffix) {
        if (dir == null) return;
        File[] old = dir.listFiles((d, name) ->
                name.startsWith(prefix + "-") && name.endsWith(suffix));
        if (old == null || old.length < KEEP) return;
        Arrays.sort(old, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = KEEP - 1; i < old.length; i++) {
            old[i].delete();
        }
    }
}
