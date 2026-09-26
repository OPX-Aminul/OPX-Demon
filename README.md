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

## Rootless engine v2 — UML on arm64 (reproduced build)

The original developer's `rootless-650` test release changed the rootless engine: kernel **7.2**
(7.2.0-rc4), **one rootfs boots both QEMU and UML**, and the guest is reached over **SSH**. OPX-Demon
carries its own build scripts that reproduce that engine from public sources:

| Artifact | What it is | How this repo builds it |
|---|---|---|
| `linux-uml` | arm64 **User-Mode Linux** kernel — a normal userspace ELF (`CONFIG_UML=y`, `CONFIG_UML_ARM64=y`, `CONFIG_STATIC_LINK=y`), kernel 7.2-rc4 lineage, USB-WiFi drivers built in | `build-tools/uml-build.sh` inside the `uml-builder` Docker stage: Linux 7.2-rc UML tree, `make ARCH=um SUBARCH=arm64` with the Android NDK (clang/LLD) toolchain and `build-tools/uml-arm64.config` |
| `stub_exe` | ~1.9 KB static arm64 ELF carrying a `uml-userspace` build note — the UML build tree's stub binary | collected from the same UML build output |
| `uml-netd` | rootless **UML network gateway** — a small static arm64 daemon that plays the `10.0.2.2` gateway for the guest's `vec0` (see below) | `build-tools/uml-netd.c` compiled statically with the NDK in the `uml-netd-builder` Docker stage |
| `Image` / `initrd.img` / `rootfs.imgz` | the shared 7.2 QEMU-boot kernel and rootfs (one image boots both engines) | existing `rootfs-builder` / `kernel-builder` stages |

Run the UML stage alone with `./build-all.sh uml` (or the full engine with `./build-all.sh all`); CI
uploads `linux-uml` + `stub_exe` + `uml-netd` to the `uml-mode-all-file` release and re-pins their
hashes into the `rootless_v2` block of `opx_manifest.json`. The rootfs kernel side now targets the
7.2 series to match: keep `KERNEL_VERSION` in sync with the UML tree when bumping either.

### Guest networking without root — `uml-netd` (BESS gateway)

The classic UML slirp/daemon networking (`eth0=tap,...`) needs `CAP_NET_ADMIN` (TUNSETIFF), which an
app uid never has, so a rootless UML guest would have no network at all. OPX-Demon closes that gap
without root, without VPN, and without rebuilding the kernel: the UML tree's **vector-net driver**
ships a **BESS transport** in which the *kernel* is a client of an `AF_UNIX SOCK_SEQPACKET` socket —
one seqpacket = one raw Ethernet frame, no header, no capabilities. The boot command line therefore
uses `vec0:transport=bess,dst=<uml-netd.sock>` (guest = `10.0.2.15/24`, MAC `52:54:00:12:34:15`),
and the app spawns **`uml-netd`** (`build-tools/uml-netd.c`) listening on that socket:

* **ARP** — proxy-answers everything with the gateway MAC `52:54:00:12:34:02` and sends gratuitous
  ARPs for the first seconds after the kernel connects;
* **ICMP** — answers echo requests so `ping 10.0.2.2` works;
* **TCP relay** — a guest connection to `10.0.2.2:<port>` (or to `127.0.0.0/8`) is relayed to
  `127.0.0.1:<same port>` in the app: that is exactly what carries
  `usbip attach -r 10.0.2.2 -b <busid>` to the USB/IP server;
* **UDP DNS** — guest queries are forwarded to the resolver passed via `--dns`.

**Guest internet.** QEMU gets its connectivity from slirp's user-mode NAT; UML has nothing
underneath it, so `uml-netd` owns the egress policy for every other destination:

| `--egress` | guest connects to | result |
| --- | --- | --- |
| `direct` (default) | `10.0.2.2`, `127.0.0.0/8` | relayed to `127.0.0.1:<same port>` (usbip) |
| `direct` (default) | anything else | opened by the daemon itself — it runs inside the app process, so its sockets already carry the app's `INTERNET` permission (no root, no `VpnService`) |
| `socks` (`--socks host:port`) | anything else | SOCKS5 `CONNECT` tunnel (RFC 1928, no auth) through a proxy on the device |
| `loopback` | anything | the old gateway-only behaviour, for A/B comparison |

So `apt update`, `curl`, `pip`, `nmap -sT` etc. work in the UML guest out of the box. A SOCKS5
proxy can be forced from SharedPreferences (`opx_demon` / `uml_socks5` = `host:port`) for networks
where direct egress is blocked. DNS stays a UDP relay to `--dns` (default `8.8.8.8`), so hostnames
resolve on the device either way.

