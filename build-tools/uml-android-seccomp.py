#!/usr/bin/env python3
# ─────────────────────────────────────────────────────────────────────────────
# uml-android-seccomp.py — make UML's SECCOMP userspace mode work on Android
#
# SYMPTOM (POCOPHONE F1, Android 10, kernel 4.14): linux-uml prints
#   "Checking that seccomp filters can be installed..." and the whole engine
#   dies with exit code 159 (128 + SIGSYS). No failure reason is printed
#   because the probe helper closed every fd above 0 and the kernel had not
#   registered its console yet.
#
# ROOT CAUSE (proven on device, 2026-09-28):
#   Android installs an unremovable SECCOMP_RET_KILL filter in every app
#   process (zygote). For syscall numbers the 4.14 syscall table does not
#   implement (close_range = 436 exists only from kernel 5.9), Android's
#   filter does NOT let the kernel answer -ENOSYS — the filter kills the
#   process with SIGSYS the moment the number is issued. Every fallback
#   written as "if close_range fails with -ENOSYS, close fds one by one"
#   is therefore dead code: the process is already dead before the return
#   value exists.
#
# FIX: on Android, never issue close_range at all.
#   * probe helper (start_up.c seccomp_helper): close fds 1..4095 one by one
#     with plain close() — always, not only on -ENOSYS.
#   * real stub (stub_exe.c real_init): same, closing 1..1023 with
#     stub_syscall1(__NR_close, fd). FD 0 is the signalling socket and must
#     survive, exactly as close_range(1, ~0U, 0) leaves it.
#   * userspace_tramp (skas/process.c): replace the CLOEXEC-marking
#     close_range(0, ~0U, CLOSE_RANGE_CLOEXEC) with a close-on-exec loop over
#     FD_MAXFD..0 using fcntl(F_SETFD, FD_CLOEXEC); memory fds are re-cleared
#     right after, unchanged.
#
# Kept from earlier rounds (still required):
#   * personality(PER_LINUX | ADDR_NO_RANDOMIZE) is skipped entirely —
#     personality(136) is not whitelisted either and attempting it kills the
#     process before anything is printed (verified: the CI-built kernel with
#     this patch boots past main() on the same phone).
#   * fcntl(0, F_SETFD, 0) after the dup2 in userspace_tramp, because the
#     CLOEXEC loop must not eat the signalling socket.
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

# 1a) The stub needs errno.h for -ENOSYS/-EINVAL in unpatched upstream shapes.
patch(SRC + "/arch/um/kernel/skas/stub_exe.c",
      """#include <asm/unistd.h>
#include <sysdep/stub.h>
""",
      """#include <asm/unistd.h>
#include <errno.h>
#include <sysdep/stub.h>
""")

# 1b) The real stub: close everything above fd 0 WITHOUT calling close_range.
#     Android 10's app filter kills unknown syscall numbers (436 is not in the
#     4.14 table) with SIGSYS before the kernel could even answer -ENOSYS, so
#     a "fall back on -ENOSYS" here was dead code — the engine died with exit
#     159 inside the probe before the stub ever ran. FD 0 is the signalling
#     socket used for FD passing and must survive, exactly as
#     close_range(1, ~0U, 0) leaves it. 1024 covers every fd the tramp can
#     leak into execveat; going higher is harmless.
patch(SRC + "/arch/um/kernel/skas/stub_exe.c",
      """
		res = stub_syscall3(__NR_close_range, 1, ~0U, 0);
		if (res != 0)
			stub_syscall1(__NR_exit, 13);
""",
      """
		/*
		 * Android's app seccomp filter kills the process the moment
		 * an unknown-to-the-4.14-syscall-table number (close_range,
		 * 436, kernel 5.9+) is issued — it never gets to answer
		 * -ENOSYS, so any "fall back when it fails" was dead code.
		 * Close the descriptors one by one instead; fd 0 is the
		 * signalling socket used for FD passing and must survive,
		 * exactly as close_range(1, ~0U, 0) leaves it.
		 */
		for (res = 1; res < 1024; res++)
			stub_syscall1(__NR_close, res);
""")

