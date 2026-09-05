# ─────────────────────────────────────────────────────────────────────────────
# StrykerOSS Unified Dockerfile
# Custom arm64 kernel: Xiaomi/MIUI USB ep0 maxpacket fix + USB-WiFi drivers
# Docker Buildx GHA mode=max caches ALL layers for fast rebuilds
# ─────────────────────────────────────────────────────────────────────────────

# ==============================================================================
# SECTION 0: Custom kernel (Xiaomi/MIUI USB fix + USB-WiFi drivers + firmware)
# ==============================================================================

FROM debian:bookworm AS kernel-builder
ARG KERNEL_VERSION=6.12.94
ENV DEBIAN_FRONTEND=noninteractive
WORKDIR /usr/src

RUN apt-get update && apt-get install -y --no-install-recommends \
    bc bison flex libssl-dev libelf-dev make patch cpio kmod wget xz-utils python3 \
    crossbuild-essential-arm64 \
    && rm -rf /var/lib/apt/lists/*

RUN wget -q https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-${KERNEL_VERSION}.tar.xz \
    && tar xf linux-${KERNEL_VERSION}.tar.xz

COPY build-tools/xiaomi-hub.patch /usr/src/xiaomi-hub.patch
COPY build-tools/usb-wifi.fragment /usr/src/usb-wifi.fragment

RUN cd linux-${KERNEL_VERSION} \
    && patch -p1 < /usr/src/xiaomi-hub.patch \
    && grep -q 'correcting to full-speed' drivers/usb/core/hub.c \
    && make ARCH=arm64 defconfig \
    && scripts/kconfig/merge_config.sh -m -O . ./.config /usr/src/usb-wifi.fragment >/dev/null 2>&1 || true \
    && make ARCH=arm64 olddefconfig \
    && grep -q 'CONFIG_USB_XHCI_HCD=y' .config \
    && grep -q 'CONFIG_RTL8XXXU=y' .config \
    && grep -q 'CONFIG_ATH9K_HTC=y' .config \
    && make -j$(nproc) ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- Image \
    && make -j$(nproc) ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- modules \
    && make ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- INSTALL_MOD_PATH=/work/modules modules_install

# Bake the Realtek 8188FU/EU firmware (rtl8xxxu) + rtw88 + ath9k_htc blobs
RUN mkdir -p /work/firmware/rtlwifi /work/firmware/rtw88 /work/firmware/ath9k_htc \
    && cd /work/firmware/rtlwifi \
    && for f in rtl8188fu.fw rtl8188fufw.bin rtl8188eufw.bin rtl8188efw.bin \
                rtl8188cufw.bin rtl8192cufw.bin rtl8192eufw.bin rtl8723bu_fw.bin \
                rtl8723aufw.bin rtl8812aufw.bin rtl8812aefw.bin; do \
         wget -q -O "$f" "https://gitlab.com/kernel-firmware/linux-firmware/-/raw/main/rtlwifi/$f" || true; \
       done \
    && cd /work/firmware/rtw88 \
    && for f in rtw8821c_fw.bin rtw8822b_fw.bin rtw8822c_fw.bin rtw8812a_fw.bin; do \
         wget -q -O "$f" "https://gitlab.com/kernel-firmware/linux-firmware/-/raw/main/rtw88/$f" || true; \
       done \
    && cd /work/firmware/ath9k_htc \
    && for f in htc_9271-1.4.0.fw htc_7010-1.4.0.fw; do \
         wget -q -O "$f" "https://gitlab.com/kernel-firmware/linux-firmware/-/raw/main/ath9k_htc/$f" || true; \
       done \
    && find /work/firmware -type f -size +1k | sort \
    && for f in rtlwifi/rtl8188fu.fw rtlwifi/rtl8188fufw.bin rtlwifi/rtl8188eufw.bin \
                rtlwifi/rtl8188cufw.bin rtw88/rtw8821c_fw.bin \
                rtw88/rtw8822b_fw.bin rtw88/rtw8822c_fw.bin \
                ath9k_htc/htc_9271-1.4.0.fw; do \
         [ -s "/work/firmware/$f" ] || { echo "FATAL: required firmware missing: $f" >&2; exit 1; }; \
       done

# ==============================================================================
# SECTION 1: Rootfs (Debian Trixie + pentest tools + stryker-agentd)
# ==============================================================================

FROM debian:trixie AS rootfs-builder
ARG SYSTEM_VERSION=0
ENV DEBIAN_FRONTEND=noninteractive
ENV DEBIAN_VERSION=trixie

RUN apt-get update && apt-get install -y --no-install-recommends \
    debootstrap \
    squashfs-tools \
    e2fsprogs \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /work

# Create minimal rootfs via debootstrap
RUN debootstrap --variant=minbase --arch=arm64 \
    trixie /work/rootfs http://deb.debian.org/debian

# Copy guest core payload (pixie.py, checker.py, stryker-agentd, etc.)
COPY rootless-assets/stryker-guest-core.tar /work/stryker-guest-core.tar

# Install packages (exact match with original dpkg status)
COPY build-rootfs/build-rootfs.sh /work/build-rootfs.sh
RUN chmod +x /work/build-rootfs.sh && /work/build-rootfs.sh

# Install the custom kernel + matching modules + firmware into the rootfs
COPY --from=kernel-builder /work/modules/lib/modules /work/custom-modules
COPY --from=kernel-builder /work/firmware /work/custom-firmware
COPY --from=kernel-builder /usr/src/linux-6.12.94/arch/arm64/boot/Image /work/custom-Image

RUN chroot /work/rootfs /bin/sh -c 'export DEBIAN_FRONTEND=noninteractive; \
        dpkg --purge linux-image-arm64 >/dev/null 2>&1 || true; \
        rm -rf /lib/modules /usr/lib/modules /boot/vmlinuz-* /boot/initrd.img-*' \
    && mkdir -p /work/rootfs/lib/modules \
    && cp -a /work/custom-modules/. /work/rootfs/lib/modules/ \
    && mkdir -p /work/rootfs/usr/lib/firmware \
    && cp -a /work/custom-firmware/. /work/rootfs/usr/lib/firmware/ \
    && KVER=$(ls /work/rootfs/lib/modules | head -n1) \
    && cp /work/custom-Image /work/rootfs/boot/vmlinuz-${KVER} \
    && mkdir -p /work/rootfs/proc /work/rootfs/sys /work/rootfs/dev \
    && mount -t proc proc /work/rootfs/proc 2>/dev/null || true \
    && mount -t devtmpfs devtmpfs /work/rootfs/dev 2>/dev/null || true \
    && chroot /work/rootfs /bin/sh -c "export DEBIAN_FRONTEND=noninteractive; \
         update-initramfs -c -k ${KVER} >/dev/null 2>&1; depmod -a ${KVER} >/dev/null 2>&1; true" \
    && umount /work/rootfs/dev 2>/dev/null || true \
    && umount /work/rootfs/proc 2>/dev/null || true \
    && ls -lh /work/rootfs/boot/vmlinuz-* /work/rootfs/boot/initrd.img-*

# Extract the custom kernel + regenerated initrd. The custom-kernel install above never
# silently skips, so a missing /work/Image or /work/initrd.img here is a hard failure —
# a VM without them cannot boot at all, and nothing a later apt run would add could fix it.
RUN cp /work/rootfs/boot/vmlinuz-* /work/Image
RUN cp /work/rootfs/boot/initrd.img* /work/initrd.img
RUN ls -lh /work/Image /work/initrd.img

# Create gzip-compressed ext4 image (exact format match with original rootfs.imgz)
RUN dd if=/dev/zero of=/work/rootfs.img bs=1M count=1500 \
    && mkfs.ext4 -q -F -d /work/rootfs /work/rootfs.img \
    && gzip -9 -c /work/rootfs.img > /work/rootfs.imgz \
    && rm /work/rootfs.img \
    && ls -lh /work/rootfs.imgz

# ==============================================================================
# SECTION 2: QEMU & Bridge (Android ARM64) — Exact Match
# ==============================================================================

FROM debian:bookworm AS qemu-builder
ARG QEMU_VERSION=11.0.2
ENV QEMU_DIR=qemu-${QEMU_VERSION}
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    wget curl unzip xz-utils ca-certificates git bzip2 ninja-build python3 python3-pip \
    pkg-config flex bison make cmake autoconf automake libtool libglib2.0-dev \
    libglib2.0-bin gettext libintl-perl binutils-aarch64-linux-gnu patchelf \
    && rm -rf /var/lib/apt/lists/*
RUN pip3 install --break-system-packages meson 2>/dev/null || pip3 install meson

# NDK (exact version for cross-compilation)
RUN wget -q https://dl.google.com/android/repository/android-ndk-r27c-linux.zip -O /tmp/ndk.zip \
    && unzip -q /tmp/ndk.zip -d /opt && mv /opt/android-ndk-r27c /opt/ndk && rm /tmp/ndk.zip
ENV NDK=/opt/ndk LLVM=/opt/ndk/toolchains/llvm/prebuilt/linux-x86_64 PREFIX=/opt/deps
ENV CC="${LLVM}/bin/aarch64-linux-android26-clang" AR="${LLVM}/bin/llvm-ar" RANLIB="${LLVM}/bin/llvm-ranlib"
RUN mkdir -p ${PREFIX}/{lib,include,lib/pkgconfig}

# Cross-compilation setup
RUN printf '#!/bin/sh\nexport PKG_CONFIG_LIBDIR=/opt/deps/lib/pkgconfig\nexport PKG_CONFIG_PATH=\nexec pkg-config "$@"\n' \
    > /usr/local/bin/aarch64-android-pkg-config && chmod +x /usr/local/bin/aarch64-android-pkg-config \
    && ln -s /usr/local/bin/aarch64-android-pkg-config ${LLVM}/bin/llvm-pkg-config

COPY build-tools/cross-android-aarch64.ini /opt/cross-android-aarch64.ini

# Dependencies (exact versions matching original build)
RUN wget -q https://github.com/PCRE2Project/pcre2/releases/download/pcre2-10.44/pcre2-10.44.tar.gz && tar xf pcre2-10.44.tar.gz && cd pcre2-10.44 && ./configure --host=aarch64-linux-android --prefix=${PREFIX} --enable-static --disable-shared CC="${CC}" && make -j$(nproc) install
RUN wget -q https://github.com/libffi/libffi/releases/download/v3.4.6/libffi-3.4.6.tar.gz && tar xf libffi-3.4.6.tar.gz && cd libffi-3.4.6 && ./configure --host=aarch64-linux-android --prefix=${PREFIX} --enable-static --disable-shared CC="${CC}" && make -j$(nproc) install

# iconv shim (Android Bionic lacks iconv)
RUN printf '#ifndef OPX_ICONV_H\n#define OPX_ICONV_H\n#include <stddef.h>\ntypedef void *iconv_t;\niconv_t iconv_open(const char *tocode, const char *fromcode);\nsize_t iconv(iconv_t cd, char **inbuf, size_t *inbytesleft, char **outbuf, size_t *outbytesleft);\nint iconv_close(iconv_t cd);\n#endif\n' > ${PREFIX}/include/iconv.h \
    && printf '#include <errno.h>\n#include <stddef.h>\n#include <string.h>\n#include <iconv.h>\niconv_t iconv_open(const char *tocode, const char *fromcode) {\n    if (!tocode || !fromcode) { errno = EINVAL; return (iconv_t)-1; }\n    return (iconv_t)1;\n}\nsize_t iconv(iconv_t cd, char **inbuf, size_t *inbytesleft, char **outbuf, size_t *outbytesleft) {\n    (void)cd;\n    if (!inbuf || !inbytesleft || !outbuf || !outbytesleft) { errno = EINVAL; return (size_t)-1; }\n    if (!*inbuf || *inbytesleft == 0) return 0;\n    if (!*outbuf || *outbytesleft == 0) { errno = E2BIG; return (size_t)-1; }\n    size_t n = (*inbytesleft < *outbytesleft) ? *inbytesleft : *outbytesleft;\n    memcpy(*outbuf, *inbuf, n);\n    *inbuf += n;\n    *outbuf += n;\n    *inbytesleft -= n;\n    *outbytesleft -= n;\n    if (*inbytesleft != 0) { errno = E2BIG; return (size_t)-1; }\n    return 0;\n}\nint iconv_close(iconv_t cd) { (void)cd; return 0; }\n' > /tmp/iconv_shim.c \
    && ${CC} --sysroot=${LLVM}/sysroot -target aarch64-linux-android26 -I${PREFIX}/include -c /tmp/iconv_shim.c -o /tmp/iconv_shim.o \
    && ${AR} rcs ${PREFIX}/lib/libiconv.a /tmp/iconv_shim.o \
    && cp ${PREFIX}/lib/libiconv.a ${LLVM}/sysroot/usr/lib/aarch64-linux-android/26/libiconv.a

RUN wget -q https://download.gnome.org/sources/glib/2.82/glib-2.82.5.tar.xz && tar xf glib-2.82.5.tar.xz && cd glib-2.82.5 && meson setup _build --cross-file /opt/cross-android-aarch64.ini --prefix ${PREFIX} --default-library static -Dselinux=disabled -Dlibmount=disabled && ninja -C _build install
RUN wget -q https://cairographics.org/releases/pixman-0.44.2.tar.xz && tar xf pixman-0.44.2.tar.xz && cd pixman-0.44.2 && meson setup _build --cross-file /opt/cross-android-aarch64.ini --prefix ${PREFIX} --default-library static -Da64-neon=disabled && ninja -C _build install
RUN wget -q https://download.savannah.gnu.org/releases/attr/attr-2.5.2.tar.gz && tar xf attr-2.5.2.tar.gz && cd attr-2.5.2 && ./configure --host=aarch64-linux-android --prefix=${PREFIX} --enable-static --disable-shared CC="${CC}" && make -j$(nproc) install && cp ${PREFIX}/lib/libattr.a ${LLVM}/sysroot/usr/lib/aarch64-linux-android/26/libattr.a
RUN git clone --depth=1 https://github.com/kaniini/libucontext.git /tmp/libucontext && make -C /tmp/libucontext ARCH=aarch64 CC="${CC}" EXPORT_UNPREFIXED=yes && install -Dm644 /tmp/libucontext/libucontext.a ${PREFIX}/lib/libucontext.a && install -Dm644 /tmp/libucontext/include/libucontext/libucontext.h ${PREFIX}/include/libucontext/libucontext.h && install -Dm644 /tmp/libucontext/arch/common/include/libucontext/bits.h ${PREFIX}/include/libucontext/bits.h \
    && printf '#ifndef OPX_UCONTEXT_SHIM_H\n#define OPX_UCONTEXT_SHIM_H\n#include_next <ucontext.h>\n#include <libucontext/libucontext.h>\n#define getcontext libucontext_getcontext\n#define makecontext libucontext_makecontext\n#define setcontext libucontext_setcontext\n#define swapcontext libucontext_swapcontext\n#endif\n' > ${PREFIX}/include/ucontext.h

# libusb (required for USB passthrough)
RUN wget -q https://github.com/libusb/libusb/releases/download/v1.0.27/libusb-1.0.27.tar.bz2 \
    && tar xf libusb-1.0.27.tar.bz2 && cd libusb-1.0.27 \
    && ./configure --host=aarch64-linux-android --prefix=${PREFIX} --enable-static --disable-shared --disable-udev CC="${CC}" \
    && make -j$(nproc) install

# QEMU source + patches
RUN wget -q https://download.qemu.org/${QEMU_DIR}.tar.xz && tar xf ${QEMU_DIR}.tar.xz
RUN sed -i "s/rt = cc.find_library('rt', required: true)/rt = cc.find_library('rt', required: false)/" ${QEMU_DIR}/meson.build
RUN printf '#undef st_atime_nsec\n#undef st_mtime_nsec\n#undef st_ctime_nsec\n' | cat - ${QEMU_DIR}/fsdev/9p-marshal.h > /tmp/9p-marshal.h && mv /tmp/9p-marshal.h ${QEMU_DIR}/fsdev/9p-marshal.h
RUN printf '# disabled for Android Bionic\n' > ${QEMU_DIR}/contrib/ivshmem-server/meson.build \
    && printf '# disabled for Android Bionic\n' > ${QEMU_DIR}/contrib/ivshmem-client/meson.build

# shm_open/shm_unlink shim
RUN printf '#ifndef OPX_SHM_SHIM_H\n#define OPX_SHM_SHIM_H\nextern int shm_open(const char *, int, unsigned);\nextern int shm_unlink(const char *);\n#endif\n' > /opt/shm_shim.h
RUN printf '#include <sys/syscall.h>\n#include <unistd.h>\n#include <errno.h>\n#ifndef SYS_memfd_create\n#define SYS_memfd_create 279\n#endif\nint shm_open(const char *n, int f, unsigned m) {\n    (void)f; (void)m;\n    while (*n == '"'"'/'"'"') n++;\n    long fd = syscall(SYS_memfd_create, n, 0);\n    if (fd < 0) { errno = (int)(-fd); return -1; }\n    return (int)fd;\n}\nint shm_unlink(const char *n) { (void)n; return 0; }\n' > /tmp/shm_stub.c \
    && ${CC} --sysroot=${LLVM}/sysroot -target aarch64-linux-android26 -c /tmp/shm_stub.c -o /tmp/shm_stub.o \
    && ${AR} rcs ${PREFIX}/lib/libshm.a /tmp/shm_stub.o

# coroutine sigsetjmp shim (PAC-free for Pixel 10)
RUN printf '#ifndef OPX_QEMU_JMP_H\n#define OPX_QEMU_JMP_H\n#include <setjmp.h>\nextern int _qemu_setjmp(sigjmp_buf);\n__attribute__((noreturn)) extern void _qemu_longjmp(sigjmp_buf, int);\n#endif\n' > /opt/qemu_jmp.h \
    && printf '.text\n.global _qemu_setjmp\n.type _qemu_setjmp,%%function\n_qemu_setjmp:\nstp x19,x20,[x0,#0]\nstp x21,x22,[x0,#16]\nstp x23,x24,[x0,#32]\nstp x25,x26,[x0,#48]\nstp x27,x28,[x0,#64]\nstp x29,x30,[x0,#80]\nmov x9,sp\nstr x9,[x0,#96]\nstp d8,d9,[x0,#104]\nstp d10,d11,[x0,#120]\nstp d12,d13,[x0,#136]\nstp d14,d15,[x0,#152]\nmov w0,#0\nret\n.size _qemu_setjmp,.-_qemu_setjmp\n.global _qemu_longjmp\n.type _qemu_longjmp,%%function\n_qemu_longjmp:\nldp x19,x20,[x0,#0]\nldp x21,x22,[x0,#16]\nldp x23,x24,[x0,#32]\nldp x25,x26,[x0,#48]\nldp x27,x28,[x0,#64]\nldp x29,x30,[x0,#80]\nldr x9,[x0,#96]\nmov sp,x9\nldp d8,d9,[x0,#104]\nldp d10,d11,[x0,#120]\nldp d12,d13,[x0,#136]\nldp d14,d15,[x0,#152]\ncmp w1,#0\ncsinc w0,w1,wzr,ne\nbr x30\n.size _qemu_longjmp,.-_qemu_longjmp\n.section .note.GNU-stack,"",%%progbits\n' > /tmp/qemu_jmp.S \
    && ${CC} --sysroot=${LLVM}/sysroot -target aarch64-linux-android26 -c /tmp/qemu_jmp.S -o /tmp/qemu_jmp.o \
    && ${AR} rcs ${PREFIX}/lib/libqemujmp.a /tmp/qemu_jmp.o

# Patch coroutine-ucontext.c
RUN sed -i '1i#include "/opt/qemu_jmp.h"' ${QEMU_DIR}/util/coroutine-ucontext.c \
    && sed -i 's/\bsigsetjmp(\([^,]*\), *0)/_qemu_setjmp(\1)/g' ${QEMU_DIR}/util/coroutine-ucontext.c \
    && sed -i 's/\bsiglongjmp(/_qemu_longjmp(/g' ${QEMU_DIR}/util/coroutine-ucontext.c

# USB passthrough: skip libusb device enumeration
RUN sed -i 's@^    rc = libusb_init(&ctx);@#if defined(__ANDROID__)\n    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);\n#endif\n    rc = libusb_init(\&ctx);@' ${QEMU_DIR}/hw/usb/host-libusb.c \
    && grep -q LIBUSB_OPTION_NO_DEVICE_DISCOVERY ${QEMU_DIR}/hw/usb/host-libusb.c

# Xiaomi/MIUI USB speed correction quirk (EXTRA improvement over original)
COPY build-tools/xiaomi-usb-quirk.c /tmp/xiaomi-usb-quirk.c
RUN cd ${QEMU_DIR}/hw/usb \
    && sed -i '/cbuf\[7\] = 64;/r /tmp/xiaomi-usb-quirk.c' host-libusb.c \
    && grep -q 'USB_SPEED_LOW.*USB_SPEED_FULL' host-libusb.c

# Configure and build QEMU (exact flags matching original)
RUN cd ${QEMU_DIR} && ./configure \
    --cc="${CC}" \
    --cross-prefix="${LLVM}/bin/llvm-" \
    --extra-cflags="-fPIC -DANDROID -include /opt/shm_shim.h -I${PREFIX}/include -I${PREFIX}/include/glib-2.0 -I${PREFIX}/lib/glib-2.0/include" \
    --extra-ldflags="-L${PREFIX}/lib -Wl,-z,max-page-size=16384 ${PREFIX}/lib/libucontext.a ${PREFIX}/lib/libshm.a ${PREFIX}/lib/libqemujmp.a" \
    --prefix=/opt/qemu-out \
    --target-list=aarch64-softmmu \
    --enable-tcg \
    --enable-slirp \
    --enable-virtfs \
    --enable-libusb \
    --enable-pie \
    --disable-docs \
    --disable-gtk \
    --disable-sdl \
    --disable-vnc \
    --disable-vhost-user \
    --disable-plugins \
    --with-coroutine=ucontext \
    && make -j$(nproc) install

# Rename QEMU binary. QEMU is linked with DT_NEEDED "libslirp.so.0" while the
# app ships the library as "libslirp.so", so also repoint the binary's needed
# entry to the shipped name. Without this, Android boots die with:
# CANNOT LINK EXECUTABLE ... library "libslirp.so.0" not found.
RUN cp /opt/qemu-out/bin/qemu-system-aarch64 /opt/qemu-out/qemu-system-aarch64 \
    && cp /opt/qemu-out/lib/libslirp.so.0 /opt/qemu-out/libslirp.so \
    && patchelf --set-soname libslirp.so /opt/qemu-out/libslirp.so \
    && patchelf --replace-needed libslirp.so.0 libslirp.so /opt/qemu-out/qemu-system-aarch64

# ==============================================================================
# SECTION 3: Final Artifacts — Exact Match with Original Release
# ==============================================================================

FROM scratch AS final
# Kernel (Debian stock — exact match)
COPY --from=rootfs-builder /work/Image /Image
# Initramfs (Debian stock — exact match)
COPY --from=rootfs-builder /work/initrd.img /initrd.img
# Rootfs (gzip-compressed ext4 — exact format match)
COPY --from=rootfs-builder /work/rootfs.imgz /rootfs.imgz
# QEMU binary + networking library
COPY --from=qemu-builder /opt/qemu-out/qemu-system-aarch64 /qemu-system-aarch64
COPY --from=qemu-builder /opt/qemu-out/libslirp.so /libslirp.so
