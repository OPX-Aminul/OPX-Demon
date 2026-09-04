package com.zalexdev.stryker.engine;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * Runs commands on the guest's serial console.
 *
 * This is the second way into the VM, and it exists because the first one cannot bootstrap
 * itself. {@link GuestExec} only speaks to stryker-agentd on port 1050, and the agent is
 * delivered by {@link RootlessEngine#deployGuestCore()} over that same port — which makes it an
 * update path, never an install path. A rootfs whose agent is missing, whose socat is missing,
 * or whose unit failed to start can therefore never be repaired: the VM boots fine and then sits
 * at "waiting for the guest agent" forever, with the app unable to run a single command to find
 * out why.
 *
 * QEMU already wires ttyAMA0 to a unix socket. The rootfs autologins root on it (serial-getty
 * override), but an older rootfs without that override still presents "stryker login:" — so
 * {@link #open} drives the login program (root / stryker) when it sees a prompt. It is slow and
 * line-oriented, so it is only used when the agent is unreachable — never on the hot path.
 */
final class GuestConsole {

    private static final String TAG = "GuestConsole";
    private static final String MARK = "__STRYKER_CON__";
    private static final int READ_TIMEOUT_MS = 20000;

    private GuestConsole() {
    }

    /**
     * Runs {@code command} and returns the console output between the echoed command and the
     * marker. Empty when the console could not be reached — the caller cannot distinguish that
     * from a command that printed nothing, so probe with something that always prints.
     */
    static ArrayList<String> run(String command, String socketPath, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        if (command == null || socketPath == null) return out;
        LocalSocket sock = open(socketPath, timeoutMs);
        if (sock == null) return out;
        try {
            OutputStream os = sock.getOutputStream();
            // A leading newline lands us on a fresh prompt even if the console was mid-line.
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
     * still boot to "stryker login:" — commands written there are consumed as a username and
     * vanish. When no shell prompt is seen, log in with the rootfs credentials (root / stryker)
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
                    os.write("stryker\n".getBytes(StandardCharsets.UTF_8));
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
    private static boolean promptSeen(String text) {
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

    /** Keeps the real output: drops the echoed command line, the marker lines and the prompt. */
    private static void collect(String raw, ArrayList<String> out) {
        for (String line : raw.split("\r?\n")) {
            String t = line.replace("\r", "").trim();
            if (t.isEmpty()) continue;
            if (t.contains(MARK)) continue;
            if (t.startsWith("echo " + MARK)) continue;
            if (t.endsWith("#") && t.contains("@")) continue;
            out.add(t);
        }
    }
}
