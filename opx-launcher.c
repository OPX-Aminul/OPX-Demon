/*
 * opx-launcher — sets PR_SET_PDEATHSIG before exec'ing QEMU.
 *
 * When Android uninstalls/reinstalls the APK (parent SIGKILL'd), QEMU
 * survives as an orphan. This wrapper tells the kernel to send SIGKILL
 * to QEMU when our parent dies.
 *
 * Args: argv[0]=launcher, argv[1]=qemu-path, argv[2..]=args
 */

#include <signal.h>
#include <stdio.h>
#include <sys/prctl.h>
#include <unistd.h>

int main(int argc, char *argv[]) {
    if (argc < 2) {
        fprintf(stderr, "opx-launcher: usage: launcher <qemu-path> [args...]\n");
        return 2;
    }

    /* Tell the kernel to kill us when our parent dies. */
    (void)prctl(PR_SET_PDEATHSIG, SIGKILL, 0, 0, 0);

    /* Race guard: if parent already died, we are reparented to init. */
    if (getppid() == 1) {
        return 1;
    }

    /* exec the real QEMU binary. PR_SET_PDEATHSIG is preserved across execve(). */
    execv(argv[1], &argv[1]);

    /* execv only returns on failure. */
    perror("opx-launcher: execv");
    return 127;
}
