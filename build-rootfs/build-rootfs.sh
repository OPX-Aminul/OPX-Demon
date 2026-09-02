#!/bin/sh
set -eu
ROOTFS=/work/rootfs

: "${DEBIAN_VERSION:?DEBIAN_VERSION must be set (e.g. trixie)}"

# ── Configure apt sources ────────────────────────────────────────────────────
mkdir -p "$ROOTFS/etc/apt"
cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian ${DEBIAN_VERSION} main contrib non-free non-free-firmware
deb http://deb.debian.org/debian ${DEBIAN_VERSION}-updates main contrib non-free non-free-firmware
deb http://security.debian.org/debian-security ${DEBIAN_VERSION}-security main contrib non-free non-free-firmware
EOF

# ── Install pentest packages via chroot apt ──────────────────────────────────
chroot "$ROOTFS" /bin/sh -c '
export DEBIAN_FRONTEND=noninteractive
apt-get update || true
apt-get install -y --no-install-recommends \
    openrc \
    busybox \
    bash \
    iproute2 \
    iputils-ping \
    dropbear \
    curl \
    ca-certificates \
    libcap2-bin \
    sudo \
    gzip \
    xz-utils \
    socat \
    kmod \
    procps \
    nano \
    less \
    git \
    python3 \
    python3-pip \
    python3-dev \
    nmap \
    masscan \
    nikto \
    sqlmap \
    hydra \
    john \
    hashcat \
    aircrack-ng \
    reaver \
    pixiewps \
    bully \
    wifite2 \
    hostapd \
    dnsmasq \
    iw \
    wireless-tools \
    wpa-supplicant \
    tcpdump \
    netcat-openbsd \
    socat \
    socat \
    net-tools \
    dnsutils \
    whois \
    traceroute \
    mtr-tiny \
    2>/dev/null || true

# Install WiFi firmware packages (non-free)
apt-get install -y --no-install-recommends \
    firmware-realtek \
    firmware-atheros \
    firmware-brcm80211 \
    firmware-libertas \
    firmware-misc-nonfree \
    2>/dev/null || true

# Clean up apt cache
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*
'

# ── Set root password ────────────────────────────────────────────────────────
ROOT_HASH=$(openssl passwd -6 stryker)
sed -i "s|^root:[^:]*:|root:${ROOT_HASH}:|" "$ROOTFS/etc/shadow"

# ── Strip docs/man/locale to shrink squashfs ────────────────────────────────
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# ── Pre-create minimal runtime dirs ──────────────────────────────────────────
mkdir -p "$ROOTFS/run"

# ── Configure init system ───────────────────────────────────────────────────
cat > "$ROOTFS/etc/inittab" <<'EOF'
::respawn:/sbin/getty -n -l /bin/ash 0 console vt100
EOF

cat > "$ROOTFS/etc/profile.d/stryker-prompt.sh" <<'EOF'
export PS1='\[\033[1;32m\]stryker\[\033[0m\]:\[\033[1;34m\]\w\[\033[0m\]\$ '
alias ll='ls -la'
alias la='ls -A'
EOF

# ── Enable networking at boot ───────────────────────────────────────────────
mkdir -p "$ROOTFS/etc/init.d"
cat > "$ROOTFS/etc/init.d/stryker-network" <<'NETEOF'
#!/sbin/openrc-run
depend() {
    need localmount
    keyword -stop
}
start() {
    ebegin "Configuring network"
    ip link set lo up
    ip addr add 127.0.0.1/8 dev lo
    ip link set eth0 up
    udhcpc -i eth0 -b 2>/dev/null || true
    eend $?
}
NETEOF
chmod +x "$ROOTFS/etc/init.d/stryker-network"

cat > "$ROOTFS/etc/init.d/stryker-ready" <<'READYEOF'
#!/sbin/openrc-run
depend() {
    need stryker-network
    keyword -stop
}
start() {
    ebegin "StrykerOSS ready"
    echo "StrykerOSS VM Ready!" > /dev/console
    echo "IP: $(ip -4 addr show eth0 | grep -oP '"'"'inet \K[\d.]+'""')" > /dev/console
    eend $?
}
READYEOF
chmod +x "$ROOTFS/etc/init.d/stryker-ready"

# Enable services
ln -sf /etc/init.d/stryker-network "$ROOTFS/etc/runlevels/default/stryker-network" 2>/dev/null || true
ln -sf /etc/init.d/stryker-ready "$ROOTFS/etc/runlevels/default/stryker-ready" 2>/dev/null || true

echo "Rootfs build complete."
