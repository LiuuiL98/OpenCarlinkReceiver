package com.opencarlink.receiver.protocol;

/**
 * CRC-16/ARC implementation matching the table-driven algorithm recovered from
 * ICCOA v1.5.3's UCar protocol header code.
 *
 * Parameters: reflected polynomial 0xA001, init 0x0000, xorout 0x0000.
 * The UCar message header uses the first 18 bytes as the checksum input and
 * stores the resulting 16-bit value in big-endian order at offsets 18-19.
 */
public final class Crc16Arc {
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < TABLE.length; i++) {
            int value = i;
            for (int bit = 0; bit < 8; bit++) {
                value = ((value & 1) != 0) ? ((value >>> 1) ^ 0xA001) : (value >>> 1);
            }
            TABLE[i] = value & 0xFFFF;
        }
    }

    private Crc16Arc() {
    }

    /**
     * Calculates CRC-16/ARC over a byte-array range.
     *
     * @param data input bytes
     * @param offset first byte included
     * @param length number of bytes included
     * @return unsigned 16-bit CRC represented as an int
     */
    public static int checksum(byte[] data, int offset, int length) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException("offset=" + offset + ", length=" + length
                    + ", data.length=" + data.length);
        }

        int crc = 0x0000;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            int index = (crc ^ (data[i] & 0xFF)) & 0xFF;
            crc = ((crc >>> 8) ^ TABLE[index]) & 0xFFFF;
        }
        return crc;
    }

    /**
     * Calculates CRC-16/ARC over all bytes in the array.
     */
    public static int checksum(byte[] data) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        return checksum(data, 0, data.length);
    }
}
