#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# StrykerOSS Exact-Match Build Script
# Builds: Rootfs + QEMU (uses Debian Trixie stock kernel)
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BLUE='\033[1;34m'
GREEN='\033[1;32m'
RED='\033[1;31m'
NC='\033[0m'

log() { printf "${BLUE}==>${NC} %s\n" "$*"; }
error() { printf "${RED}ERROR:${NC} %s\n" "$*"; exit 1; }
success() { printf "${GREEN}SUCCESS:${NC} %s\n" "$*"; }

show_help() {
    cat <<EOF
StrykerOSS Exact-Match Build Tool

Usage: $0 [command]

Commands:
  all       Build rootfs + QEMU
  rootfs    Build Debian Trixie rootfs only (rootfs.imgz)
  qemu      Build QEMU + libslirp only
  clean     Remove build artifacts

EOF
}

build_rootfs() {
    log "Building rootfs (Debian Trixie + pentest tools + stryker-agentd)..."
    docker build --network=host \
        --build-arg "SYSTEM_VERSION=60" \
        -t stryker-rootfs-builder --target rootfs-builder "$SCRIPT_DIR"
    docker rm -f stryker-rootfs-extract 2>/dev/null || true
    docker create --name stryker-rootfs-extract stryker-rootfs-builder
    mkdir -p "$SCRIPT_DIR/output"
    docker cp stryker-rootfs-extract:/work/rootfs.imgz "$SCRIPT_DIR/output/rootfs.imgz"
    docker rm stryker-rootfs-extract >/dev/null
    success "Built output/rootfs.imgz ($(du -h "$SCRIPT_DIR/output/rootfs.imgz" | cut -f1))"
}

build_qemu() {
    log "Building QEMU 11.0.2 for Android ARM64..."
    docker build --build-arg "QEMU_VERSION=11.0.2" \
        -t stryker-qemu-builder --target qemu-builder "$SCRIPT_DIR"
    docker rm -f stryker-qemu-extract 2>/dev/null || true
    docker create --name stryker-qemu-extract stryker-qemu-builder
    mkdir -p "$SCRIPT_DIR/output"
    docker cp stryker-qemu-extract:/opt/qemu-out/qemu-system-aarch64 "$SCRIPT_DIR/output/"
    docker cp stryker-qemu-extract:/opt/qemu-out/libslirp.so "$SCRIPT_DIR/output/"
    docker rm stryker-qemu-extract >/dev/null
    success "QEMU ready."
    ls -lh "$SCRIPT_DIR/output/qemu-system-aarch64" "$SCRIPT_DIR/output/libslirp.so"
}

[ $# -eq 0 ] && { show_help; exit 1; }

case "$1" in
    rootfs)    build_rootfs ;;
    qemu)      build_qemu ;;
    all)
        build_rootfs
        build_qemu
        ;;
    clean)
        log "Cleaning..."
        rm -rf "$SCRIPT_DIR/output"
        docker rmi stryker-rootfs-builder stryker-qemu-builder 2>/dev/null || true
        success "Cleaned."
        ;;
    *)
        show_help
        exit 1
        ;;
esac
