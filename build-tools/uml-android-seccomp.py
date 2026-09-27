#!/usr/bin/env python3
# ─────────────────────────────────────────────────────────────────────────────
# uml-android-seccomp.py — make UML's SECCOMP userspace mode work on Android
#
# SYMPTOM (POCOPHONE F1, Android 10): linux-uml boots the kernel to
#   "Run /sbin/init as init process" and dies with exit code 159 (SIGSYS).
#
# WHY: Android installs an unremovable seccomp filter in every app process
# (zygote, SECCOMP_RET_KILL). In UML's SECCOMP userspace mode that filter is
# survivable: the stub traps every guest syscall and hands it to the UML
# kernel, and the stub's own host syscalls (futex/recvmsg/close/mmap/munmap/
# rt_sigreturn) are inside Android's app allowlist. In ptrace mode the guest
# executes syscalls as REAL host syscalls in the app process, and the first
# one Android 10 does not allow (systemd needs mount/umount2/chown/mknod and
# friends) kills the whole engine with SIGSYS → exit 159.
#
# The tree never reaches SECCOMP mode on Android 10 because both the probe
# (arch/um/os-Linux/start_up.c seccomp_helper) and the real stub
# (arch/um/kernel/skas/stub_exe.c real_init) hard-require close_range(436),
# a kernel 5.9+ syscall. Android 10 ships kernel 4.9/4.14, the raw call
# answers -ENOSYS, the probe prints "no close_range" and UML silently falls
# back to ptrace mode. Fix: fall back to closing descriptors one by one when
# close_range is not implemented. fd 0 is the stub's signalling socket and
# must survive — exactly what close_range(1, ~0U, 0) already guarantees.
#
# Usage: uml-android-seccomp.py <linux-src-dir>   (applies in place, idempotent)
# ─────────────────────────────────────────────────────────────────────────────
import sys

def patch(path, old, new):
    with open(path) as fh:
        src = fh.read()
    if new in src:
        print("  already patched: %s" % path)
        return
    if src.count(old) != 1:
        sys.exit("FATAL: %s: anchor not found (or not unique) — wrong tree revision" % path)
    with open(path, "w") as fh:
        fh.write(src.replace(old, new))
    print("  patched: %s" % path)

SRC = sys.argv[1] if len(sys.argv) > 1 else "."

# 1a) The stub needs errno.h for -ENOSYS/-EINVAL.
patch(SRC + "/arch/um/kernel/skas/stub_exe.c",
      """#include <asm/unistd.h>
#include <sysdep/stub.h>
""",
      """#include <asm/unistd.h>
#include <errno.h>
#include <sysdep/stub.h>
""")

# 1b) The real stub: close_range(1, ~0U, 0) → fall back to per-fd close().
patch(SRC + "/arch/um/kernel/skas/stub_exe.c",
      """		res = stub_syscall3(__NR_close_range, 1, ~0U, 0);
		if (res != 0)
			stub_syscall1(__NR_exit, 13);
""",
      """		res = stub_syscall3(__NR_close_range, 1, ~0U, 0);
		if (res != 0 && res != -ENOSYS && res != -EINVAL)
			stub_syscall1(__NR_exit, 13);
		if (res != 0) {
			/*
			 * Kernel < 5.9 has no close_range and answers the raw
			 * call with -ENOSYS — Android 10 ships 4.9/4.14. Close
			 * the descriptors one by one instead. FD 0 is the
			 * signalling socket used for FD passing and must
			 * survive, exactly as close_range(1, ~0U, 0) leaves it.
			 */
			for (res = 1; res < 4096; res++)
				stub_syscall1(__NR_close, res);
		}
""")

# 2) The probe helper in start_up.c: same fallback, so init_seccomp() reports
#    "OK" and SECCOMP mode is chosen instead of ptrace on pre-5.9 hosts.
patch(SRC + "/arch/um/os-Linux/start_up.c",
      """	struct sigaction sa;

	/* close_range is needed for the stub */
	if (stub_syscall3(__NR_close_range, 1, ~0U, 0))
		exit(1);
""",
      """	struct sigaction sa;
	long res;
	unsigned int fd;

	/*
	 * close_range is needed for the stub, but kernel < 5.9 has no such
	 * syscall and answers the raw call with -ENOSYS — Android 10 ships
	 * 4.9/4.14. Without the fallback below the probe reports
	 * "no close_range" and UML drops to ptrace mode, where the first
	 * guest syscall Android's app seccomp filter does not allow kills
	 * the engine with SIGSYS. Close the descriptors one by one instead;
	 * fd 0 must survive, exactly as close_range(1, ~0U, 0) leaves it.
	 */
	res = stub_syscall3(__NR_close_range, 1, ~0U, 0);
	if (res && res != -ENOSYS && res != -EINVAL)
		exit(1);
	if (res) {
		for (fd = 1; fd < 4096; fd++)
			stub_syscall1(__NR_close, fd);
	}
""")

# 2b) UML's main() calls personality(PER_LINUX | ADDR_NO_RANDOMIZE) before it
#     maps anything. Android 10's app filter does NOT whitelist personality
#     (no entry in bionic's SECCOMP_WHITELIST_*): even ATTEMPTING the call
#     kills the process with SIGSYS, before UML prints anything. Skip the
#     call entirely — the address-space layout only has to be stable, not
#     predictable, and the re-exec fallback cannot help anyway (execve
#     re-enters the same filter). ret=-1 makes the success branch dead.
patch(SRC + "/arch/um/os-Linux/main.c",
      """\t/* Disable randomization and re-exec if it was changed successfully */\n\tret = personality(PER_LINUX | ADDR_NO_RANDOMIZE);""",
      """\t/* Disable randomization and re-exec if it was changed successfully */\n\t/*\n\t * Android's app seccomp filter does not whitelist personality(); even\n\t * attempting the call kills the process with SIGSYS. Skip it: the\n\t * layout only has to be stable, not predictable, and the re-exec below\n\t * cannot help anyway (execve re-enters the same filter). ret = -1\n\t * makes the success branch dead without touching its shape.\n\t */\n\tret = -1;""")

# 3) userspace_tramp() marks every fd CLOEXEC with close_range(..., FLAG) —
#    a no-op on pre-5.9 hosts (raw -ENOSYS, nothing gets marked), so the UML
#    port listener fd leaks across the execveat. The stub closes everything
#    above 0 afterwards, but belt-and-braces: after the dup2 to fd 0, clear
#    CLOEXEC explicitly on it.
patch(SRC + "/arch/um/os-Linux/skas/process.c",
      """	/* dup2 signaling FD/socket to STDIN */
	if (dup2(tramp_data->sockpair[0], 0) < 0)
		exit(3);
	close(tramp_data->sockpair[0]);
""",
      """	/* dup2 signaling FD/socket to STDIN */
	if (dup2(tramp_data->sockpair[0], 0) < 0)
		exit(3);
	close(tramp_data->sockpair[0]);
	/*
	 * On pre-5.9 hosts the CLOEXEC-marking close_range above is a raw
	 * -ENOSYS no-op, so fds keep their flags. dup2() deliberately does
	 * not clear the flag, and a CLOEXEC fd 0 would be closed by the
	 * very execveat below — the stub would then start with no
	 * signalling socket. Clear it explicitly.
	 */
	if (fcntl(0, F_SETFD, 0) != 0)
		exit(3);
""")

print("uml-android-seccomp: OK")
