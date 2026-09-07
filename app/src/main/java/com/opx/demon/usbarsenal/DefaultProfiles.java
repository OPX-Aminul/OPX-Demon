package com.opx.demon.usbarsenal;

import androidx.annotation.NonNull;

import com.opx.demon.hid.backchannel.BackChannelMarker;
import com.opx.demon.hid.configfs.GadgetFunction;
import com.opx.demon.hid.configfs.GadgetProfile;
import com.opx.demon.hid.configfs.TargetOs;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class DefaultProfiles {

    private DefaultProfiles() {}

    @NonNull
    public static List<GadgetProfile> list() {
        List<GadgetProfile> out = new ArrayList<>();
        out.add(new GadgetProfile(
                0, "HID Keyboard (Windows)", TargetOs.WINDOWS,
                EnumSet.of(GadgetFunction.HID_KEYBOARD),
                "0x046d", "0xc31c",
                "Logitech", "USB Keyboard", "OPXDEMON001",
                "OPX-Demon HID", null, false, false, ""));
        out.add(new GadgetProfile(
                0, "HID Keyboard + Mouse", TargetOs.GENERIC,
                EnumSet.of(GadgetFunction.HID_KEYBOARD, GadgetFunction.HID_MOUSE),
                "0x046d", "0xc52b",
                "Logitech", "Unifying Receiver", "OPXDEMON002",
                "OPX-Demon HID Combo", null, false, false, ""));
        out.add(new GadgetProfile(
                0, "Mass Storage (Bootable ISO)", TargetOs.GENERIC,
                EnumSet.of(GadgetFunction.MASS_STORAGE),
                "0x0951", "0x1666",
                "Kingston", "DataTraveler", "OPXDEMON003",
                "OPX-Demon Storage", null, true, true, "OpxDemon  Boot   1.0 "));
        out.add(new GadgetProfile(
                0, "HID + Mass Storage", TargetOs.WINDOWS,
                EnumSet.of(GadgetFunction.HID_KEYBOARD,
                           GadgetFunction.HID_MOUSE,
                           GadgetFunction.MASS_STORAGE),
                "0x046d", "0xc52b",
                "Logitech", "Composite Device", "OPXDEMON004",
                "OPX-Demon Combo", null, false, false, "OpxDemon  Combo  1.0 "));
        out.add(new GadgetProfile(
                0, "RNDIS Network (Windows)", TargetOs.WINDOWS,
                EnumSet.of(GadgetFunction.RNDIS),
                "0x0525", "0xa4a2",
                "Linux Foundation", "RNDIS Ethernet", "OPXDEMON005",
                "OPX-Demon Network", null, false, false, ""));
        out.add(new GadgetProfile(
                0, "ECM Network (Linux / macOS)", TargetOs.LINUX,
                EnumSet.of(GadgetFunction.ECM),
                "0x0525", "0xa4a1",
                "Linux Foundation", "CDC Ethernet", "OPXDEMON006",
                "OPX-Demon ECM", null, false, false, ""));
        out.add(new GadgetProfile(
                0, "Serial Port (ACM)", TargetOs.GENERIC,
                EnumSet.of(GadgetFunction.ACM),
                "0x0525", "0xa4a7",
                "Linux Foundation", "CDC ACM", "OPXDEMON007",
                "OPX-Demon Serial", null, false, false, ""));
        out.add(new GadgetProfile(
                0, BackChannelMarker.PROFILE_NAME, TargetOs.WINDOWS,
                EnumSet.of(GadgetFunction.HID_KEYBOARD, GadgetFunction.ACM),
                BackChannelMarker.VID, BackChannelMarker.PID,
                BackChannelMarker.MANUFACTURER, BackChannelMarker.PRODUCT,
                BackChannelMarker.SERIAL, "OPX-Demon Back-Channel",
                null, false, false, ""));
        out.add(new GadgetProfile(
                0, BackChannelMarker.HIDTOGO_PROFILE_NAME, TargetOs.GENERIC,
                EnumSet.of(GadgetFunction.HID_KEYBOARD,
                           GadgetFunction.HID_MOUSE,
                           GadgetFunction.ACM),
                BackChannelMarker.HIDTOGO_VID, BackChannelMarker.HIDTOGO_PID,
                BackChannelMarker.MANUFACTURER, BackChannelMarker.HIDTOGO_PRODUCT,
                BackChannelMarker.HIDTOGO_SERIAL, "OPX-Demon HID-to-Go",
                null, false, false, ""));
        return out;
    }
}
