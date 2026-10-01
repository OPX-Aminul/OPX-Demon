package com.opx.demon.engine;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * The UML kernel's own console, as a duplex byte channel.
 *
 * <p>Everything else in this app assumed the UML console was a device it could
 * open by name. It is not. {@code arch/um/drivers/stdio_console.c} sets up
 * virtual console 0 from {@code CONFIG_CON_ZERO_CHAN}, and the kernel this app
 * ships is configured with
 *
 * <pre>CONFIG_CON_ZERO_CHAN="fd:0,fd:1"</pre>
 *
 * so tty0 — the console the guest kernel and systemd actually print to — <em>is</em>
 * the UML process's own stdin/stdout. The {@code "Virtual console 1 assigned
 * device '/dev/pts/N'"} line printed by {@code late_initcall stdio_init} is the
 * <em>second</em> console (tty1, {@code CONFIG_CON_CHAN="pts"}), not the one
 * systemd writes to. Writing to that pty reaches a terminal nothing is logged
 * in on, which is why the earlier rounds' pty bootstrap never saw a shell.
 *
 * <p>So this class is the only correct out-of-band path into a guest whose
 * agent is not answering: it owns the process's stdout reader (so the boot log
 * and this channel never compete for the same stream) and writes commands into
 * the process's stdin.
 */
final class UmlStdio {

    private static final String TAG = "UmlStdio";
    /** Rolling window of console output kept for command collection. */
    private static final int WINDOW = 128 * 1024;
    private static final int READ_CHUNK = 8192;

    /** Sink for every line the kernel prints, so the boot log still sees them. */
    interface LineSink {
        void onLine(String line);
    }

    private static volatile UmlStdio current;

    private final StringBuilder window = new StringBuilder();
    private final Object lock = new Object();
    /**
     * Total characters ever read. This — not window.length() — is what command
     * offsets are taken against: the window is trimmed to stay bounded, so
     * window.length() shrinks under a caller and an offset taken before the trim
     * would point past the end, making every later command look unanswered.
     */
    private long totalChars;
    private final OutputStream stdin;
    private volatile boolean closed;
    /** Set once the console has echoed a shell prompt — i.e. there is a shell. */
    private volatile boolean shellSeen;
    /** Set by exchange(): the completion marker came back, so a shell is there. */
    private volatile boolean answered;

