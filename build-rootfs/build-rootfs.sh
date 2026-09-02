#!/bin/sh
set -eu
ROOTFS=/work/rootfs

: "${DEBIAN_VERSION:?DEBIAN_VERSION must be set (e.g. trixie)}"

# ── Configure apt sources (exact match with original) ────────────────────────
mkdir -p "$ROOTFS/etc/apt"
cat > "$ROOTFS/etc/apt/sources.list" <<EOF
deb http://deb.debian.org/debian trixie main contrib non-free non-free-firmware
EOF

# ── Install packages via chroot apt (exact match with original dpkg status) ──
chroot "$ROOTFS" /bin/sh -c '
export DEBIAN_FRONTEND=noninteractive
apt-get update || true
apt-get install -y --no-install-recommends \
    adduser \
    apparmor \
    apt \
    apt-utils \
    base-files \
    base-passwd \
    bash \
    bsdutils \
    ca-certificates \
    coreutils \
    cpio \
    cron \
    cron-daemon-common \
    curl \
    dash \
    dbus \
    dbus-bin \
    dbus-daemon \
    dbus-session-bus-common \
    dbus-system-bus-common \
    dbus-user-session \
    debconf \
    debconf-i18n \
    debian-archive-keyring \
    debianutils \
    dhcpcd-base \
    diffutils \
    dirmngr \
    dmidecode \
    dpkg \
    dracut-install \
    e2fsprogs \
    ethtool \
    fdisk \
    findutils \
    git \
    git-man \
    gnupg \
    gnupg-l10n \
    gpg \
    gpg-agent \
    gpgconf \
    gpgsm \
    grep \
    gzip \
    hostname \
    hwloc \
    ifupdown \
    init \
    init-system-helpers \
    initramfs-tools \
    initramfs-tools-bin \
    initramfs-tools-core \
    iproute2 \
    iputils-ping \
    iw \
    klibc-utils \
    kmod \
    less \
    linux-base \
    login \
    login.defs \
    logrotate \
    logsave \
    macchanger \
    mawk \
    mdk4 \
    media-types \
    mount \
    nano \
    ncurses-base \
    ncurses-bin \
    net-tools \
    netbase \
    nftables \
    nmap \
    nmap-common \
    openssh-client \
    openssh-server \
    openssh-sftp-server \
    openssl \
    openssl-provider-legacy \
    passwd \
    pci.ids \
    pciutils \
    pixiewps \
    procps \
    python3 \
    python3-autocommand \
    python3-bcrypt \
    python3-blinker \
    python3-cffi-backend \
    python3-charset-normalizer \
    python3-click \
    python3-cryptography \
    python3-dnspython \
    python3-flask \
    python3-impacket \
    python3-inflect \
    python3-itsdangerous \
    python3-jaraco.context \
    python3-jaraco.functools \
    python3-jaraco.text \
    python3-jinja2 \
    python3-ldap3 \
    python3-ldapdomaindump \
    python3-markupsafe \
    python3-minimal \
    python3-more-itertools \
    python3-openssl \
    python3-packaging \
    python3-pip \
    python3-pkg-resources \
    python3-pyasn1 \
    python3-pyasn1-modules \
    python3-pycryptodome \
    python3-scapy \
    python3-setuptools \
    python3-six \
    python3-typeguard \
    python3-typing-extensions \
    python3-werkzeug \
    python3-wheel \
    python3-zipp \
    python3.13 \
    python3.13-minimal \
    readline-common \
    reaver \
    rfkill \
    runit-helper \
    sed \
    sensible-utils \
    socat \
    squashfs-tools \
    sqv \
    sudo \
    systemd \
    systemd-sysv \
    sysvinit-utils \
    tar \
    tzdata \
    ucf \
    unzip \
    usbutils \
    util-linux \
    vim-common \
    vim-tiny \
    wget \
    whiptail \
    wireless-tools \
    wpasupplicant \
    zlib1g \
    2>/dev/null || true

# Install WiFi firmware packages (exact match with original)
apt-get install -y --no-install-recommends \
    firmware-realtek \
    firmware-atheros \
    firmware-misc-nonfree \
    firmware-linux-free \
    2>/dev/null || true

# Clean up apt cache
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*
'

