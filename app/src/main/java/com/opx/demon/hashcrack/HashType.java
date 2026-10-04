package com.opx.demon.hashcrack;

import org.bouncycastle.crypto.digests.MD4Digest;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public enum HashType {

    MD5("MD5", "MD5", 32),
    SHA1("SHA-1", "SHA1", 40),
    SHA224("SHA-224", "SHA224", 56),
    SHA256("SHA-256", "SHA256", 64),
    SHA384("SHA-384", "SHA384", 96),
    SHA512("SHA-512", "SHA512", 128),

    NTLM(null, "NTLM", 32),

    MYSQL41(null, "MySQL 4.1+", 40),

    MD5MD5(null, "MD5(MD5)", 32);

    private final String algorithm;
    public final String title;
    public final int hexLength;

    HashType(String algorithm, String title, int hexLength) {
        this.algorithm = algorithm;
        this.title = title;
        this.hexLength = hexLength;
    }

    public static List<HashType> candidatesFor(String hash) {
        List<HashType> out = new ArrayList<>();
        if (hash == null) return out;
        String h = normalize(hash);
        for (HashType t : values()) {
            if (t.hexLength == h.length()) out.add(t);
        }
        return out;
    }

    public static String normalize(String hash) {
        if (hash == null) return "";
        String h = hash.trim().toLowerCase(Locale.US);
        if (h.startsWith("*")) h = h.substring(1);
        return h;
    }

    public static boolean isHex(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    public Digester digester() throws NoSuchAlgorithmException {
        return new Digester(this);
    }

    public static class Digester {

        private final HashType type;
        private final MessageDigest primary;
        private final MessageDigest secondary;
        private final MD4Digest md4;
        private final byte[] md4Out = new byte[16];
        private final char[] hexOut;

        Digester(HashType type) throws NoSuchAlgorithmException {
            this.type = type;
            this.hexOut = new char[type.hexLength];
            switch (type) {
                case NTLM:
                    primary = null;
                    secondary = null;
                    md4 = new MD4Digest();
                    break;
                case MYSQL41:
                    primary = MessageDigest.getInstance("SHA-1");
                    secondary = MessageDigest.getInstance("SHA-1");
                    md4 = null;
                    break;
                case MD5MD5:
                    primary = MessageDigest.getInstance("MD5");
                    secondary = MessageDigest.getInstance("MD5");
                    md4 = null;
                    break;
                default:
                    primary = MessageDigest.getInstance(type.algorithm);
                    secondary = null;
                    md4 = null;
                    break;
            }
        }

        public String hash(String candidate) {
            byte[] digest;
            switch (type) {
                case NTLM: {
                    byte[] utf16 = utf16le(candidate);
                    md4.reset();
                    md4.update(utf16, 0, utf16.length);
                    md4.doFinal(md4Out, 0);
                    digest = md4Out;
                    break;
                }
                case MYSQL41: {
                    primary.reset();
                    secondary.reset();
                    digest = secondary.digest(primary.digest(bytes(candidate)));
                    break;
                }
                case MD5MD5: {
                    primary.reset();
                    secondary.reset();
                    digest = secondary.digest(asciiHex(primary.digest(bytes(candidate))));
                    break;
                }
                default:
                    primary.reset();
                    digest = primary.digest(bytes(candidate));
                    break;
            }
            return hex(digest);
        }

        private String hex(byte[] bytes) {
            int n = Math.min(bytes.length, hexOut.length / 2);
            for (int i = 0; i < n; i++) {
                int v = bytes[i] & 0xFF;
                hexOut[i * 2] = HEX[v >>> 4];
                hexOut[i * 2 + 1] = HEX[v & 0x0F];
            }
            return new String(hexOut, 0, n * 2);
        }

        private static byte[] asciiHex(byte[] digest) {
            byte[] out = new byte[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                int v = digest[i] & 0xFF;
                out[i * 2] = (byte) HEX[v >>> 4];
                out[i * 2 + 1] = (byte) HEX[v & 0x0F];
            }
            return out;
        }

        private static byte[] bytes(String s) {
            try {
                return s.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                return s.getBytes();
            }
        }

        private static byte[] utf16le(String s) {
            try {
                return s.getBytes("UTF-16LE");
            } catch (UnsupportedEncodingException e) {
                return s.getBytes();
            }
        }
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}
