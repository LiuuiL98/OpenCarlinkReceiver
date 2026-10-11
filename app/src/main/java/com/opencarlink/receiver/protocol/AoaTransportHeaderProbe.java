package com.opencarlink.receiver.protocol;

/**
 * Diagnostic view of the official ICCOA AOA transport's observed 8-byte prefix.
 *
 * The two words are intentionally unnamed: static analysis has not yet
 * established their semantics or the body-read length formula.
 */
public final class AoaTransportHeaderProbe {
    public static final int HEADER_LENGTH = 8;

    private AoaTransportHeaderProbe() {}

    public static boolean isAvailable(byte[] data, int returnedLength) {
        return data != null && data.length >= HEADER_LENGTH && returnedLength >= HEADER_LENGTH;
    }

    public static long wordBigEndian(byte[] data, int offset) {
        checkRange(data, offset);
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (long) (data[offset + 3] & 0xff);
    }

    public static long wordLittleEndian(byte[] data, int offset) {
        checkRange(data, offset);
        return ((long) (data[offset + 3] & 0xff) << 24)
                | ((long) (data[offset + 2] & 0xff) << 16)
                | ((long) (data[offset + 1] & 0xff) << 8)
                | (long) (data[offset] & 0xff);
    }

    private static void checkRange(byte[] data, int offset) {
        if (data == null) throw new IllegalArgumentException("data == null");
        if (offset < 0 || offset > data.length - 4) {
            throw new IllegalArgumentException("need four bytes at offset " + offset);
        }
    }
}