`uml-netd` implements a mini TCP stack per connection (MSS 1400, cumulative ACKs, retransmit,
reap after 30 idle minutes) and its lifecycle is bound to the kernel connection: UML died or the app
was killed → socket EOF → the daemon exits and unlinks its socket (no `PR_SET_PDEATHSIG`, which
misfires on the forking *thread*), and it gives up if the kernel never connects within 5 minutes.
Boot is degradable: if `uml-netd` is missing or dies at start-up, the guest still boots (legacy
`eth0=tap` diagnostic cmdline) — only guest→host traffic, and with it USB passthrough, needs the
daemon. CI compiles it from `build-tools/uml-netd.c` with the same NDK, uploads it to
`uml-mode-all-file`, and re-pins `rootless_v2.uml_netd`; a functional harness
(`build-tools/test-uml-netd.py`, 42 checks) simulates the kernel side and validates ARP, ICMP, TCP
relay, RST-on-refused, DNS, direct egress, the SOCKS5 handshake (including that the gateway path
stays on loopback while a proxy is configured) and the argument validation.

### USB passthrough in UML mode (USB/IP)

UML has no QEMU to hand devices to, so the app runs its own **USB/IP server**
(`app/src/main/java/com/opx/demon/engine/UmlUsbServer.java`) on port 3240 and the guest binds it:
the UML kernel carries `CONFIG_USBIP_VHCI_HCD=y` (8-port VHCI) and the Debian Trixie rootfs ships
`/usr/sbin/usbip` (usbip-utils 2.0). `attach` claims the Android `UsbDeviceConnection`, exports a
USB/IP device struct, then runs `usbip attach -r 10.0.2.2 -b <busid>` inside the guest over the
agent/console path — the kernel's `vhci_hcd` then streams URBs to the app's server, which services
them with `controlTransfer` / `bulkTransfer`. The wire protocol (op_common, 312-byte device struct,
48-byte URB headers) matches usbip-utils 2.0 byte-for-byte.

The **Xiaomi/MIUI fix applies to UML mode on both ends**: the UML kernel is patched at build time
with `build-tools/uml-xiaomi-hub.patch` (the same ep0-maxpacket/speed reclassification as the QEMU
`hub.c` fix), and the server re-applies the spec-level invariant at export time (never report
`USB_SPEED_LOW` for a device whose real descriptor says `bMaxPacketSize0 > 8`), so a misreported
Realtek adapter enumerates instead of dying with `Invalid ep0 maxpacket`.

The app talks to both engines through one interface (`engine/UsbBridge.java`):
`UsbPassthroughManager` (QEMU, usb-host over QMP) and `UmlUsbServer` (UML, USB/IP) implement it, so
the attach dialog, dashboard device list and WiFi scanner are engine-agnostic.

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

### CI: automatic version bump + release + OTA manifest

`Build & Update Release` (engine binaries) fires `Build APK` on success, and the APK job
**publishes a new version every time** without any manual step:

1. **Bump** — `versionCode` + 1 and `versionName` patch bumped (`1.1.8 → 1.1.9 → 1.2.0`, rolling
   into the next minor instead of reaching double-digit patches) in `app/build.gradle`.
2. **Build** — `./gradlew assembleRelease` (lightweight APK; the engine binaries are downloaded at
   runtime from the release assets).
3. **Release** — the APK is uploaded to a release for that exact version tag (`v1.1.9`), created
   automatically when it does not exist yet; an existing tag is clobbered instead (retry-safe).
4. **OTA manifest** — the `app` block of `opx_manifest.json` is updated with the new
   `versionCode`, `versionName`, APK URL, `sha256` and `size` (changelog = the release notes) and
   committed to `main` together with the bumped `build.gradle`, so installed apps see the update —
   `UpdateManager` only offers an update when `app.versionCode > BuildConfig.VERSION_CODE`.

Committing the version bump does not re-trigger the release build (its path filter only covers
`Dockerfile`, `build-tools/**`, `build-rootfs/**`, `rootless-assets/**` and `build.yml` itself), so
the pipeline cannot loop. Because the bump is committed, each run advances from the version that
was actually published.

### Release signing

Release builds are signed with the repository's committed release keystore. The store path and
credentials live in `gradle.properties` (already active for every Gradle run) and are read as
project properties in `app/build.gradle`. To sign with a different key, override those properties
via `~/.gradle/gradle.properties`, `-P`, or environment variables.

> Security note: the release key is committed so CI can reproduce installable
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
build-tools/                              # kernel patching + firmware + UML build assets
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