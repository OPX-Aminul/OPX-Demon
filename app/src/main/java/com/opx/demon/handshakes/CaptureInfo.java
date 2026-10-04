package com.opx.demon.handshakes;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class CaptureInfo {

    public enum Kind {
        HANDSHAKE,
        PMKID,
        EMPTY,
        UNREADABLE
    }

    public Kind kind = Kind.UNREADABLE;
    public String ssid = "";
    public String bssid = "";
    public String station = "";
    public int packets;
    public int eapol;
    public boolean pmkid;

    public boolean usable() {
        return kind == Kind.HANDSHAKE || kind == Kind.PMKID;
    }

    private static final Map<String, CaptureInfo> CACHE = new HashMap<>();

    public static CaptureInfo of(File f) {
        if (f == null) return new CaptureInfo();
        String key = f.getAbsolutePath() + ":" + f.length() + ":" + f.lastModified();
        synchronized (CACHE) {
            CaptureInfo hit = CACHE.get(key);
            if (hit != null) return hit;
        }
        CaptureInfo info = read(f);
        synchronized (CACHE) {
            if (CACHE.size() > 512) CACHE.clear();
            CACHE.put(key, info);
        }
        return info;
    }

    public static void forget() {
        synchronized (CACHE) {
            CACHE.clear();
        }
    }

    private static final int MAX_PACKETS = 400_000;

    private static CaptureInfo read(File f) {
        CaptureInfo out = new CaptureInfo();
        if (f == null || !f.isFile() || f.length() < 24) return out;

        try (InputStream raw = new FileInputStream(f);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw, 1 << 16))) {

            byte[] magic = new byte[4];
            in.readFully(magic);
            boolean little;
            int m0 = magic[0] & 0xFF, m1 = magic[1] & 0xFF, m2 = magic[2] & 0xFF, m3 = magic[3] & 0xFF;
            if (m0 == 0xD4 && m1 == 0xC3 && m2 == 0xB2 && m3 == 0xA1) little = true;
            else if (m0 == 0x4D && m1 == 0x3C && m2 == 0xB2 && m3 == 0xA1) little = true;
            else if (m0 == 0xA1 && m1 == 0xB2 && m2 == 0xC3 && m3 == 0xD4) little = false;
            else if (m0 == 0xA1 && m1 == 0xB2 && m2 == 0x3C && m3 == 0x4D) little = false;
            else return out;

            skip(in, 16);
            int link = int32(in, little);

            if (link != 105 && link != 127) return out;

            Set<Integer> msgs = new HashSet<>();
            byte[] buf = new byte[65536];
            byte[] rec = new byte[16];

            while (out.packets < MAX_PACKETS) {
                if (!readOrEof(in, rec, 16)) break;
                int incl = little
                        ? (rec[8] & 0xFF) | ((rec[9] & 0xFF) << 8)
                                | ((rec[10] & 0xFF) << 16) | ((rec[11] & 0xFF) << 24)
                        : (rec[11] & 0xFF) | ((rec[10] & 0xFF) << 8)
                                | ((rec[9] & 0xFF) << 16) | ((rec[8] & 0xFF) << 24);
                if (incl <= 0 || incl > buf.length) break;
                if (!readOrEof(in, buf, incl)) break;
                out.packets++;
                parse(buf, incl, link, out, msgs);
            }

            out.eapol = msgs.size();
            boolean full = (msgs.contains(1) && msgs.contains(2))
                    || (msgs.contains(2) && msgs.contains(3))
                    || (msgs.contains(3) && msgs.contains(4));
            if (full) out.kind = Kind.HANDSHAKE;
            else if (out.pmkid) out.kind = Kind.PMKID;
            else out.kind = Kind.EMPTY;
        } catch (Throwable t) {
            return out;
        }
        return out;
    }

    private static boolean readOrEof(DataInputStream in, byte[] buf, int n)
            throws java.io.IOException {
        int got = 0;
        while (got < n) {
            int r = in.read(buf, got, n - got);
            if (r < 0) return false;
            got += r;
        }
        return true;
    }

    private static void parse(byte[] p, int len, int link, CaptureInfo out, Set<Integer> msgs) {
        int off = 0;
        if (link == 127) {
            if (len < 4) return;
            int rtLen = (p[2] & 0xFF) | ((p[3] & 0xFF) << 8);
            if (rtLen < 8 || rtLen >= len) return;
            off = rtLen;
        }
        if (len - off < 24) return;

        int fc = (p[off] & 0xFF) | ((p[off + 1] & 0xFF) << 8);
        int type = (fc >> 2) & 0x3;
        int subtype = (fc >> 4) & 0xF;

        if (type == 0 && (subtype == 8 || subtype == 5)) {
            if (out.ssid.isEmpty()) {
                int ie = off + 24 + 12;
                if (ie + 2 <= len && (p[ie] & 0xFF) == 0) {
                    int n = p[ie + 1] & 0xFF;
                    if (n > 0 && ie + 2 + n <= len) {
                        String name = new String(p, ie + 2, n, java.nio.charset.StandardCharsets.UTF_8);
                        if (!name.trim().isEmpty() && name.indexOf('\0') < 0) out.ssid = name;
                    }
                }
            }
            if (out.bssid.isEmpty()) out.bssid = mac(p, off + 16);
            return;
        }

        if (type != 2) return;
        int hdr = 24;
        if ((fc & 0x0300) == 0x0300) hdr += 6;
        if ((subtype & 0x08) != 0) hdr += 2;
        if ((fc & 0x4000) != 0) return;
        int at = off + hdr;
        if (len - at < 8) return;

        if ((p[at] & 0xFF) != 0xAA || (p[at + 1] & 0xFF) != 0xAA || (p[at + 2] & 0xFF) != 0x03) return;
        if ((p[at + 6] & 0xFF) != 0x88 || (p[at + 7] & 0xFF) != 0x8E) return;

        int k = at + 8;
        if (len - k < 99) return;
        int keyInfo = ((p[k + 5] & 0xFF) << 8) | (p[k + 6] & 0xFF);
        int kdLen = ((p[k + 97] & 0xFF) << 8) | (p[k + 98] & 0xFF);
        boolean ack = (keyInfo & 0x0080) != 0;
        boolean mic = (keyInfo & 0x0100) != 0;
        boolean secure = (keyInfo & 0x0200) != 0;

        int msg;
        if (ack && !mic) msg = 1;
        else if (mic && ack) msg = 3;
        else if (mic && secure) msg = 4;
        else if (mic) msg = 2;
        else return;
        msgs.add(msg);

        boolean fromAp = msg == 1 || msg == 3;
        String a1 = mac(p, off + 4), a2 = mac(p, off + 10);
        if (out.bssid.isEmpty()) out.bssid = fromAp ? a2 : a1;
        if (out.station.isEmpty()) out.station = fromAp ? a1 : a2;

        if (msg == 1 && kdLen >= 22) {
            int d = k + 99;
            int end = Math.min(len, d + kdLen);
            while (d + 2 <= end) {
                int t = p[d] & 0xFF, n = p[d + 1] & 0xFF;
                if (t == 0xDD && n >= 20 && d + 6 <= end
                        && (p[d + 2] & 0xFF) == 0x00 && (p[d + 3] & 0xFF) == 0x0F
                        && (p[d + 4] & 0xFF) == 0xAC && (p[d + 5] & 0xFF) == 0x04) {
                    out.pmkid = true;
                }
                d += 2 + n;
            }
        }
    }

    private static String mac(byte[] p, int at) {
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", p[at + i]));
        }
        return sb.toString();
    }

    private static int int32(DataInputStream in, boolean little) throws java.io.IOException {
        int v = in.readInt();
        return little ? Integer.reverseBytes(v) : v;
    }

    private static void skip(DataInputStream in, int n) throws java.io.IOException {
        int left = n;
        while (left > 0) {
            int got = (int) in.skip(left);
            if (got <= 0) {
                if (in.read() < 0) throw new java.io.EOFException();
                got = 1;
            }
            left -= got;
        }
    }
}