    private UmlStdio(Process proc, LineSink sink) {
        this.stdin = proc.getOutputStream();
        Thread t = new Thread(() -> pump(proc, sink), "opxdemon-uml-console");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Takes ownership of {@code proc}'s stdout and starts the reader. Call this
     * INSTEAD of a separate pump on {@link Process#getInputStream()} — two
     * readers on one pipe split the boot log between them and lose lines.
     */
    static void attach(Process proc, LineSink sink) {
        detach();
        try {
            current = new UmlStdio(proc, sink);
        } catch (Throwable t) {
            Log.w(TAG, "could not attach to the UML console: " + t.getMessage());
        }
    }

    static void detach() {
        UmlStdio c = current;
        current = null;
        if (c != null) c.closed = true;
    }

    static UmlStdio current() {
        UmlStdio c = current;
        return (c != null && !c.closed) ? c : null;
    }

    /** True once anything resembling a shell prompt has been seen on the console. */
    boolean shellSeen() {
        return shellSeen;
    }

    /** True when the last {@link #exchange} saw its completion marker come back. */
    boolean answered() {
        return answered;
    }

    /**
     * The last {@code n} characters the console printed. Does not wait and does
     * not consume: it answers "what state is the console in right now", which is
     * what deciding between "type a command" and "log in first" needs — agetty
     * printed its prompt minutes ago, long before this call.
     */
    String recent(int n) {
        synchronized (lock) {
            int len = window.length();
            return len <= n ? window.toString() : window.substring(len - n);
        }
    }

    /** Bytes of console text seen so far — lets callers tell a silent console. */
    long consumed() {
        synchronized (lock) {
            return window.length();
        }
    }

    private void pump(Process proc, LineSink sink) {
        StringBuilder pending = new StringBuilder();
        try (InputStream is = proc.getInputStream()) {
            byte[] chunk = new byte[READ_CHUNK];
            int n;
            while (!closed && (n = is.read(chunk)) > 0) {
                String s = new String(chunk, 0, n, StandardCharsets.UTF_8);
                synchronized (lock) {
                    window.append(s);
                    totalChars += s.length();
                    if (window.length() > WINDOW) {
                        window.delete(0, window.length() - WINDOW);
                    }
                    lock.notifyAll();
                }
                if (sink != null) {
                    pending.append(s);
                    int nl;
                    while ((nl = pending.indexOf("\n")) >= 0) {
                        String line = pending.substring(0, nl).replace("\r", "");
                        pending.delete(0, nl + 1);
                        if (GuestConsole.promptSeen(line)) shellSeen = true;
                        try {
                            sink.onLine(line);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable t) {
            if (!closed) Log.w(TAG, "console reader ended: " + t.getMessage());
        } finally {
            closed = true;
            synchronized (lock) {
                lock.notifyAll();
            }
        }
    }

    private long cursor() {
        synchronized (lock) {
            return totalChars;
        }
    }

    /** Console text printed since the given absolute offset, or "" if it was trimmed away. */
    private String since(long offset) {
        synchronized (lock) {
            long base = totalChars - window.length();
            if (offset >= totalChars) return "";
            if (offset <= base) return window.toString();
            return window.substring((int) (offset - base));
        }
    }

    /**
     * Writes {@code command} followed by a completion marker to the guest's
     * console and collects everything printed until the marker comes back.
     *
     * <p>The tty echoes what we type, so the marker normally appears twice: once
     * in the echoed command line and once as the shell's own output. A console
     * with echo disabled only prints it once, so a single occurrence plus a
     * short grace period is accepted too.
     *
     * @return the console output, never null; empty when the marker never came
     *         back within {@code timeoutMs} (a dead or shell-less console).
     */
    ArrayList<String> exchange(String command, int timeoutMs) {
        ArrayList<String> out = new ArrayList<>();
        answered = false;
        if (command == null || closed) return out;
        long start = cursor();
        String payload = "\n" + command + "\n" + "echo " + GuestConsole.MARK + "$?\n";
        try {
            stdin.write(payload.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Throwable t) {
            Log.w(TAG, "console write failed: " + t.getMessage());
            return out;
        }
        long deadline = System.currentTimeMillis() + Math.max(1500, timeoutMs);
        long firstMarkAt = 0;
        while (System.currentTimeMillis() < deadline) {
            synchronized (lock) {
                String text = since(start);
                int count = countOf(text, GuestConsole.MARK);
                if (count >= 1) answered = true;
                if (count >= 2) break;
                if (count >= 1) {
                    if (firstMarkAt == 0) {
                        firstMarkAt = System.currentTimeMillis();
                    } else if (System.currentTimeMillis() - firstMarkAt > 1200) {
                        break; // no echo on this console — take what we have
                    }
                } else {
                    firstMarkAt = 0;
                }
                try {
                    lock.wait(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        synchronized (lock) {
            String text = since(start);
            if (GuestConsole.promptSeen(text)) shellSeen = true;
            GuestConsole.collect(text, out);
        }
        return out;
    }

    /**
     * Writes one line to the console with no completion marker attached.
     *
     * <p>Only for driving a login program: agetty reads the first line as a
     * username, so anything appended after it — including this class's marker
     * echo — is consumed as the password and the handshake never completes.
     */
    void send(String line) {
        if (closed || line == null) return;
        try {
            stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Throwable t) {
            Log.w(TAG, "console write failed: " + t.getMessage());
        }
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
}
