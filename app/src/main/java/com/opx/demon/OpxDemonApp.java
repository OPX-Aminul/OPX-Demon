package com.opx.demon;

import android.os.Build;

import com.opx.demon.logger.LogEntry;
import com.opx.demon.logger.LogStore;
import com.opx.demon.ota.NotificationCenter;
import com.opx.demon.ota.UpdateScheduler;

public class OpxDemonApp extends com.opx.demon.terminal.App {

    @Override
    public void onCreate() {
        super.onCreate();
        LogStore store = LogStore.init(this);
        store.add(LogEntry.INFO, "session", "==== OPX-Demon " + BuildConfig.VERSION_NAME
                + " session start ====");
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "unknown";
        store.add(LogEntry.INFO, "session", "Device: " + Build.MANUFACTURER + " " + Build.MODEL
                + " · Android " + Build.VERSION.RELEASE
                + " (" + abi + ")");
        NotificationCenter.ensureChannel(this);
        UpdateScheduler.schedule(this);
    }
}