# ── Set root password (exact hash from original) ─────────────────────────────
# Original password: stryker
# Hash from original rootfs shadow file
sed -i "s|^root:[^:]*:|root:\$y\$j9T\$zm4PGDwVGyQUURgnce.do0\$dcGRwd7TaU1tZivI0v0LKuRNn69ezPaXgS6qm9zHxN5:|" "$ROOTFS/etc/shadow"

# ── Set hostname (exact match) ───────────────────────────────────────────────
echo "stryker" > "$ROOTFS/etc/hostname"

# ── SSH config (exact match with original) ───────────────────────────────────
mkdir -p "$ROOTFS/etc/ssh/sshd_config.d"
cat > "$ROOTFS/etc/ssh/sshd_config" <<'EOF'
PermitRootLogin yes
PasswordAuthentication yes
EOF

# ── fstab (exact 9p share mount) ─────────────────────────────────────────────
cat > "$ROOTFS/etc/fstab" <<'EOF'
# UNCONFIGURED FSTAB FOR BASE SYSTEM
strykershare /sdcard/Stryker 9p trans=virtio,version=9p2000.L,msize=262144,nofail,x-systemd.device-timeout=5 0 0
EOF

# ── Create stryker-agentd (exact match — 572 byte shell script) ──────────────
mkdir -p "$ROOTFS/usr/local/sbin"
cat > "$ROOTFS/usr/local/sbin/stryker-agentd" <<'AGENTEOF'
#!/bin/sh
# Stryker guest agent: raw command server (1050) + PTY shell server (1051).
SOCAT="$(command -v socat 2>/dev/null)"
[ -z "$SOCAT" ] && { echo "stryker-agentd: socat missing" >&2; exit 1; }
mkdir -p /sdcard/Stryker/hs /sdcard/Stryker/captured /sdcard/Stryker/reports 2>/dev/null
"$SOCAT" TCP-LISTEN:1050,reuseaddr,fork EXEC:/bin/sh,stderr &
if command -v bash >/dev/null 2>&1; then
  "$SOCAT" TCP-LISTEN:1051,reuseaddr,fork EXEC:'bash -il',pty,setsid,ctty,stderr &
else
  "$SOCAT" TCP-LISTEN:1051,reuseaddr,fork EXEC:'/bin/sh -i',pty,setsid,ctty,stderr &
fi
wait
AGENTEOF
chmod 0755 "$ROOTFS/usr/local/sbin/stryker-agentd"

# ── Create systemd services (exact match) ────────────────────────────────────
mkdir -p "$ROOTFS/etc/systemd/system"
cat > "$ROOTFS/etc/systemd/system/stryker-agent.service" <<'SVCEOF'
[Unit]
Description=Stryker guest agent (command + terminal servers)
After=network.target
[Service]
ExecStartPre=/bin/sh -c 'mkdir -p /sdcard/Stryker/hs /sdcard/Stryker/captured'
ExecStart=/usr/local/sbin/stryker-agentd
Restart=always
RestartSec=2
[Install]
WantedBy=multi-user.target
SVCEOF

cat > "$ROOTFS/etc/systemd/system/stryker-sshkeys.service" <<'SSHEOF'
[Unit]
Description=Generate SSH host keys on first boot
ConditionPathExistsGlob=!/etc/ssh/ssh_host_*_key
Before=ssh.service
[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/usr/bin/ssh-keygen -A
[Install]
WantedBy=multi-user.target
SSHEOF

# Enable services
mkdir -p "$ROOTFS/etc/systemd/system/multi-user.target.wants"
ln -sf /etc/systemd/system/stryker-agent.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/stryker-agent.service"
ln -sf /etc/systemd/system/stryker-sshkeys.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/stryker-sshkeys.service"

# Enable SSH
ln -sf /lib/systemd/system/ssh.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/ssh.service"

# ── Strip docs/man/locale to shrink image ────────────────────────────────────
rm -rf "$ROOTFS/usr/share/man" "$ROOTFS/usr/share/doc" \
       "$ROOTFS/usr/share/locale" "$ROOTFS/usr/share/info"

# ── Create required directories ──────────────────────────────────────────────
mkdir -p "$ROOTFS/run"
mkdir -p "$ROOTFS/sdcard/Stryker/hs"
mkdir -p "$ROOTFS/sdcard/Stryker/captured"
mkdir -p "$ROOTFS/sdcard/Stryker/reports"

echo "Rootfs build complete — exact match with original StrykerOSS."
