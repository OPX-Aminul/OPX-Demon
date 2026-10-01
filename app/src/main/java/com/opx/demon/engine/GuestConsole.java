package com.opx.demon.engine;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Runs commands on the guest's serial console.
 *
 * This is the second way into the VM, and it exists because the first one cannot bootstrap
 * itself. {@link GuestExec} only speaks to opxdemon-agentd on port 1050, and the agent is
 * delivered by {@link RootlessEngine#deployGuestCore()} over that same port — which makes it an
 * update path, never an install path. A rootfs whose agent is missing, whose socat is missing,
 * or whose unit failed to start can therefore never be repaired: the VM boots fine and then sits
 * at "waiting for the guest agent" forever, with the app unable to run a single command to find
 * out why.
 *
 * QEMU already wires ttyAMA0 to a unix socket. The rootfs autologins root on it (serial-getty
 * override), but an older rootfs without that override still presents "opxdemon login:" — so
 * {@link #open} drives the login program (root / opxdemon) when it sees a prompt. It is slow and
 * line-oriented, so it is only used when the agent is unreachable — never on the hot path.
 */
final class GuestConsole {

    private static final String TAG = "GuestConsole";
    /** Completion marker echoed by the guest shell to prove a command finished. */
    static final String MARK = "__OPX_DEMON_CON__";
    /** Printed by the channel-liveness probe; never a real command. */
    private static final String PROBE_MARK = "__OPX_DEMON_CONSOLE__";
    private static final int READ_TIMEOUT_MS = 20000;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    /** Port: channel the UML engine listens on for its serial console. */
    static final int PORT = 1050;

    /**
     * Boot-log tap. A UML kernel prints everything to its console (host :1050) once the
     * console driver is registered, which happens long before the rootfs mount — so on a
     * failed boot the reason (VFS root-device error, panic, ...) is read by the next console
     * session and then thrown away, leaving only the pre-console stub lines in the log.
     * RootlessEngine installs a tap for the lifetime of a boot; without it every UML
     * failure looks identical and undiagnosable.
     */
    public interface Observer {
        void onConsoleText(String text);
    }

    private static volatile Observer observer;

    static void setObserver(Observer o) {
        observer = o;
    }

    static void emit(String text) {
        Observer o = observer;
        if (o != null && text != null && text.length() > 0) {
            try {
                o.onConsoleText(text);
            } catch (Throwable ignored) {
            }
        }
    }

    private GuestConsole() {
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs {@code command} and returns the console output between the echoed command and the
     * marker. Empty when the console could not be reached — the caller cannot distinguish that
     * from a command that printed nothing, so probe with something that always prints.
     *
     * UML note: {@code socketPath == null} routes to the kernel's console pty
     * (no vec0 address needed) when one has been captured, else to TCP
     * 127.0.0.1:1050 — uml-netd's forward, which requires a healthy vec0.
     */
    static ArrayList<String> run(String command, String socketPath, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        if (command == null) return out;
        // An explicit /dev/pts path is the UML console pty — a terminal device,
        // NOT a LocalSocket address; route it to the raw-stream backend.
        if (socketPath != null && socketPath.startsWith("/dev/pts/")) {
            return runPts(socketPath, command, timeoutMs);
        }
        if (socketPath == null) return runUml(command, timeoutMs);
        LocalSocket sock = open(socketPath, timeoutMs);
        if (sock == null) return out;
        try {
            OutputStream os = sock.getOutputStream();
            // open() has already driven any login prompt; the leading newline lands on a fresh
            // shell line even if the console was mid-line when we got it.
            os.write(("\n" + command + "\n" + "echo " + MARK + "$?\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();

            InputStream is = sock.getInputStream();
            StringBuilder buf = new StringBuilder();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + (timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            while (System.currentTimeMillis() < deadline) {
                int r = is.read(chunk);
                if (r <= 0) break;
                buf.append(new String(chunk, 0, r, StandardCharsets.UTF_8));
                // The marker is echoed once by the shell reading our line and printed once as
                // output; wait for the second so the command has actually finished.
                if (countOf(buf, MARK) >= 2) break;
            }
            collect(buf.toString(), out);
        } catch (Exception e) {
            Log.w(TAG, "console command failed: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    /**
     * Connects to the console socket and makes sure a root shell is at the other end.
     *
     * The new rootfs autologins root, so most of the time the shell is already there. But the
     * override only ships in newer rootfs builds, and guests installed from older artifacts
     * still boot to "opxdemon login:" — commands written there are consumed as a username and
     * vanish. When no shell prompt is seen, log in with the rootfs credentials (root / opxdemon)
     * before returning. Returns null when the console cannot be reached at all.
     */
    private static LocalSocket open(String socketPath, int timeoutMs) {
        LocalSocket sock = new LocalSocket();
        try {
            sock.connect(new LocalSocketAddress(socketPath, LocalSocketAddress.Namespace.FILESYSTEM));
            sock.setSoTimeout(READ_TIMEOUT_MS);

            StringBuilder buf = new StringBuilder();
            drain(sock, buf, 3000);
            if (!promptSeen(buf.toString())) {
                // Not a shell: drive the login program. Debian login asks for the password
                // with "Password:" and accepts the user on the next prompt.
                OutputStream os = sock.getOutputStream();
                os.write("\nroot\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                buf.setLength(0);
                drain(sock, buf, 3000);
                if (buf.toString().toLowerCase(java.util.Locale.ROOT).contains("password")) {
                    os.write("opxdemon\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                buf.setLength(0);
                drain(sock, buf, 3000);
            }
            sock.setSoTimeout(timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            return sock;
        } catch (Exception e) {
            Log.w(TAG, "console connect failed: " + e.getMessage());
            try { sock.close(); } catch (Exception ignored) {}
            return null;
        }
    }

    /**
     * Host-side pty slave of the UML console (e.g. /dev/pts/1), reported by the
     * kernel on stdout ("Virtual console 1 assigned device '/dev/pts/1'") and set
     * by RootlessEngine for the lifetime of the current boot.
     */
    private static volatile String umlPtsDevice;
    /** Set by runPts(): the last command's completion marker came back. */
    private static volatile boolean ptsAlive;

    static void setUmlPtsDevice(String path) {
        umlPtsDevice = (path != null && path.startsWith("/dev/pts/")) ? path : null;
    }

    /** Current console pty, or null before the kernel has reported one. */
    static String umlPts() {
        return umlPtsDevice;
    }

    /**
     * Runs a command on whichever UML console channel is actually alive.
     *
     * <p>Three channels exist and which one carries a shell depends on the
     * guest, not on us: the kernel process's own stdin/stdout (tty0, where the
     * kernel and systemd print), the /dev/pts node the kernel announces for
     * tty1, and a TCP forward that only works once vec0 has an address. A
     * channel with no getty on it echoes nothing forever, so trying them in turn
     * with the caller's full timeout would spend the whole timeout on the first
     * dead one. {@link #umlChannel()} therefore probes each one with a short
     * echo and remembers the winner for the rest of the boot; the probe costs a
     * few seconds once instead of minutes per command.
     */
    private static ArrayList<String> runUml(String command, int timeoutMs) {
        switch (umlChannel()) {
            case "stdio": {
                UmlStdio stdio = UmlStdio.current();
                if (stdio != null) return runStdio(stdio, command, timeoutMs);
                break;
            }
            case "pts": {
                String pts = umlPtsDevice;
                if (pts != null && new java.io.File(pts).exists()) {
                    return runPts(pts, command, timeoutMs);
                }
                break;
            }
            case "tcp":
                return runTcp(command, timeoutMs);
            default:
                break;
        }
        // No channel is known to answer (the console has not spoken yet, or the
        // winner died). Walk the full ladder with a short budget each, so one
        // dead channel cannot eat the caller's whole timeout.
        UmlStdio stdio = UmlStdio.current();
        if (stdio != null) {
            ArrayList<String> r = runStdio(stdio, command, Math.min(timeoutMs, 20_000));
            // answered() is the only honest success test: a command that
            // legitimately prints nothing still reached a shell, and a console
            // that echoed nothing must fall through instead of looking like a
            // silent success.
            if (stdio.answered()) { umlChannelName = "stdio"; return r; }
        }
        String pts = umlPtsDevice;
        if (pts != null && pts.startsWith("/dev/pts/") && new java.io.File(pts).exists()) {
            ArrayList<String> r = runPts(pts, command, Math.min(timeoutMs, 20_000));
            if (ptsAlive) { umlChannelName = "pts"; return r; }
        }
        return runTcp(command, timeoutMs);
    }

    /**
     * True when the QEMU serial socket is carrying a shell.
     *
     * <p>The socket file exists the moment QEMU starts, long before the guest's
     * agetty is on it, so its existence proves nothing. This sends one short
     * echo and looks for the completion marker; the login handshake inside
     * {@link #open} runs first, so a console sitting at "opxdemon login:" counts
     * as usable instead of being mistaken for a dead channel.
     */
    static boolean probeSerialSocket(String socketPath) {
        if (socketPath == null || !new java.io.File(socketPath).exists()) return false;
        for (String l : run("echo " + PROBE_MARK, socketPath, 10_000)) {
            if (l != null && l.contains(PROBE_MARK)) return true;
        }
        return false;
    }

    /**
     * The UML console channel that answered a live echo, or "none" while the
     * guest console has not spoken yet. Cheap by design: one short echo per
     * candidate, then the answer is cached for the whole boot.
     */
    static String umlChannel() {
        String known = umlChannelName;
        if (known != null) return known;
        UmlStdio stdio = UmlStdio.current();
        if (stdio != null && !stdio.recent(4000).isEmpty()) {
            // The console has printed, so something is listening on it. Ask it
            // directly rather than guessing from log text.
            probeStdio(stdio);
            if (umlChannelName != null) return umlChannelName;
        }
        String pts = umlPtsDevice;
        if (pts != null && pts.startsWith("/dev/pts/") && new java.io.File(pts).exists()) {
            ptsAlive = false;
            runPts(pts, "echo " + PROBE_MARK, 8000);
            if (ptsAlive) { umlChannelName = "pts"; return "pts"; }
        }
        return "none";
    }

    /**
     * Sends the probe echo and lets runStdio()'s command path decide whether a
     * shell is there. Sets the cached channel name when it is.
     */
    private static void probeStdio(UmlStdio stdio) {
        runStdio(stdio, "echo " + PROBE_MARK, 6000);
        if (stdio.answered()) umlChannelName = "stdio";
    }

    /** Drops the cached console-channel decision; called per boot attempt. */
    static void resetUmlChannel() {
        umlChannelName = null;
        ptsAlive = false;
    }

    /** Cached winner of {@link #umlChannel()}; null = not probed yet. */
    private static volatile String umlChannelName;

    /**
     * stdin/stdout console backend for the UML engine.
     *
     * Same write/handshake/collect contract as the other backends, but over the
     * kernel process's own pipes. Before running the command it clears any
     * pending output and, when no shell prompt is visible, drives agetty's login
     * program the same way the pty/TCP backends do — a guest console that boots
     * to "opxdemon login:" would otherwise eat every command as a username.
     *
     * Returns an empty list when the console never answered, so the caller can
     * fall through to the next backend instead of believing a dead console.
     */
    private static ArrayList<String> runStdio(UmlStdio stdio, String command, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        try {
            // Look at the console's recent output, not a fresh window: agetty
            // printed "opxdemon login:" long before this call.
            String recent = stdio.recent(4000);
            if (!promptSeen(recent) && recent.toLowerCase(Locale.ROOT).contains("login")) {
                // agetty is up: log in with the rootfs credentials (root/opxdemon).
                // Raw sends, not exchange(): agetty takes the first line as the
                // username and the second as the password, so nothing may be
                // appended to either of them.
                stdio.send("root");
                sleep(1500);
                stdio.send("opxdemon");
                sleep(2500);
            }
            out = stdio.exchange(command, timeoutMs);
        } catch (Throwable t) {
            Log.w(TAG, "stdio console command failed: " + t.getMessage());
        }
        return out;
    }

    /**
     * pts console backend for the UML engine: the kernel creates its console pty
     * inside our process and keeps it open, so the app — running as the same uid —
     * can read and write it directly. Same write/handshake/collect contract as the
     * TCP backend, including the root/opxdemon login handshake for rootfs images
     * that still boot to "opxdemon login:".
     */
    private static ArrayList<String> runPts(String ptsPath, String command, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        try (java.io.FileInputStream is = new java.io.FileInputStream(ptsPath);
             java.io.FileOutputStream os = new java.io.FileOutputStream(ptsPath)) {
            StringBuilder buf = new StringBuilder();
            drainStream(is, buf, 3000);
            if (!promptSeen(buf.toString())) {
                os.write("\nroot\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                buf.setLength(0);
                drainStream(is, buf, 3000);
                if (buf.toString().toLowerCase(Locale.ROOT).contains("password")) {
                    os.write("opxdemon\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                buf.setLength(0);
                drainStream(is, buf, 3000);
            }
            os.write(("\n" + command + "\n" + "echo " + MARK + "$?\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            buf.setLength(0);
            drainStream(is, buf, timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            String raw = buf.toString();
            // The marker comes back only when a shell consumed the line, so this
            // is the channel's liveness signal — the callers that have to choose
            // between channels cannot see it any other way (collect() strips the
            // marker out of the returned lines on purpose).
            ptsAlive = countOf(raw, MARK) >= 1;
            collect(raw, out);
        } catch (Exception e) {
            Log.w(TAG, "pts console command failed: " + e.getMessage());
        }
        return out;
    }

    /**
     * Reads a raw console stream until it has been quiet for {@code quietMs} — the
     * FileInputStream analogue of drainTcp(). available()-polling with a silence
     * timer, because a plain blocking read() would wait forever on a console that
     * has nothing more to say.
     */
    private static void drainStream(InputStream is, StringBuilder buf, int quietMs) {
        long deadline = System.currentTimeMillis() + quietMs;
        byte[] chunk = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            try {
                int avail = is.available();
                if (avail <= 0) {
                    Thread.sleep(50);
                    continue;
                }
                int r = is.read(chunk, 0, Math.min(avail, chunk.length));
                if (r <= 0) return;
                String s = new String(chunk, 0, r, StandardCharsets.UTF_8);
                emit(s);
                buf.append(s);
                deadline = System.currentTimeMillis() + quietMs; // still receiving
            } catch (InterruptedException ie) {
                return;
            } catch (Exception e) {
                return;
            }
        }
    }

    /**
     * TCP console backend for the UML engine (port: channel on 127.0.0.1:1050).
     *
     * The same write/handshake/collect contract as the unix-socket path, just over a plain
     * TCP socket. The UML console is the kernel's tty directly, so the login handshake
     * below still runs harmlessly when no shell prompt is seen yet.
     */
    private static ArrayList<String> runTcp(String command, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        java.net.Socket sock = new java.net.Socket();
        try {
            sock.connect(new java.net.InetSocketAddress("127.0.0.1", PORT), CONNECT_TIMEOUT_MS);
            sock.setSoTimeout(timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            OutputStream os = sock.getOutputStream();
            StringBuilder buf = new StringBuilder();
            drainTcp(sock, buf, 3000);
            if (!promptSeen(buf.toString())) {
                os.write("\nroot\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
                buf.setLength(0);
                drainTcp(sock, buf, 3000);
                if (buf.toString().toLowerCase(java.util.Locale.ROOT).contains("password")) {
                    os.write("opxdemon\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                buf.setLength(0);
                drainTcp(sock, buf, 3000);
            }
            os.write(("\n" + command + "\n" + "echo " + MARK + "$?\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            buf.setLength(0);
            drainTcp(sock, buf, timeoutMs > 0 ? timeoutMs : READ_TIMEOUT_MS);
            collect(buf.toString(), out);
        } catch (Exception e) {
            Log.w(TAG, "tcp console command failed: " + e.getMessage());
        } finally {
            try { sock.close(); } catch (Exception ignored) {}
        }
        return out;
    }

    private static void drainTcp(java.net.Socket sock, StringBuilder buf, int quietMs) {
        try {
            InputStream is = sock.getInputStream();
            sock.setSoTimeout(quietMs);
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + quietMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int r = is.read(chunk);
                    if (r <= 0) return;
                    String s = new String(chunk, 0, r, StandardCharsets.UTF_8);
                    emit(s);
                    buf.append(s);
                } catch (java.net.SocketTimeoutException ste) {
                    return;
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** Reads whatever the console emits until quiet or deadline — enough to catch a prompt. */
    private static void drain(LocalSocket sock, StringBuilder buf, int quietMs) {
        try {
            InputStream is = sock.getInputStream();
            // Bound each read to the quiet window: with the default (long) read timeout a silent
            // console would block one read() call far past the deadline we are aiming for.
            sock.setSoTimeout(quietMs);
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + quietMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int r = is.read(chunk);
                    if (r <= 0) return;
                    buf.append(new String(chunk, 0, r, StandardCharsets.UTF_8));
                } catch (java.net.SocketTimeoutException ste) {
                    return; // quiet period — the console has said its piece
                }
            }
        } catch (Exception ignored) {
        }
    }

    /** True when the console looks like a shell rather than agetty's login prompt. */
    static boolean promptSeen(String text) {
        String t = text.replace("\r", "").toLowerCase(java.util.Locale.ROOT);
        if (t.contains("login:")) return false;
        for (String line : text.split("\r?\n")) {
            String s = line.replace("\r", "").trim();
            if (s.endsWith("#")) return true;   // root prompt
            if (s.endsWith("$")) return true;   // user prompt
        }
        return false;
    }

    private static int countOf(CharSequence hay, String needle) {
        int n = 0, from = 0;
        String s = hay.toString();
        while (true) {
            int i = s.indexOf(needle, from);
            if (i < 0) return n;
            n++;
            from = i + needle.length();
        }
    }

    /** Keeps the real output: drops the echoed command line, the marker lines, prompts and login noise. */
    static void collect(String raw, ArrayList<String> out) {
        for (String line : raw.split("\r?\n")) {
            String t = line.replace("\r", "").trim();
            if (t.isEmpty()) continue;
            if (t.contains(MARK)) continue;
            // The liveness probe needs its marker: callers that only want to know
            // "did a shell answer" cannot see it once it has been filtered out.
            if (t.contains(PROBE_MARK)) { out.add(t); continue; }
            if (t.startsWith("echo " + MARK)) continue;
            if (t.endsWith("#") && t.contains("@")) continue;
            // Login noise our handshake can produce; none of it is command output.
            String low = t.toLowerCase(Locale.ROOT);
            if (low.endsWith("login:") || low.startsWith("password:")
                    || low.contains("last login") || low.contains("login incorrect")) continue;
            out.add(t);
        }
    }
}
