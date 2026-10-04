package com.opx.demon.engine;

import android.content.Context;

import com.opx.demon.utils.Core;

/**
 * Remembers which VM engine a USB Wi-Fi adapter was bound to, so the guest can
 * be brought up on demand and torn down without the user re-picking.
 *
 * Adapted from strykerapp 6.5. The upstream version reaches the engine through
 * an Engines dispatcher and a GuestEngine interface, because upstream splits
 * the two VMs into separate classes. This app runs both engines through a
 * single RootlessEngine, so the binding logic is the same but the resolution
 * goes through RootlessEngine directly — porting the dispatcher as well would
 * mean re-plumbing every engine caller for no gain here.
 *
 * The stored iface matters as much as the engine: an adapter is only bound
 * while that exact guest interface exists, and a guest that booted for some
 * other reason must not be mistaken for the adapter session.
 */
public final class WifiEngine {

    public static final String PREF = "wifi_engine";
    public static final String PREF_VERIFIED = "wifi_engine_verified";
    public static final String PREF_ADAPTER = "wifi_engine_adapter";
    public static final String PREF_IFACE = "wifi_engine_iface";

    private WifiEngine() {
    }

    public static boolean armed(Core core) {
        return core != null && core.getBoolean(PREF_VERIFIED) && configured(core) != null;
    }

    /** The engine this adapter was armed on, or null when nothing is armed. */
    public static EngineType configured(Core core) {
        if (core == null) return null;
        String v = core.getString(PREF);
        if (EngineType.ROOTLESS_UML.name().equals(v)) return EngineType.ROOTLESS_UML;
        if (EngineType.ROOTLESS.name().equals(v)) return EngineType.ROOTLESS;
        return null;
    }

    /** Record a pending choice; it only counts as armed after {@link #arm}. */
    public static void choose(Core core, EngineType type) {
        if (core == null || type == null) return;
        core.putString(PREF, type.name());
        core.putBoolean(PREF_VERIFIED, false);
    }

    /** Record the verified binding: engine, adapter identity and guest interface. */
    public static void arm(Core core, EngineType type, String adapterVidPid, String iface) {
        if (core == null || type == null) return;
        core.putString(PREF, type.name());
        core.putBoolean(PREF_VERIFIED, true);
        core.putString(PREF_ADAPTER, adapterVidPid == null ? "" : adapterVidPid);
        core.putString(PREF_IFACE, iface == null ? "" : iface);
    }

    public static void disarm(Core core) {
        if (core == null) return;
        core.putString(PREF, "");
        core.putBoolean(PREF_VERIFIED, false);
        core.putString(PREF_ADAPTER, "");
        core.putString(PREF_IFACE, "");
    }

    public static String adapter(Core core) {
        return core == null ? "" : core.getString(PREF_ADAPTER);
    }

    public static String iface(Core core) {
        return core == null ? "" : core.getString(PREF_IFACE);
    }

    /**
     * True when the interface the guest reports is the one this adapter was
     * bound to. Monitor mode renames the interface (wlan0 becomes wlan0mon), so
     * both the bare name and the monitor-suffixed form count as a match — and
     * an unset binding never matches, so an unrelated guest cannot be torn down
     * by accident.
     */
    public static boolean isGuestInterface(Core core, String iface) {
        String own = iface(core);
        if (own.isEmpty() || iface == null || iface.isEmpty()) return false;
        return iface.equals(own) || iface.equals(own + "mon") || own.equals(iface + "mon");
    }

    /** The live engine for a Core bound to an adapter, or null. */
    public static RootlessEngine engine(Context context) {
        Core core = new Core(context);
        EngineType type = configured(core);
        if (type == null || !core.getBoolean(PREF_VERIFIED)) return null;
        return RootlessEngine.get(context);
    }
}