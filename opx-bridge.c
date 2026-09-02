/*
 * opx-bridge — virtio-console socket <-> PTY relay for StrykerOSS
 *
 * Bidirectional relay between:
 *   stdin/stdout  — PTY slave (Termux TerminalSession)
 *   terminal.sock — QEMU virtio-console chardev (/dev/hvc0 in VM; primary terminal)
 *
 * Signals:
 *   SIGPIPE  — ignored; EPIPE on write = EOF
 *   SIGINT   — graceful shutdown
 *   SIGTERM  — graceful shutdown
 *   SIGWINCH — async flag, debounced in select() loop
 *
 * Args: <terminal.sock> <ctrl.sock>
 */

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/select.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#define RESIZE_DEBOUNCE_MS 80
#define WAKE_FALLBACK_POLL_MS 50

static volatile sig_atomic_t g_winch    = 0;
static volatile sig_atomic_t g_shutdown = 0;
static int                   g_ctrl_fd   = -1;
static int                   g_term_fd   = -1;
static int                   g_winch_pending  = 0;
static long                  g_winch_last_ms  = 0;

static int                   g_wake_fd[2] = { -1, -1 };

static struct termios        g_saved_termios;
static int                   g_termios_saved = 0;

static void on_winch(int sig) {
    (void)sig;
    g_winch = 1;
}

static void on_shutdown(int sig) {
    (void)sig;
    g_shutdown = 1;
    if (g_wake_fd[1] >= 0) {
        char c = 'x';
        (void)write(g_wake_fd[1], &c, 1);
    }
}

static long now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec * 1000L + ts.tv_nsec / 1000000L;
}

static void send_resize(void) {
    struct winsize ws;
    if (g_ctrl_fd < 0) return;
    if (ioctl(STDOUT_FILENO, TIOCGWINSZ, &ws) < 0) return;
    if (ws.ws_row == 0 || ws.ws_col == 0) return;
    char buf[64];
    int n = snprintf(buf, sizeof(buf), "RESIZE %d %d\n", ws.ws_row, ws.ws_col);
    if (write(g_ctrl_fd, buf, n) < 0) {}
}

static void cleanup(void) {
    if (g_termios_saved) tcsetattr(STDIN_FILENO, TCSANOW, &g_saved_termios);
    if (g_ctrl_fd >= 0) close(g_ctrl_fd);
    if (g_term_fd >= 0) close(g_term_fd);
    if (g_wake_fd[0] >= 0) close(g_wake_fd[0]);
    if (g_wake_fd[1] >= 0) close(g_wake_fd[1]);
}

int main(int argc, char *argv[]) {
    if (argc < 3) {
        fprintf(stderr, "Usage: opx-bridge <terminal.sock> <ctrl.sock>\n");
        return 1;
    }

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = on_shutdown;
    sigaction(SIGINT,  &sa, NULL);
    sigaction(SIGTERM, &sa, NULL);
    sa.sa_handler = on_winch;
    sigaction(SIGWINCH, &sa, NULL);
    sa.sa_handler = SIG_IGN;
    sigaction(SIGPIPE, &sa, NULL);

    socketpair(AF_UNIX, SOCK_STREAM, 0, g_wake_fd);

    /* Save and set raw termios */
    if (tcgetattr(STDIN_FILENO, &g_saved_termios) == 0) {
        g_termios_saved = 1;
        struct termios raw = g_saved_termios;
        cfmakeraw(&raw);
        tcsetattr(STDIN_FILENO, TCSANOW, &raw);
    }

    /* Connect to terminal.sock */
    g_term_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, argv[1], sizeof(addr.sun_path) - 1);
    if (connect(g_term_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("connect terminal.sock");
        cleanup();
        return 1;
    }

    /* Connect to ctrl.sock */
    g_ctrl_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, argv[2], sizeof(addr.sun_path) - 1);
    if (connect(g_ctrl_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("connect ctrl.sock");
        cleanup();
        return 1;
    }

    /* Send initial resize */
    send_resize();

    int maxfd = (g_term_fd > g_wake_fd[0] ? g_term_fd : g_wake_fd[0]);
    if (STDIN_FILENO > maxfd) maxfd = STDIN_FILENO;

    while (!g_shutdown) {
        fd_set rfds;
        FD_ZERO(&rfds);
        FD_SET(STDIN_FILENO, &rfds);
        FD_SET(g_term_fd, &rfds);
        if (g_wake_fd[0] >= 0) FD_SET(g_wake_fd[0], &rfds);

        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = WAKE_FALLBACK_POLL_MS * 1000;

        int ret = select(maxfd + 1, &rfds, NULL, NULL, &tv);
        if (ret < 0) {
            if (errno == EINTR) continue;
            break;
        }

        /* Handle debounce */
        if (g_winch) {
            g_winch = 0;
            g_winch_pending = 1;
            g_winch_last_ms = now_ms();
        }
        if (g_winch_pending && (now_ms() - g_winch_last_ms) >= RESIZE_DEBOUNCE_MS) {
            g_winch_pending = 0;
            send_resize();
        }

        /* Drain wake pipe */
        if (g_wake_fd[0] >= 0 && FD_ISSET(g_wake_fd[0], &rfds)) {
            char buf[32];
            read(g_wake_fd[0], buf, sizeof(buf));
        }

        /* PTY -> VM */
        if (FD_ISSET(STDIN_FILENO, &rfds)) {
            char buf[4096];
            ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
            if (n <= 0) break;
            if (write(g_term_fd, buf, n) < 0) break;
        }

        /* VM -> PTY */
        if (FD_ISSET(g_term_fd, &rfds)) {
            char buf[4096];
            ssize_t n = read(g_term_fd, buf, sizeof(buf));
            if (n <= 0) break;
            if (write(STDOUT_FILENO, buf, n) < 0) break;
        }
    }

    cleanup();
    return 0;
}
