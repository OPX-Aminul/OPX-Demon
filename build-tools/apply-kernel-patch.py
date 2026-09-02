#!/usr/bin/env python3
"""Apply Xiaomi/MIUI USB speed correction to kernel hub.c.

Inserts a new else-if branch before the "Invalid ep0 maxpacket" error block
that catches low-speed devices with maxpacket=64 (a Xiaomi/MIUI host stack bug)
and treats them as full-speed instead of rejecting them.
"""
import sys

FNAME = "drivers/usb/core/hub.c"

with open(FNAME) as f:
    content = f.read()

# The exact block in kernel v7.1 hub.c
old_block = """\t} else {
\t\t/* Initial guess is wrong and descriptor's value is invalid */
\t\tdev_err(&udev->dev, "Invalid ep0 maxpacket: %d\\n", maxp0);
\t\tretval = -EMSGSIZE;
\t\tgoto fail;
\t}"""

new_block = """\t} else if (udev->speed == USB_SPEED_LOW &&
\t\t\ti == 64) {
\t\t/* Xiaomi/MIUI host stack bug: full-speed devices misreported
\t\t * as low-speed. Accept maxpacket=64 and treat as full-speed
\t\t * (low-speed maxpacket must be <=8 per USB spec) (issue #85).
\t\t */
\t\tudev->speed = USB_SPEED_FULL;
\t\tudev->ep0.desc.wMaxPacketSize = cpu_to_le16(i);
\t\tusb_ep0_reinit(udev);
\t} else {
\t\t/* Initial guess is wrong and descriptor's value is invalid */
\t\tdev_err(&udev->dev, "Invalid ep0 maxpacket: %d\\n", maxp0);
\t\tretval = -EMSGSIZE;
\t\tgoto fail;
\t}"""

if old_block not in content:
    print(f"ERROR: pattern not found in {FNAME}", file=sys.stderr)
    sys.exit(1)

content = content.replace(old_block, new_block, 1)

if "Xiaomi/MIUI" not in content:
    print("ERROR: patch not applied!", file=sys.stderr)
    sys.exit(1)

with open(FNAME, "w") as f:
    f.write(content)

print("Kernel hub.c patch applied successfully")
