#!/bin/bash
# ─────────────────────────────────────────────────────────────────────────────
# StrykerOSS Unified Build Script
# Builds: Kernel, Initramfs, Rootfs, QEMU + native helpers
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Colors ────────────────────────────────────────────────────────────────────
BLUE='\033[1;34m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
NC='\033[0m'

log() { printf "${BLUE}==>${NC} %s\n" "$*"; }
warn() { printf "${YELLOW}WARNING:${NC} %s\n" "$*"; }
error() { printf "${RED}ERROR:${NC} %s\n" "$*"; exit 1; }
success() { printf "${GREEN}SUCCESS:${NC} %s\n" "$*"; }

show_help() {
    cat <<EOF
StrykerOSS Unified Build Tool

Usage: $0 [command]

Commands:
  all           Build everything (Kernel, Initramfs, Rootfs, QEMU)
  kernel        Build custom kernel only (Image)
  initramfs     Build kernel + initramfs (Image + initrd.img)
  rootfs        Build Debian Trixie rootfs squashfs (rootfs.imgz)
  qemu          Build QEMU + bridge + launcher
  clean         Remove build artifacts

EOF
}

build_kernel() {
    log "Building custom kernel 7.1.5 for aarch64 (Docker)..."
    docker build --network=host \
        --build-arg "KERNEL_VERSION=7.1.5" \
        -t stryker-kernel-builder --target kernel-builder "$SCRIPT_DIR"
    log "Extracting kernel artifact..."
    docker rm -f stryker-kernel-extract 2>/dev/null || true
    docker create --name stryker-kernel-extract stryker-kernel-builder
    mkdir -p "$SCRIPT_DIR/output"
    docker cp stryker-kernel-extract:/output/Image "$SCRIPT_DIR/output/Image"
    docker rm stryker-kernel-extract >/dev/null
    success "Custom kernel ready: output/Image ($(du -h "$SCRIPT_DIR/output/Image" | cut -f1))"
}

build_initramfs() {
    log "Building kernel + Debian Initramfs (Docker)..."
    docker build --network=host \
        --build-arg "KERNEL_VERSION=7.1.5" \
        -t stryker-builder --target packer "$SCRIPT_DIR"

    log "Extracting initramfs artifacts..."
    docker rm stryker-extract 2>/dev/null || true
    docker create --name stryker-extract stryker-builder /bin/true
    mkdir -p "$SCRIPT_DIR/output"
    docker cp stryker-extract:/output/Image "$SCRIPT_DIR/output/Image"
    docker cp stryker-extract:/output/initrd.img "$SCRIPT_DIR/output/initrd.img"
    docker rm stryker-extract >/dev/null
    success "Kernel + initramfs ready."
    ls -lh "$SCRIPT_DIR/output/Image" "$SCRIPT_DIR/output/initrd.img"
}

build_rootfs() {
    log "Building Debian Trixie rootfs squashfs (rootfs.imgz)..."
    docker build --network=host \
        --build-arg "SYSTEM_VERSION=60" \
        -t stryker-rootfs-builder --target rootfs-packer "$SCRIPT_DIR"
    docker rm -f stryker-rootfs-extract 2>/dev/null || true
    docker create --name stryker-rootfs-extract stryker-rootfs-builder
    mkdir -p "$SCRIPT_DIR/output"
    docker cp stryker-rootfs-extract:/work/rootfs.imgz "$SCRIPT_DIR/output/rootfs.imgz"
    docker rm stryker-rootfs-extract >/dev/null
    success "Built output/rootfs.imgz ($(du -h "$SCRIPT_DIR/output/rootfs.imgz" | cut -f1))"
}

build_qemu() {
    log "Building QEMU 11.0.2 for Android ARM64 (Docker)..."
    docker build --build-arg "QEMU_VERSION=11.0.2" \
        -t stryker-qemu-builder --target final "$SCRIPT_DIR"

    log "Extracting QEMU artifacts..."
    docker rm -f stryker-qemu-extract 2>/dev/null || true
    docker create --name stryker-qemu-extract stryker-qemu-builder

    mkdir -p "$SCRIPT_DIR/output"
    docker cp stryker-qemu-extract:/qemu-system-aarch64     "$SCRIPT_DIR/output/"
    docker cp stryker-qemu-extract:/libslirp.so              "$SCRIPT_DIR/output/"
    docker rm stryker-qemu-extract >/dev/null

    success "QEMU and helpers ready."
    ls -lh "$SCRIPT_DIR/output/qemu-system-aarch64" "$SCRIPT_DIR/output/libslirp.so"
}

[ $# -eq 0 ] && { show_help; exit 1; }

case "$1" in
    kernel)    build_kernel ;;
    initramfs) build_initramfs ;;
    rootfs)    build_rootfs ;;
    qemu)      build_qemu ;;
    all)
        build_initramfs
        build_rootfs
        build_qemu
        ;;
    clean)
        log "Cleaning up..."
        rm -rf "$SCRIPT_DIR/output"
        docker rmi stryker-kernel-builder stryker-builder stryker-rootfs-builder stryker-qemu-builder 2>/dev/null || true
        success "Cleaned."
        ;;
    *)
        show_help
        exit 1
        ;;
esac
