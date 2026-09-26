package com.opx.demon.engine;

import android.hardware.usb.UsbDevice;

import java.util.List;

/**
 * Transport-neutral USB passthrough API.
 *
 * The QEMU engine forwards devices through usb-host devices added over QMP
 * ({@link UsbPassthroughManager}); the UML engine exports them to the guest's
 * VHCI over USB/IP ({@link UmlUsbServer}). Both engines need exactly the same
 * surface from the app — permission handling, Wi-Fi candidate selection, attach
 * and detach — so the call sites (MainActivity attach dialog, Dashboard device
 * list, WiFi scan flow) can be engine-agnostic.
 */
public interface UsbBridge {

    /** Finds a device by "vid:pid" hex, e.g. "0bda:f179". */
    UsbDevice findByVidPid(String vidPid);

    boolean hasPermission(UsbDevice device);

    boolean isAttached(UsbDevice device);

    /** Number of devices currently passed into the guest. */
    int attachedCount();

    /** Asks Android for USB permission if needed, then attaches. */
    void requestUsbPermission(UsbDevice device, UsbPassthroughManager.PermissionCallback cb);

    /** Asks Android for USB permission if needed, then attaches. Callback is always invoked. */
    void attachAsync(UsbDevice device, UsbPassthroughManager.AttachCallback done);

    /** Attaches now; permission must already be granted. */
    boolean attach(UsbDevice device);

    void detach(int deviceId);

    void detachAll();

    boolean hasAttached();

    /** True when the device has at least one vendor-specific / wireless-controller interface. */
    boolean isWifiCandidate(UsbDevice d);

    List<UsbDevice> pickWifiDevices();

    /** Attaches every Wi-Fi candidate within waitMs; returns how many made it. */
    int attachAllWifiDongles(long waitMs);

    UsbDevice pickWifiDevice();
}
