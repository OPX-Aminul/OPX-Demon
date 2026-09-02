/* Xiaomi/MIUI USB speed correction quirk (issue #85) */
/* Xiaomi/MIUI host stacks misreport full-speed devices as low-speed. */
/* After USB-3 maxpacket fixup above, check: if the host says low-speed */
/* but the device descriptor's bMaxPacketSize0 is 64, it is actually   */
/* full-speed (low-speed maxpacket must be ≤8 per USB spec). Correct   */
/* the speed so the guest kernel accepts the device.                   */
if (udev->speed == USB_SPEED_LOW && xfer->actual_length >= 8 && r->cbuf[7] == 64) { udev->speed = USB_SPEED_FULL; }
