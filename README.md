# OPX-Demon

> A free and open-source mobile pentest suite for Android. Authorized testing only.

**OPX-Demon** bundles a curated set of network, wireless and web security tools into a single
Android application, exposing them through a unified, modern UI. It runs a Debian trixie (arm64)
chroot so heavyweight tools (Nmap, Metasploit, Nuclei, Hydra, SearchSploit, etc.) execute natively
on the device — the same rootfs the rootless QEMU VM boots when root is unavailable. A built-in
terminal (drawer → **Terminal**, or the **OPX-Demon Terminal** launcher icon) drops straight into
that chroot — no external shell app required.

- **Developer**: OPX-AMINUL (OP AMINUL FF)
- **Company**: OPX
- **Website**: [opaminulff.vercel.app](https://opaminulff.vercel.app/)
- **Source**: [github.com/OPX-Aminul/OPX-Demon](https://github.com/OPX-Aminul/OPX-Demon)
- **License**: GNU GPL v3.0

---

## Xiaomi / MIUI USB fix — solved here

The original project's engine fails to use USB Wi-Fi adapters on **Xiaomi / MIUI phones** (Poco F1
and similar). Xiaomi's kernels misreport full-speed USB devices as **low-speed**, so Realtek
adapters (e.g. `rtl8188fu`, VID:PID `0bda:f179`) abort enumeration with `Invalid ep0 maxpacket` and
the adapter never shows up — `iw dev` stays empty and WiFi attacks are impossible.

**OPX-Demon ships the fix**, on two independent layers:

1. **Kernel layer (custom 6.12.94 arm64 kernel)** — a `hub.c` classification fix that reclassifies
   full-speed devices wrongly reported as low-speed before the driver USB core sees them.
2. **QEMU layer** — a `host-libusb` quirk that corrects the misreported speed as the device is
   attached through QEMU's USB host backend.

Supporting changes that make it stick:

- A custom kernel is compiled in CI and shipped (with the patched `hub.c`) instead of the stock
  kernel, so the fix survives on every device.
- USB-Wi-Fi drivers are pre-enabled and built in: `rtl8xxxu`, `rtw88` (`8821cu` / `8822b` / `8822c` /
  `8812au`), `ath9k_htc`, `carl9170`, `mt76`, `rt2x00`, `rtl8187`, `zd1211rw`.
- `rtl8188fufw.bin`, `rtl8188eufw.bin`, `rtw88` and `ath9k_htc` firmware are baked into the rootfs, so
  install the engine and the adapter just works with no extra downloads.
- `loop`, `squashfs` and `overlay` are built-in, so `.img` / `.iso` mounting and kernel-module
  recovery work inside the guest without extra steps.

Additional fixes over the original: incremental core repair (only the missing binaries are
re-fetched), the guest is re-waited after the resize2fs cycle (fixes "VM not reachable on :1050"),
rootless downloads resume instead of restarting, QEMU finds `libslirp` at boot, VM boot no longer
gets stuck at the login prompt, and the cameradar installer falls back to a
rate-limit-free download URL when the GitHub API is blocked.

---

## Capabilities

| Module | Description |
|---|---|
| **Dashboard** | Live overview of the chroot, USB adapters, mounted state and quick actions. |
| **WiFi networks** | Scan, deauth, handshake capture, WPS attacks (Pixie Dust, common pins, custom pins) via external monitor-mode adapters. |
| **Handshakes** | Local handshake storage with rename, share, export to OnlineHashCrack and on-device cracking via Hashcat. |
| **MAC changer** | Inline + dedicated MAC randomizer with persistent profiles. |
| **WhisperPair (BLE)** | Fast Pair device discovery, CVE-2025-36911 vulnerability check and full exploit chain, post-pair account-key write and HFP audio capture/passthrough. |
| **Local network** | Nmap host discovery, port scans, OS fingerprinting, per-device exploit dispatch with a live terminal. |
| **Nmap** | Direct Nmap interface with custom scripts, NSE, and exported reports. |
| **Web scanner (Nuclei)** | Multi-target Nuclei scans with severity-grouped findings and per-finding evidence. |
| **Arsenal** | Custom exploit / scanner database with template arguments (`{IP}`, `{PORT}`, `{MAC}`, `{GW}`, `{MASK}`). |
| **HID Attacks** | DuckyScript-compatible USB HID injection — pure-Java parser (Hak5 v1 + v3 superset), 7 bundled keyboard layouts and live execution log. |
| **USB Arsenal** | USB-gadget profile manager — toggle HID keyboard/mouse, mass-storage, RNDIS/ECM/ACM functions, customise VID/PID/serial, mount `.img`/`.iso` images. |
| **Metasploit** | Native MSF console inside the chroot with sessions, payload generation and module browser. |
| **GeoMac** | OSM-based map of captured BSSIDs / handshakes with WiGLE-style export (KML/CSV). |
| **VNC desktop** | Stand-up an in-chroot XFCE/Xfce-VNC session and view it locally. |
| **Core manager** | Mount / unmount / repair the chroot, manage installed components. |

---

## Requirements

- **Rooted Android device** (Magisk or KernelSU recommended) for the native chroot engine;
  the rootless QEMU engine also runs on stock devices.
- **~1 GB free internal storage** for the chroot, bundled tools and signatures.
- **External monitor-mode USB Wi-Fi adapter** for handshake capture and deauthentication
  (Atheros AR9271 / Realtek 88XXAU recommended).
- **Gadget-capable kernel (optional)** for HID Attacks and USB Arsenal (USB configfs enabled,
  kernel ≥ 3.19, `/sys/class/udc/` populated). Most modern OEM and custom kernels meet this.

---

## Build

Standard Android Gradle build (Java 17 toolchain, R8 minification for release).

```bash
# Debug APK
./gradlew assembleDebug

# Release APK (minified + R8)
./gradlew assembleRelease

# Lint
./gradlew lint
```

Output APKs land in `app/build/outputs/apk/`.

### Release signing

Release builds are signed with the repository's committed release keystore. The store path and
credentials live in `gradle.properties` (already active for every Gradle run) and are read as
project properties in `app/build.gradle`. To sign with a different key, override those properties
via `~/.gradle/gradle.properties`, `-P`, or environment variables.

> Security note: the release key is committed so CI and contributors can reproduce installable
> signed builds. It is intended for sideloaded builds, not store-distributed apps that need update
> integrity.

---

## Installation (end users)

1. Install the APK on a **rooted** device (`adb install`, or sideload), or a stock device when you
   plan to use the rootless engine.
2. On first launch the in-app installer will:
   - Request root (`su`) and storage/runtime permissions.
   - Download and unpack the Debian trixie arm64 chroot core.
   - Mount the chroot and install optional components (Metasploit, Nuclei, Hydra, SearchSploit).
3. Open the built-in terminal (drawer → **Terminal**) for a shell straight into the chroot.
4. Plug in a supported USB Wi-Fi adapter for monitor-mode features.

---

## Project layout

```
app/
├── src/main/java/                       # module sources (Dashboard, WiFi, Handshakes,
│                                         #  MAC, Local Network, Nmap, Nuclei, Arsenal, HID,
│                                         #  USB Arsenal, Metasploit, GeoMac, VNC, WhisperPair,
│                                         #  engine · OTA · core manager)
├── src/main/jni/                         # native code (ndk-build)
├── src/main/assets/                      # chroot scripts, wordlists, busybox
└── src/main/res/                         # layouts, drawables, strings, themes
build-tools/                              # kernel patching + firmware build assets
build-rootfs/                             # rootfs build scripts (CI)
.github/workflows/                        # core rebuild + signed APK pipelines
```

---

## Contributing

PRs and issues are welcome at [github.com/OPX-Aminul/OPX-Demon](https://github.com/OPX-Aminul/OPX-Demon).

Guidelines:

- Keep modules self-contained and reuse the existing core helpers (settings, SQLite, asset
  extraction, root process execution) rather than re-rolling them.
- Match the existing Material 3 design language.

---

## License

OPX-Demon is free software distributed under the **GNU General Public License v3.0**.
See [`LICENSE`](LICENSE) for the full text. Bundled third-party components keep their own
GPLv3-compatible licenses — see [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md) and the
in-app *About → Open-source licenses* screen.

---

## Disclaimer

OPX-Demon is provided **for authorized security testing, education and research only**. You are
responsible for complying with all applicable laws and obtaining explicit permission before testing
any system or device you do not own. The authors accept no liability for misuse.

---

<sub>OPX-Demon — developer OPX-AMINUL · company OPX · opaminulff.vercel.app · GPLv3</sub>