# 2) The probe helper in start_up.c: same rule — never issue close_range on
#    Android. This is the exact line that killed the engine in the field:
#    "Checking that seccomp filters can be installed..." followed by exit 159.
patch(SRC + "/arch/um/os-Linux/start_up.c",
      """
	struct sigaction sa;

	/* close_range is needed for the stub */
	if (stub_syscall3(__NR_close_range, 1, ~0U, 0))
		exit(1);
""",
      """
	struct sigaction sa;
	unsigned int fd;

	/*
	 * Never issue close_range here on Android: the app seccomp filter
	 * KILLs the process when an unknown-to-the-host-syscall-table number
	 * (close_range, 436, kernel 5.9+) is issued — it does not return
	 * -ENOSYS first, so a "fall back when it fails" could never run. That
	 * raw call is what died with SIGSYS (exit 159) right after
	 * "Checking that seccomp filters can be installed..." on Android 10
	 * (kernel 4.14). Close the descriptors one by one instead; fd 0 is
	 * the UML kernel's side of the probe, and everything above 0 is
	 * closed for the same reason close_range(1, ~0U, 0) would.
	 */
	for (fd = 1; fd < 4096; fd++)
		stub_syscall1(__NR_close, fd);
""")

# 2b) UML's main() calls personality(PER_LINUX | ADDR_NO_RANDOMIZE) before it
#     maps anything. Android 10's app filter does NOT whitelist personality
#     (no entry in bionic's SECCOMP_WHITELIST_*): even ATTEMPTING the call
#     kills the process with SIGSYS, before UML prints anything. Skip the
#     call entirely — the address-space layout only has to be stable, not
#     predictable, and the re-exec fallback cannot help anyway (execve
#     re-enters the same filter). ret=-1 makes the success branch dead.
patch(SRC + "/arch/um/os-Linux/main.c",
      """\t/* Disable randomization and re-exec if it was changed successfully */
	ret = personality(PER_LINUX | ADDR_NO_RANDOMIZE);""",
      """\t/* Disable randomization and re-exec if it was changed successfully */
	/*
	 * Android's app seccomp filter does not whitelist personality(); even
	 * attempting the call kills the process with SIGSYS. Skip it: the
	 * layout only has to be stable, not predictable, and the re-exec below
	 * cannot help anyway (execve re-enters the same filter). ret = -1
	 * makes the success branch dead without touching its shape.
	 */
	ret = -1;""")

# 3) userspace_tramp() marks every fd CLOEXEC with close_range(..., FLAG) —
#    on Android that call is the same SIGSYS landmine as above, so replace it
#    wholesale with a plain fcntl(F_SETFD, FD_CLOEXEC) loop. FD_MAXFD (1024)
#    is the kernel's per-process fd table limit, same ceiling upstream's
#    close_range covers; the fcntl flag never changes the fd numbers, and the
#    memory-related fds are re-cleared immediately after, unchanged. Note the
#    loop must run BEFORE the dup2: it would otherwise flag fd 0 (the
#    signalling socket) as close-on-exec, and the explicit clear below is
#    what keeps it alive across execveat.
patch(SRC + "/arch/um/os-Linux/skas/process.c",
      """
	syscall(__NR_close_range, 0, ~0U, CLOSE_RANGE_CLOEXEC);

	fcntl(init_data.stub_data_fd, F_SETFD, 0);
""",
      """
	/*
	 * Android's app seccomp filter kills close_range (436, kernel 5.9+,
	 * absent from Android 10's 4.14 table) instead of answering it with
	 * -ENOSYS, so mark the CLOEXEC flag one fd at a time with plain
	 * fcntl(). 1024 is the kernel's default fd-table ceiling (FD_SETSIZE;
	 * FD_MAXFD is a BSD-ism absent from bionic's headers, which is what
	 * USER_CFLAGS compiles against). Running before the dup2 below is
	 * safe: dup2() clears FD_CLOEXEC on the descriptor it creates, so fd 0
	 * (the signalling socket) survives execveat unflagged.
	 */
	{
		int fdi;

		for (fdi = 1023; fdi >= 0; fdi--)
			fcntl(fdi, F_SETFD, FD_CLOEXEC);
	}

	fcntl(init_data.stub_data_fd, F_SETFD, 0);
""")

print("uml-android-seccomp: OK")
