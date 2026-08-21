package com.checkba.service.evidence;

import java.security.SecureRandom;

/** 26 位 Crockford base32 ULID：前 10 位毫秒时间戳，后 16 位随机。只用于书签名，不追求单调。 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RND = new SecureRandom();

    private Ulid() {}

    public static String next() {
        char[] out = new char[26];
        long t = System.currentTimeMillis();
        for (int i = 9; i >= 0; i--) { out[i] = ALPHABET[(int) (t & 31)]; t >>>= 5; }
        byte[] r = new byte[10];
        RND.nextBytes(r);
        long a = 0; for (int i = 0; i < 5; i++) a = (a << 8) | (r[i] & 0xff);
        long b = 0; for (int i = 5; i < 10; i++) b = (b << 8) | (r[i] & 0xff);
        for (int i = 17; i >= 10; i--) { out[i] = ALPHABET[(int) (a & 31)]; a >>>= 5; }
        for (int i = 25; i >= 18; i--) { out[i] = ALPHABET[(int) (b & 31)]; b >>>= 5; }
        return new String(out);
    }
}
